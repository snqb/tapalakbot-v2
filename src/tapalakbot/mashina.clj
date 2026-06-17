(ns tapalakbot.mashina
  "Mashina.kg API client — Kyrgyzstan's largest auto marketplace.
  
  API base: https://www.mashina.kg/api
  No authentication needed for public endpoints.
  
  Endpoints:
  - /api/ads/listings - Search listings
  - /api/ads/count-total - Count total ads
  - /api/categories - Get categories
  - /api/public/data - Get filters
  - /api/ads/slug/{slug} - Get listing by slug
  - /api/analytics/price-trends/{slug} - Price trends
  
  Example:
    (require '[tapalakbot.mashina :as m])
    (m/search-cars! {:brand \"hyundai\"})"
  (:require [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private base-url "https://www.mashina.kg/api")
(def ^:private user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

;; ---------------------------------------------------------------------------
;; HTTP client
;; ---------------------------------------------------------------------------

(defn- mashina-request
  "Make request to mashina.kg API."
  [path & {:keys [query-params] :or {}}]
  (let [url (str base-url path)]
    (log/info "Fetching:" url)
    (try
      (let [resp (http/get url
                           {:headers {"User-Agent" user-agent
                                      "Accept" "application/json"}
                            :query-params query-params
                            :as :json
                            :socket-timeout 30000
                            :conn-timeout 30000})]
        (:body resp))
      (catch Exception e
        (log/error "Mashina request failed:" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn search-cars
  "Search mashina.kg for cars.
   
   Options:
   - :page - page number (default 1)
   - :size - results per page (default 20)
   - :query - search query (brand, model, etc.)"
  [& {:keys [page size query]
      :or {page 1 size 20}}]
  (let [params (cond-> {"page" page "size" size}
                 query (assoc "q" query))
        result (mashina-request "/ads/listings" :query-params params)]
    (when result
      {:listings (mapv (fn [item]
                         {:id (:id item)
                          :title (:title item)
                          :slug (:slug item)
                          :price (when-let [p (first (:prices item))]
                                   {:amount (:amount p)
                                    :currency (:currency p)})
                          :description (:description item)
                          :year (some #(when (= (:slug %) "year") (:value_number %))
                                      (:attributes item))
                          :mileage (some #(when (= (:slug %) "mileage")
                                            (get-in % [:value_json :value]))
                                         (:attributes item))
                          :engine (some #(when (= (:slug %) "engine_volume")
                                           (:value_number %))
                                        (:attributes item))
                          :gearbox (some #(when (= (:slug %) "gearbox")
                                            (get-in % [:value_json :name]))
                                         (:attributes item))
                          :city (some #(when (= (:slug %) "city")
                                         (get-in % [:value_json :name]))
                                      (:attributes item))
                          :images (mapv #(or (:big %) (:medium %) (:thumb %)) (:images item))
                          :url (str "https://www.mashina.kg/ru/" (:slug item))})
                       (:items result))
       :total (:total result)
       :page (:page result)
       :pages (:pages result)})))

(defn get-categories
  "Get available categories."
  []
  (mashina-request "/categories"))

(defn get-listing
  "Get listing details by slug."
  [slug]
  (mashina-request (str "/ads/slug/" slug)))

(defn count-total
  "Count total listings."
  []
  (let [result (mashina-request "/ads/count-total")]
    (:total result)))

;; ---------------------------------------------------------------------------
;; Convenience functions
;; ---------------------------------------------------------------------------

(defn search-cars!
  "Convenience function to search for cars by query."
  [query & {:keys [page size] :or {page 1 size 20}}]
  (search-cars :query query :page page :size size))

(defn healthcheck
  "Check if mashina.kg API is accessible."
  []
  (try
    (let [total (count-total)]
      (if total
        {:status :ok :total-listings total}
        {:status :error :error "No data returned"}))
    (catch Exception e
      {:status :error :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Debug / CLI
;; ---------------------------------------------------------------------------

(defn -main
  "Test mashina.kg client"
  [& args]
  (let [action (or (first args) "search")
        brand (second args)]
    (case action
      "search" (do
                 (println "Searching mashina.kg for" (or brand "all cars") "...")
                 (let [result (if brand
                                (search-cars! brand)
                                (search-cars))]
                   (println "Found" (:total result) "total listings")
                   (doseq [l (take 5 (:listings result))]
                     (println "  -" (:title l) "|" (get-in l [:price :amount]) (get-in l [:price :currency])))))
      "health" (do
                 (println "Checking mashina.kg health...")
                 (println (healthcheck)))
      (println "Usage: mashina.clj [search|health] [brand]"))))
