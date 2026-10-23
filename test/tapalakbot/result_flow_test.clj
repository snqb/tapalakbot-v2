(ns tapalakbot.result-flow-test
  (:require [clj-harness.telegram :as tg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tapalakbot.bot :as bot]
            [tapalakbot.core :as t]
            [tapalakbot.result-store :as result-store]))
(deftest initial-search-delivers-the-default-ordered-result-document
  (let [run @#'bot/handle-orchestrated
        sent (atom [])
        pool [{:id :expensive :title "High" :price 300 :currency "KGS"
               :url "https://lalafo.kg/ad/high" :decision {:fit :direct}}
              {:id :cheap :title "Low" :price 100 :currency "KGS"
               :url "https://lalafo.kg/ad/low" :decision {:fit :direct}}
              {:id :review :title "Review" :price 50 :currency "KGS"
               :url "https://lalafo.kg/ad/review" :decision {:fit :possible}}]]
    (with-redefs-fn
      {#'result-store/result-pools (atom {})
       #'result-store/ad-cache (atom {})
       #'t/ask-stream (fn [& _] {:text "Есть варианты. Проверьте состояние."
                                :cards pool :pool pool :query "дорожка" :search-status :ok})
       #'tg/send-message (fn [& _] {"message_id" 1})
       #'tg/send-md (fn [& _] {"ok" true})
       #'tg/send-rich-message (fn [_ & options]
                               (swap! sent conj (apply hash-map options))
                               {"ok" true})
       #'tg/edit-message (fn [& _])
       #'tg/delete-message (fn [& _])
       #'bot/rename-topic! (fn [& _])}
      (fn []
        (run {:chat-id -100 :user-id 42 :thread-id 17 :text "дорожка"})
        (let [document (first @sent)
              html (or (:html document) "")]
          (is (every? #(str/includes? html (:url %)) pool))
          (is (< (.indexOf html "https://lalafo.kg/ad/low")
                 (.indexOf html "https://lalafo.kg/ad/high")
                 (.indexOf html "https://lalafo.kg/ad/review")))
          (is (= 17 (:thread-id document)))
          (is (= :cheap (:id (result-store/get-ad "tg-42:-100:17" 1)))))))))
