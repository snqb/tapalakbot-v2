(ns tapalakbot.intent-test
  (:require [clojure.test :refer :all]
            [tapalakbot.intent :as intent]
            [cheshire.core :as json]))

;; ════════════════════ HELPERS ════════════════════

(defn- mock-llm-response
  "Create a mock LLM response with intent JSON."
  [intent-str query & [confidence]]
  {"choices" [{"message" {"content"
                           (json/generate-string
                            {:intent intent-str
                             :query query
                             :confidence (or confidence 0.9)})}}]})

;; ════════════════════ FALLBACK ════════════════════

(deftest test-llm-failure-fallback
  (testing "falls back to :search when LLM fails and no session"
    (with-redefs [clj-harness.llm/llm (fn [& _] (throw (Exception. "no LLM")))]
      (let [result (intent/classify-intent "какой-то запрос" nil)]
        (is (= :search (:intent result)))
        (is (= "какой-то запрос" (:query result))))))

  (testing "falls back to :research when LLM fails and session exists"
    (with-redefs [clj-harness.llm/llm (fn [& _] (throw (Exception. "no LLM")))]
      (let [result (intent/classify-intent "хочу айфон" {:last-search "iphone 13"})]
        (is (= :research (:intent result)))))))

;; ════════════════════ LLM CLASSIFICATION ════════════════════

(deftest test-classify-search
  (with-redefs [clj-harness.llm/llm
                (fn [& _] (mock-llm-response "search" "iphone 13"))]
    (let [result (intent/classify-intent "iphone 13" nil)]
      (is (= :search (:intent result)))
      (is (= "iphone 13" (:query result))))))

(deftest test-classify-followup
  (with-redefs [clj-harness.llm/llm
                (fn [& _] (mock-llm-response "followup" "which is better"))]
    (let [result (intent/classify-intent "which is better"
                                         {:last-search "iphone 13"
                                          :last-card-count 5})]
      (is (= :followup (:intent result))))))

(deftest test-classify-research
  (with-redefs [clj-harness.llm/llm
                (fn [& _] (mock-llm-response "research" "велосипед для города"))]
    (let [result (intent/classify-intent "хочу велосипед для города" nil)]
      (is (= :research (:intent result))))))

(deftest test-classify-chat
  (with-redefs [clj-harness.llm/llm
                (fn [& _] (mock-llm-response "chat" "как дела"))]
    (let [result (intent/classify-intent "как дела" nil)]
      (is (= :chat (:intent result))))))

(deftest test-classify-compare
  (with-redefs [clj-harness.llm/llm
                (fn [& _] (mock-llm-response "compare" "iphone vs samsung"))]
    (let [result (intent/classify-intent "что лучше iphone или samsung" nil)]
      (is (= :compare (:intent result))))))

(deftest test-classify-refine
  (with-redefs [clj-harness.llm/llm
                (fn [& _] (mock-llm-response "refine" "дешевле"))]
    (let [result (intent/classify-intent "дешевле" {:last-search "iphone 13"})]
      (is (= :refine (:intent result))))))

;; ════════════════════ EDGE CASES ════════════════════

(deftest test-unknown-intent-falls-to-search
  (testing "unknown intent string falls back to :search"
    (with-redefs [clj-harness.llm/llm
                  (fn [& _] (mock-llm-response "something_weird" "query"))]
      (let [result (intent/classify-intent "query" nil)]
        (is (= :search (:intent result)))))))
