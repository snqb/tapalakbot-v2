(ns tapalakbot.search-test
  (:require [clojure.test :refer [deftest is]]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.jev :as jev]
            [tapalakbot.search :as search]))

(deftest selection-sees-full-description-without-title-token-loss
  (let [description (str (apply str (repeat 120 "Описание. "))
                         "Компактная дорожка для ходьбы под столом.")]
    (with-redefs [lalafo/search
                  (fn [_]
                    {"items" [{"id" "a" "title" "Bodyline"
                               "price" 10000 "currency" "KGS"
                               "desc" description
                               "url" "https://lalafo.kg/bishkek/ads/id-a"}]})
                  jev/select-cards
                  (fn [query cards _]
                    (is (= "дорожка для ходьбы до 15000" query))
                    (is (= description (:desc (first cards))))
                    {:cards cards :selection {:input-count 1 :accepted-count 1}})]
      (let [result (search/search "дорожка для ходьбы до 15000"
                                  {:queries ["беговая дорожка"]})]
        (is (= ["a"] (mapv :id (:cards result))))
        (is (= 10000 (get-in result [:stats :avg])))))))

(deftest budget-checks-source-currency-before-semantic-selection
  (with-redefs [lalafo/search
                (fn [_]
                  {"items" [{"id" "kgs" "title" "Телефон" "price" 15000 "currency" "KGS"}
                             {"id" "usd" "title" "Телефон" "price" 200 "currency" "USD"}
                             {"id" "high" "title" "Телефон" "price" 25000 "currency" "KGS"}
                             {"id" "unknown" "title" "Телефон" "price" nil "currency" "KGS"}
                             {"id" "unknown-currency" "title" "Телефон" "price" 15000 "currency" nil}
                             {"id" "missing-currency" "title" "Телефон" "price" 15000}]})
                jev/select-cards
                (fn [_ cards _]
                  (is (= ["kgs"] (mapv :id cards)))
                  {:cards cards :selection {:input-count 1 :accepted-count 1}})]
    (is (= ["kgs"] (mapv :id (:cards (search/search "телефон до 20000")))))))

(deftest missing-price-and-image-are-not-semantic-exclusions
  (with-redefs [lalafo/search
                (fn [_] {"items" [{"id" "a" "title" "Пианино" "price" nil
                                   "currency" "KGS" "desc" "Отдам пианино"}]})
                jev/select-cards
                (fn [_ cards _] {:cards cards :selection {:accepted-count 1}})]
    (let [card (first (:cards (search/search "пианино")))]
      (is (= "a" (:id card)))
      (is (nil? (:price card)))
      (is (nil? (:image card))))))

(deftest unavailable-selector-is-not-an-empty-market
  (with-redefs [lalafo/search
                (fn [_] {"items" [{"id" "a" "title" "Товар"}]})
                jev/select-cards
                (fn [& _] (throw (ex-info "Unavailable" {:type :selection-unavailable})))]
    (let [result (search/search "товар")]
      (is (= :unavailable (:search-status result)))
      (is (empty? (:cards result))))))

(deftest empty-valid-selection-stays-empty
  (with-redefs [lalafo/search
                (fn [_] {"items" [{"id" "a" "title" "Чехол" "price" 500}]})
                jev/select-cards
                (fn [& _] {:cards [] :selection {:input-count 1 :accepted-count 0
                                                 :rejected-count 1}})]
    (let [result (search/search "телефон")]
      (is (= :ok (:search-status result)))
      (is (empty? (:cards result)))
      (is (= 1 (get-in result [:selection :rejected-count]))))))

(deftest marketplace-stats-never-mix-usd-and-kgs
  (let [stats @#'search/compute-stats]
    (is (= {:avg 1500000 :min 1000000 :max 2000000 :count 2}
           (stats [{:price 1000000 :currency "KGS"}
                   {:price 2000000 :currency "сом"}
                   {:price 22000 :currency "USD"}])))))

(deftest explicit-replacement-budget-removes-old-minimum-and-maximum
  (let [seen-query (atom nil)]
    (with-redefs [lalafo/search
                  (fn [_]
                    {"items" [{"id" "low" "title" "Телефон" "price" 5000 "currency" "KGS"}
                               {"id" "high" "title" "Телефон" "price" 40000 "currency" "KGS"}]})
                  jev/select-cards
                  (fn [query cards _]
                    (reset! seen-query query)
                    {:cards cards :selection {}})]
      (let [result (search/search "телефон от 10000 до 20000"
                                  {:budget-override? true :price-min nil :price-max 50000})]
        (is (= ["low" "high"] (mapv :id (:cards result))))
        (is (nil? (get-in result [:qb-result :price-min])))
        (is (= 50000 (get-in result [:qb-result :price-max])))
        (is (not (re-find #"10000|20000" @seen-query)))))))
