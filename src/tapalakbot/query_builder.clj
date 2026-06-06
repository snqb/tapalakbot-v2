(ns tapalakbot.query-builder
  "Unified query builder for all marketplace platforms.
   
   Extracts structured search params from natural language:
   - Price constraints (min/max in KGS)
   - Platform routing (Lalafo, Mashina, Bazar)
   - Category matching
   - Optimal search queries
   
   Used by:
   - Agent smart_search tool (initial search)
   - Tracker (user subscription matching)
   - Scanner (background monitoring)"
  (:require [clj-harness.llm :as llm]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════════════ PRICE EXTRACTION ════════════════════════════

(def ^:private price-patterns
  "Regex patterns for extracting price from Russian/Kyrgyz text.
   Matches: до 20к, до 20000, от 5000, 5-10 тыс, бюджет 15000, etc."
  [{:regex #"(?:до|макс|не более|budget)\s*(\d[\d\s]*)\s*(?:к|кгс|сом|тенге)?"
    :type :max}
   {:regex #"(?:от|мин|минимум|не менее)\s*(\d[\d\s]*)\s*(?:к|кгс|сом|тенге)?"
    :type :min}
   {:regex #"(\d[\d\s]*)\s*[-–—]\s*(\d[\d\s]*)\s*(?:к|тыс|кгс|сом)?"
    :type :range}
   {:regex #"(?:бюджет|цена|стоимость)\s*(?:до\s*)?(\d[\d\s]*)\s*(?:к|кгс|сом)?"
    :type :max}
   {:regex #"(?:^|\s)(\d[\d\s]*)\s*(?:к|кгс|сом|тенге)(?:\s|$)"
    :type :max}
   {:regex #"(?:до|макс)\s*(\d[\d\s]*)\s*(?:кгс|сом|тенге)"
    :type :max}])

(defn- parse-price-value
  "Parse price string, handling 'к' suffix and space separators.
   '20к' → 20000, '20000' → 20000, '500' → 500 (no auto-multiply)."
  [s & {:keys [has-k-suffix?] :or {has-k-suffix? false}}]
  (let [clean (str/replace s #"\s+" "")
        num (try (Long/parseLong clean) (catch Exception _ nil))]
    (when num
      (if has-k-suffix?
        (* num 1000)
        num))))
(defn extract-price
  "Extract price constraints from natural language text.
   Returns {:price-min N :price-max N} or nil."
  [text]
  (let [text-lower (str/lower-case text)
        results (atom [])]
    (doseq [{:keys [regex type]} price-patterns]
      (when-let [match (re-find regex text-lower)]
        ;; Check full match for "к"/"тыс" suffix
        (let [full-match (first match)
              has-k? (or (str/includes? full-match "к")
                         (str/includes? full-match "тыс"))]
          (case type
            :max
            (let [val (parse-price-value (second match) :has-k-suffix? has-k?)]
              (when val (swap! results conj {:price-max val})))
            :min
            (let [val (parse-price-value (second match) :has-k-suffix? has-k?)]
              (when val (swap! results conj {:price-min val})))
            :range
            (let [lo (parse-price-value (second match) :has-k-suffix? has-k?)
                  hi (parse-price-value (nth match 2) :has-k-suffix? has-k?)]
              (when (and lo hi) (swap! results conj {:price-min lo :price-max hi})))))))
    (when (seq @results)
      ;; Merge all extracted ranges — take widest bounds
      (let [res @results
            mins (keep :price-min res)
            maxs (keep :price-max res)]
        (when (or (seq mins) (seq maxs))
          {:price-min (when (seq mins) (apply min mins))
           :price-max (when (seq maxs) (apply max maxs))})))))

;; ════════════════════════════ PLATFORM DETECTION ════════════════════════════

(def ^:private car-keywords
  "Keywords that indicate the user is looking for a car."
  #{"машина" "авто" "автомобиль" "car" "cars" "auto"
    "toyota" "тойота" "bmw" "бмв"
    "mercedes" "мерседес" "nissan" "ниссан" "kia" "киа"
    "hyundai" "хундай" "солярис" "solaris" "camry" "камри"
    "акура" "acura" "lexus" "лексус" "mazda" "мазда"
    "ford" "форд" "chevrolet" "шевроле" "volkswagen"
    "land cruiser" "ленд крузер" "prado" "прадо"
    "rio" "рио" "accent" "акцент" "elantra" "элантра"
    "sonata" "соната" "tucson" "туксон" "sportage" "спортаж"
    "outlander" "аутлендер" "asx" "аскс" "pajero" "паджеро"
    "duster" "дастер" "logan" "логан" "sandero" "сандеро"
    "nexia" "матиз" "matiz" "cobalt" "кобальт"
    "malibu" "малибу" "capture" "каптир" "kaptur" "каптур"
    "riora" "риора" "gentra" "джентра" "spark" "спарк"
    "ceed" "сид"})

(def ^:private auto-specific-brands
  "Car brand names that definitively indicate auto search."
  #{"hyundai" "toyota" "bmw" "mercedes" "nissan" "kia"
    "honda" "ford" "chevrolet" "volkswagen" "mazda" "subaru"
    "mitsubishi" "suzuki" "lexus" "infiniti" "acura"
    "seat" "skoda" "renault" "peugeot" "citroen" "opel"
    "fiat" "alfa" "porsche" "land rover" "jeep" "dodge"
    "chrysler" "gmc" "cadillac" "lincoln" "tesla"
    ;; Russian/CIS
    "ваз" "lada" "лада" "уаз" "uaz" "газ" "gaz"
    "камаз" "kamaz" "урал" "ural" "зил" "zil"
    ;; Chinese
    "chery" "чери" "geely" "джили" "haval" "хавал"
    "changan" "чанган" "byd" "бе-ай-ди" "great wall" "грейт волл"
    "liaidon" "лиадон" "faw" "фав" "dongfeng" "донгфенг"
    "exeed" "эксид" "omoda" "омода" "jetour" "джетур"
    "tiggo" "тигго" "arrizo" "арризо" "emgrand" "эмгранд"
    "monjaro" "монжаро" "coolray" "кулрей" "azkarra" "азкарра"})

(defn detect-platform
  "Detect which platform(s) to search based on user query.
   Returns {:platforms [:lalafo :mashina :bazar] :is-auto? bool :is-electronics? bool}."
  [text]
  (let [text-lower (str/lower-case text)
        words (set (str/split text-lower #"\s+"))
        ;; Check for car-specific brands (exact match in words)
        has-brand? (boolean (some auto-specific-brands words))
        ;; Check for general car keywords
        has-car-keyword? (boolean (some car-keywords words))
        ;; Check for auto-specific terms
        is-auto? (and (or has-brand? has-car-keyword?)
                      ;; Exclude: "стиральная машина", "посудомоечная машина" are NOT cars
                      (not (re-find #"(стиральная|посудомоечная|швейная|пишущая)\s+машина" text-lower)))
        ;; Electronics keywords
        electronics-words #{"iphone" "samsung" "xiaomi" "apple" "android"
                           "телефон" "смартфон" "phone" "smartphone"
                           "ноутбук" "laptop" "macbook" "笔记本"
                           "планшет" "tablet" "ipad"
                           "наушники" "headphones" "airpods"
                           "видеокарта" "gpu" "rtx" "gtx"
                           "процессор" "cpu" "processor"
                           "монитор" "monitor" "tv" "телевизор"
                           "кондиционер" "air conditioner" "сплит"
                           "холодильник" "refrigerator"
                           "стиральная" "washing"
                           "пылесос" "vacuum"
                           "playstation" "ps5" "ps4" "xbox" "приставка"
                           "колонка" "speaker" "sonos" "jbl" "marshall"}
        is-electronics? (boolean (some electronics-words words))
        ;; Real estate keywords
        real-estate-words #{"квартира" "apartment" "дом" "house" "комната" "room"
                           "офис" "office" "магазин" "store" "склад" "warehouse"
                           "аренда" "rent" "продажа" "sale" "купить" "buy"
                           "снять" "нежилое" "commercial" "коммерческая"}
        is-real-estate? (boolean (some real-estate-words words))]
    {:platforms (cond
                 is-auto? [:mashina :bazar]  ;; Cars: Mashina (primary) + Bazar
                 is-electronics? [:lalafo :bazar]  ;; Electronics: Lalafo + Bazar
                 is-real-estate? [:lalafo]  ;; Real estate: Lalafo only
                 :else [:lalafo :bazar])  ;; Default: Lalafo + Bazar
     :is-auto? is-auto?
     :is-electronics? is-electronics?
     :is-real-estate? is-real-estate?}))

;; ════════════════════════════ CATEGORY MATCHING ════════════════════════════

(def ^:private bazar-category-map
  "Map of intent keywords to Bazar.kg category keys."
  {"авто" :transport-cars "машина" :transport-cars "car" :transport-cars
   "мото" :transport-moto "мoto" :transport-moto "мотоцикл" :transport-moto
   "запчасти" :transport-parts "запчаст" :transport-parts
   "электроника" :electronics "телефон" :electronics "phone" :electronics
   "ноутбук" :electronics "laptop" :electronics
   "дом" :home-garden "home" :home-garden "сад" :home-garden
   "дети" :children "детское" :children "child" :children
   "одежда" :clothing "clothes" :clothing "обувь" :clothing
   "услуги" :services "service" :services
   "работа" :jobs "job" :jobs
   "животные" :animals "pet" :animals "animals" :animals
   "недвижимость" :real-estate "квартира" :real-estate})

(defn match-bazar-category
  "Match user query to Bazar.kg category key."
  [text]
  (let [text-lower (str/lower-case text)]
    (or (some (fn [[kw cat]]
                (when (str/includes? text-lower kw) cat))
              bazar-category-map)
        :electronics)))  ;; Default to electronics

;; ════════════════════════════ LLM-BASED ENRICHMENT ════════════════════════════

(def ^:private enrich-prompt
  "You are a search query parser for Kyrgyz marketplaces.
Given a user's natural language input, extract structured search parameters.

Return ONLY a JSON object:
{
  \"query\": \"clean search term (Russian/English)\",
  \"price_min\": number|null,
  \"price_max\": number|null,
  \"platform\": \"lalafo\"|\"mashina\"|\"bazar\"|\"all\",
  \"category_hint\": \"suggested Lalafo category name or null\",
  \"is_auto\": bool,
  \"mashina_query\": \"car-specific query for mashina.kg or null\"
}

Rules:
1. 'query' should be the core search term without price/condition words
2. 'platform': cars → 'mashina', general goods → 'lalafo', electronics → 'all'
3. 'category_hint': most specific Lalafo category name (e.g., 'Air Conditioners', 'iPads')
4. 'mashina_query': for cars, include brand + model + year if mentioned
5. Preserve original language (Russian stays Russian, English stays English)")

(defn enrich-with-llm
  "Use LLM to extract structured params from complex queries.
   Returns enriched params map."
  [text]
  (try
    (let [messages [{:role "system" :content enrich-prompt}
                    {:role "user" :content text}]
          resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 300)
          content (get-in resp ["choices" 0 "message" "content"])
          json-str (or (re-find #"(?s)\{.*\}" content) "{}")
          parsed (try (json/parse-string json-str true) (catch Exception _ {}))]
      (when (seq parsed)
        {:query (:query parsed)
         :price-min (:price_min parsed)
         :price-max (:price_max parsed)
         :platform (keyword (or (:platform parsed) "lalafo"))
         :category-hint (:category_hint parsed)
         :is-auto? (:is_auto parsed false)
         :mashina-query (:mashina_query parsed)}))
    (catch Exception e
      (log/warn :query-enrich-failed :error (.getMessage e))
      nil)))

;; ════════════════════════════ MAIN BUILDER ════════════════════════════

(def ^:private condition-patterns
  "Patterns for detecting product condition (new/used) from query text.
   Uses simple string matching to avoid escaping issues."
  [["новый" :new] ["новая" :new] ["новое" :new] ["новые" :new]
   ["новьё" :new] ["запечатан" :new] ["brand new" :new]
   ["б/у" :used] ["б.у." :used] ["бу " :used] [" бу" :used]
   ["подержан" :used] ["бэушн" :used] ["used" :used]
   ["с пробегом" :used]
   ["как новый" :like-new] ["отличное состояние" :like-new]
   ["идеальное состояние" :like-new]
   ["восстановлен" :refurbished] ["refurbished" :refurbished]
   ["требует ремонта" :broken] ["на запчасти" :broken]
   ["не работает" :broken] ["битый" :broken]])

(defn extract-condition
  "Extract product condition from natural language query.
   Returns :new, :used, :like-new, :refurbished, :broken, or nil."
  [text]
  (let [t (str/lower-case (or text ""))]
    (some (fn [[pattern condition]]
            (when (str/includes? t pattern) condition))
          condition-patterns)))

(defn build
  "Build structured search params from natural language input.
   
   Returns map with:
   - :query — clean search term
   - :price-min — minimum price in KGS (or nil)
   - :price-max — maximum price in KGS (or nil)
   - :platforms — vector of platform keywords [:lalafo :mashina :bazar]
   - :is-auto? — true if searching for cars
   - :lalafo-category-id — matched Lalafo category ID (or nil)
   - :lalafo-category-name — matched category name (or nil)
   - :mashina-query — car-specific query for Mashina (or nil)
   - :bazar-category — Bazar.kg category key
   - :raw-text — original input text"
  [text & {:keys [use-llm?] :or {use-llm? true}}]
  (log/info :query-builder-start :text text :use-llm? use-llm?)
  (let [;; Step 1: Deterministic price extraction
        price-params (extract-price text)
        ;; Step 2: Platform detection
        platform-params (detect-platform text)
        ;; Step 3: LLM enrichment (for complex queries)
        llm-params (when use-llm? (enrich-with-llm text))
        ;; Step 4: Build clean query (strip price/condition words)
        clean-query (-> text
                        (str/replace #"(?:до|от|макс|мин|бюджет|цена|стоимость)\s*\d[\d\s]*(?:к|кгс|сом|тыс)?" "")
                        (str/replace #"\d[\d\s]*\s*(?:[-–—])\s*\d[\d\s]*(?:к|тыс|кгс|сом)?" "")
                        str/trim)
        ;; Step 5: Merge all params (LLM overrides deterministic where better)
        final-query (or (:query llm-params) clean-query text)
        final-price-min (or (:price-min llm-params) (:price-min price-params))
        final-price-max (or (:price-max llm-params) (:price-max price-params))
        final-platforms (if (:platform llm-params)
                          [(:platform llm-params)]
                          (:platforms platform-params))
        ;; Step 6: Bazar category
        bazar-cat (match-bazar-category text)
        ;; Step 7: Mashina query (for cars)
        mashina-query (or (:mashina-query llm-params)
                          (when (:is-auto? platform-params) text))]
    (let [result {:query final-query
                  :price-min final-price-min
                  :price-max final-price-max
                  :platforms final-platforms
                  :is-auto? (or (:is-auto? llm-params) (:is-auto? platform-params))
                  :is-electronics? (:is-electronics? platform-params)
                  :is-real-estate? (:is-real-estate? platform-params)
                  :lalafo-category-id nil  ;; Will be filled by tracker at creation time
                  :lalafo-category-name (:category-hint llm-params)
                  :mashina-query mashina-query
                  :bazar-category bazar-cat
                  :condition (extract-condition text)
                  :raw-text text}]
      (log/info :query-builder-result
                :query final-query
                :price [final-price-min final-price-max]
                :platforms final-platforms
                :is-auto? (:is-auto? result))
      result)))

;; ════════════════════════════ API PARAM MAPPING ════════════════════════════

(defn to-lalafo-params
  "Convert QueryBuilder result to Lalafo API params."
  [{:keys [query price-min price-max lalafo-category-id]}]
  (cond-> {"queries" [query]
           "city_id" 103184}
    price-min (assoc "price_min" price-min)
    price-max (assoc "price_max" price-max)
    lalafo-category-id (assoc "category_id" lalafo-category-id)))

(defn to-mashina-params
  "Convert QueryBuilder result to Mashina API params."
  [{:keys [mashina-query query]}]
  {"query" (or mashina-query query)
   "size" 20})

(defn to-bazar-params
  "Convert QueryBuilder result to Bazar API params."
  [{:keys [query bazar-category]}]
  {:category bazar-category
   :brand query})

;; ════════════════════════════ CONVENIENCE ════════════════════════════

(defn parse
  "Quick parse without LLM (deterministic only).
   Useful for scanner/monitor where speed matters."
  [text]
  (build text :use-llm? false))

(defn parse-with-llm
  "Parse with LLM enrichment (full extraction).
   Used for agent and track creation."
  [text]
  (build text :use-llm? true))

;; ════════════════════════════ ACCESSORY FILTER ════════════════════════════

(def ^:private accessory-keywords
  "Keywords in title that indicate this item is an accessory, case, charger,
   repair service, or part — NOT the actual product the user wants.
   Higher score = more likely to be junk. Used as deterministic pre-filter
   before LLM relevance filtering."
  ;; Score 5: strong accessory signal (almost certainly not the product)
  {:strong #{"зарядк" "зарядное" "зарядный" "charger" "кабел" "кабель"
             "адаптер" "adapter" "стекло защитное" "стекло" "плeнк"
             "чехол" "чехоль" "case" "обложк" "ремонт" "починк"
             "установка" "установк" "монтаж" "настройк" "прошивк"
             "гравировк" "замена" "восстановлен"
             "коробка" "упаковка" "box" "packaging"
             "гарантия" "страховка" "insurance" "warranty"
             "подарочный сертификат" "gift card"
             "схема" "инструкция" "manual"}
   ;; Score 3: medium signal (could be accessory but need context)
   :medium #{"держател" "холдер" "holder" "подставк" "stand"
             "кронштейн" "mount" "креплен" "bracket"
             "наклейк" "стикер" "sticker" "скин" "skin"
             "заглушк" "plug" "колпачек" "cap"
             "аксессуар" "accessory" "дополнительн"
             "запчаст" "spare part" "комплектующ"
             "батарейк" "battery" "аккумулятор"
             "переходник" "коннектор" "connector"
             "кнопк" "button" "клавиш"}
   ;; Score 2: weak signal (frequently co-occurs with real products)
   :weak #{"услуг" "сервис" "service" "аренда" "прокат" "rent"
            "обмен" "trade" "бартер"}})

(defn- accessory-score
  "Score 0-5 for how likely an item title is an accessory/service.
   0 = seems like the real product, 5 = definitely junk.
   Returns integer score."
  [title]
  (let [t (str/lower-case (or title ""))
        strong-hits (count (filter #(str/includes? t %) (:strong accessory-keywords)))
        medium-hits (count (filter #(str/includes? t %) (:medium accessory-keywords)))
        weak-hits (count (filter #(str/includes? t %) (:weak accessory-keywords)))]
    (-> 0
        (+ (* strong-hits 5))
        (+ (* medium-hits 3))
        (+ (* weak-hits 2))
        (min 15))))

(defn filter-accessories
  "Pre-filter items to remove obvious accessories/services.
   Returns vector of items with accessory-score < threshold.
   Default threshold: filter items scoring >=5 (strong accessory signals).
   
   Keeps items even with high scores if user WAS looking for accessories.
   Example: 'чехол iphone 13' → user wants an accessory, don't filter cases."
  ([items user-query]
   (filter-accessories items user-query 5))
  ([items user-query threshold]
   (let [;; Check if user is looking FOR an accessory (not a product)
         user-wants-accessory? (and user-query
                                    (some #(str/includes? (str/lower-case user-query) %)
                                          ["чехол" "зарядк" "кабел" "наушник"
                                           "кейс" "стекло" "пленк" "адаптер"
                                           "батарейк" "аккумулятор"]))
         scored (mapv (fn [item]
                        (let [title (or (get item "title") (get item :title ""))]
                          (assoc item :_accessory-score (accessory-score title))))
                      items)
         filtered (if user-wants-accessory?
                    scored  ;; User wants an accessory — show everything
                    (filterv #(< (:_accessory-score % 0) threshold) scored))]
     (log/info :accessory-filter :total (count items) :kept (count filtered)
               :user-wants-accessory? user-wants-accessory?)
     filtered)))

;; ════════════════════════════ TESTS ════════════════════════════

(comment
  ;; Price extraction tests
  (extract-price "кондиционер до 20к")
  ;; => {:price-max 20000}
  
  (extract-price "iphone от 15000 до 30000")
  ;; => {:price-min 15000 :price-max 30000}
  
  (extract-price "ноутбук бюджет 25000")
  ;; => {:price-max 25000}
  
  (extract-price "macbook 5-10 тыс")
  ;; => {:price-min 5000 :price-max 10000}
  
  ;; Platform detection
  (detect-platform "hyundai solaris 2020")
  ;; => {:platforms [:mashina :bazar], :is-auto? true, ...}
  
  (detect-platform "iphone 13")
  ;; => {:platforms [:lalafo :bazar], :is-electronics? true, ...}
  
  (detect-platform "квартира в бишкеке")
  ;; => {:platforms [:lalafo], :is-real-estate? true, ...}
  
  ;; Full build
  (build "кондиционер до 20к")
  (build "hyundai solaris 2020 до 800000")
  (build "iphone 13 pro max")
  
  ;; Without LLM
  (parse "roутер до 4000"))
