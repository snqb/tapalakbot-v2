(ns tapalakbot.mashina
  "Mashina.kg API client with Cloudflare bypass via RiskBypass.
  
  Provides search, listing details, and filter endpoints for the
  Kyrgyz auto marketplace mashina.kg.
  
  API structure (discovered via JS bundle analysis):
  - Base: https://api.mashina.kg/api
  - Filters: /api/filters/{type} (passenger, commercial, moto, parts, service, urgent, specs)
  - Catalog: /api/catalog (main catalog endpoint)
  - Sitemap: https://api.mashina.kg/ru/search/{type}
  
  Example:
    (require '[tapalakbot.mashina :as m])
    (m/search-cars! {:brand \"toyota\" :model \"camry\"})"
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [tapalakbot.riskbypass :as rb]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private base-url "https://api.mashina.kg")
(def ^:private site "mashina.kg")
(def ^:private target-url "https://api.mashina.kg/api/catalog/")

;; ---------------------------------------------------------------------------
;; Session management
;; ---------------------------------------------------------------------------

(def ^:private session-cache (atom nil))

(defn- get-session []
  (or @session-cache
      (when-let [session (rb/get-or-solve-session! site target-url)]
        (reset! session-cache session)
        session)))

(defn invalidate-session!
  "Force refresh the mashina.kg session."
  []
  (reset! session-cache nil)
  (rb/clear-session! site)
  (log/info "Mashina session invalidated"))

;; ---------------------------------------------------------------------------
;; HTTP client
;; ---------------------------------------------------------------------------

(defn- mashina-request
  "Make authenticated request to mashina.kg API."
  [path & {:keys [method query-params] :or {method :get}}]
  (let [session (get-session)
        _ (when-not session
            (throw (ex-info "No valid mashina.kg session" {:path path})))
        url (str base-url path)
        headers {"User-Agent" (:user-agent session)
                 "Accept" "application/json"
                 "Origin" "https://www.mashina.kg"
                 "Referer" "https://www.mashina.kg/"
                 "Cookie" (clojure.string/join "; "
                                               (map (fn [[k v]] (str k "=" v))
                                                    (:cookies session)))}
        opts (cond-> {:headers headers
                      :as :json
                      :socket-timeout 30000
                      :conn-timeout 30000}
               query-params (assoc :query-params query-params)
               (= method :post) (assoc :method :post))]
    (try
      (let [resp (http/request (assoc opts :url url))]
        (when (= 403 (:status resp))
          (log/warn "Got 403 - session may be invalid, refreshing...")
          (invalidate-session!)
          (throw (ex-info "Session expired" {:status 403})))
        (:body resp))
      (catch clojure.lang.ExceptionInfo e
        (if (and (= 403 (:status (ex-data e)))
                 (not (:retried? (ex-data e))))
          (do
            (invalidate-session!)
            (mashina-request path :method method :query-params query-params))
          (throw e)))
      (catch Exception e
        (log/error "Mashina request failed:" (.getMessage e))
        (throw e)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn get-filters
  "Get filter options for a vehicle type.
   Types: passenger, commercial, moto, parts, service, urgent, specs"
  [vehicle-type]
  (log/info "Fetching filters for" vehicle-type)
  (mashina-request (str "/api/filters/" (name vehicle-type))))

(defn search-catalog
  "Search the mashina.kg catalog.
   
   Options:
   - :type - vehicle type (passenger, commercial, etc.)
   - :brand - car brand (toyota, honda, etc.)
   - :model - car model (camry, civic, etc.)
   - :year-from - minimum year
   - :year-to - maximum year
   - :price-from - minimum price KGS
   - :price-to - maximum price KGS
   - :region - region code
   - :page - page number (default 1)
   - :per-page - results per page (default 20)"
  [& {:keys [type brand model year-from year-to price-from price-to region page per-page]
      :or {type "passenger" page 1 per-page 20}}]
  (log/info "Searching mashina.kg catalog:" {:type type :brand brand :model model})
  (let [params (cond-> {"page" page "per_page" per-page}
                 brand (assoc "brand" brand)
                 model (assoc "model" model)
                 year-from (assoc "year_from" year-from)
                 year-to (assoc "year_to" year-to)
                 price-from (assoc "price_from" price-from)
                 price-to (assoc "price_to" price-to)
                 region (assoc "region" region))]
    (mashina-request "/api/catalog" :query-params params)))

(defn get-listing
  "Get details for a specific listing."
  [listing-id]
  (log/info "Fetching listing:" listing-id)
  (mashina-request (str "/api/listings/" listing-id)))

(defn search-by-sitemap
  "Search using the sitemap-style URLs.
   Types: passenger, commercial, motorcycles, special, parts, services, urgent"
  [search-type & {:keys [page] :or {page 1}}]
  (log/info "Searching via sitemap:" search-type)
  (mashina-request (str "/ru/search/" (name search-type))
                   :query-params {"page" page}))

;; ---------------------------------------------------------------------------
;; Convenience functions
;; ---------------------------------------------------------------------------

(defn search-car!
  "Search for a car by brand/model. Returns first page of results."
  [brand model]
  (search-catalog :brand brand :model model))

(defn search-all-cars!
  "Search all cars with optional filters."
  [& {:keys [brand model year-from year-to price-from price-to region]
      :or {}}]
  (search-catalog :type "passenger"
                  :brand brand
                  :model model
                  :year-from year-from
                  :year-to year-to
                  :price-from price-from
                  :price-to price-to
                  :region region))

(defn get-popular-brands
  "Get popular car brands from filters."
  []
  (try
    (let [filters (get-filters :passenger)]
      (log/info "Got filters, extracting brands...")
      filters)
    (catch Exception e
      (log/error "Failed to get brands:" (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; Health check
;; ---------------------------------------------------------------------------

(defn healthcheck
  "Check if mashina.kg API is accessible."
  []
  (try
    (let [session (get-session)]
      (if session
        {:status :ok
         :has-session true
         :cf-clearance (boolean (:cf-clearance session))}
        {:status :error
         :has-session false
         :error "No valid session"}))
    (catch Exception e
      {:status :error
       :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Debug / CLI
;; ---------------------------------------------------------------------------

(defn -main
  "Test mashina.kg client"
  [& args]
  (let [action (or (first args) "health")]
    (case action
      "health" (do
                 (println "Checking mashina.kg health...")
                 (let [result (healthcheck)]
                   (println result)))
      "brands" (do
                 (println "Fetching popular brands...")
                 (let [result (get-popular-brands)]
                   (println result)))
      "search" (let [brand (second args)
                     model (nth args 2 "")]
                 (println "Searching for" brand model "...")
                 (let [result (search-car! brand model)]
                   (println result)))
      (println "Usage: mashina.clj [health|brands|search] [brand] [model]"))))
