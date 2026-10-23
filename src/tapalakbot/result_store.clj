 (ns tapalakbot.result-store
   "Bounded, expiring state for listing drill-down and deterministic pagination."
   (:require [clojure.string :as str]
             [clojure.tools.logging :as log]
             [tapalakbot.result-order :as result-order]))

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
   "Replace a conversation's drill-down cache with the latest ordered view."
   [owner-id cards]
   (when (and owner-id (seq cards))
     (let [indexed (into {}
                         (map-indexed (fn [index card]
                                        [(inc index)
                                         (assoc card :result-index (inc index))])
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

 (defn- fresh-cursor-id []
   (subs (str/replace (str (random-uuid)) "-" "") 0 10))

 (defn- purge-expired [pools now]
   (into {}
         (remove (fn [[_ state]]
                   (> (- now (:created-at state)) result-pool-ttl-ms)))
         pools))

 (defn- owner-pools [pools user-id]
   (remove (fn [[_ state]] (= user-id (:user-id state))) pools))

 (defn cache-result-pool!
   "Atomically replace the owner's cached complete pool with its first view.
    A cursor is retained even when the pool fits on one page so sort controls
    can create another view without a new search."
   ([user-id query cards shown-count]
    (cache-result-pool! user-id query cards shown-count {}))
   ([user-id query cards shown-count context]
    (let [cards (vec cards)]
      (when (seq cards)
        (let [now (System/currentTimeMillis)
              cursor-id (fresh-cursor-id)
              page-end (min (count cards) (max 0 (int shown-count)))
              indexed (mapv (fn [index card]
                              (assoc card :result-index (inc index)))
                            (range (count cards)) cards)
              state {:user-id user-id
                     :query query
                     :source-pool indexed
                     :cards indexed
                     :offset page-end
                     :context context
                     :sort-mode (:sort-mode context)
                     :created-at now}]
          (swap! result-pools
                 (fn [pools]
                   (assoc (purge-expired (owner-pools pools user-id) now)
                          cursor-id state)))
          cursor-id)))))

 (defn sort-result-pool!
   "Atomically replace an active view with a sorted view and return its first page.
    The old cursor is removed, making old page/sort callbacks stale."
   [user-id cursor-id mode requested-page-size]
   (when (contains? result-order/supported-modes mode)
      (loop []
       (let [pools @result-pools
             state (get pools cursor-id)
             now (System/currentTimeMillis)
             expired? (and state (> (- now (:created-at state)) result-pool-ttl-ms))]
        (cond
          (nil? state) nil
          (or expired? (not= user-id (:user-id state)))
          (do
            (when expired? (swap! result-pools dissoc cursor-id))
            nil)
          :else
          (let [ordered (mapv (fn [index card]
                                (assoc card :result-index (inc index)))
                              (range)
                              (result-order/order-cards (:source-pool state) mode))
                end (min (count ordered) (max 0 (int requested-page-size)))
                next-id (fresh-cursor-id)
                next-state (assoc state
                                  :cards ordered
                                  :offset end
                                  :sort-mode mode
                                  :context (assoc (:context state)
                                                  :cards ordered :sort-mode mode))
                next-pools (assoc (dissoc pools cursor-id) next-id next-state)]
            (if (compare-and-set! result-pools pools next-pools)
              {:cursor-id next-id
               :pool ordered
               :cards (subvec ordered 0 end)
               :query (:query state)
               :context (:context next-state)
               :sort-mode mode
               :start (when (pos? end) 1)
               :end end
               :total (count ordered)
               :has-more (< end (count ordered))}
              (recur))))))))

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
         expired?
         (do
           (swap! result-pools dissoc cursor-id)
           nil)
         complete? nil
         (not= user-id (:user-id state)) nil
         :else
         (let [start (:offset state)
               end (min (count (:cards state)) (+ start requested-page-size))
               page {:cards (subvec (:cards state) start end)
                     :query (:query state)
                     :context (:context state)
                     :sort-mode (:sort-mode state)
                     :start (inc start)
                     :end end
                     :total (count (:cards state))
                     :has-more (< end (count (:cards state)))}
               updated (assoc state :offset end)]
           (if (compare-and-set! result-pools pools (assoc pools cursor-id updated))
             page
             (recur)))))))

