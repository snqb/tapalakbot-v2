(ns tapalakbot.monitor.tracker
  "Background tracker: checks user tracking filters against Lalafo,
   sends Telegram notifications for new items."
  (:require [tapalakbot.monitor.store :as store]
            [tapalakbot.lalafo :as lalafo]
            [clj-harness.telegram :as tg]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ══════════════════════ CONFIG ══════════════════════

(def ^:private check-interval-ms
  "Check every 2 hours."
  (* 2 60 60 1000))

(def ^:private max-notifications-per-check
  "Max new items to notify per filter per check cycle."
  5)

(def ^:private max-pages-per-query 1)

(def ^:private per-page 40)

;; ══════════════════════ CHECK LOGIC ══════════════════════

(defn- search-track
  "Search Lalafo with a track's filters. Returns items found."
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
          (doseq [item items]
            (let [item-id (get item "id")
                  price (get item "price")]
              (when (and price (> price 50))
                (swap! all-items assoc item-id item)))))
        (catch Exception e
          (log/warn :track-search-error :query q :error (.getMessage e)))))
    (vals @all-items)))

(defn- format-price [p]
  (if (and p (> p 0))
    (str (format "%,.0f" (double p)) " сом")
    "цена неизвестна"))

(defn- format-notification
  "Format a notification message for new items."
  [track-title new-items]
  (let [lines (mapv (fn [item]
                      (let [title (get item "title" "")
                            price (get item "price")
                            raw-url (or (get item "url") "")
                            url (if (str/starts-with? raw-url "http")
                                  raw-url
                                  (str "https://lalafo.kg" raw-url))]
                        (str "• " title "\n  " (format-price price) "\n  🔗 " url)))
                    (take max-notifications-per-check new-items))
        remaining (- (count new-items) max-notifications-per-check)]
    (str "🔔 Новое по фильтру «" track-title "»:\n\n"
         (str/join "\n\n" lines)
         (when (pos? remaining)
           (str "\n\n... и ещё " remaining)))))

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
      ;; Mark all found items as seen (even beyond notify limit)
      (doseq [item new-items]
        (store/mark-item-seen! id (get item "id")))
      ;; Update check timestamp
      (store/mark-track-checked! id)
      ;; Send notification if new items found
      (when (pos? notify-count)
        (if-let [tg-user-id (extract-user-id-from-track user_id)]
          (let [msg (format-notification title (take notify-count new-items))]
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
  "Check if a track is due for checking based on its notify_interval."
  [{:keys [last_checked_at notify_interval]}]
  (let [interval-h (or notify_interval 24)
        interval-ms (* interval-h 60 60 1000)]
    (if last_checked_at
      (let [checked-ms (.getTime (java.sql.Timestamp/valueOf last_checked_at))
            now-ms (System/currentTimeMillis)]
        (>= (- now-ms checked-ms) interval-ms))
      true)))

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
