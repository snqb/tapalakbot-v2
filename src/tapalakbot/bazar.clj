(ns tapalakbot.bazar
  "Bazar.kg marketplace client for Kyrgyz classifieds.
  
  Provides search and listing parsing for the bazar.kg marketplace.
  No API available - uses HTML parsing with Schema.org structured data.
  
  Example:
    (require '[tapalakbot.bazar :as bazar])
    (bazar/search-cars! \"hyundai\")"
  (:require [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private base-url "https://www.bazar.kg")
(def ^:private user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

;; ---------------------------------------------------------------------------
;; Categories
;; ---------------------------------------------------------------------------

(def categories
  {:transport-cars "/kyrgyzstan/transport/legkovye-avtomobili"
   :transport-moto "/kyrgyzstan/transport/moto"
   :transport-parts "/kyrgyzstan/transport/avtozapchasti-i-aksessuary"
   :transport-commercial "/kyrgyzstan/transport/kommerchesky-i-spec-transport"
   :real-estate "/kyrgyzstan/nedvizhimost"
   :electronics "/kyrgyzstan/elektronika"
   :home-garden "/kyrgyzstan/dlya-doma-i-dachi"
   :children "/kyrgyzstan/detskiy-mir"
   :clothing "/kyrgyzstan/personal-items"
   :services "/kyrgyzstan/uslugi"
   :jobs "/kyrgyzstan/rabota"
   :animals "/kyrgyzstan/zhivotnye"})

;; ---------------------------------------------------------------------------
;; Regex patterns (plain strings, avoiding Clojure reader escaping)
;; ---------------------------------------------------------------------------

(def ^:private re-listing-id
  (re-pattern "class=\"title\"><a href=\"/details/([^\"]+)\""))

(def ^:private re-title-text
  (re-pattern "(?s)class=\"title\"><a href=\"/details/[^\"]+\">\\s*([^<]+)\\s*</p>"))

(def ^:private re-price
  (re-pattern "itemprop=\"price\" content=\"([^\"]+)\""))

(def ^:private re-currency
  (re-pattern "itemprop=\"priceCurrency\" content=\"([^\"]+)\""))

(def ^:private re-url
  (re-pattern "itemprop=\"url\" content=\"([^\"]+)\""))

(def ^:private re-description
  (re-pattern "itemprop=\"description\" content=\"([^\"]+)\""))

(def ^:private re-image
  (re-pattern "itemprop=\"image\" content=\"([^\"]+)\""))

(def ^:private re-category-name
  (re-pattern "itemprop=\"name\" content=\"([^\"]+)\""))

;; ---------------------------------------------------------------------------
;; HTML parsing helpers
;; ---------------------------------------------------------------------------

(defn- extract-listings
  "Extract listings from HTML using Schema.org structured data.
   
   HTML structure per listing:
   - itemprop=name content='Kategorija: Hyundai' (category name, inside card)
   - itemprop=category content='...' (category breadcrumb)
   - itemprop=image content='...' (image)
   - itemprop=name content='Hyundai Grandeur 2022' (PRODUCT TITLE)
   - itemprop=url content='...' (url)
   - itemprop=description content='...' (description)
   
   We split by category marker, then the first name after each split is the product title."
  [html]
  (let [;; Split by category marker to isolate each listing
        parts (str/split html (re-pattern "<meta itemprop=.category."))
        ;; Skip the first part (before any listing)
        listing-parts (rest parts)]
    (vec
     (keep
      (fn [part]
        (let [;; The first name after category marker is the product title
              names (re-seq re-category-name part)
              title (when (seq names) (second (first names)))
              ;; Extract price from the part BEFORE category (inside the card)
              ;; We need to look at the previous chunk - use the url to find price
              ;; Actually, price is also in this chunk via itemprop=price
              price-match (first (re-seq re-price part))
              price-str (second price-match)
              price (when price-str
                      (try (Long/parseLong price-str) (catch Exception _ nil)))
              currency-match (first (re-seq re-currency part))
              currency (second currency-match)
              url-match (first (re-seq re-url part))
              url (second url-match)
              desc-match (first (re-seq re-description part))
              desc (second desc-match)
              img-match (first (re-seq re-image part))
              image (second img-match)]
          (when (and title (not (str/starts-with? title "Kategor")))
            {:id (when url (second (re-find #"/details/(.+)" url)))
             :title (str/trim title)
             :price price
             :currency (or currency "KGS")
             :url url
             :description desc
             :image image})))
      listing-parts))))

(defn- extract-total-pages
  "Extract total pages from pagination."
  [html]
  (let [pages (re-seq #"page=(\d+)" html)
        nums (map #(Long/parseLong (second %)) pages)]
    (when (seq nums)
      (apply max nums))))

;; ---------------------------------------------------------------------------
;; HTTP client
;; ---------------------------------------------------------------------------

(defn- fetch-page
  "Fetch a page from bazar.kg."
  [path & {:keys [page] :or {page 1}}]
  (let [url (if (> page 1)
              (str base-url path "?page=" page)
              (str base-url path))]
    (log/info "Fetching:" url)
    (try
      (let [resp (http/get url
                           {:headers {"User-Agent" user-agent
                                      "Accept" "text/html,application/xhtml+xml"
                                      "Accept-Language" "ru-RU,ru;q=0.9,en;q=0.8"}
                            :as :html
                            :socket-timeout 30000
                            :conn-timeout 30000})]
        (when (= 200 (:status resp))
          (:body resp)))
      (catch Exception e
        (log/error "Failed to fetch" url ":" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn search
  "Search bazar.kg by category.
   
   Options:
   - :category - category key from `categories` map (default :transport-cars)
   - :page - page number (default 1)
   - :brand - filter by brand name"
  [& {:keys [category page brand]
      :or {category :transport-cars page 1}}]
  (let [path (get categories category category)
        html (fetch-page path :page page)]
    (if html
      (let [listings (extract-listings html)
            total-pages (extract-total-pages html)
            filtered (if brand
                       (filter #(str/includes?
                                 (str/lower-case (:title % ""))
                                 (str/lower-case brand))
                               listings)
                       listings)]
        {:listings (vec filtered)
         :page page
         :total-pages total-pages
         :has-more (< page (or total-pages 1))})
      {:listings [] :page page :total-pages 0 :has-more false})))

(defn search-all-pages
  "Search and collect results from multiple pages."
  [& {:keys [category brand max-pages]
      :or {category :transport-cars max-pages 3}}]
  (loop [page 1
         all-listings []]
    (if (> page max-pages)
      {:listings all-listings :total (count all-listings)}
      (let [result (apply search :category category :page page
                          (when brand [:brand brand]))
            listings (:listings result)]
        (if (empty? listings)
          {:listings all-listings :total (count all-listings)}
          (recur (inc page) (vec (concat all-listings listings))))))))

(defn get-listing
  "Get detailed information for a specific listing."
  [listing-id]
  (let [html (fetch-page (str "/details/" listing-id))]
    (when html
      (let [title (second (re-find #"<h1[^>]*>([^<]+)</h1>" html))
            price (second (re-find #"<span class=\"sub\">\s*([^<]+)" html))
            description (second (re-find #"<p class=\"description\"[^>]*>([^<]+)" html))
            location (second (re-find #"<div class=\"adress\">\s*([^<]+)" html))]
        {:id listing-id
         :title (str/trim (or title ""))
         :price price
         :description (str/trim (or description ""))
         :location (str/trim (or location ""))
         :url (str base-url "/details/" listing-id)}))))

(defn search-cars!
  "Convenience function to search for cars by brand."
  [brand & {:keys [page max-pages] :or {page 1 max-pages 1}}]
  (if (> max-pages 1)
    (search-all-pages :category :transport-cars :brand brand :max-pages max-pages)
    (search :category :transport-cars :brand brand :page page)))

;; ---------------------------------------------------------------------------
;; Debug / CLI
;; ---------------------------------------------------------------------------

(defn -main
  "Test bazar.kg client"
  [& args]
  (let [action (or (first args) "search")
        brand (second args)]
    (case action
      "search" (do
                 (println "Searching bazar.kg for" (or brand "all cars") "...")
                 (let [result (if brand
                                (search-cars! brand)
                                (search :category :transport-cars))]
                   (println "Found" (count (:listings result)) "listings")
                   (doseq [listing (take 5 (:listings result))]
                     (println "  -" (:title listing) "|" (:price listing) (:currency listing)))))
      "categories" (do
                     (println "Available categories:")
                     (doseq [[k v] categories]
                       (println "  " k "->" v)))
      (println "Usage: bazar.clj [search|categories] [brand]"))))
