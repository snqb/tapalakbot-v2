(ns tapalakbot.advice-grounding-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clj-harness.infra :as infra]
            [tapalakbot.advice-grounding :as grounding]))

(def card {:id "a" :title "Модель A" :desc "Описание продавца" :price 12000 :currency "KGS"
           :url "https://lalafo.kg/a" :image "https://img5.lalafo.com/a.jpg"
           :recommendation {:position 0 :reason "На фото надпись A.\n\nМотор прослужит вдвое дольше."
                            :checks ["Попросите проверить ход под нагрузкой."]}})

(defn response [supported]
  {"model" "typesafe/jev-1.13"
   "answers" (into {} (map-indexed (fn [i yes?]
                                     [(str "c" i) {"type" "choice" "confidence" 0.99
                                                  "choice" (if yes? "supported" "unsupported")
                                                  "probabilities" {"supported" (if yes? 0.99 0.01)
                                                                   "unsupported" (if yes? 0.01 0.99)}}]) supported))})

(deftest unsupported-claims-are-not-delivered-as-photo-facts
  (let [answers (atom [[false] [true false true]])]
    (with-redefs [infra/read-api-key (constantly "test")
                  infra/http-post (fn [& _]
                                    (let [next-answer (first @answers)]
                                      (swap! answers next)
                                      (response next-answer)))]
      (let [result (grounding/check [] [card] "Это оригинал.") chosen (first (:cards result))]
        (is (= :checked (:status result)))
        (is (not (str/includes? (:text result) "Это оригинал")))
        (is (= "На фото надпись A." (get-in chosen [:recommendation :reason])))
        (is (= ["Попросите проверить ход под нагрузкой."] (get-in chosen [:recommendation :checks])))
        (is (= (dissoc card :recommendation) (dissoc chosen :recommendation)))))))

(deftest partial-typed-answer-cannot-approve-an-explanation
  (with-redefs [infra/read-api-key (constantly "test") infra/http-post (fn [& _] (response [true]))]
    (let [result (grounding/check [] [card] "Это оригинал.")]
      (is (= :unavailable (:status result)))
      (is (nil? (:recommendation (first (:cards result)))))
      (is (= (dissoc card :recommendation) (first (:cards result)))))))

(deftest individually-bounded-contexts-are-batched
  (let [source-text (apply str (repeat 7500 "x"))
        sources [{:id "r1" :title "Source 1" :text source-text}
                 {:id "r2" :title "Source 2" :text source-text}]
        card-for (fn [id source-id]
                   {:id id :title id :desc (apply str (repeat 7000 "d"))
                    :recommendation {:reason "Reason"
                                     :evidence [{:source-id source-id :quote "quote"}]
                                     :checks ["Check"]}})
        cards [(card-for "a" "r1") (card-for "b" "r2")]
        calls (atom 0)]
    (with-redefs [infra/read-api-key (constantly "test")
                  infra/http-post (fn [_ body & _]
                                    (swap! calls inc)
                                    (let [questions (:questions (json/parse-string body true))]
                                      (response (repeat (count questions) true))))]
      (let [result (grounding/check sources cards "")]
        (is (= 2 @calls))
        (is (= cards (:cards result)))))))

