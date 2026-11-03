(ns tapalakbot.bot-state-test
  (:require [clojure.test :refer [deftest is]]
            [tapalakbot.bot-state :as state]))

(deftest stale-state-is-pruned-lazily
  (reset! state/user-state
          {"stale" {:last-seen (atom 0)
                    :city-id (atom nil)}})
  (state/get-user-state "fresh")
  (is (not (contains? @state/user-state "stale")))

  (reset! state/conversation-state
          {"stale" {:lock (atom :idle)
                    :pending (atom nil)
                    :last-seen (atom 0)}})
  (state/try-acquire! "fresh")
  (state/release! "fresh")
  (is (not (contains? @state/conversation-state "stale")))

  (reset! state/pending-track-queries
          {"stale" {:owner-id "tg-1:1:17" :request {:title "old"} :created-at 0}})
  (state/cache-track-query! "tg-1:1:17" {:title "fresh"})
  (is (not (contains? @state/pending-track-queries "stale"))))

(deftest pending-update-is-consumed-once
  (reset! state/conversation-state {})
  (state/store-pending! "tg-42" {:text "latest"})
  (is (= {:text "latest"} (state/take-pending! "tg-42")))
  (is (nil? (state/take-pending! "tg-42"))))

(deftest tracking-query-enforces-conversation-and-single-use
  (reset! state/pending-track-queries {})
  (let [request {:title "iPhone 13" :price-max 20000}
        callback-id (state/cache-track-query! "tg-42:-100:17" request)]
    (is (nil? (state/take-track-query! "tg-42:-100:18" callback-id)))
    (is (= request (state/take-track-query! "tg-42:-100:17" callback-id)))
    (is (nil? (state/take-track-query! "tg-42:-100:17" callback-id)))))
