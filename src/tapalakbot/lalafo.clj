(ns tapalakbot.lalafo
  "Direct Lalafo.kg API client using Java 11 HttpClient.
   Replaces the Python CLI shell-out approach — no subprocess, no deadlocks,
   no stderr noise, no uv startup cost.

   API: GET https://lalafo.kg/api/search/v3/feed/search?q=...&expand=url&page=...
   Returns JSON with items[], _meta{totalCount, hasMore}.
   
   Quality pre-filter (ported from lalafo_cli.py):
   - Must have price > 50
   - Title >= 8 chars
   - Not ALL CAPS (>15 chars)
   - Must have images"
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async :refer [<! >! go go-loop chan timeout close!]]
   [clojure.string :as str]
   [clojure.tools.logging :as log])
  (:import
   [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
   [java.net URI]
   [java.time Duration]))

;; ══════════════════════════ CONFIG ══════════════════════════

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

;; ══════════════════════════ HTTP ══════════════════════════

(defn- build-client []
  "Create a Java HttpClient with 10s connect timeout, 20s request timeout."
  (.. (HttpClient/newBuilder)
      (connectTimeout (Duration/ofSeconds 10))
      (build)))

(defn- get-json
  "GET a Lalafo API endpoint, parse JSON, with retry.
   Returns parsed JSON map, or nil on failure."
  ([client path params]
   (get-json client path params 3))
  ([client path params retries]
   (let [uri (str base-url "/" (str/replace path #"^/" ""))
         query-str (when (seq params)
                     (str "?" (str/join "&"
                                        (map (fn [[k v]] (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8")))
                                             params))))
         full-url (str uri (or query-str ""))
         request (let [builder (doto (HttpRequest/newBuilder)
                                 (.uri (URI/create full-url))
                                 (.timeout (Duration/ofSeconds 20)))]
                   (reduce-kv (fn [b k v] (.header b k v))
                              builder
                              default-headers)
                   (-> builder (.GET) (.build)))]
     (try
       (let [resp (.send client request (HttpResponse$BodyHandlers/ofString))
             status (.statusCode resp)
             body (.body resp)]
         (log/info :lalafo-http status :url (subs full-url 0 (min 80 (count full-url))))
         (case status
           200 (try (json/parse-string body)
                    (catch Exception e
                      (log/warn :lalafo-parse-error (.getMessage e) :body (subs body 0 (min 100 (count body))))
                      nil))
           (401 403 419) nil           ; auth/captcha — just skip
           (if (pos? retries)
             (do (Thread/sleep (* (- 4 retries) 2000))
                 (get-json client path params (dec retries)))
             (do (log/warn :lalafo-http-error status :url full-url)
                 nil))))
       (catch java.net.http.HttpConnectTimeoutException e
         (log/warn :lalafo-timeout full-url)
         (if (pos? retries)
           (do (Thread/sleep 2000)
               (get-json client path params (dec retries)))
           nil))
       (catch Exception e
         (log/warn :lalafo-error (.getMessage e) :url full-url)
         nil)))))

;; ══════════════════════════ QUALITY FILTER ══════════════════════════

(defn- quality-filter
  "Deterministic quality pre-filter. Removes obvious junk.
   Rules: must have price > 50, title >= 8 chars, not ALL CAPS, has images.
   Ported from lalafo_cli.py."
  [items]
  (filterv (fn [item]
             (let [price (get item "price")
                   title (get item "title" "")
                   images (get item "images")]
               (and price
                    (> price 50)
                    (>= (count title) 8)
                    (not (and (= title (str/upper-case title))
                              (> (count title) 15)))
                    (seq images))))
           items))

;; ══════════════════════════ API CALLS ══════════════════════════

(defn- search-page
  "Search Lalafo for a single query, single page.
   Returns {:items [...] :total-count N :has-more? boolean}."
  [client query & {:keys [category-id price-min price-max city-id page per-page]
                   :or {city-id 103184 page 1 per-page 200}}]
  (let [params (cond-> {:expand "url" :page page :per-page per-page}
                 query (assoc :q query)
                 category-id (assoc :category_id category-id)
                 price-min (assoc :price_from price-min)
                 price-max (assoc :price_to price-max)
                 city-id (assoc :city_id city-id))
        data (get-json client "search/v3/feed/search" params)]
    (if data
      (let [meta (get data "_meta")
            items (get data "items" [])]
        {:items items
         :total-count (get meta "totalCount" (count items))
         :has-more? (or (get meta "hasMore" false) (get meta "has_more" false))})
      {:items [] :total-count 0 :has-more? false})))

(defn- search-all-pages
  "Search all pages for one query, returns vector of unique items.
   Returns [found-count items pages-scanned]."
  [client query & {:keys [category-id price-min price-max city-id max-pages per-page]
                   :or {city-id 103184 max-pages 3 per-page 200}}]
  (loop [page 1
         seen (transient {})
         pages 0]
    (if (> page max-pages)
      (let [ps (persistent! seen)]
        [(count ps) (vals ps) pages])
      (let [{:keys [items has-more?]} (search-page client query
                                                   :category-id category-id
                                                   :price-min price-min
                                                   :price-max price-max
                                                   :city-id city-id
                                                   :page page
                                                   :per-page per-page)]
        (if (or (empty? items) (zero? (count items)))
          (let [ps (persistent! seen)]
            [(count ps) (vals ps) (inc pages)])
          (let [new-seen (reduce (fn [s item]
                                   (if (contains? s (get item "id"))
                                     s
                                     (assoc! s (get item "id") item)))
                                 seen
                                 items)]
            (if (or (not has-more?) (empty? items))
              (let [ps (persistent! new-seen)]
                [(count ps) (vals ps) (inc pages)])
              (recur (inc page) new-seen (inc pages)))))))))

;; ══════════════════════════ MAIN SEARCH ══════════════════════════

(defn search
  "Multi-query parallel search across Lalafo.kg.
   Args map:
     :queries      — vector of search strings (up to 6)
     :category-id  — optional filter
     :price-min    — min price
     :price-max    — max price
     :city-id      — default 103184 (Bishkek)
     :max-pages    — max pages per query (default 3)
     :per-page     — items per page (default 200)
     :candidate-limit — max items to return (default 250)
   
   Returns JSON string (for LLM tool output compatibility)."
  [{:strs [queries category_id price_min price_max city_id max_pages per_page candidate_limit]
    :or {city_id 103184 max_pages 3 per_page 200 candidate_limit 250}
    :as _args}]
  (let [qs (if (sequential? queries) (vec queries) [(str queries)])
        ;; Limit to 6 parallel queries
        qs (take 6 qs)
        client (build-client)
        quality-min (max 50 (or price_min 0))]
    (loop [results []
           [q & more] qs]
      (if q
        (let [[found items pages] (search-all-pages client q
                                                    :category-id category_id
                                                    :price-min price_min
                                                    :price-max price_max
                                                    :city-id city_id
                                                    :max-pages max_pages
                                                    :per-page per_page)]
          (recur (conj results {:query q :found found :items items :pages pages})
                 more))
        ;; All queries done — deduplicate, quality-filter, format
        (let [all-items (reduce (fn [acc {:keys [items]}]
                                  (reduce (fn [m item] (assoc m (get item "id") item)) acc items))
                                {}
                                results)
              total-raw (count all-items)
              total-pages (reduce + (map :pages results))
              filtered (quality-filter (vals all-items))
              ;; Apply price filter on top of quality filter
              with-price (if price_max
                           (filterv #(or (nil? (get % "price")) (<= (get % "price") price_max)) filtered)
                           filtered)
              total-qf (count with-price)
              ;; Compact format for LLM
              cand-limit (min (int candidate_limit) 250)
              items-out (mapv (fn [item]
                                (let [images (get item "images")
                                      raw-url (or (get item "url") "")
                                      full-url (if (str/starts-with? raw-url "http")
                                                 raw-url
                                                 (str "https://lalafo.kg" raw-url))]
                                  {:id (get item "id")
                                   :title (subs (or (get item "title") "") 0 (min 80 (count (get item "title" ""))))
                                   :price (get item "price")
                                   :currency (or (get item "currency") "KGS")
                                   :url full-url
                                   :desc (-> (or (get item "description") "")
                                             (str/replace #"\n" " ")
                                             (subs 0 (min 80 (count (or (get item "description") "")))))
                                   :images (when (seq images)
                                             (mapv (fn [img]
                                                     (if (string? img)
                                                       {"original_url" img "thumbnail_url" img}
                                                       (select-keys img ["original_url" "thumbnail_url"])))
                                                   images))}))
                              (take cand-limit with-price))]
          (json/generate-string
           {:found total-qf
            :truncated (> total-qf cand-limit)
            :items items-out
            :stats {:raw total-raw :filtered total-qf :pages total-pages}}
           {:pretty true}))))))

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
  (l/get-categories-raw))