(deftest incomplete-later-batch-does-not-publish-a-partially-checked-shortlist
  (let [sources [{:id "r1" :text (apply str (repeat 7500 "x"))}
                 {:id "r2" :text (apply str (repeat 7500 "y"))}]
        cards (mapv (fn [id] {:id id :desc (apply str (repeat 7000 "d"))
                              :recommendation {:reason "Заявлено продавцом."
                                               :checks ["Уточнить состояние."]
                                               :evidence [{:source-id id :quote "x"}]}})
                    ["r1" "r2"])
        calls (atom 0)]
    (with-redefs [infra/read-api-key (constantly "test")
                  infra/http-post (fn [_ body & _]
                                    (let [request (json/parse-string body true)]
                                      (is (every? #(not (contains? % :recommendation))
                                                  (get-in request [:state :listings])))
                                      (if (= 1 (swap! calls inc))
                                        (response (repeat (count (:questions request)) true))
                                        (response [true]))))]
      (let [result (grounding/check sources cards "")]
        (is (= :unavailable (:status result)))
        (is (every? #(not (contains? % :recommendation)) (:cards result)))))))

(deftest one-listings-photo-cannot-become-another-listings-evidence
  (let [cards [(assoc card :visual-evidence {:observations [{:text "A"}]})
               (assoc card :id "b" :title "Модель B")]
        contexts (atom [])]
    (with-redefs [infra/read-api-key (constantly "test")
                  infra/http-post (fn [_ body & _]
                                    (let [request (json/parse-string body true)]
                                      (swap! contexts conj (get-in request [:state :listings]))
                                      (response (repeat (count (:questions request)) true))))]
      (grounding/check [] cards "")
      (is (= [["a"] ["b"]] (mapv #(mapv :id %) @contexts)))
      (is (false? (:has-photo-evidence (first (second @contexts))))))))

(deftest unsupported-proposal-returns-actionable-feedback-without-a-partial-view
  (with-redefs [infra/read-api-key (constantly "test")
                infra/http-post (fn [& _] (response [true false true]))]
    (let [result (grounding/validate-recommendations [] [card])]
      (is (= :unsupported (:status result)))
      (is (= [{:target :reason :id "a" :text "Мотор прослужит вдвое дольше."}]
             (:issues result)))
      (is (not (contains? result :cards))))))

(deftest validated-notes-survive-rejection-of-unrelated-final-prose
  (let [safe (assoc-in card [:recommendation :reason] "На фото надпись A.")
        validating? (atom true)]
    (with-redefs [infra/read-api-key (constantly "test")
                  infra/http-post (fn [_ body & _]
                                    (let [questions (:questions (json/parse-string body true))]
                                      (response (repeat (count questions) @validating?))))]
      (let [proposal (grounding/validate-recommendations [] [safe])]
        (is (= :ok (:status proposal)))
        (reset! validating? false)
        (let [delivered (grounding/check [] (:cards proposal) "Логотип доказывает подлинность.")]
          (is (= :checked (:status delivered)))
          (is (not (str/includes? (:text delivered) "Логотип доказывает подлинность.")))
          (is (= (:cards proposal) (:cards delivered)))
          (is (= (dissoc safe :recommendation)
                 (dissoc (first (:cards delivered)) :recommendation))))))))

(deftest new-proposals-cannot-bypass-validation-with-an-old-approval
  (with-redefs [infra/read-api-key (constantly "test")
                infra/http-post (fn [& _] (response [false false false]))]
    (let [marked (assoc-in card [:recommendation :grounded?] true)]
      (is (= :unsupported (:status (grounding/validate-recommendations [] [marked])))))))

(deftest incomplete-proposal-validation-never-becomes-tool-success
  (with-redefs [infra/read-api-key (constantly "test")
                infra/http-post (fn [& _] (response [true]))]
    (let [result (grounding/validate-recommendations [] [card])]
      (is (= :unavailable (:status result)))
      (is (not (contains? result :cards))))))

(deftest photographs-cannot-certify-absence-of-damage
  (with-redefs [infra/read-api-key (constantly "test")
                infra/http-post (fn [& _] (response [true true]))]
    (let [candidate (assoc card :recommendation
                           {:reason "По фото подтверждается отсутствие явных царапин на корпусе."
                            :checks ["Осмотреть корпус на царапины при встрече."]})
          result (grounding/validate-recommendations [] [candidate])]
      (is (= :unsupported (:status result)))
      (is (= :reason (get-in result [:issues 0 :target])))
      (is (not (contains? result :cards))))))

(deftest unknown-hidden-damage-is-not-a-photo-guarantee
  (with-redefs [infra/read-api-key (constantly "test")
                infra/http-post (fn [& _] (response [true true]))]
    (let [candidate (assoc card :recommendation
                           {:reason "На фото читается маркировка A; отсутствие скрытых дефектов остаётся неизвестным."
                            :checks ["Проверить прибор при встрече."]})]
      (is (= :ok (:status (grounding/validate-recommendations [] [candidate])))))))
