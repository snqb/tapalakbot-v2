(ns tapalakbot.mashina-test
  (:require [clojure.test :refer [deftest is]]
            [clj-http.client :as http]
            [tapalakbot.mashina :as mashina]))

(deftest search-results-use-the-canonical-details-route
  (with-redefs [http/get
                (fn [_url _options]
                  {:body {:items [{:id 10078362
                                   :title "Toyota Camry IX"
                                   :slug "toyota-camry-6a4adf3144a55a90f6fffeb8"
                                   :prices []
                                   :attributes []
                                   :images []}]
                          :total 1
                          :page 1
                          :pages 1}})]
    (let [listing (-> (mashina/search-cars :query "Toyota") :listings first)]
      (is (= "https://mashina.kg/details/toyota-camry-6a4adf3144a55a90f6fffeb8"
             (:url listing))))))

(deftest search-cars-defaults-to-a-hundred-candidates
  (let [request-options (atom nil)]
    (with-redefs [http/get
                  (fn [_url options]
                    (reset! request-options options)
                    {:body {:items [] :total 0 :page 1 :pages 0}})]
      (mashina/search-cars :query "Toyota Camry")
      (is (= 100 (get-in @request-options [:query-params "size"]))))))

(deftest search-cars-normalizes-kgs-as-the-comparable-price
  (with-redefs [http/get
                (fn [_url _options]
                  {:body {:items [{:id 1
                                   :title "Toyota Camry VIII"
                                   :slug "toyota-camry-real"
                                   :prices [{:currency "USD" :amount 22000 :is_original false}
                                            {:currency "KGS" :amount 1923500 :is_original true}]
                                   :attributes []
                                   :images []}]
                          :total 1 :page 1 :pages 1}})]
    (let [listing (-> (mashina/search-cars :query "Toyota Camry") :listings first)]
      (is (= {:amount 1923500 :currency "KGS"}
             (:price listing)))
      (is (= 22000 (:price-usd listing)))
      (is (= 1923500 (:price-kgs listing))))))
