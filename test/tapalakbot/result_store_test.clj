(ns tapalakbot.result-store-test
  (:require [clojure.test :refer [deftest is]]
            [tapalakbot.result-store :as result-store]))

(deftest cursor-pages-through-ranked-pool-without-duplicates
  (reset! result-store/result-pools {})
  (let [cards (mapv (fn [id] {:id id :title (str "Card " id)}) (range 45))
        cursor-id (result-store/cache-result-pool! "tg-42" "camry" cards 20)
        second-page (result-store/next-result-page! "tg-42" cursor-id 20)
        third-page (result-store/next-result-page! "tg-42" cursor-id 20)]
    (is (= (vec (range 20 40)) (mapv :id (:cards second-page))))
    (is (true? (:has-more second-page)))
    (is (= (vec (range 40 45)) (mapv :id (:cards third-page))))
    (is (false? (:has-more third-page)))
    (is (nil? (result-store/next-result-page! "tg-42" cursor-id 20)))
    (is (= 45 (count (set (concat (range 20)
                                  (map :id (:cards second-page))
                                  (map :id (:cards third-page)))))))))

(deftest cursor-rejects-another-user
  (reset! result-store/result-pools {})
  (let [cursor-id (result-store/cache-result-pool!
                   "tg-owner" "camry" [{:id 1} {:id 2}] 1)]
    (is (nil? (result-store/next-result-page! "tg-other" cursor-id 1)))))

(deftest stale-state-is-removed-on-access
  (reset! result-store/ad-cache
          {"stale-user" {:created-at 0
                         :cards {1 {:id 1 :title "Expired"}}}})
  (is (nil? (result-store/get-ad "stale-user" 1)))
  (is (empty? @result-store/ad-cache))

  (reset! result-store/result-pools
          {"stale-cursor" {:user-id "tg-42"
                           :query "camry"
                           :cards [{:id 1}]
                           :offset 0
                           :created-at 0}})
  (is (nil? (result-store/next-result-page! "tg-42" "stale-cursor" 20)))
  (is (empty? @result-store/result-pools)))
