(ns tapalakbot.core-test
  (:require [clj-harness.llm :as llm]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tapalakbot.core :as core]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.monitor.tracker :as tracker]
            [tapalakbot.query-builder :as qb]))

(deftest production-agent-uses-configured-openrouter-model
  (let [config (:config @core/tapalakbot)]
    (is (= :gemini-3.5-flash (:model config)))
    (is (= :openrouter (:provider config)))))

(deftest mashina-results-format-with-real-links
  (let [format-results @#'core/format-mashina-results
        output (binding [core/*current-user-id* "tg-42"]
                 (format-results
                  {:total 1
                   :listings [{:id 1
                               :title "Toyota Camry 70"
                               :url "https://mashina.kg/details/1"
                               :year 2020
                               :mileage 50000
                               :price {:amount 1500000}}]}))]
    (is (str/includes? output "Toyota Camry 70"))
    (is (str/includes? output "https://mashina.kg/details/1"))))

(deftest helper-models-use-the-same-openrouter-contract
  (let [calls (atom [])
        fake-llm (fn [model _messages _tools & options]
                   (swap! calls conj {:model model :options (apply hash-map options)})
                   {"choices" [{"message" {"content" "{\"category_id\":null,\"category_name\":null}"}}]})]
    (with-redefs [llm/llm fake-llm
                  lalafo/search-categories (constantly "No categories")]
      (qb/enrich-with-llm "iphone")
      (tracker/match-category "iphone"))
    (is (= 2 (count @calls)))
    (is (every? #(= :gemini-3.5-flash (:model %)) @calls))
    (is (every? #(= :openrouter (get-in % [:options :provider])) @calls))))

(deftest marketplace-image-prefers-original-resolution
  (let [preferred-image (ns-resolve 'tapalakbot.core 'preferred-lalafo-image)]
    (is (some? preferred-image))
    (when preferred-image
      (is (= "https://cdn/original.jpg"
             (preferred-image
              {"thumbnail_url" "https://cdn/thumb.jpg"
               "images" [{"original_url" "https://cdn/original.jpg"
                          "thumbnail_url" "https://cdn/nested-thumb.jpg"}]}))))))
