(ns tapalakbot.query-builder
  "Unified query builder for all marketplace searches.
   
   Extracts structured search params from natural language:
   - Price constraints (min/max in KGS)
   - Lalafo routing
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
  [{:regex #"(?:до|макс|не более|budget)\s*(\d[\d\s]*(?:[.,]\d+)?)\s*(?:млн|миллион(?:а|ов)?)"
    :type :max
    :multiplier 1000000}
   {:regex #"(?:от|мин|минимум|не менее)\s*(\d[\d\s]*(?:[.,]\d+)?)\s*(?:млн|миллион(?:а|ов)?)"
    :type :min
    :multiplier 1000000}
   {:regex #"(\d+(?:[.,]\d+)?)\s*[-–—]\s*(\d+(?:[.,]\d+)?)\s*(?:млн|миллион(?:а|ов)?)"
    :type :range
    :multiplier 1000000}
   {:regex #"(?:до|макс|не более|budget)\s*(\d[\d\s]*)\s*(?:к|кгс|сом|тенге)?"
    :type :max}
   {:regex #"(?:от|мин|минимум|не менее)\s*(\d[\d\s]*)\s*(?:к|кгс|сом|тенге)?"
    :type :min}
   ;; Range: "15-30 тыс", "1000-2000 сом" — requires price word or suffix
   ;; Model numbers like "м2-3" (M2-M3 chips) are NOT prices
   {:regex #"(?:цена|бюджет|прайс|[оo]т|[дd]о)\s*(\d[\d\s]*)\s*[-–—]\s*(\d[\d\s]*)\s*(?:к|тыс|кгс|сом)?"
    :type :range}
   ;; Range with currency suffix (no price word needed): "15000-35000 сом"
   {:regex #"(\d[\d\s]*)\s*[-–—]\s*(\d[\d\s]*)\s*(?:к|тыс|кгс|сом)"
    :type :range}
   ;; Plain range with large numbers (>=1000): "10000-20000" — but NOT "2-3" (model numbers)
   {:regex #"(\d{4,}[\d\s]*)\s*[-–—]\s*(\d{4,}[\d\s]*)"
    :type :range}
   {:regex #"(?:бюджет|цена|стоимость)\s*(?:до\s*)?(\d[\d\s]*)\s*(?:к|кгс|сом)?"
    :type :max}
   {:regex #"(?:^|\s)(\d[\d\s]*)\s*(?:к|кгс|сом|тенге)(?:\s|$)"
    :type :max}
   {:regex #"(?:до|макс)\s*(\d[\d\s]*)\s*(?:кгс|сом|тенге)"
    :type :max}])

