(ns tapalakbot.monitor.tracker
  "Background tracker for durable Lalafo subscriptions.

   A subscription stores the resolved request, its query variants and its
   Telegram delivery target.  Collection and typed JEV selection are reused
   from the interactive search path; no tracker-specific semantic filters are
   applied."
  (:require [tapalakbot.monitor.store :as store]
            [tapalakbot.search :as search]
            [tapalakbot.render :as render]
            [clj-harness.telegram :as tg]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.time LocalDateTime ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def ^:private due-check-interval-ms (* 60 1000))
(def ^:private sqlite-time (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))
(def ^:private notification-batch-size 20)

(defn track-due?
  "True when a subscription has never run or its own interval elapsed."
  ([track] (track-due? track (LocalDateTime/now ZoneOffset/UTC)))
  ([{:keys [last_checked_at notify_interval]} now]
   (if-not last_checked_at
     true
     (try
       (let [last-checked (LocalDateTime/parse last_checked_at sqlite-time)
             next-check (.plusHours last-checked (long (or notify_interval 24)))]
         (not (.isAfter next-check now)))
       (catch Exception _
         (log/warn :track-invalid-last-checked :value last_checked_at)
         true)))))


(defn- parse-queries
  [queries title]
  (let [value (cond
                (sequential? queries) queries
                (string? queries) (store/parse-track-queries queries)
                :else [])
        values (->> value
                    (filter string?)
                    (map str/trim)
                    (remove str/blank?)
                    distinct
                    (take 3)
                    vec)]
    (if (seq values) values [title])))

(defn- prior-excluded-ids
  [track]
  (->> (concat (store/seen-item-ids (:id track))
               (store/parse-track-excluded-ids (:excluded_ids track)))
       (map str)
       distinct
       vec))

(defn search-track
  "Collect and select using the saved resolved request."
  [track text-query]
  (search/search text-query
                 {:queries (parse-queries (:queries track) text-query)
                  :price-min (:price_min track)
                  :price-max (:price_max track)
                  :city-id (:city_id track)
                  :category-id (:category_id track)
                  :excluded-ids (prior-excluded-ids track)
                  :budget-override? true}))

(defn- item-id [item]
  (:id item))

(defn- collection-available? [result]
  (and (= :ok (:search-status result))
       (zero? (long (or (get-in result [:collection :failed-queries]) 0)))
       (not (:error result))))

(defn- format-notification
  "Render an escaped alert heading plus source-owned native Rich HTML."
  [track-title items]
  (str "🔔 <b>«" (render/escape-html track-title) "»</b> — "
       (count items) " новых\n\n"
       (render/render-results-rich items {:include-images? true})))


(defn- delivery-target
  "New rows use their persisted chat/topic. Legacy tg-N rows keep private chat."
  [{:keys [chat_id thread_id user_id]}]
  (if (some? chat_id)
    {:chat-id chat_id :thread-id thread_id}
    (when (and (string? user_id) (re-matches #"^tg-\d+$" user_id))
      {:chat-id (subs user_id 3) :thread-id nil})))

(defn- send-confirmed? [response]
  (and (map? response)
       (some? (or (get response "message_id")
                  (:message_id response)
                  (get-in response ["result" "message_id"])
                  (get-in response [:result :message_id])))
       (not (or (= false (get response "ok"))
                (= false (:ok response))))))

(defn- send-notification! [{:keys [chat-id thread-id]} message]
  (tg/send-rich-message chat-id :html message :preview false :thread-id thread-id))


(def ^:private filter-keys
  [:title :queries :price_min :price_max :city_id :category_id :excluded_ids :notify_interval])

(defn- still-current? [track]
  (let [current (store/get-track (:id track))]
    (and (= 1 (:enabled current))
         (every? #(= (get track %) (get current %)) filter-keys))))

(defn check-track
  "Check one subscription. Positive items become seen only after send success.
   Collection failures defer the check; completed JEV decisions can retire
   rejected noise through :evaluated-ids."
  [{:keys [id title] :as track}]
  (try
    (when-not (still-current? track)
      (throw (ex-info "Subscription is no longer active with this filter" {:deferred? true})))
    (let [result (search-track track title)]
      (when-not (and (map? result) (collection-available? result))
        (log/warn :track-collection-deferred :track-id id)
        (throw (ex-info "Track collection unavailable" {:deferred? true})))
      ;; A pause, stop or filter edit during the HTTP/JEV call invalidates this result.
      (when-not (still-current? track)
        (throw (ex-info "Subscription changed during selection" {:deferred? true})))
      (let [cards (vec (:cards result))
            accepted-ids (set (keep #(some-> (item-id %) str) cards))
            evaluated-ids (set (keep #(some-> % str) (:evaluated-ids result)))
            rejected-ids (remove accepted-ids evaluated-ids)
            batch (vec (take notification-batch-size cards))]
        ;; JEV has made a completed decision about these IDs. They are not
        ;; positive listings and therefore do not depend on Telegram delivery.
        (doseq [item-id rejected-ids]
          (store/mark-item-rejected! id item-id))
        (log/info :track-check-detail :track-id id
                  :evaluated-items (count evaluated-ids)
                  :new-items (count cards))
        (if (empty? cards)
          (do
            (store/mark-track-checked! id)
            {:new-items 0 :notified? false})
          (if-let [target (delivery-target track)]
            (let [sent (try
                         (send-notification! target (format-notification title batch))
                         (catch Exception e
                           (log/warn :track-notify-error :track-id id
                                     :error (.getMessage e))
                           nil))]
              (if (send-confirmed? sent)
                (do
                  (doseq [card batch]
                    (store/mark-item-seen! id (item-id card)))
                  (store/increment-notify-count! id)
                  (store/mark-track-checked! id)
                  (log/info :track-notified :track-id id :items (count batch))
                  {:new-items (count cards) :notified? true})
                (do
                  (log/warn :track-notify-deferred :track-id id)
                  {:new-items (count cards) :notified? false})))
            (do
              (log/warn :track-invalid-delivery-target :track-id id)
              {:new-items (count cards) :notified? false})))))
    (catch clojure.lang.ExceptionInfo e
      (if (:deferred? (ex-data e))
        {:new-items 0 :notified? false}
        (do
          (log/error e :track-check-error :track-id id)
          {:new-items 0 :notified? false})))
    (catch Exception e
      (log/error e :track-check-error :track-id id)
      {:new-items 0 :notified? false})))

(defonce ^:private tracker-thread (atom nil))

(defn run-check-cycle!
  "Check due active tracks. The short tick only performs due checks."
  []
  (let [all-tracks (store/get-all-active-tracks)
        due-tracks (filterv track-due? all-tracks)]
    (if (empty? due-tracks)
      {:tracks 0 :new-items 0 :notified 0}
      (let [results (mapv check-track due-tracks)]
        {:tracks (count due-tracks)
         :new-items (reduce + (map :new-items results))
         :notified (count (filter :notified? results))}))))

(defn- run-tracker-loop!
  [sleep-fn]
  (sleep-fn (* 5 60 1000))
  (loop []
    (try
      (run-check-cycle!)
      (catch InterruptedException e
        (throw e))
      (catch Exception e
        (log/error e :tracker-loop-error (.getMessage e))))
    (sleep-fn due-check-interval-ms)
    (recur)))

(defn start-tracker!
  "Start the daemon tracker without blocking server startup."
  []
  (when @tracker-thread
    (log/warn :tracker-already-running))
  (let [t (Thread.
           #(try
              (run-tracker-loop! (fn [millis] (Thread/sleep millis)))
              (catch InterruptedException _)
              (catch Exception e
                (log/error :tracker-loop-error (.getMessage e))))
           "monitor-tracker")]
    (.setDaemon t true)
    (.start t)
    (reset! tracker-thread t)
    (log/info :tracker-started :interval-ms due-check-interval-ms)))

(defn stop-tracker!
  "Stop the daemon tracker thread."
  []
  (when-let [t @tracker-thread]
    (.interrupt t)
    (reset! tracker-thread nil)
    (log/info :tracker-stopped)))

(defn tracker-running?
  "Return whether the tracker daemon is alive."
  []
  (boolean (and @tracker-thread (.isAlive ^Thread @tracker-thread))))
