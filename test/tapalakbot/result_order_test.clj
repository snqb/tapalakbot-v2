(ns tapalakbot.result-order-test
  (:require [clojure.test :refer [deftest is]]
            [tapalakbot.result-order :as order]))

(defn- card [id fit price rank]
  {:id id :source-field (str "source-" id)
   :decision {:fit fit} :price-kgs price :rank-score rank})

(deftest direct-groups-and-price-order-are-stable
  (let [cards [(card :possible-expensive :possible 300 99)
               (card :direct-unknown :direct nil 20)
               (card :direct-cheap :direct 100 1)
               (card :direct-tie-a :direct 100 50)
               (card :direct-tie-b :direct 100 40)
               (card :possible-cheap :possible 50 80)]]
    (is (= [:direct-cheap :direct-tie-a :direct-tie-b :direct-unknown
            :possible-cheap :possible-expensive]
           (mapv :id (order/order-cards cards :price-asc))))
    (is (= [:direct-cheap :direct-tie-a :direct-tie-b :direct-unknown
            :possible-expensive :possible-cheap]
           (mapv :id (order/order-cards cards :price-desc))))
    (is (= [:direct-tie-a :direct-tie-b :direct-unknown :direct-cheap
            :possible-expensive :possible-cheap]
           (mapv :id (order/order-cards cards :relevance))))))


 (deftest relevance-prefers-jev-score-before-price-availability
   (let [cards [(assoc (card :unknown-high :direct nil 100) :price 100 :currency "USD")
                (card :known-low :direct 100 10)]]
     (is (= [:unknown-high :known-low]
            (mapv :id (order/order-cards cards :relevance))))))
(deftest unknown-currencies-are-not-compared
  (let [cards [(assoc (card :usd :direct 100 1) :price-kgs nil :price 100 :currency "USD")
               (assoc (card :kgs :direct 5000 2) :price 5000 :currency "KGS")
               (assoc (card :equivalent :direct 4500 3) :price 100 :currency "USD")]]
    (is (= [:equivalent :kgs :usd]
           (mapv :id (order/order-cards cards :price-asc))))))

(deftest ordering-preserves-every-source-field
  (let [cards [(assoc (card :a :direct 20 90)
                     :price 2 :currency "USD" :url "https://lalafo.kg/ad/a"
                     :desc "Полное описание" :images ["https://img.example/a.jpg"])
               (assoc (card :b :direct 10 80)
                      :price 10 :currency "KGS" :url "https://lalafo.kg/ad/b")]
        originals (into {} (map (juxt :id identity)) cards)]
    (doseq [mode [:price-asc :price-desc :relevance]]
      (is (= originals
             (into {} (map (juxt :id identity)) (order/order-cards cards mode)))))))
