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

 (deftest small-pools-retain-sortable-cursor
   (reset! result-store/result-pools {})
   (let [cards [{:id :high :price 200 :currency "KGS"}
                {:id :low :price 100 :currency "KGS"}]
         cursor (result-store/cache-result-pool! "tg-small" "q" cards 2 {})
         sorted (result-store/sort-result-pool! "tg-small" cursor :price-asc 20)]
     (is (some? cursor))
     (is (= [:low :high] (mapv :id (:cards sorted))))
     (is (= 1 (:start sorted)))
     (is (false? (:has-more sorted)))))

 (deftest sorting-resets-numbering-and-invalidates-old-cursor
   (reset! result-store/result-pools {})
   (let [cards [{:id :a :price 300 :currency "KGS"}
                {:id :b :price 100 :currency "KGS"}
                {:id :c :price 200 :currency "KGS"}]
         cursor (result-store/cache-result-pool! "tg-sort" "q" cards 3 {})
         sorted (result-store/sort-result-pool! "tg-sort" cursor :price-asc 2)]
     (is (= [:b :c] (mapv :id (:cards sorted))))
     (is (= [1 2] (mapv :result-index (:cards sorted))))
     (is (nil? (result-store/next-result-page! "tg-sort" cursor 2)))
     (is (= [:a] (mapv :id (:cards
                            (result-store/next-result-page!
                             "tg-sort" (:cursor-id sorted) 2)))))))

 (deftest drill-down-cache-follows-latest-order
   (reset! result-store/ad-cache {})
   (result-store/cache-ads! "tg-drill" [{:id :first} {:id :second}])
   (result-store/cache-ads! "tg-drill" [{:id :second} {:id :first}])
   (is (= :second (:id (result-store/get-ad "tg-drill" 1))))
   (is (= 1 (:result-index (result-store/get-ad "tg-drill" 1)))))

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

(deftest sorted-pages-keep-the-current-view-context
  (reset! result-store/result-pools {})
  (let [cards (mapv (fn [n] {:id n :price n :currency "KGS"}) (range 1 6))
        cursor (result-store/cache-result-pool!
                "owner" "q" cards 2 {:cards cards :sort-mode :price-asc})
        sorted (result-store/sort-result-pool! "owner" cursor :price-desc 2)
        page (result-store/next-result-page! "owner" (:cursor-id sorted) 2)]
    (is (= [5 4] (mapv :id (:cards sorted))))
    (is (= [3 2] (mapv :id (:cards page))))
    (is (= [3 4] (mapv :result-index (:cards page))))
    (is (= :price-desc (:sort-mode page) (get-in page [:context :sort-mode])))
    (is (= [5 4 3 2 1] (mapv :id (get-in page [:context :cards]))))))

(deftest rejected-sort-cannot-consume-an-owned-view
  (reset! result-store/result-pools {})
  (let [cursor (result-store/cache-result-pool!
                "owner" "q" [{:id 1 :price 10 :currency "KGS"}] 1)]
    (is (nil? (result-store/sort-result-pool! "other" cursor :price-desc 20)))
    (is (nil? (result-store/sort-result-pool! "owner" cursor :invalid 20)))
    (is (= [1] (mapv :id (:cards
                         (result-store/sort-result-pool! "owner" cursor :price-desc 20)))))))

(deftest expired-sort-never-restores-stale-source-results
  (reset! result-store/result-pools
          {"expired" {:user-id "owner" :created-at 0
                      :source-pool [{:id 1}] :cards [{:id 1}] :offset 1}})
  (is (nil? (result-store/sort-result-pool! "owner" "expired" :price-asc 20))))
