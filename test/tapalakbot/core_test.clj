(ns tapalakbot.core-test
  (:require [clj-harness.core :as harness]
            [clj-harness.llm :as llm]
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

(deftest ask-stream-returns-the-query-used-by-search
  (with-redefs [harness/handle-message-stream!
                (fn [_bot _user-id _text _stream-cb & _options]
                  (reset! core/*captured-query* "ноутбук i5 дешевле")
                  {:content "Готово"})]
    (is (= "ноутбук i5 дешевле"
           (:query (core/ask-stream "tg-42" "дешевле" nil))))))

(deftest relevance-filter-honors-a-valid-empty-decision
  (let [filter-items @#'core/relevance-filter
        items [{"id" "1" "title" "Игрушка"}
               {"id" "2" "title" "Музыкальный плакат"}]]
    (with-redefs [llm/llm
                  (fn [& _]
                    {"choices" [{"message" {"content" "[]"}}]})]
      (is (empty? (filter-items items "флюгегехаймен"))))))

(deftest relevance-filter-falls-back-only-on-malformed-output
  (let [filter-items @#'core/relevance-filter
        items [{"id" "1" "title" "iPhone 13"}]]
    (with-redefs [llm/llm
                  (fn [& _]
                    {"choices" [{"message" {"content" "not json"}}]})]
      (is (= items (vec (filter-items items "iphone 13")))))))
