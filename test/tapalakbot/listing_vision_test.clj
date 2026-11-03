(ns tapalakbot.listing-vision-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [clj-harness.infra :as infra]
   [clj-harness.observe :as observe]
   [tapalakbot.listing-vision :as vision]))

(def image "https://img5.lalafo.com/i/posters/original/test.jpg")
(def card {:id "listing-1"
           :title "Seller metadata must not be sent"
           :price 12000
           :currency "KGS"
           :url "https://lalafo.kg/ad/listing-1"
           :images [image]})

(defn response-with [content]
  {"usage" {"prompt_tokens" 4 "completion_tokens" 5 "total_tokens" 9}
   "choices" [{"finish_reason" "stop"
               "message" {"content" (json/generate-string content)}}]})

(defn with-provider [response f]
  (with-redefs [infra/read-api-key (constantly "test-key")
                infra/cfg (fn [& _] "google/gemini-3.5-flash")
                infra/http-post (fn [_ body & _]
                                  (is (not (re-find #"Seller metadata|12000|KGS" body)))
                                  response)]
    (f)))

(deftest unsafe-source-image-fails-closed
  (testing "only original HTTPS Lalafo image hosts are accepted"
    (let [calls (atom 0)]
      (with-redefs [infra/http-post (fn [& _] (swap! calls inc))]
        (is (= :unavailable
               (:status (vision/inspect-card
                         (assoc card :images ["https://evil.example/steal.jpg"])
                         "read visible branding"))))
        (is (zero? @calls))))))

(deftest missing-image-is-explicitly-unavailable
  (let [result (vision/inspect-card (dissoc card :images) "inspect visible details")]
    (is (= :unavailable (:status result)))
    (is (= "listing-1" (:listing-id result)))
    (is (not (contains? result :condition-verified?)))))

(deftest malformed-schema-and-safety-failures-are-distinguishable
  (testing "invalid image indexes are schema failures"
    (with-provider
      (response-with {:observations [{:kind "brand" :text "Lenovo"
                                      :confidence "clear" :image_index 2}]
                      :limitations []})
      #(let [result (vision/inspect-card card "read branding")]
         (is (= :unavailable (:status result)))
         (is (= :schema (:failure-kind result))))))
  (testing "unsupported functional claims are safety failures"
    (with-provider
      (response-with {:observations [{:kind "visible-feature" :text "fully functional"
                                      :confidence "clear" :image_index 0}]
                      :limitations []})
      #(let [result (vision/inspect-card card "inspect visible details")]
         (is (= :unavailable (:status result)))
         (is (= :safety (:failure-kind result))))))
  (testing "provider JSON must have the declared structured shape"
    (with-redefs [infra/read-api-key (constantly "test-key")
                  infra/cfg (fn [& _] "google/gemini-3.5-flash")
                  infra/http-post (fn [& _]
                                    {"choices" [{"message" {"content" "not-json"}}]})]
      (let [result (vision/inspect-card card "inspect visible details")]
        (is (= :unavailable (:status result)))
        (is (= :malformed (:failure-kind result)))))))

(deftest valid-visual-evidence-preserves-provenance
  (with-provider
    (response-with {:observations [{:kind "brand" :text "Lenovo"
                                    :confidence "clear" :image_index 0}
                                   {:kind "visible-damage" :text "A dark crack is visible"
                                    :confidence "uncertain" :image_index 0}]
                    :limitations ["specifications-not-visible" "functionality-not-verified"]})
    #(let [result (vision/inspect-card card "read visible branding and damage")]
       (is (= :ok (:status result)))
       (is (= [image] (:image-urls result)))
       (is (= false (:condition-verified? result)))
       (is (= :uncertain (-> result :observations second :confidence)))
       (is (= "A dark crack is visible" (-> result :observations second :text)))
       (is (= #{:brand :visible-damage}
              (set (map :kind (:observations result))))))))

(deftest visible-component-names-are-not-functional-claims
  (with-provider
    (response-with {:observations [{:kind "visible-feature"
                                    :text "Кожух двигателя и электронная панель с надписью START"
                                    :confidence "clear" :image_index 0}]
                    :limitations []})
    #(is (= :ok (:status (vision/inspect-card card "прочитай надписи"))))))

(deftest unobservable-evaluations-cannot-enter-either-visual-text-channel
  (doseq [claim ["Оригинальный Bosch" "Надёжная конструкция" "Видимых повреждений нет"
                 "Устройство исправно" "Authentic product" "Цена 12 000"
                 "Контакт t.me/shop" "Выглядит как новый"
                 "Экран без видимых глубоких повреждений"]]
    (with-provider
      (response-with {:observations [{:kind "visible-feature" :text claim
                                      :confidence "clear" :image_index 0}]
                      :limitations []})
      #(is (= :unavailable
              (:status (vision/inspect-card card "детали"))))))
  (testing "free-form unknowns are rejected in favor of safe vocabulary"
    (with-provider
      (response-with {:observations []
                      :limitations ["Мощность двигателя и точный диапазон скоростей не указаны на фото."]})
      #(let [result (vision/inspect-card card "детали")]
         (is (= :unavailable (:status result)))
         (is (= :schema (:failure-kind result))))))
  (with-provider
    (response-with {:observations [] :limitations ["functionality-not-verified"]})
    #(let [result (vision/inspect-card card "детали")]
       (is (= :ok (:status result)))
       (is (false? (:condition-verified? result))))))

(deftest normalization-failure-retains-provider-usage
  (let [events (atom [])]
    (with-redefs [observe/record! #(swap! events conj %)]
      (with-redefs [infra/read-api-key (constantly "test-key")
                    infra/cfg (fn [& _] "google/gemini-3.5-flash")
                    infra/http-post (fn [& _]
                                      (response-with {:observations []
                                                      :limitations ["not-a-supported-code"]}))]
        (let [result (vision/inspect-card card "details")]
          (is (= :unavailable (:status result)))
          (is (= :schema (:failure-kind result)))
          (is (= 9 (:total-tokens (last @events))))
          (is (= "stop" (:finish-reason (last @events)))))))))
