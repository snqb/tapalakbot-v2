(ns tapalakbot.monitor.scanner
  "Background scanner that periodically searches Lalafo for monitored categories
   and stores price snapshots in SQLite."
  (:require [tapalakbot.lalafo :as lalafo]
            [tapalakbot.monitor.store :as store]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════════════ CONFIG ════════════════════════════

(def ^:private scan-interval-ms
  "Scan every 4 hours."
  (* 4 60 60 1000))

(def ^:private items-per-query
  "Max items to fetch per query."
  40)

;; Keywords in title → exclude (accessories, services, repairs)
(def ^:private exclude-keywords
  ["чехол" "зарядк" "кабел" "адаптер" "стекло" "пленк" "ремонт"
   "установка" "прошивк" "гравировк" "батарейк" "наушник" "чехоль"
   "переходник" "холдер" "держател" "колпачек" "обложк" "чехол-"
   "аксессуар" "дополнительн" "запчаст" "комплектующ" "service"
   "аренда" "прокат"])

(defn- exclude-accessory?
  "True if item title looks like an accessory or service, not the real product."
  [title]
  (let [t (str/lower-case (or title ""))]
    (some #(str/includes? t %) exclude-keywords)))

;; ════════════════════════════ SCAN LOGIC ════════════════════════════

(defn- parse-queries
  "Parse queries string (EDN vector stored as text). Uses safe edn/read-string."
  [q]
  (try
    (clojure.edn/read-string q)
    (catch Exception _
      [q])))

(defn- scan-category
  "Scan one category: search all queries, store items + snapshots.
   Returns {:items-found N :snapshots N}."
  [{:keys [id name queries city_id] :as _category}]
  (let [qs (parse-queries queries)
        client (#'lalafo/build-client)
        ;; Search all queries for this category
        all-items (atom {})]
    (doseq [q qs]
      (try
        (let [result (#'lalafo/search-all-pages client q
                                                :city-id city_id
                                                :max-pages 1
                                                :per-page items-per-query)
              items (second result)]
          (doseq [item items]
            (let [item-id (get item "id")
                  price (get item "price")
                  title (get item "title" "")]
              (when (and price (> price 50) (< price 500000)
                         (not (exclude-accessory? title)))
                (swap! all-items assoc item-id item)))))
        (catch Exception e
          (log/warn :scan-query-error :category name :query q :error (.getMessage e)))))
    ;; Store items and record snapshots
    (let [items (vals @all-items)
          snapshots (atom [])]
      (doseq [item items]
        (let [item-id (get item "id")
              price (get item "price")
              images (get item "images")
              img-url (when (seq images)
                        (let [img (first images)]
                          (if (string? img) img (get img "original_url"))))]
          (store/upsert-item! {:id item-id
                               :category_id id
                               :title (get item "title" "")
                               :url (let [raw (or (get item "url") "")]
                                      (if (str/starts-with? raw "http")
                                        raw
                                        (str "https://lalafo.kg" raw)))
                               :price price
                               :currency (or (get item "currency") "KGS")
                               :image_url img-url
                               :city (get item "city" "")})
          (swap! snapshots conj [item-id price])))
      ;; Batch insert snapshots
      (when (seq @snapshots)
        (store/record-snapshots-batch! @snapshots))
      (log/info :scan-category :name name :items (count items) :snapshots (count @snapshots))
      {:items-found (count items)
       :snapshots (count @snapshots)})))

(defn scan-all!
  "Scan all enabled categories."
  []
  (log/info :scan-start)
  (let [cats (store/get-categories)
        results (mapv scan-category cats)
        total-items (reduce + (map :items-found results))
        total-snapshots (reduce + (map :snapshots results))]
    (log/info :scan-complete :categories (count cats) :total-items total-items :total-snapshots total-snapshots)
    {:categories (count cats)
     :items total-items
     :snapshots total-snapshots}))

(defn initial-scan!
  "Run first scan immediately on startup."
  []
  (log/info :initial-scan-start)
  (scan-all!))

;; ════════════════════════════ BACKGROUND LOOP ════════════════════════════

(defonce ^:private scan-thread (atom nil))

(defn start-scanner!
  "Start background scanning thread."
  []
  (when @scan-thread
    (log/warn :scanner-already-running))
  (let [t (Thread.
           (fn []
             (loop []
               (try
                 (scan-all!)
                 (catch Exception e
                   (log/error :scan-error (.getMessage e))))
               (Thread/sleep scan-interval-ms)
               (recur)))
           "monitor-scanner")]
    (.setDaemon t true)
    (.start t)
    (reset! scan-thread t)
    (log/info :scanner-started :interval-ms scan-interval-ms)))

(defn stop-scanner!
  "Stop background scanning thread."
  []
  (when-let [t @scan-thread]
    (.interrupt t)
    (reset! scan-thread nil)
    (log/info :scanner-stopped)))

(defn scanner-running?
  "Check if scanner thread is alive."
  []
  (boolean (and @scan-thread (.isAlive ^Thread @scan-thread))))
