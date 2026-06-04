(ns tapalakbot.monitor.tracker
  "Background tracker: checks user tracking filters against Lalafo,
   sends Telegram notifications for new items."
  (:require [tapalakbot.monitor.store :as store]
            [tapalakbot.lalafo :as lalafo]
            [clj-harness.telegram :as tg]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ══════════════════════ CONFIG ══════════════════════

(def ^:private check-interval-ms
  "Check every 2 hours."
  (* 2 60 60 1000))

(def ^:private exclude-keywords
  "Keywords in title → exclude (services, repairs, not actual premises)."
  ["сварк" "решетк" "теплоизоляц" "утеплен" "уборк" "клининг"
   "ремонт" "услуг" "установк" "монтаж" "обслуживан"
   "доставк" "груз" "перевозк" "аренд.*авто" "аренд.*техник"
   "гаранти" "запчаст" "высотн" "кровл" "фасад"
   "газоблок" "блок" "кирпич" "бетон" "плитк" "керамзит"
   "свекл" "овощ" "фрукт" "продукт" "питан"
   "сдаю.*квартир" "сдаю.*комнат" "сдаю.*мест"
   "кондиционер" "сплит-систем" "климат"
   "торгов.*оборудован" "витрин" "полк" "стеллаж"
   "экспопанель" "мдф" "пластик" "аксессуар"
   "комнаты.*собственник" "квартир.*аренд"])

(defn- exclude-service?
  "True if item title looks like a service, not actual premises."
  [title]
  (let [t (str/lower-case (or title ""))]
    (some #(str/includes? t %) exclude-keywords)))

(def ^:private max-notifications-per-check
  "Max new items to notify per filter per check cycle."
  5)

(def ^:private max-pages-per-query 1)

(def ^:private per-page 40)

;; ══════════════════════ CHECK LOGIC ══════════════════════

(defn- broaden-query
  "Create broader search variants from a specific query.
   E.g., 'помещение в центре под кофейню' → ['помещение', 'коммерческое помещение']"
  [query]
  (let [words (str/split query #"\s+")
        ;; Take first 1-2 meaningful words (skip prepositions)
        stop-words #{"в" "на" "под" "для" "от" "до" "с" "по" "и" "или" "не" "что" "как" "где" "когда"}
        meaningful (filterv #(not (stop-words (str/lower-case %))) words)
        ;; Generate variants: 1 word, 2 words, original
        variants (distinct
                  (concat
                   (when (>= (count meaningful) 1) [(first meaningful)])
                   (when (>= (count meaningful) 2) [(str/join " " (take 2 meaningful))])
                   ;; Add 'коммерческое' prefix for real estate queries
                   (when (some #(re-matches #"помещени.*|аренд.*|офис.*|магазин.*" (str/lower-case %)) meaningful)
                     ["коммерческое помещение" "помещение аренда"])))]
    (vec variants)))

(defn- search-track
  "Search Lalafo with a track's filters. Returns items found.
   Broadens query if original returns 0 results."
  [{:keys [queries price_min price_max city_id]}]
  (let [qs (store/parse-track-queries queries)
        client (#'lalafo/build-client)
        all-items (atom {})]
    (doseq [q qs]
      (try
        (let [{:keys [items]} (#'lalafo/search-all-pages
                               client q
                               :city-id (or city_id 103184)
                               :price-min price_min
                               :price-max price_max
                               :max-pages max-pages-per-query
                               :per-page per-page)]
          (log/info :track-search :query q :raw-items (count items))
          ;; If original query returned 0, try broader variants
          (when (zero? (count items))
            (let [broader (broaden-query q)]
              (log/info :track-broaden :original q :variants broader)
              (doseq [bq broader]
                (try
                  (let [[found broader-items pages] (#'lalafo/search-all-pages
                                                     client bq
                                                     :city-id (or city_id 103184)
                                                     :price-min price_min
                                                     :price-max price_max
                                                     :max-pages max-pages-per-query
                                                     :per-page per-page)]
                    (log/info :track-broaden-search :query bq :found found :items (count broader-items))
                    (doseq [item broader-items]
                      (let [item-id (get item "id")
                            price (get item "price")]
                        (when (or (nil? price) (> price 50))
                          (swap! all-items assoc item-id item)))))
                  (catch Exception e
                    (log/warn :track-broaden-error :query bq :error (.getMessage e)))))))
          ;; Process items from original query
          (doseq [item items]
            (let [item-id (get item "id")
                  price (get item "price")]
              (when (or (nil? price) (> price 50))
                (swap! all-items assoc item-id item)))))
        (catch Exception e
          (log/warn :track-search-error :query q :error (.getMessage e)))))
    (log/info :track-search-result :qs (count qs) :items (count @all-items))
    (vals @all-items)))

(defn- format-price [p]
  (if (and p (> p 0))
    (str (format "%,.0f" (double p)) " сом")
    "цена неизвестна"))

(defn- format-notification
  "Format a notification message for new items. Clean, useful format."
  [track-title new-items]
  (let [;; Filter to items with at least a decent title
        good-items (filterv (fn [item]
                              (let [title (get item "title" "")
                                    price (get item "price")]
                                (and (>= (count title) 10)
                                     (not (exclude-service? title))
                                     (or (nil? price) (> price 100)))))
                            (take (* 2 max-notifications-per-check) new-items))
        items-to-show (take max-notifications-per-check good-items)
        remaining (- (count new-items) (count items-to-show))
        lines (mapv (fn [item]
                      (let [title (get item "title" "")
                            ;; Trim long titles
                            short-title (if (> (count title) 60)
                                          (subs title 0 57) "...")
                            price (get item "price")
                            price-str (format-price price)
                            raw-url (or (get item "url") "")
                            url (if (str/starts-with? raw-url "http")
                                  raw-url
                                  (str "https://lalafo.kg" raw-url))]
                        (str "• " short-title
                             (when (and price (> price 0))
                               (str "\n  💰 " price-str))
                             "\n  🔗 " url)))
                    items-to-show)]
    (str "🔔 *«" track-title "»* — " (count new-items) " новых\n\n"
         (str/join "\n\n" lines)
         (when (pos? remaining)
           (str "\n\n... и ещё " remaining " об'явок")))))

(defn- extract-user-id-from-track
  "Extract numeric Telegram user ID from track user-id (format: 'tg-123456')."
  [user-id]
  (when (str/starts-with? user-id "tg-")
    (subs user-id 3)))

(defn- check-track
  "Check one track: search, find new items, notify user.
   Returns {:new-items N :notified? boolean}."
  [{:keys [id user_id title] :as track}]
  (try
    (let [items (search-track track)
          ;; Filter out already-seen items
          new-items (filterv #(not (store/seen-item? id (get % "id"))) items)
          notify-count (min (count new-items) max-notifications-per-check)]
      (log/info :track-check-detail :track-id id :total-items (count items) :new-items (count new-items))
      ;; Mark all found items as seen (even beyond notify limit)
      (doseq [item new-items]
        (store/mark-item-seen! id (get item "id")))
      ;; Update check timestamp
      (store/mark-track-checked! id)
      ;; Send notification if new items found
      (when (pos? notify-count)
        (if-let [tg-user-id (extract-user-id-from-track user_id)]
          (let [items-to-show (take notify-count new-items)
                _ (log/info :track-notify-items :items (mapv (fn [i] {:title (get i "title") :price (get i "price") :url (get i "url")}) items-to-show))
                msg (format-notification title items-to-show)]
            (log/info :track-notify-preview :msg msg)
            (try
              (tg/send-message tg-user-id msg :parse-mode nil)
              (store/increment-notify-count! id)
              (log/info :track-notified :track-id id :user user_id :items notify-count)
              (catch Exception e
                (log/warn :track-notify-fail :track-id id :error (.getMessage e)))))
          (log/warn :track-invalid-user-id :track-id id :user-id user_id)))
      {:new-items (count new-items) :notified? (pos? notify-count)})
    (catch Exception e
      (log/error :track-check-error :track-id (:id track) :error (.getMessage e))
      {:new-items 0 :notified? false})))

;; ══════════════════════ BACKGROUND LOOP ══════════════════════

(defonce ^:private tracker-thread (atom nil))

(defn- should-check-now?
  "Check if a track is due for checking.
   Always checks every cycle (2h) — notify_interval only controls notification frequency."
  [_track]
  true)

(defn run-check-cycle!
  "Check all active tracks that are due. Returns summary."
  []
  (let [all-tracks (store/get-all-active-tracks)
        tracks (filterv should-check-now? all-tracks)
        skipped (- (count all-tracks) (count tracks))]
    (when (pos? skipped)
      (log/info :track-skipped :count skipped))
    (if (empty? tracks)
      (do (log/info :track-check :no-active-tracks)
          {:tracks 0 :new-items 0 :notified 0})
      (let [results (mapv check-track tracks)
            total-new (reduce + (map :new-items results))
            total-notified (count (filter :notified? results))]
        (log/info :track-check-complete :tracks (count tracks) :new-items total-new :notified total-notified)
        {:tracks (count tracks) :new-items total-new :notified total-notified}))))

(defn start-tracker!
  "Start background tracking thread."
  []
  (when @tracker-thread
    (log/warn :tracker-already-running))
  (let [t (Thread.
           (fn []
             ;; Initial delay — let scanner finish first scan
             (Thread/sleep (* 5 60 1000))
             (loop []
               (try
                 (run-check-cycle!)
                 (catch Exception e
                   (log/error :tracker-loop-error (.getMessage e))))
               (Thread/sleep check-interval-ms)
               (recur)))
           "monitor-tracker")]
    (.setDaemon t true)
    (.start t)
    (reset! tracker-thread t)
    (log/info :tracker-started :interval-ms check-interval-ms)))

(defn stop-tracker!
  "Stop background tracking thread."
  []
  (when-let [t @tracker-thread]
    (.interrupt t)
    (reset! tracker-thread nil)
    (log/info :tracker-stopped)))

(defn tracker-running?
  "Check if tracker thread is alive."
  []
  (boolean (and @tracker-thread (.isAlive ^Thread @tracker-thread))))
