(ns tapalakbot.bot-state
  "Expiring user preferences, conversation serialization, and callback state."
  (:require [clojure.string :as str]))

(def ^:private state-ttl-ms (* 24 60 60 1000))
(def ^:private track-query-ttl-ms (* 2 60 60 1000))

(def user-state
  "User ID to mutable preferences used by Telegram transport."
  (atom {}))

(def conversation-state
  "Conversation ID to its single-flight lock and newest pending update."
  (atom {}))

(def pending-track-queries
  "Opaque callback ID to immutable tracking-query ownership."
  (atom {}))

(def pending-refinements
  "Opaque price-refinement callback IDs scoped to a conversation."
  (atom {}))


(defn- expired?
  [now created-at ttl-ms]
  (or (nil? created-at) (> (- now created-at) ttl-ms)))

(defn get-user-state
  [user-id]
  (let [now (System/currentTimeMillis)]
    (swap! user-state
           (fn [states]
             (into {}
                   (remove (fn [[_ {:keys [last-seen]}]]
                             (expired? now (some-> last-seen deref) state-ttl-ms)))
                   states)))
    (let [state (or (get @user-state user-id)
                    (let [fresh {:last-seen (atom now)
                                 :city-id (atom nil)}]
                      (get (swap! user-state
                                  #(if (contains? % user-id)
                                     %
                                     (assoc % user-id fresh)))
                           user-id)))]
      (reset! (:last-seen state) now)
      state)))

(defn conversation-id
  "Return the stable harness session key, isolating forum topics."
  [{:keys [chat-id user-id thread-id]}]
  (if thread-id
    (str "tg-" user-id ":" chat-id ":" thread-id)
    (str "tg-" user-id)))

(defn- get-conversation-state
  [id]
  (let [now (System/currentTimeMillis)]
    (swap! conversation-state
           (fn [states]
             (into {}
                   (remove (fn [[_ {:keys [lock pending last-seen]}]]
                             (and (= :idle @lock)
                                  (nil? @pending)
                                  (expired? now @last-seen state-ttl-ms))))
                   states)))
    (or (get @conversation-state id)
        (let [fresh {:lock (atom :idle)
                     :pending (atom nil)
                     :last-seen (atom now)}]
          (get (swap! conversation-state
                      #(if (contains? % id) % (assoc % id fresh)))
               id)))))

(defn try-acquire!
  [id]
  (let [{:keys [lock last-seen]} (get-conversation-state id)]
    (reset! last-seen (System/currentTimeMillis))
    (compare-and-set! lock :idle :busy)))

(defn release!
  [id]
  (reset! (:lock (get-conversation-state id)) :idle))

(defn store-pending!
  [id message]
  (reset! (:pending (get-conversation-state id)) message))

(defn take-pending!
  [id]
  (let [pending (:pending (get-conversation-state id))]
    (loop []
      (let [current @pending]
        (if (compare-and-set! pending current nil)
          current
          (recur))))))

(defn cache-track-query!
  "Cache a user-owned tracking query and return its opaque callback ID."
  [user-id query]
  (let [now (System/currentTimeMillis)
        callback-id (str (random-uuid))]
    (swap! pending-track-queries
           (fn [queries]
             (-> (into {}
                       (remove (fn [[_ {:keys [created-at]}]]
                                 (expired? now created-at track-query-ttl-ms)))
                       queries)
                 (assoc callback-id {:user-id user-id
                                     :query query
                                     :created-at now}))))
    callback-id))

(defn take-track-query!
  "Consume a fresh callback only when it belongs to the requesting user."
  [user-id callback-id]
  (let [{owner :user-id query :query created-at :created-at :as entry}
        (get @pending-track-queries callback-id)
        fresh? (and created-at
                    (not (expired? (System/currentTimeMillis)
                                   created-at
                                   track-query-ttl-ms)))]
    (when (and entry (= owner user-id) fresh?)
      (swap! pending-track-queries dissoc callback-id)
      query)))

(defn cache-refinement!
  "Cache a conversation-owned refinement context and return its callback ID."
  [owner-id context]
  (let [now (System/currentTimeMillis)
        callback-id (subs (str/replace (str (random-uuid)) "-" "") 0 12)]
    (swap! pending-refinements
           (fn [entries]
             (let [fresh (into {}
                               (remove (fn [[_ {:keys [created-at]}]]
                                         (expired? now created-at track-query-ttl-ms)))
                               entries)]
               (assoc fresh callback-id {:owner-id owner-id
                                         :context context
                                         :created-at now}))))
    callback-id))

(defn take-refinement!
  "Consume a fresh refinement callback only for its owning conversation."
  [owner-id callback-id]
  (let [{entry-owner :owner-id context :context created-at :created-at
         :as entry} (get @pending-refinements callback-id)
        fresh? (and created-at
                    (not (expired? (System/currentTimeMillis)
                                   created-at
                                   track-query-ttl-ms)))]
    (when (and entry (= owner-id entry-owner) fresh?)
      (swap! pending-refinements dissoc callback-id)
      context)))
