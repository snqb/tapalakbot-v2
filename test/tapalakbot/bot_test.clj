(ns tapalakbot.bot-test
  "Unit tests for bot formatting, text processing, and message handling.
   Tests the private functions: strip-tables, strip-fake-urls, citation-replace,
   parse-update-extended. No LLM calls — pure function tests."
  (:require [clojure.test :refer [deftest is testing are]]
            [tapalakbot.bot :as bot]
            [tapalakbot.core :as t]
            [clojure.string :as str]))

;; Access private vars for testing
(def strip-tables* @#'bot/strip-tables)
(def strip-fake-urls* @#'bot/strip-fake-urls)
(def citation-replace* @#'bot/citation-replace)
(def parse-update-extended* @#'bot/parse-update-extended)

(defn- with-url-store
  "Temporarily set url-store for a user, run fn, then restore."
  [user-id store-map f]
  (let [url-store-var (var tapalakbot.core/url-store)
        url-store-atom (.deref url-store-var)
        old @url-store-atom]
    (try
      (swap! url-store-atom assoc user-id store-map)
      (f)
      (finally
        (reset! url-store-atom old)))))

;; ════════════════════════════ STRIP-TABLES ════════════════════════════

(deftest strip-tables-test
  (testing "removes markdown table separator rows"
    (is (= "" (strip-tables* "| --- | --- |")))
    (is (= "" (strip-tables* "| :---: | :---: |")))
    (is (= "" (strip-tables* "|---|---|"))))

  (testing "removes table data rows"
    (is (= "" (strip-tables* "| iPhone 13 | 25000 |")))
    (is (= "" (strip-tables* "| MacBook | 80000 | Excellent |"))))

  (testing "preserves non-table text"
    (is (= "Hello world" (strip-tables* "Hello world")))
    (is (= "• Item 1\n• Item 2" (strip-tables* "• Item 1\n• Item 2"))))

  (testing "handles mixed content"
    (let [text "Header text\n| col1 | col2 |\n| --- | --- |\n| a | b |\nFooter text"]
      (let [result (strip-tables* text)]
        (is (str/includes? result "Header text"))
        (is (str/includes? result "Footer text"))
        (is (not (str/includes? result "|")))))))

;; ════════════════════════════ STRIP-FAKE-URLS ════════════════════════════

(deftest strip-fake-urls-test
  (testing "preserves lalafo.kg URLs (when in url-store)"
    (with-url-store "test-user" {"A" {:url "https://lalafo.kg/bishkek/ads/iphone-13-id-123"
                                       :title "iPhone 13"
                                       :item-id "123"}}
      (fn []
        (let [url "https://lalafo.kg/bishkek/ads/iphone-13-id-123"
              result (strip-fake-urls* (str "🔗 " url) "test-user")]
          (is (= (str "🔗 " url) result))))))

  (testing "replaces non-lalafo URLs"
    (let [result1 (strip-fake-urls* "🔗 https://google.com/search" "test-user")
          result2 (strip-fake-urls* "https://apple.com/iphone" "test-user")]
      (is (str/includes? result1 "⚠️"))
      (is (str/includes? result2 "⚠️"))))

  (testing "preserves mashina.kg URLs"
    (let [url "https://mashina.kg/details/123"]
      (is (= url (strip-fake-urls* url "test-user")))))

  (testing "handles multiple URLs"
    (let [text (str "See https://lalafo.kg/good and https://google.com/bad")
          result (strip-fake-urls* text "test-user")]
      (is (str/includes? result "lalafo.kg"))
      (is (str/includes? result "⚠️"))))

  (testing "handles text with no URLs"
    (is (= "No links here" (strip-fake-urls* "No links here" "test-user")))))

;; ════════════════════════════ CITATION-REPLACE ════════════════════════════

(deftest citation-replace-test
  (testing "replaces #A letter token with clickable link (dash format)"
    (with-url-store "test-user" {"A" {:url "https://lalafo.kg/bishkek/ads/iphone-13-id-113333"
                                       :title "iPhone 13 128GB"
                                       :item-id "113333"}}
      (fn []
        (let [text "- #A [iPhone 13] | 25 000 KGS"
              result (citation-replace* text "test-user")]
          (is (str/includes? result "<a href='https://lalafo.kg/bishkek/ads/iphone-13-id-113333'>"))
          (is (not (str/includes? result "#A")))))))

  (testing "replaces #A letter token with clickable link (bullet format)"
    (with-url-store "test-user" {"A" {:url "https://lalafo.kg/item/1"
                                       :title "MacBook Pro M1"
                                       :item-id "1"}}
      (fn []
        (let [text "• MacBook Pro — #A — 80 000 сом"
              result (citation-replace* text "test-user")]
          (is (str/includes? result "<a href='https://lalafo.kg/item/1'>"))
          (is (not (str/includes? result "#A")))))))

  (testing "Pass 2: standalone #A token gets converted to link"
    (with-url-store "test-user" {"A" {:url "https://lalafo.kg/x"
                                       :title "Test Item"
                                       :item-id "99"}}
      (fn []
        (let [text "#A — 1000 сом"
              result (citation-replace* text "test-user")]
          (is (str/includes? result "<a href='https://lalafo.kg/x'>"))
          (is (not (str/includes? result "#A")))))))

  (testing "replaces invented #Z token with [нет данных]"
    (with-url-store "test-user" {"A" {:url "https://lalafo.kg/real" :title "Real Item" :item-id "1"}}
      (fn []
        (let [text "- #A Real Item — 100 KGS\n• Made up — #Z — 1000 сом"
              result (citation-replace* text "test-user")]
          (is (str/includes? result "<a href='https://lalafo.kg/real'>"))
          (is (str/includes? result "[нет данных]"))
          (is (not (str/includes? result "#Z")))
          ;; #A is replaced with link, so it should not appear as raw token
          (is (not (str/includes? result "#A")))))))

  (testing "handles empty url-store"
    (with-url-store "nonexistent" {}
      (fn []
        (let [text "• Item — #A — 1000 сом"
              result (citation-replace* text "nonexistent")]
          (is (= text result))))))

  (testing "handles multiple letter tokens"
    (with-url-store "user1" {"A" {:url "https://lalafo.kg/a" :title "Item A" :item-id "1"}
                              "B" {:url "https://lalafo.kg/b" :title "Item B" :item-id "2"}}
      (fn []
        (let [text "- #A Item A — 100 KGS\n• #B Item B — 200 KGS"
              result (citation-replace* text "user1")]
          (is (str/includes? result "lalafo.kg/a"))
          (is (str/includes? result "lalafo.kg/b"))
          (is (not (str/includes? result "#A")))
          (is (not (str/includes? result "#B")))))))

  (testing "handles legacy string-format url-store entries"
    (with-url-store "u" {"C" "https://lalafo.kg/x"}
      (fn []
        (let [text "- #C — old format entry"
              result (citation-replace* text "u")]
          (is (str/includes? result "<a href='https://lalafo.kg/x'>"))
          (is (not (str/includes? result "#C"))))))))

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

;; ════════════════════════════ INTEGRATION: FULL PIPELINE ════════════════════════════

(deftest formatting-pipeline-test
  (testing "strip-tables → strip-fake-urls pipeline"
    (let [text "Results\n| iPhone | 25000 |\n| --- | --- |\nSee https://fake-site.com for more"
          result (-> text strip-tables* (strip-fake-urls* "test-user"))]
      (is (not (str/includes? result "|")))
      (is (str/includes? result "⚠️"))))

  (testing "citation → strip-fake-urls pipeline with letter tokens"
    (with-url-store "u" {"A" {:url "https://lalafo.kg/good" :title "iPhone 13" :item-id "123"}}
      (fn []
        (let [text "- #A [iPhone 13] — 25 000 KGS\nSee https://fake.com"
              result (-> text (citation-replace* "u") (strip-fake-urls* "u"))]
          (is (str/includes? result "lalafo.kg/good"))
          (is (str/includes? result "⚠️"))
          (is (not (str/includes? result "#A"))))))))
