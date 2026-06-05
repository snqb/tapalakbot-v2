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
  (testing "preserves lalafo.kg URLs"
    (let [url "https://lalafo.kg/bishkek/ads/iphone-13-id-123"]
      (is (= (str "🔗 " url) (strip-fake-urls* (str "🔗 " url))))))

  (testing "replaces non-lalafo URLs"
    (is (str/includes? (strip-fake-urls* "🔗 https://google.com/search") "ссылка недоступна"))
    (is (str/includes? (strip-fake-urls* "https://apple.com/iphone") "ссылка недоступна")))

  (testing "preserves lalafo URLs without emoji prefix"
    (let [url "https://lalafo.kg/bishkek/ads/item-id-456"]
      (is (= url (strip-fake-urls* url)))))

  (testing "handles multiple URLs"
    (let [text (str "See " "https://lalafo.kg/good" " and " "https://google.com/bad")]
      (let [result (strip-fake-urls* text)]
        (is (str/includes? result "lalafo.kg"))
        (is (str/includes? result "ссылка недоступна")))))

  (testing "handles text with no URLs"
    (is (= "No links here" (strip-fake-urls* "No links here")))))

;; ════════════════════════════ CITATION-REPLACE ════════════════════════════

(deftest citation-replace-test
  (testing "replaces #ID with clickable link when URL exists"
    (with-url-store "test-user" {"113333" "https://lalafo.kg/bishkek/ads/iphone-13-id-113333"}
      (fn []
        (let [text "• iPhone 13 — #113333 — 25 000 сом"
              result (citation-replace* text "test-user")]
          (is (str/includes? result "[iPhone 13 —](https://lalafo.kg/bishkek/ads/iphone-13-id-113333)")
          (str "Unexpected result: " result))
          (is (not (str/includes? result "#113333")))))))

  (testing "preserves #ID when URL not in store"
    (with-url-store "test-user" {}
      (fn []
        (let [text "• Item — #999999 — 1000 сом"
              result (citation-replace* text "test-user")]
          (is (str/includes? result "#999999"))))))

  (testing "handles empty url-store"
    (with-url-store "nonexistent" {}
      (fn []
        (let [text "• Item — #123456 — 1000 сом"
              result (citation-replace* text "nonexistent")]
          (is (= text result))))))

  (testing "handles multiple IDs"
    (with-url-store "user1" {"111" "https://lalafo.kg/a"
                              "222" "https://lalafo.kg/b"}
      (fn []
        (let [text "• Item A — #111 — 100\n• Item B — #222 — 200"
              result (citation-replace* text "user1")]
          (is (str/includes? result "lalafo.kg/a"))
          (is (str/includes? result "lalafo.kg/b"))
          (is (not (str/includes? result "#111")))
          (is (not (str/includes? result "#222")))))))

  (testing "does not replace # in non-bullet context"
    (with-url-store "u" {"12345" "https://lalafo.kg/x"}
      (fn []
        (let [text "Price is #12345 som"
              result (citation-replace* text "u")]
          (is (str/includes? result "#12345")))))))

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
          result (-> text strip-tables* strip-fake-urls*)]
      (is (not (str/includes? result "|")))
      (is (str/includes? result "ссылка недоступна"))))

  (testing "citation → strip-fake-urls pipeline"
    (with-url-store "u" {"123" "https://lalafo.kg/good"}
      (fn []
        (let [text "• iPhone — #123 — 25000\nSee https://fake.com"
              result (-> text (citation-replace* "u") strip-fake-urls*)]
          (is (str/includes? result "lalafo.kg/good"))
          (is (str/includes? result "ссылка недоступна"))
          (is (not (str/includes? result "#123"))))))))
