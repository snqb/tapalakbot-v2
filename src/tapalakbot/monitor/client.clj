(ns tapalakbot.monitor.client
  "HTTP client for the Lalafo price monitor API.
   Fetches market intelligence from localhost:8787."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.net URI]
           [java.time Duration]))

;; ════════════════════════════ CONFIG ════════════════════════════

(def ^:private base-url
  (or (System/getenv "MONITOR_API_URL")
      "http://localhost:8787"))

(def ^:private timeout-ms 5000)

;; ════════════════════════════ HTTP ════════════════════════════

(def ^:private client
  (delay
    (.. (HttpClient/newBuilder)
        (connectTimeout (Duration/ofMillis timeout-ms))
        (build))))

(defn- get-json
  "GET a JSON endpoint from the monitor API. Returns parsed map or nil."
  [path]
  (try
    (let [url (str base-url path)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create url))
                      (.timeout (Duration/ofMillis timeout-ms))
                      (.GET)
                      (.build))
          resp (.send @client request (HttpResponse$BodyHandlers/ofString))]
      (if (= 200 (.statusCode resp))
        (json/parse-string (.body resp) true)
        (do (log/warn :monitor-api :status (.statusCode resp) :path path)
            nil)))
    (catch Exception e
      (log/debug :monitor-api-error :path path :error (.getMessage e))
      nil)))

;; ════════════════════════════ PUBLIC API ════════════════════════════

(defn health-check
  "Check if monitor API is reachable."
  []
  (boolean (get-json "/health")))

(defn fetch-start-digest
  "Get formatted price digest for /start greeting.
   Returns {:text \"...\" :categories N :items N :last-scan \"...\"} or nil."
  []
  (get-json "/prices/start"))

(defn fetch-trending
  "Get trending prices per category.
   Returns {:trending {\"iPhone\" {:count N :items [...]}}} or nil."
  []
  (get-json "/prices/trending"))

(defn fetch-categories
  "Get category summary with stats.
   Returns {:categories [{:id N :name \"...\" :item_count N :avg_price N}]} or nil."
  []
  (get-json "/prices/categories"))

(defn search-items
  "Search items by query string.
   Returns {:query \"...\" :count N :items [...]} or nil."
  [query]
  (get-json (str "/prices/search?q=" (java.net.URLEncoder/encode (str query) "UTF-8"))))

(defn fetch-category-items
  "Get all items for a specific category by ID.
   Returns {:items [...] :count N} or nil."
  [category-id]
  (get-json (str "/prices/category/" category-id)))

(defn fetch-item-history
  "Get price history for a specific item.
   Returns {:item {...} :history [...] :count N} or nil."
  [item-id]
  (get-json (str "/prices/history/" item-id)))

(defn fetch-deals
  "Get items priced below average.
   Returns {:deals [...] :count N} or nil."
  []
  (get-json "/prices/deals"))

(defn trigger-scan
  "Trigger an immediate scan."
  []
  (get-json "/scan"))

;; ════════════════════════════ HELPERS ════════════════════════════

(defn format-category-stats
  "Format category stats as readable text for Telegram."
  [categories]
  (let [cats (:categories categories)]
    (when (seq cats)
      (clojure.string/join
       "\n"
       (map (fn [c]
              (str "• " (:name c) " — "
                   (:item_count c) " об\\'яв, "
                   (when-let [p (:avg_price c)]
                     (str "ср. " (format "%,.0f" (double p)) " сом"))))
            cats)))))

(defn format-search-results
  "Format search results as readable text for Telegram."
  [{:keys [query count items]}]
  (when (seq items)
    (str "🔍 «" query "» — " count " об\\'явлений:\n\n"
         (clojure.string/join
          "\n"
          (take 5
                (map (fn [i]
                       (str "• " (when-let [p (:price i)]
                                   (str (format "%,.0f" (double p)) " сом"))
                            " — " (:title i)
                            (when (:url i)
                              (str "\n  🔗 " (:url i)))))
                     items))))))