(defn- price-suffix-multiplier
  [full-match]
  (cond
    (re-find #"(?:млн|миллион(?:а|ов)?)\s*$" full-match) 1000000
    (re-find #"\d\s*(?:к|тыс)\s*$" full-match) 1000
    :else 1))

(defn- parse-price-value
  "Parse a numeric price and normalize its explicit suffix to KGS units."
  [s multiplier]
  (try
    (-> (str/replace s #"\s+" "")
        (str/replace "," ".")
        bigdec
        (* multiplier)
        long)
    (catch Exception _ nil)))
(defn extract-price
  "Extract price constraints from natural language text.
   Returns {:price-min N :price-max N} or nil."
  [text]
  (let [text-lower (str/lower-case text)
        results (atom [])]
    (doseq [{:keys [regex type multiplier]} price-patterns]
      (when-let [match (re-find regex text-lower)]
        (let [full-match (first match)
              multiplier (or multiplier (price-suffix-multiplier full-match))]
          (case type
            :max
            (when-let [val (parse-price-value (second match) multiplier)]
              (swap! results conj {:price-max val}))
            :min
            (when-let [val (parse-price-value (second match) multiplier)]
              (swap! results conj {:price-min val}))
            :range
            (let [lo (parse-price-value (second match) multiplier)
                  hi (parse-price-value (nth match 2) multiplier)]
              (when (and lo hi)
                (swap! results conj {:price-min lo :price-max hi})))))))
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
  "Detect whether a query is for a car while routing every query to Lalafo.
   Returns {:platforms [:lalafo] :is-auto? bool :is-electronics? bool}."
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
    {:platforms [:lalafo]
     :is-auto? is-auto?
     :is-electronics? is-electronics?
     :is-real-estate? is-real-estate?}))

;; ════════════════════════════ CATEGORY MATCHING ════════════════════════════

;; ════════════════════════════ LLM-BASED ENRICHMENT ════════════════════════════

(def ^:private enrich-prompt
  "You are a search query parser for Kyrgyz marketplaces.
Given a user's natural language input, extract structured search parameters.

Return ONLY a JSON object:
{
  \"query\": \"clean search term (Russian/English)\",
  \"price_min\": number|null,
  \"price_max\": number|null,
  \"category_hint\": \"suggested Lalafo category name or null\",
  \"is_auto\": bool
}

Rules:
1. 'query' should be the core search term without price/condition words
2. 'category_hint': most specific Lalafo category name (e.g., 'Air Conditioners', 'iPads')
3. 'is_auto': true only when the user is looking for a vehicle
4. Preserve original language (Russian stays Russian, English stays English)")

(defn enrich-with-llm
  "Use LLM to extract structured params from complex queries.
   Returns enriched params map."
  [text]
  (try
    (let [messages [{:role "system" :content enrich-prompt}
                    {:role "user" :content text}]
          resp (llm/llm :gemini-3.5-flash messages [] :provider :openrouter :max-tokens 300)
          content (get-in resp ["choices" 0 "message" "content"])
          json-str (or (re-find #"(?s)\{.*\}" content) "{}")
          parsed (try (json/parse-string json-str true) (catch Exception _ {}))]
      (when (seq parsed)
        {:query (:query parsed)
         :price-min (:price_min parsed)
         :price-max (:price_max parsed)
         :category-hint (:category_hint parsed)
         :is-auto? (:is_auto parsed false)}))
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
   - :platforms — always [:lalafo]
   - :is-auto? — true if searching for cars
   - :lalafo-category-id — matched Lalafo category ID (or nil)
   - :lalafo-category-name — matched category name (or nil)
   
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
                        (str/replace #"(?:до|от|макс|мин|бюджет|цена|стоимость)\s*\d[\d\s]*(?:[.,]\d+)?\s*(?:млн|миллион(?:а|ов)?|к|кгс|сом|тыс)?" "")
                        (str/replace #"\d[\d\s]*\s*(?:[-–—])\s*\d[\d\s]*(?:к|тыс|кгс|сом)?" "")
                        str/trim)
        ;; Step 5: Merge all params (LLM overrides deterministic where better)
        final-query (or (:query llm-params) clean-query text)
        final-price-min (or (:price-min llm-params) (:price-min price-params))
        final-price-max (or (:price-max llm-params) (:price-max price-params))
        final-platforms [:lalafo]
        result {:query final-query
                :price-min final-price-min
                :price-max final-price-max
                :platforms final-platforms
                :is-auto? (or (:is-auto? llm-params) (:is-auto? platform-params))
                :is-electronics? (:is-electronics? platform-params)
                :is-real-estate? (:is-real-estate? platform-params)
                :lalafo-category-id nil  ;; Will be filled by tracker at creation time
                :lalafo-category-name (:category-hint llm-params)
                :some-cat :none
                :condition (extract-condition text)
                :raw-text text}]
    (log/info :query-builder-result
              :query final-query
              :price [final-price-min final-price-max]
              :platforms final-platforms
              :is-auto? (:is-auto? result))
    result))

;; ════════════════════════════ API PARAM MAPPING ════════════════════════════

;; ════════════════════════════ CONVENIENCE ════════════════════════════

(defn parse
  "Quick parse without LLM (deterministic only).
   Useful for scanner/monitor where speed matters."
  [text]
  (build text :use-llm? false))

;; ════════════════════════════ TESTS ════════════════════════════

