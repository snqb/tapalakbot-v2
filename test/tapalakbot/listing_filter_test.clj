(ns tapalakbot.listing-filter-test
  (:require [clojure.test :refer [deftest is testing]]
            [tapalakbot.listing-filter :as listing-filter]))

(deftest weighted-accessory-signals
  (testing "strong accessory terms cross the default threshold"
    (is (<= 5 (listing-filter/accessory-score "Чехол для iPhone 13")))
    (is (<= 5 (listing-filter/accessory-score "Адаптер питания USB-C"))))
  (testing "ordinary product titles remain unpenalized"
    (is (zero? (listing-filter/accessory-score "iPhone 13 Pro 128GB")))))

(deftest filter-honors-explicit-accessory-intent
  (let [items [{:title "iPhone 13 Pro 128GB"}
               {:title "Чехол для iPhone 13"}]]
    (is (= ["iPhone 13 Pro 128GB"]
           (mapv :title (listing-filter/filter-accessories items "iphone 13"))))
    (is (= items
           (listing-filter/filter-accessories items "чехол iphone 13")))))

(deftest filter-supports-string-keyed-source-items
  (let [items [{"title" "MacBook Air M2"}
               {"title" "Зарядка MacBook USB-C"}]]
    (is (= ["MacBook Air M2"]
           (mapv #(get % "title")
                 (listing-filter/filter-accessories items "macbook air"))))))
