(ns tapalakbot.bot-test
  "Unit tests for bot formatting, text processing, and message handling.
   Tests the private function: parse-update-extended. No LLM calls — pure function tests."
  (:require [clojure.test :refer [deftest is testing are]]
            [tapalakbot.bot :as bot]
            [tapalakbot.core :as t]
            [clojure.string :as str]))

;; Access private var for testing
(def parse-update-extended* @#'bot/parse-update-extended)

;; ════════════════════════════ PARSE-UPDATE-EXTENDED ════════════════════════════

(deftest parse-update-extended-test
  (testing "parses regular text message"
    (let [update {"message" {"chat" {"id" 123}
                              "from" {"id" 456 "first_name" "Test"}
                              "text" "hello"
                              "message_id" 789}}
          result (parse-update-extended* update)]
      (is (= 123 (:chat-id result)))
      (is (= 456 (:user-id result)))
      (is (= "hello" (:text result)))
      (is (= 789 (:message-id result)))
      (is (= "Test" (:first-name result)))
      (is (nil? (:callback-id result)))))

  (testing "parses callback query"
    (let [update {"callback_query" {"id" "cb-123"
                                     "data" "track:1234"
                                     "from" {"id" 789}
                                     "message" {"chat" {"id" 100}
                                                 "message_id" 200}}}
          result (parse-update-extended* update)]
      (is (= "cb-123" (:callback-id result)))
      (is (= "track:1234" (:data result)))
      (is (= 789 (:user-id result)))
      (is (= 100 (:chat-id result)))
      (is (= 200 (:msg-id result)))
      (is (nil? (:text result)))))

  (testing "handles missing first_name gracefully"
    (let [update {"message" {"chat" {"id" 1}
                              "from" {"id" 2}
                              "message_id" 3}}
          result (parse-update-extended* update)]
      (is (= "друг" (:first-name result)))))

  (testing "returns nil for unknown update type"
    (let [update {"unknown_type" {"foo" "bar"}}
          result (parse-update-extended* update)]
      (is (nil? result))))

  (testing "parses message with location"
    (let [update {"message" {"chat" {"id" 1}
                              "from" {"id" 2}
                              "message_id" 3
                              "location" {"latitude" 42.87 "longitude" 74.59}}}
          result (parse-update-extended* update)]
      (is (some? (:location result)))
      (is (= 42.87 (:lat (:location result))))
      (is (= 74.59 (:lon (:location result)))))))
