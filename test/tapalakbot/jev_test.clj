(ns tapalakbot.jev-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [clj-harness.infra :as infra]
   [tapalakbot.jev :as jev]
   [tapalakbot.lalafo :as lalafo]
   [tapalakbot.search :as search]))

(def card
  {:id "source-1"
   :title "Ноутбук Lenovo ThinkPad"
   :desc "Полное описание товара без сокращений."
   :price 50000
   :currency "KGS"
   :url "https://lalafo.kg/item/source-1"})

(defn unavailable? [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo e
      (= :selection-unavailable (:type (ex-data e))))))

(defn choice-answer [choice values]
  {:type "choice"
   :choice choice
   :probabilities (zipmap values (map #(if (= % choice) 1.0 0.0) values))
   :confidence 0.8})

(defn response-for [question-keys choices]
  {:model "typesafe/jev-1.13"
   :answers
   (into {}
         (map (fn [key]
                (let [kind (keyword (last (clojure.string/split key #"_")))]
                  [key (choice-answer
                        (get choices kind)
                        (case kind
                          :fit ["direct" "possible" "no"]
                          :condition ["new" "used" "damaged" "unknown"]
                          :evidence ["strong" "moderate" "weak" "missing"]
                          :risk ["none" "missing" "suspicious" "price_conflict" "incompatible"]))]))
              question-keys))})

(defn mocked-http [choices]
  (fn [_url body & _]
    (let [request (json/parse-string body false)]
      (response-for (keys (get request "questions")) choices))))

(deftest malformed-and-partial-results-fail-closed
  (testing "missing answers are not treated as an empty valid decision"
    (is (unavailable?
         #(jev/validate-response {:model "typesafe/jev-1.13" :answers {}}
                                 ["i0_fit"]))))
  (testing "foreign question keys cannot be accepted"
    (is (unavailable?
         #(jev/validate-response
           {:model "typesafe/jev-1.13"
            :answers {"i0_fit" (choice-answer "direct" ["direct" "possible" "no"])
                      "i9_fit" (choice-answer "direct" ["direct" "possible" "no"])} }
           ["i0_fit"]))))
  (testing "unknown enum and incomplete probabilities fail closed"
    (is (unavailable?
         #(jev/validate-response
           {:model "typesafe/jev-1.13"
            :answers {"i0_fit" (assoc (choice-answer "direct" ["direct" "possible" "no"])
                                       :choice "unknown")}}
           ["i0_fit"])))
    (is (unavailable?
         #(jev/validate-response
           {:model "typesafe/jev-1.13"
            :answers {"i0_fit" (assoc (choice-answer "direct" ["direct" "possible" "no"])
                                       :probabilities {"direct" 1.0})}}
           ["i0_fit"])))))

(deftest empty-real-decision-stays-empty
  (with-redefs [infra/read-api-key (constantly "test-key")
                infra/http-post (mocked-http {:fit "no"
                                              :condition "unknown"
                                              :evidence "weak"
                                              :risk "none"})]
    (let [result (jev/select-cards "нужен ноутбук" [card] {})]
      (is (= [] (:cards result)))
      (is (= {:input-count 1 :accepted-count 0 :rejected-count 1 :uncertain-count 0}
             (select-keys (:selection result)
                          [:input-count :accepted-count :rejected-count :uncertain-count]))))))

(deftest explicit-incompatibility-overrules-a-positive-fit
  (let [keys ["i0_fit" "i0_condition" "i0_evidence" "i0_risk"]
        response (response-for keys {:fit "direct" :condition "unknown"
                                     :evidence "strong" :risk "incompatible"})
        answers (jev/validate-response response keys)]
    (is (= [] (jev/annotate-cards [card] answers)))))

(deftest missing-condition-data-is-warned-not-invented
  (let [answers {"i0_fit" (choice-answer "direct" ["direct" "possible" "no"])
                 "i0_condition" (choice-answer "unknown" ["new" "used" "damaged" "unknown"])
                 "i0_evidence" (choice-answer "moderate" ["strong" "moderate" "weak" "missing"])
                 "i0_risk" (choice-answer "missing" ["none" "missing" "suspicious" "incompatible"])}
        [annotated] (jev/annotate-cards [card] answers)]
    (is (= :unknown (get-in annotated [:decision :condition])))
    (is (some #(= "Состояние в источнике не указано." %)
              (get-in annotated [:decision :warnings])))
    (is (not-any? #(re-find #"Новое|Использован" %)
                  (get-in annotated [:decision :reasons])))))

(deftest oversized-single-card-fails-without-truncation
  (let [huge (assoc card :desc (apply str (repeat 40000 "x")))]
    (is (unavailable? #(jev/chunk-cards "найди товар" [huge])))))

(deftest a-partial-batch-cannot-leak-other-batches-as-recommendations
  (with-redefs [infra/read-api-key (constantly "test-key")
                infra/http-post
                (fn [_ body & _]
                  (let [request (json/parse-string body)
                        keys (keys (get request "questions"))
                        response (response-for keys {:fit "direct" :condition "used"
                                                       :evidence "strong" :risk "none"})]
                    (if (zero? (get-in request ["state" "listings" 0 "index"]))
                      response
                      (update response :answers dissoc (first keys)))))]
    (is (unavailable?
         #(jev/select-cards "ноутбук"
                            (mapv (fn [n] (assoc card :id (str n))) (range 21))
                            {})))))

(defn- admission-result [fit evidence risk]
  (with-redefs
    [infra/read-api-key (constantly "test-key")
     lalafo/search
     (fn [_] {"items" [{"id" "safe" "title" "Электрическая дорожка для ходьбы"
                        "desc" "Полный компактный тренажёр для ходьбы под столом."
                        "price" 10000 "currency" "KGS" "url" "https://lalafo.kg/ads/safe"}
                       {"id" "candidate" "title" "Брали за 18000" "desc" "Брали за 18000"
                        "price" 15000 "currency" "KGS" "url" "https://lalafo.kg/ads/candidate"}]})
     infra/http-post
     (fn [_ body & _]
       (let [questions (get (json/parse-string body) "questions")]
         {:model "typesafe/jev-1.13"
          :answers
          (into {}
                (map (fn [[key question]]
                       (let [candidate? (clojure.string/starts-with? key "i1_")
                             kind (keyword (last (clojure.string/split key #"_")))
                             choice (case kind
                                      :fit (if candidate? fit "direct")
                                      :condition "unknown"
                                      :evidence (if candidate? evidence "strong")
                                      :risk (if candidate? risk "none"))
                             values (conj (set (keys (get question "criteria"))) choice)]
                         [key (choice-answer choice values)])))
                questions)}))]
    (search/search "электрическая дорожка для ходьбы под столом до 20000 сом")))

(deftest missing-identity-evidence-cannot-be-recommended
  (let [result (admission-result "possible" "missing" "none")]
    (is (= :ok (:search-status result)))
    (is (= ["safe"] (mapv :id (:cards result))))))

(deftest current-offer-price-conflict-is-quarantined-not-a-provider-failure
  (let [result (admission-result "direct" "strong" "price_conflict")]
    (is (= :ok (:search-status result)))
    (is (= ["safe"] (mapv :id (:cards result))))))

(deftest source-risk-prevents-confirmation-without-reviving-rejected-items
  (let [result (admission-result "direct" "strong" "suspicious")
        candidate (some #(when (= "candidate" (:id %)) %) (:cards result))]
    (is (= :possible (get-in candidate [:decision :fit]))))
  (let [result (admission-result "no" "strong" "suspicious")]
    (is (= ["safe"] (mapv :id (:cards result))))))
