(ns tapalakbot.core-test
  (:require [clj-harness.core :as harness]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tapalakbot.core :as core]
            [tapalakbot.search :as search]))

(deftest writer-never-receives-source-price-or-action-url
  (let [card {:id "a" :title "Дорожка для ходьбы" :price 12345
              :url "https://lalafo.kg/bishkek/ads/id-a"
              :decision {:reasons ["Подходит для ходьбы"]}}
        result {:query "дорожка" :cards [card] :search-status :ok
                :selection {:input-count 1 :accepted-count 1}}]
    (with-redefs [search/search (fn [& _] result)]
      (binding [core/*captured-cards* (atom [])
                core/*captured-query* (atom nil)
                core/*captured-search* (atom nil)]
        (let [context ((:execute (second core/tools)) {"user_want" "дорожка"})]
          (is (str/includes? context "Подходит для ходьбы"))
          (is (not (str/includes? context "12345")))
          (is (not (str/includes? context (:url card))))
          (is (= [card] @core/*captured-cards*)))))))

(deftest failed-search-overrides-an-invented-successful-summary
  (with-redefs [harness/handle-message-stream!
                (fn [& _]
                  (reset! core/*captured-search*
                          {:search-status :unavailable :error "Проверка недоступна"})
                  {:content "Нашёл лучшие товары"})]
    (let [result (core/ask-stream "test-failure" "дорожка" nil)]
      (is (= "Проверка недоступна" (:text result)))
      (is (= :unavailable (:search-status result)))
      (is (empty? (:cards result))))))

(deftest new-product-clears-prior-marketplace-context
  (let [session (atom {"messages" [{"role" "user" "content" "ноутбук"}
                                   {"role" "assistant" "content" "старые объявления"}
                                   {"role" "user" "content" "роутер"}]
                       "data" {"heap" {"old" "старый ноутбук"}
                                "last-search" "ноутбук"}})]
    (core/pre-hook "test" "роутер" session)
    (is (= [{"role" "user" "content" "роутер"}] (get @session "messages")))
    (is (nil? (get-in @session ["data" "heap"])))
    (is (nil? (get-in @session ["data" "last-search"])))))

(deftest price-only-followup-keeps-the-product-context
  (let [messages [{"role" "user" "content" "дорожка для ходьбы"}
                  {"role" "assistant" "content" "Подборка"}
                  {"role" "user" "content" "а до 15000 есть?"}]
        session (atom {"messages" messages})]
    (core/pre-hook "test" "а до 15000 есть?" session)
    (is (= messages (get @session "messages")))))

(deftest empty-provider-answer-is-unavailable-not-an-empty-market
  (with-redefs [harness/handle-message-stream! (constantly "")]
    (let [result (core/ask-stream "test-provider-outage" "телефон" nil)]
      (is (= :unavailable (:search-status result)))
      (is (nil? (:selection result)))
      (is (= [] (:cards result))))))
