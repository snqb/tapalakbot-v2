(ns tapalakbot.lalafo
  "Direct Lalafo.kg API client using Java 11 HttpClient."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [tapalakbot.lalafo-collection :as collection])
  (:import
   [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
   [java.net URI]
   [java.time Duration]))

(def ^:private base-url "https://lalafo.kg/api")

(def ^:private default-headers
  {"Accept" "application/json, text/plain, */*"
   "Accept-Language" "en-US,en;q=0.9"
   "Device" "pc"
   "Language" "ru_RU"
   "Country-Id" "12"
   "sec-ch-ua" "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\""
   "sec-ch-ua-mobile" "?0"
   "sec-ch-ua-platform" "\"macOS\""
   "sec-fetch-dest" "empty"
   "sec-fetch-mode" "cors"
   "sec-fetch-site" "same-origin"
   "x-cache-bypass" "yes"
   "User-Agent" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"})

(defn- build-client []
  (.. (HttpClient/newBuilder)
      (connectTimeout (Duration/ofSeconds 10))
      (build)))

(defn- get-json
  "GET a Lalafo API endpoint, parse JSON, with retry. Nil means fetch failure."
  ([client path params] (get-json client path params 3))
  ([client path params retries]
   (let [uri (str base-url "/" (str/replace path #"^/" ""))
         query-str (when (seq params)
                     (str "?" (str/join "&"
                                        (map (fn [[k v]]
                                               (str (name k) "="
                                                    (java.net.URLEncoder/encode (str v) "UTF-8")))
                                             params))))
         full-url (str uri (or query-str ""))
         request (let [builder (doto (HttpRequest/newBuilder)
                                 (.uri (URI/create full-url))
                                 (.timeout (Duration/ofSeconds 20)))]
                   (reduce-kv (fn [b k v] (.header b k v)) builder default-headers)
                   (-> builder (.GET) (.build)))]
     (try
       (let [resp (.send client request (HttpResponse$BodyHandlers/ofString))
             status (.statusCode resp)
             body (.body resp)]
         (log/info :lalafo-http status :url (subs full-url 0 (min 80 (count full-url))))
         (case status
           200 (try
                 (json/parse-string body)
                 (catch Exception e
                   (log/warn :lalafo-parse-error (.getMessage e))
                   nil))
           (401 403 419) nil
           (if (pos? retries)
             (do (Thread/sleep (* (- 4 retries) 2000))
                 (get-json client path params (dec retries)))
             (do (log/warn :lalafo-http-error status :url full-url) nil))))
       (catch java.net.http.HttpConnectTimeoutException _
         (if (pos? retries)
           (do (Thread/sleep 2000)
               (get-json client path params (dec retries)))
           nil))
       (catch Exception e
         (log/warn :lalafo-error (.getMessage e) :url full-url)
         nil)))))

(defn- option-or-default
  [opts key default]
  (if (contains? opts key) (get opts key) default))

(defn- search-page
  "Search one query/page. :failed? distinguishes transport failure from empty results."
  [client query & {:keys [category-id price-min price-max page per-page] :as opts}]
  (let [city-id (option-or-default opts :city-id 103184)
        page (or page 1)
        per-page (or per-page 200)
        params (cond-> {:expand "url" :page page :per-page per-page}
                 query (assoc :q query)
                 category-id (assoc :category_id category-id)
                 (some? city-id) (assoc :city_id city-id))
        data (get-json client "search/v3/feed/search" params)]
    (if (and (map? data) (sequential? (get data "items")))
      (let [meta (if (map? (get data "_meta")) (get data "_meta") {})
            items (if (sequential? (get data "items")) (get data "items") [])
            total-count (get meta "totalCount")
            has-more (or (get meta "hasMore") (get meta "has_more"))]
        {:ok? true
         :failed? false
         :items (vec items)
         :total-count (if (number? total-count) total-count (count items))
         :has-more? (boolean has-more)
         :page page})
      {:ok? false :failed? true :items [] :total-count 0 :has-more? false :page page})))


(defn- search-all-pages
  "Compatibility API for monitor callers: [unique-count items pages-scanned].
   A failed query throws instead of masquerading as an empty result."
  [client query & {:keys [category-id price-min price-max max-pages per-page] :as opts}]
  (let [city-id (option-or-default opts :city-id 103184)
        max-pages (or max-pages 5)
        per-page (or per-page 200)]
    (loop [page 1 seen #{} items [] pages 0]
      (if (> page max-pages)
        [(count items) items pages]
        (let [result (search-page client query
                                  :category-id category-id
                                  :price-min price-min
                                  :price-max price-max
                                  :city-id city-id
                                  :page page
                                  :per-page per-page)]
          (when (:failed? result)
            (throw (ex-info "Lalafo query failed"
                            {:type :lalafo-fetch-failed :query query :page page})))
          (let [[new-items new-seen]
                (reduce (fn [[found ids] item]
                          (let [id (str (get item "id"))]
                            (if (or (contains? ids id)
                                    (nil? (collection/normalize-item item)))
                              [found ids]
                              [(conj found item) (conj ids id)])))
                        [[] seen] (:items result))
                items' (into items new-items)
                pages' (inc pages)]
            (if (or (empty? (:items result))
                    (not (collection/has-next-page? result per-page))
                    (= new-seen seen))
              [(count items') items' pages']
              (recur (inc page) new-seen items' pages'))))))))


(defn search
  "Search up to six query variants and return JSON with up to 200 full listings."
  [args]
  (let [queries (get args "queries")
        qs (->> (if (sequential? queries) queries [(str queries)])
                (map str)
                (remove str/blank?)
                (take 6)
                vec)
        city-id (if (contains? args "city_id") (get args "city_id") 103184)
        opts {:category-id (get args "category_id")
              :price-min (get args "price_min")
              :price-max (get args "price_max")
              :city-id city-id
              :max-pages (get args "max_pages")
              :per-page (get args "per_page")
              :candidate-limit (get args "candidate_limit")
              :budget-currency (or (get args "price_currency")
                                   (get args "currency")
                                   "KGS")}
        client (build-client)
        result (collection/collect-candidates
                qs
                opts
                (fn [{:keys [query category-id price-min price-max city-id page per-page]}]
                  (search-page client query
                               :category-id category-id
                               :price-min price-min
                               :price-max price-max
                               :city-id city-id
                               :page page
                               :per-page per-page)))]
    (when (and (zero? (:succeeded-queries result))
               (pos? (count qs)))
      (throw (ex-info "All Lalafo queries failed"
                      {:type :lalafo-fetch-failed
                       :failed-queries (:failed-queries result)
                       :queries (count qs)})))
    (let [filtered (filterv #(collection/within-budget?
                              % (:price-min opts) (:price-max opts)
                              (:budget-currency opts))
                            (:items result))
          stats {:raw (:raw result)
                 :filtered (count filtered)
                 :pages (:pages result)
                 :queries (count qs)
                 :truncated (boolean (:truncated result))
                 :failed-queries (:failed-queries result)}]
      (json/generate-string {:found (count filtered)
                             :truncated (:truncated stats)
                             :items filtered
                             :stats stats}
                            {:pretty true}))))

;; ══════════════════════════ CATEGORIES ══════════════════════════

(defn get-categories-raw
  "Fetch raw category tree JSON from Lalafo API.
   Returns parsed JSON or nil."
  []
  (let [client (build-client)]
    (get-json client "categories/v3/list" {:language "ru_RU"})))

;; ══════════════════════════ HEALTHCHECK ══════════════════════════

(defn smoke-test
  "Run a quick search to verify Lalafo API is reachable.
   Returns {:ok? true} or {:ok? false :error ...}."
  []
  (try
    (let [client (build-client)
          {:keys [items total-count]} (search-page client "iphone"
                                                   :page 1 :per-page 5)]
      (if (and (> total-count 0) (seq items))
        (do (log/info :lalafo-smoke-test :ok? true :found total-count)
            {:ok? true :found total-count})
        (do (log/warn :lalafo-smoke-test :ok? false :found total-count :items-count (count items))
            {:ok? false :error (str "No results. totalCount=" total-count " items=" (count items))})))
    (catch Exception e
      (log/error :lalafo-smoke-test-error (.getMessage e))
      {:ok? false :error (.getMessage e)})))

;; ══════════════════════════ CATEGORIES ══════════════════════════

(def ^:private important-roots
  #{"Electronics" "Transport" "Property" "Sport & hobby" "Services" "Job" "Pets"})

(def ^:private russian-hints
  {"Headphones" "наушники, блютуз наушники, TWS"
   "Modems, Broadband & Networking" "роутер, маршрутизатор, wifi, модем"
   "Laptops and Netbooks" "ноутбук, лэптоп"
   "Mobile Phones" "телефон, смартфон, iPhone, Samsung"
   "TVs" "телевизор, ТВ"
   "Speakers & sound systems" "колонка, акустика"
   "Washing machine" "стиральная машина"
   "Refrigerators" "холодильник"
   "Apartments for rent" "аренда квартиры, снять квартиру"
   "Sale of apartments" "купить квартиру"
   "Houses for sale" "дом продажа, купить дом"
   "Used cars" "автомобиль, машина"
   "Bicycles" "велосипед, велик"
   "Computer Parts" "видеокарта, процессор, оперативка"
   "Video Games & Consoles" "приставка, PS5, PlayStation, Xbox"
   "Photo Cameras" "фотоаппарат"
   "Smart watches" "смарт часы, apple watch"
   "Vacancy" "вакансия, работа"
   "Resumes, CVs" "резюме"
   "Musicial instruments" "гитара, пианино, музыкальные инструменты"
   "Hunting and fishing" "рыбалка, охота, удочка"
   "Tablets & E-books" "планшет, электронная книга"
   "iPads" "айпад, ipad"
   "Air Conditioners" "кондиционер"
   "Microwave ovens" "микроволновка"
   "Vacuum cleaners" "пылесос"
   "Electronic cigarettes" "айкос, iqos, электронная сигарета, вейп"
   "Commercial real estate for rent" "аренда помещение, коммерческая недвижимость, офис аренда, магазин аренда, кафе помещение, кофейня, ресторан"
   "Office rentals" "офис аренда, аренда офиса, аренда офисных помещений"
   "Retail rentals" "магазин аренда, аренда магазина, торговое помещение, торговля"
   "Restaurant and cafe rentals" "кафе, кофейня, ресторан, чайхана, столовая, помещение под кафе"
   "Warehouse and workshop rentals" "склад, аренда склада, мастерская, гараж аренда"})

(defn fetch-categories-raw
  "Fetch raw category list from Lalafo API.
   Returns vector of category maps with keys: id, name, parent_id, depth, lft, rgt, count."
  []
  (let [client (build-client)
        data (get-json client "catalog/v2/categories" {})]
    (get data "data" [])))

(defn- build-category-map
  "Build a map of id→category from raw API data."
  [raw-cats]
  (reduce (fn [m c] (assoc m (get c "id") c)) {} raw-cats))

(defn- is-leaf? [cat]
  (= (- (get cat "rgt") (get cat "lft")) 1))

(defn- skip-region? [name]
  (some #(str/starts-with? name %) ["AZ -" "KG -" "RS -" "Help for" "PL -" "deleted" "Other"
                                    "Freedom Freebies" "Xoncha of charity" "Freebies"
                                    "Friendship & dating" "Personal  items" "Children's Item"
                                    "Medical Supplies" "Winter holidays" "Rest on Issyk-Kul"]))

(defn format-categories-prompt
  "Build compact category prompt from raw API data. ~3K chars for LLM.
   Same output format as Python build_category_prompt."
  [raw-cats]
  (let [by-id (build-category-map raw-cats)
        roots (filter #(= (get % "depth") 1) raw-cats)]
    (str/join "\n"
              (concat ["## Lalafo categories (use specific leaf category_ids)"]
                      (mapcat (fn [root]
                                (let [name (get root "name")]
                                  (when (or (contains? important-roots name)
                                            (not (skip-region? name)))
                                    (let [children (->> raw-cats
                                                        (filter #(= (get % "parent_id") (get root "id")))
                                                        (remove #(skip-region? (get % "name")))
                                                        (take 10))]
                                      (concat [(str "\n" name " [" (get root "id") "]:")]
                                              (mapcat (fn [child]
                                                        (let [cname (get child "name")
                                                              hint (get russian-hints cname "")
                                                              hint-str (if (not (str/blank? hint)) (str " ← " hint) "")]
                                                          (concat [(str "  [" (get child "id") "] " cname hint-str)]
                                                                  (when (not (is-leaf? child))
                                                                    (->> raw-cats
                                                                         (filter #(= (get % "parent_id") (get child "id")))
                                                                         (remove #(skip-region? (get % "name")))
                                                                         (filter #(contains? russian-hints (get % "name")))
                                                                         (mapv (fn [gc]
                                                                                 (str "    [" (get gc "id") "] " (get gc "name")
                                                                                      " ← " (get russian-hints (get gc "name"))))))))))
                                                      children))))))
                              roots)
                      ["\nRULES:"
                       "- Pick the MOST SPECIFIC (deepest) category_id."
                       "- For 'наушники' → use Headphones leaf, NOT Audio or Electronics."
                       "- For 'роутер' → use Networking leaf, NOT Computers or Electronics."
                       "- If unsure about category, set category_id: null and use keyword search."]))))

(defn search-categories
  "Search categories by name (fuzzy substring match against English names + Russian hints).
   Also matches individual words from multi-word queries.
   Returns formatted string for LLM."
  [search-term]
  (let [raw (fetch-categories-raw)
        words (str/split (str/lower-case search-term) #"\s+")
        matches (->> raw
                     (filter (fn [c]
                               (let [name (str/lower-case (get c "name"))
                                     hint (str/lower-case (get russian-hints (get c "name") ""))]
                                 (some (fn [w]
                                         (or (str/includes? name w)
                                             (str/includes? hint w)))
                                       words))))
                     (sort-by #(get % "depth"))
                     (take 10)
                     vec)]
    (if (seq matches)
      (str "Categories matching '" search-term "':\n"
           (str/join "\n" (mapv (fn [c]
                                  (str "  [" (get c "id") "] " (get c "name")
                                       " (depth " (get c "depth") ")"))
                                matches)))
      (str "No categories found for '" search-term "'."))))

;; ══════════════════════════ EXA RESEARCH ══════════════════════════

(defn exa-research
  "Search the web via Exa API. Returns JSON string with results.
   Gets API key from env EXA_API_KEY or pass."
  [query]
  (let [api-key (or (System/getenv "EXA_API_KEY")
                    (try
                      (let [proc (.exec (Runtime/getRuntime)
                                        (into-array String ["pass" "show" "api/exa"]))
                            out (slurp (.getInputStream proc))]
                        (.waitFor proc)
                        (str/trim out))
                      (catch Exception _ nil)))]
    (if (str/blank? api-key)
      (json/generate-string {:error "No Exa API key configured"})
      (let [client (build-client)
            req (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "https://api.exa.ai/search"))
                    (.header "x-api-key" api-key)
                    (.header "Content-Type" "application/json")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                            (json/generate-string {:query query
                                                   :type "auto"
                                                   :numResults 5
                                                   :contents {:text {:maxCharacters 300}}})))
                    (.timeout (Duration/ofSeconds 15))
                    (.build))
            resp (.send client req (HttpResponse$BodyHandlers/ofString))]
        (if (= 200 (.statusCode resp))
          (let [body (.body resp)
                data (json/parse-string body)
                results (mapv (fn [r]
                                {:title (get r "title" "")
                                 :url (get r "url" "")
                                 :snippet (-> (get r "text" (get r "snippet" ""))
                                              (subs 0 (min 300 (count (or (get r "text") "")))))
                                 :score (get r "score")})
                              (get data "results" []))]
            (json/generate-string {:results results} {:pretty true}))
          (json/generate-string {:error (str "Exa API returned " (.statusCode resp))}))))))

(comment
  ;; REPL testing
  (require '[tapalakbot.lalafo :as l])

  ;; Smoke test
  (l/smoke-test)

  ;; Single search
  (def client (#'tapalakbot.lalafo/build-client))
  (#'tapalakbot.lalafo/search-page client "iphone" :per-page 3)

  ;; Full search
  (println (l/search {"queries" ["iphone 12" "iphone 13"] "price_max" 30000}))

  ;; Categories
  (println (l/format-categories-prompt @(delay (l/fetch-categories-raw))))

  ;; Search categories
  (println (l/search-categories "наушники"))

  ;; Exa research
  (println (l/exa-research "iPad Air M1 specs 2025"))

  ;; Smoke test
  (l/smoke-test))
