(ns tapalakbot.lalafo-collection
  "Pure normalization and deterministic collection helpers for Lalafo results."
  (:require [clojure.string :as str])
  (:import [java.net URI]))

(def lalafo-host "https://lalafo.kg")

(defn canonical-url
  "Return an HTTPS Lalafo listing URL, or nil for malformed/non-Lalafo URLs."
  [raw-url]
  (when (and (string? raw-url) (not (str/blank? raw-url)))
    (let [url (cond
                 (str/starts-with? raw-url "//") (str "https:" raw-url)
                 (str/starts-with? raw-url "/") (str lalafo-host raw-url)
                 :else raw-url)]
      (try
        (let [uri (URI/create url)
              scheme (some-> (.getScheme uri) str/lower-case)
              host (some-> (.getHost uri) str/lower-case)]
          (when (and (#{"http" "https"} scheme)
                     (= "lalafo.kg" host)
                     (nil? (.getUserInfo uri))
                     (or (= -1 (.getPort uri)) (= 443 (.getPort uri))))
            (if (= "http" scheme)
              (str "https://lalafo.kg" (.getRawPath uri)
                   (when (.getRawQuery uri) (str "?" (.getRawQuery uri)))
                   (when (.getRawFragment uri) (str "#" (.getRawFragment uri))))
              url)))
        (catch IllegalArgumentException _ nil)))))

(defn- image-url
  [image]
  (let [raw (cond
               (string? image) image
               (map? image) (or (get image "original_url")
                                (get image "originalUrl")
                                (get image "url")
                                (get image "image_url")
                                (get image "thumbnail_url"))
               :else nil)]
    (when (and (string? raw) (not (str/blank? raw)))
      (try
        (let [uri (URI/create raw)
              scheme (some-> (.getScheme uri) str/lower-case)]
          (when (and (#{"http" "https"} scheme)
                     (not (str/blank? (.getHost uri)))
                     (nil? (.getUserInfo uri)))
            raw))
        (catch IllegalArgumentException _ nil)))))

(defn source-images
  "Extract original source image URLs without replacing them with thumbnails."
  [images]
  (->> (if (sequential? images) images [])
       (map image-url)
       (filter #(and (string? %) (not (str/blank? %))))
       vec))

(defn normalize-item
  "Preserve the source listing fields needed by downstream selectors.
   Invalid IDs or non-HTTP(S) source URLs are rejected; no quality heuristics are applied."
  [item]
  (when (map? item)
    (let [id (get item "id")
          url (canonical-url (or (get item "url")
                                 (get item "canonical_url")
                                 (get item "canonicalUrl")))
          title (or (get item "title") "")
          desc (or (get item "description") (get item "desc") "")
          images (source-images (get item "images"))
          image (or (image-url (get item "image"))
                    (image-url (get item "image_url"))
                    (first images))
          price-kgs (or (get item "price_kgs")
                        (get item "priceKgs")
                        (get item "price_kgs_equivalent"))]
      (when (and (some? id) url)
        {"id" id
         "title" (if (string? title) title (str title))
         "desc" (if (string? desc) desc (str desc))
         "price" (get item "price")
         "currency" (get item "currency")
         "price_kgs" price-kgs
         "url" url
         "image" image
         "images" images
         "city" (or (get item "city") (get item "location"))
         "condition" (get item "condition")
         "params" (or (get item "params")
                       (get item "parameters")
                       (get item "attributes"))}))))

(defn round-robin-items
  "Interleave page result item vectors in query order, preserving each page order."
  [page-results]
  (let [item-vectors (mapv #(vec (or (:items %) [])) page-results)
        max-items (reduce max 0 (map count item-vectors))]
    (vec (for [offset (range max-items)
               items item-vectors
               :let [item (nth items offset nil)]
               :when item]
           item))))

(defn unique-normalized
  "Normalize and deduplicate items in encounter order by string source ID."
  [items seen]
  (reduce (fn [[out ids] item]
            (if-let [normalized (normalize-item item)]
              (let [id (str (get normalized "id"))]
                (if (contains? ids id)
                  [out ids]
                  [(conj out normalized) (conj ids id)]))
              [out ids]))
          [[] seen]
          items))


(defn novel-ids-by-query
  "Claim IDs in query order so duplicate variants do not trigger extra pages."
  [page-results seen]
  (loop [remaining page-results
         claimed seen
         result {}]
    (if-let [page (first remaining)]
      (let [ids (->> (:items page)
                     (keep normalize-item)
                     (map #(str (get % "id")))
                     set)
            novel (set (remove #(contains? claimed %) ids))]
        (recur (next remaining)
               (into claimed ids)
               (assoc result (:query-index page) novel)))
      result)))

(defn normalize-currency
  [currency]
  (some-> currency str str/trim str/upper-case
          (str/replace "СОМ" "KGS")))

(defn- numeric
  [value]
  (cond
    (number? value) value
    (string? value) (try (bigdec (str/replace value #"[ ,]" ""))
                         (catch NumberFormatException _ nil))
    :else nil))

(defn comparable-price
  "Return a price only when it is comparable to the requested budget currency."
  [item budget-currency]
  (let [budget-currency (normalize-currency (or budget-currency "KGS"))
        currency (normalize-currency (get item "currency"))
        raw-price (numeric (get item "price"))
        price-kgs (numeric (get item "price_kgs"))]
    (cond
      (and (= budget-currency "KGS") price-kgs) price-kgs
      (and (= budget-currency "KGS") (= currency "KGS")) raw-price
      (and (= currency budget-currency) (not= budget-currency "KGS")) raw-price
      :else nil)))

(defn within-budget?
  "Compare only known same-currency prices; unknown/missing prices remain visible."
  [item price-min price-max budget-currency]
  (if-let [price (comparable-price item budget-currency)]
    (and (or (nil? price-min) (<= (bigdec price-min) price))
         (or (nil? price-max) (<= price (bigdec price-max))))
    true))

(defn has-next-page?
  [{:keys [has-more? total-count items page]} per-page]
  (or has-more?
      (and (number? total-count)
           (pos? (count items))
           (> total-count (* page per-page)))))

(defn- fetch-concurrently
  "Run at most three page requests at a time, retaining request order."
  [fetch-page requests]
  (vec
   (mapcat (fn [batch]
             (let [futures (mapv (fn [request]
                                   (future
                                     (try
                                       (assoc (fetch-page request)
                                              :query-index (:query-index request)
                                              :page (:page request))
                                       (catch Exception e
                                         {:ok? false :failed? true :items []
                                          :page (:page request)
                                          :query-index (:query-index request)
                                          :error e}))))
                                 batch)]
               (mapv deref futures)))
           (partition-all 3 requests))))

(defn collect-candidates
  "Collect bounded source candidates with injected page fetches.
   The callback receives a request map and returns a page result."
  [queries opts fetch-page]
  (let [limit (min 200 (max 1 (int (or (:candidate-limit opts) 200))))
        max-pages (max 1 (int (or (:max-pages opts) 5)))
        per-page (max 1 (int (or (:per-page opts) 200)))
        base-request (fn [index query page]
                       {:query-index index :query query :page page
                        :category-id (:category-id opts)
                        :price-min (:price-min opts)
                        :price-max (:price-max opts)
                        :city-id (:city-id opts)
                        :per-page per-page})]
    (loop [states (mapv (fn [index query]
                          {:query-index index :query query :next? true :page 1
                           :pages 0 :failed? false :succeeded? false})
                        (range) queries)
           seen #{}
           items []
           raw-count 0
           truncated? false
           raw-ids #{}]
      (let [active (filterv #(and (:next? %)
                                  (<= (:page %) max-pages))
                            states)]
        (if (or (empty? active) (>= (count items) limit))
          {:items (vec (take limit items))
           :raw raw-count
           :pages (reduce + 0 (map :pages states))
           :failed-queries (count (filter :failed? states))
           :succeeded-queries (count (filter :succeeded? states))
           :truncated (boolean (or truncated? (some :next? active)
                                   (some :truncated? states)))}
          (let [requests (mapv #(base-request (:query-index %) (:query %) (:page %)) active)
                results (fetch-concurrently fetch-page requests)
                fetched-ids (into raw-ids (keep #(some-> (get % "id") str))
                                  (mapcat :items results))
                by-index (sort-by :query-index results)
                page-items (round-robin-items by-index)
                [new-items seen'] (unique-normalized page-items seen)
                novel-ids (novel-ids-by-query by-index seen)
                room (max 0 (- limit (count items)))
                items' (into items (take room new-items))
                overflow? (> (count new-items) room)
                states' (mapv (fn [state]
                                (if-let [result (first (filter #(= (:query-index %)
                                                                     (:query-index state))
                                                               results))]
                                  (let [novel? (seq (get novel-ids (:query-index state)))
                                        failed? (or (:failed? state) (:failed? result))
                                        succeeded? (or (:succeeded? state) (:ok? result))
                                        next? (and (:ok? result)
                                                   novel?
                                                   (pos? (count (:items result)))
                                                   (< (:page state) max-pages)
                                                   (has-next-page? result per-page))]
                                    (assoc state
                                           :next? next?
                                           :truncated? (and (:ok? result)
                                                            (= (:page state) max-pages)
                                                            (has-next-page? result per-page))
                                           :page (inc (:page state))
                                           :pages (inc (:pages state))
                                           :failed? failed?
                                           :succeeded? succeeded?))
                                  state))
                              states)]
            (recur states' seen' items' (count fetched-ids)
                   (or truncated? overflow?) fetched-ids)))))))
