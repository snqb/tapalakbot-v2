(ns tapalakbot.result-flow-test
  (:require [clj-harness.telegram :as tg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tapalakbot.bot :as bot]
            [tapalakbot.core :as t]
            [tapalakbot.telegram-conversation :as conversation]
            [tapalakbot.result-store :as result-store]))

(deftest initial-results-and-cached-sort-deliver-an-actionable-rich-document
  (let [run conversation/handle-message!
        handle-callback @#'bot/handle-callback
        sent (atom [])
        agent-calls (atom 0)
        pool [{:id :expensive :title "High" :price 300 :currency "KGS"
               :url "https://lalafo.kg/ad/high" :decision {:fit :direct}}
              {:id :cheap :title "Low" :price 100 :currency "KGS"
               :url "https://lalafo.kg/ad/low" :decision {:fit :direct}}
              {:id :review :title "Review" :price 50 :currency "KGS"
               :url "https://lalafo.kg/ad/review" :decision {:fit :possible}}]]
    (with-redefs-fn
      {#'result-store/result-pools (atom {})
       #'result-store/ad-cache (atom {})
       #'t/ask-stream (fn [& _]
                       (swap! agent-calls inc)
                       {:text "Есть варианты. Проверьте состояние."
                        :cards pool :pool pool :query "дорожка" :search-status :ok})
       #'tg/send-message (fn [& _] {"message_id" 1})
       #'tg/send-md (fn [& _] {"ok" true})
       #'tg/call (fn [method body]
                   (when (= "sendRichMessage" method) (swap! sent conj body))
                   {"ok" true "result" {"message_id" 2}})
       #'tg/edit-message (fn [& _])
       #'tg/delete-message (fn [& _])
       #'conversation/rename-topic! (fn [& _])}
      (fn []
        (run {:chat-id -100 :user-id 42 :thread-id 17 :text "дорожка"})
        (let [document (first @sent)
              html (or (get-in document ["rich_message" "html"]) "")
              buttons (mapcat identity (get-in document ["reply_markup" "inline_keyboard"]))
              sort-data (some #(let [data (get % "callback_data" "")]
                                 (when (str/starts-with? data "sort:price-desc:") data))
                              buttons)]
          (is (every? #(str/includes? html (:url %)) pool))
          (is (< (.indexOf html "https://lalafo.kg/ad/low")
                 (.indexOf html "https://lalafo.kg/ad/high")
                 (.indexOf html "https://lalafo.kg/ad/review")))
          (is (= 17 (get document "message_thread_id")))
          (is (= :cheap (:id (result-store/get-ad "tg-42:-100:17" 1))))
          (is (string? sort-data))
          (when sort-data
            (handle-callback {:data sort-data :callback-id "sort"
                              :chat-id -100 :user-id 42 :thread-id 17})
            (let [sorted-html (get-in (second @sent) ["rich_message" "html"] "")]
              (is (< (.indexOf sorted-html "https://lalafo.kg/ad/high")
                     (.indexOf sorted-html "https://lalafo.kg/ad/low")
                     (.indexOf sorted-html "https://lalafo.kg/ad/review")))
              (is (= :expensive (:id (result-store/get-ad "tg-42:-100:17" 1))))))
          (is (= 1 @agent-calls)))))))

(deftest stale-pages-alert-without-restarting-search-or-delivering-old-cards
  (let [handle-callback @#'bot/handle-callback
        calls (atom [])
        agent-calls (atom 0)]
    (with-redefs-fn
      {#'result-store/result-pools (atom {})
       #'t/ask-stream (fn [& _] (swap! agent-calls inc) {:cards []})
       #'tg/call (fn [method body]
                   (swap! calls conj [method body])
                   {"ok" true})}
      (fn []
        (let [cursor (result-store/cache-result-pool!
                      "tg-42:-100:17" "q"
                      [{:id 1 :price 100 :currency "KGS"}
                       {:id 2 :price 200 :currency "KGS"}] 1)
              sorted (result-store/sort-result-pool! "tg-42:-100:17" cursor :price-asc 20)
              callback {:callback-id "stale" :chat-id -100 :user-id 42 :thread-id 17}]
          (handle-callback (assoc callback :data (str "page:" cursor)))
          (handle-callback (assoc callback :callback-id "exhausted"
                                 :data (str "page:" (:cursor-id sorted))))
          (handle-callback (assoc callback :callback-id "foreign" :user-id 99
                                 :data (str "page:" (:cursor-id sorted)))))))
    (is (= [true false true]
           (mapv #(true? (get-in % [1 "show_alert"])) @calls)))
    (is (= ["answerCallbackQuery" "answerCallbackQuery" "answerCallbackQuery"]
           (mapv first @calls)))
    (is (zero? @agent-calls))))
