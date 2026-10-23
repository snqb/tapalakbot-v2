(ns tapalakbot.result-store
  "Bounded, expiring state for listing drill-down and deterministic pagination."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def candidate-pool-limit 200)
(def page-size 20)
(def ^:private ad-cache-ttl-ms (* 2 60 60 1000))
(def ^:private result-pool-ttl-ms (* 30 60 1000))

(def ad-cache
  "User ID to a timestamped numeric card index for drill-down callbacks."
  (atom {}))

(def result-pools
  "Opaque cursor ID to an immutable ranked candidate pool and current offset."
  (atom {}))

(defn cache-ads!
  "Replace a conversation's drill-down cache with cards indexed by overall result."
  [owner-id cards]
  (when (and owner-id (seq cards))
    (let [indexed (into {}
                        (map-indexed (fn [index card]
                                       [(inc index)
                                        (assoc card :result-index
                                               (or (:result-index card) (inc index)))])
                                     cards))]
      (swap! ad-cache assoc owner-id
             {:created-at (System/currentTimeMillis)
              :cards indexed})
      (log/info :ad-cache-update :owner owner-id :count (count indexed))
      {:start 1 :count (count indexed)})))

(defn get-ad
  "Return a cached card by user ID and numeric index, evicting stale state."
  [user-id index]
  (when-let [{:keys [created-at cards]} (get @ad-cache user-id)]
    (if (<= (- (System/currentTimeMillis) created-at) ad-cache-ttl-ms)
      (get cards index)
      (do
        (swap! ad-cache dissoc user-id)
        nil))))

(defn cache-result-pool!
  "Cache ranked cards and return a cursor only when unseen cards remain.
   Each card receives its immutable overall result index for pagination."
  ([user-id query cards shown-count]
   (cache-result-pool! user-id query cards shown-count {}))
  ([user-id query cards shown-count context]
   (let [cards (->> cards
                    vec
                    (map-indexed (fn [index card]
                                   (assoc card :result-index (inc index))))
                    vec)]
     (when (< shown-count (count cards))
       (let [now (System/currentTimeMillis)
             cursor-id (subs (str/replace (str (random-uuid)) "-" "") 0 10)]
         (swap! result-pools
                (fn [pools]
                  (let [active (into {}
                                     (remove (fn [[_ state]]
                                               (> (- now (:created-at state))
                                                  result-pool-ttl-ms)))
                                     pools)]
                    (assoc active cursor-id {:user-id user-id
                                             :query query
                                             :cards cards
                                             :offset shown-count
                                             :context context
                                             :created-at now}))))
         cursor-id)))))

(defn next-result-page!
  "Atomically advance an unexpired cursor and return its owner's next page."
  [user-id cursor-id requested-page-size]
  (loop []
    (let [pools @result-pools
          state (get pools cursor-id)
          now (System/currentTimeMillis)
          expired? (and state
                        (> (- now (:created-at state)) result-pool-ttl-ms))
          complete? (and state
                         (>= (:offset state) (count (:cards state))))]
      (cond
        (nil? state) nil
        (or expired? complete?)
        (do
          (swap! result-pools dissoc cursor-id)
          nil)
        (not= user-id (:user-id state)) nil
        :else
        (let [start (:offset state)
              end (min (count (:cards state)) (+ start requested-page-size))
              page {:cards (subvec (:cards state) start end)
                    :query (:query state)
                    :context (:context state)
                    :start (inc start)
                    :end end
                    :total (count (:cards state))
                    :has-more (< end (count (:cards state)))}
              updated (assoc state :offset end)]
          (if (compare-and-set! result-pools pools (assoc pools cursor-id updated))
            page
            (recur)))))))
