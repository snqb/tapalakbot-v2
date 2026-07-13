(ns tapalakbot.search-test
  (:require [clojure.test :refer [deftest is]]
            [tapalakbot.search :as search]))

(deftest exact-model-and-budget-filter-before-ranking
  (let [rank (ns-resolve 'tapalakbot.search 'rank-marketplace-cards)
        cards [{:id 1 :title "Toyota Camry VIII (XV70) 2.5 AT"
                :price 1900000 :currency "KGS" :year 2019 :mileage 100000
                :url "https://mashina.kg/details/camry-1"}
               {:id 2 :title "Toyota Camry VIII (XV70) 2.5 AT"
                :price 1500000 :currency "KGS" :year 2021 :mileage 50000
                :url "https://mashina.kg/details/camry-2"}
               {:id 3 :title "Toyota Camry IX (XV80) 2.5 AT"
                :price 1800000 :currency "KGS" :year 2025
                :url "https://mashina.kg/details/camry-80"}
               {:id 4 :title "Toyota Highlander IV (U70)"
                :price 1700000 :currency "KGS"
                :url "https://mashina.kg/details/highlander"}
               {:id 5 :title "Toyota Camry VIII (XV70) 2.5 AT"
                :price 2100000 :currency "KGS"
                :url "https://mashina.kg/details/over-budget"}]]
    (is (some? rank))
    (when rank
      (let [ranked (rank cards {:query "Toyota Camry 70" :price-max 2000000})]
        (is (= [1 2] (mapv :id ranked)))
        (is (= [:great :good] (mapv :tier ranked)))))))

(deftest ranking-deduplicates-by-canonical-url
  (let [rank (ns-resolve 'tapalakbot.search 'rank-marketplace-cards)
        card {:title "Toyota Camry VIII (XV70)"
              :price 1800000
              :url "https://mashina.kg/details/same"}]
    (is (some? rank))
    (when rank
      (is (= 1 (count (rank [card card] {:query "Toyota Camry 70"})))))))

(deftest specific-model-query-never-falls-back-to-wrong-models
  (let [cards [{:id 1 :title "Toyota Land Cruiser 200" :price 1900000
                :currency "KGS" :platform :mashina}
               {:id 2 :title "Toyota Camry 50" :price 1500000
                :currency "KGS" :platform :mashina}]]
    (is (empty?
         (search/rank-marketplace-cards
          cards {:query "Toyota Camry 70" :price-max 2000000})))))

(deftest marketplace-stats-never-mix-usd-and-kgs
  (let [compute-stats (ns-resolve 'tapalakbot.search 'compute-stats)]
    (is (= {:avg 1500000 :min 1000000 :max 2000000 :count 2}
           (compute-stats [{:price 1000000 :currency "KGS"}
                           {:price 2000000 :currency "сом"}
                           {:price 22000 :currency "USD"}])))))
