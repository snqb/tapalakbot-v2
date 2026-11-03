(ns tapalakbot.core-test
  (:require [clj-harness.core :as harness]
            [cheshire.core :as json]
            [tapalakbot.conversation :as conversation]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tapalakbot.core :as core]
            [tapalakbot.search :as search]))

(deftest writer-never-receives-source-price-or-action-url
  (let [card {:id "a" :title "Дорожка для ходьбы" :desc "Полное описание товара без сокращений."
              :price 12345
              :url "https://lalafo.kg/bishkek/ads/id-a"
              :decision {:reasons ["Подходит для ходьбы"]}}
        result {:query "дорожка" :cards [card] :search-status :ok
                :selection {:input-count 1 :accepted-count 1}}]
    (with-redefs [search/search (fn [& _] result)]
      (binding [core/*captured-cards* (atom [])
                core/*captured-query* (atom nil)
                core/*captured-search* (atom nil)]
        (let [context ((:execute (some #(when (= "search" (:name %)) %) core/tools))
                       {"user_want" "дорожка"})]
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

(deftest freeform-feedback-keeps-the-conversation-for-the-agent
  (let [state {"messages" [{"role" "user" "content" "компактная дорожка"}
                            {"role" "assistant" "content" "Показаны варианты"}
                            {"role" "user" "content" "эти не подходят — взрослому тесно"}]
               "data" {"last-search" "компактная дорожка"}}
        session (atom state)]
    (core/pre-hook "test" "эти не подходят — взрослому тесно" session)
    (is (= state @session))))

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

(deftest old-feedback-cannot-reject-the-newly-displayed-page
  (let [session (atom {})
        reject (:execute (some #(when (= "reject_results" (:name %)) %) core/tools))]
    (conversation/remember-search! session {:user-want "дорожка"}
                                   {:cards [{:id "current" :title "Вариант"}]
                                    :search-status :ok})
    (binding [core/*current-session* session
              core/*current-message* "Первый вариант не подходит: нужен складной."]
      (is (= "invalid" (get (json/parse-string
                             (reject {"quote" "Первый и второй не подходят" "numbers" [1]}))
                            "status")))
      (is (empty? (:excluded-ids (conversation/current session))))
      (is (= "recorded" (get (json/parse-string
                              (reject {"quote" "Первый вариант не подходит: нужен складной."
                                       "numbers" [1]})) "status")))
      (is (= ["current"] (:excluded-ids (conversation/current session)))))))
