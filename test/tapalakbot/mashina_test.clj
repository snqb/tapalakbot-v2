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
