(ns tapalakbot.monitor.api
  "HTTP API server for Lalafo price monitor.
   Endpoints:
     GET /health              — health check
     GET /prices/trending     — latest prices per category
     GET /prices/deals        — items below average price
     GET /prices/history/:id  — price history for an item
     GET /prices/category/:id — category summary + items
     GET /prices/stats        — overall stats
     GET /prices/categories   — list all categories with stats"
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [ring.util.response :as response]
            [tapalakbot.monitor.store :as store]
            [tapalakbot.monitor.scanner :as scanner]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════════════ HELPERS ════════════════════════════

(defn- json-response
  "Create a JSON response with status code."
  ([data] (json-response 200 data))
  ([status data]
   (-> (response/response (json/generate-string data))
       (response/content-type "application/json")
       (response/status status))))

(defn- parse-path-id
  "Extract ID from URI path. /prices/history/123 → 123"
  [uri prefix]
  (when-let [[_ id-str] (re-find (re-pattern (str prefix "/(\\d+)")) uri)]
    (Long/parseLong id-str)))

;; ════════════════════════════ ROUTES ════════════════════════════

(defn- handle-health [_]
  (let [stats (store/get-stats)]
    (json-response
     {:status "ok"
      :scanner {:running (scanner/scanner-running?)}
      :db {:categories (:categories stats)
           :items (:items stats)
           :snapshots (:snapshots stats)
           :last-scan (:last-scan stats)}})))

(defn- handle-trending [_]
  (let [trending (store/get-trending)]
    (json-response
     {:trending (into {}
                      (map (fn [[cat-name items]]
                             [cat-name
                              {:count (count items)
                               :items (mapv (fn [i]
                                              {:id (:id i)
                                               :title (:title i)
                                               :price (:current_price i)
                                               :currency (:currency i)
                                               :url (:url i)
                                               :city (:city i)
                                               :image (:image_url i)
                                               :min_price (:min_price i)
                                               :max_price (:max_price i)
                                               :snapshots (:snapshot_count i)})
                                            items)}])
                           trending))})))

(defn- handle-deals [_]
  (let [deals (store/get-deals)]
    (json-response
     {:deals (mapv (fn [d]
                     {:id (:id d)
                      :title (:title d)
                      :price (:current_price d)
                      :currency (:currency d)
                      :url (:url d)
                      :category (:category_name d)
                      :avg_price (:avg_price d)
                      :discount_pct (:discount_pct d)
                      :samples (:samples d)})
                   deals)
      :count (count deals)})))

(defn- handle-history [request]
  (if-let [item-id (parse-path-id (:uri request) "/prices/history")]
    (let [history (store/get-history item-id)
          item (store/get-item item-id)]
      (json-response
       {:item (when item
                {:id (:id item)
                 :title (:title item)
                 :url (:url item)
                 :current_price (:current_price item)
                 :category (:category_name item)})
        :history (mapv (fn [h]
                         {:price (:price h)
                          :date (:scanned_at h)})
                       history)
        :count (count history)}))
    (json-response 400 {:error "Missing item ID"})))

(defn- handle-category [request]
  (if-let [cat-id (parse-path-id (:uri request) "/prices/category")]
    (let [items (store/get-items-by-category cat-id)
          history (store/get-history-by-category cat-id)]
      (json-response
       {:items (mapv (fn [i]
                       {:id (:id i)
                        :title (:title i)
                        :price (:current_price i)
                        :currency (:currency i)
                        :url (:url i)
                        :city (:city i)})
                     items)
        :history history
        :count (count items)}))
    (json-response 400 {:error "Missing category ID"})))

(defn- handle-categories [_]
  (let [summary (store/get-category-summary)]
    (json-response
     {:categories (mapv (fn [c]
                          {:id (:id c)
                           :name (:name c)
                           :item_count (:item_count c)
                           :avg_price (:avg_price c)
                           :min_price (:min_price c)
                           :max_price (:max_price c)
                           :snapshots (:snapshot_count c)})
                        summary)})))

(defn- handle-stats [_]
  (let [stats (store/get-stats)]
    (json-response
     {:categories (:categories stats)
      :items (:items stats)
      :snapshots (:snapshots stats)
      :last-scan (:last-scan stats)
      :price-range (:price-range stats)
      :scanner {:running (scanner/scanner-running?)}})))

(defn- handle-scan-now [_]
  (future (scanner/scan-all!))
  (json-response {:status "scan started"}))

(defn- format-price [p]
  (if (and p (> p 0))
    (str (format "%,.0f" (double p)) " KGS")
    "?"))

(defn- handle-start-digest [_]
  "Formatted price digest for Telegram /start."
  (let [summary (store/get-category-summary)
        stats (store/get-stats)
        lines (vec
               (concat
                [(str "📊 Рынок Lalafo.kg — " (:items stats) " товаров, "
                      (:snapshots stats) " снимков цен")
                 (str "🕐 Обновлено: " (or (:last-scan stats) "нет данных"))
                 ""]
                (mapv (fn [c]
                        (str "*" (:name c) "* — "
                             (:item_count c) " объявлений\n"
                             "  💰 Средняя: " (format-price (:avg_price c)) "\n"
                             "  📉 Мин: " (format-price (:min_price c))
                             " — 📈 Макс: " (format-price (:max_price c))))
                      summary)
                [""
                 "🔍 Ищите товар — я помогу найти лучшую цену!"]))]
    (json-response
     {:text (str/join "\n" lines)
      :categories (count summary)
      :items (:items stats)
      :last-scan (:last-scan stats)})))

(defn- handle-search [request]
  "Search items by query string."
  (let [query (get-in request [:params "q"])]
    (if (str/blank? query)
      (json-response 400 {:error "Missing ?q= parameter"})
      (let [summary (store/get-category-summary)
            ;; Search across all items by title match
            items (jdbc/execute! (store/get-datasource)
                                 ["SELECT i.*, c.name as category_name
                                   FROM monitor_items i
                                   JOIN monitor_categories c ON i.category_id = c.id
                                   WHERE i.title LIKE ? AND i.current_price > 0 AND i.current_price < 500000
                                   ORDER BY i.current_price ASC LIMIT 20"
                                  (str "%" query "%")]
                                 {:builder-fn rs/as-unqualified-maps})]
        (json-response
         {:query query
          :count (count items)
          :items (mapv (fn [i]
                         {:id (:id i)
                          :title (:title i)
                          :price (:current_price i)
                          :currency (:currency i)
                          :url (:url i)
                          :category (:category_name i)})
                       items)})))))

;; ════════════════════════════ ROUTER ════════════════════════════

(defn- route [request]
  (let [{:keys [uri request-method]} request]
    (case request-method
      :get
      (cond
        (= uri "/health")            (handle-health request)
        (= uri "/prices/trending")   (handle-trending request)
        (= uri "/prices/deals")      (handle-deals request)
        (= uri "/prices/stats")      (handle-stats request)
        (= uri "/prices/categories") (handle-categories request)
        (= uri "/prices/start")      (handle-start-digest request)
        (re-find #"/prices/search" uri) (handle-search request)
        (re-find #"/prices/history/\d+" uri)   (handle-history request)
        (re-find #"/prices/category/\d+" uri)  (handle-category request)
        :else (json-response 404 {:error "Not found" :available-endpoints
                                  ["/health" "/prices/trending" "/prices/deals"
                                   "/prices/stats" "/prices/categories"
                                   "/prices/history/:id" "/prices/category/:id"]}))

      :post
      (cond
        (= uri "/scan") (handle-scan-now request)
        :else (json-response 404 {:error "Not found"}))

      (json-response 405 {:error "Method not allowed"}))))

;; ════════════════════════════ MIDDLEWARE ════════════════════════════

(def app
  (-> route
      params/wrap-params))

;; ════════════════════════════ SERVER ════════════════════════════

(defonce ^:private server (atom nil))

(defn start-server!
  "Start the HTTP API server."
  [& {:keys [port] :or {port 8787}}]
  (when @server
    (log/warn :server-already-running))
  (let [s (jetty/run-jetty app {:port port :join? false})]
    (reset! server s)
    (log/info :api-server-started :port port)
    s))

(defn stop-server!
  "Stop the HTTP API server."
  []
  (when-let [s @server]
    (.stop ^org.eclipse.jetty.server.Server s)
    (reset! server nil)
    (log/info :api-server-stopped)))

(defn server-running?
  "Check if server is running."
  []
  (boolean (and @server (.isStarted ^org.eclipse.jetty.server.Server @server))))
