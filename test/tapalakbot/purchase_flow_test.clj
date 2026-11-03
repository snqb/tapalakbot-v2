(ns tapalakbot.purchase-flow-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [tapalakbot.conversation :as conversation]
            [tapalakbot.core :as core]
            [tapalakbot.jev :as jev]
            [tapalakbot.listing-vision :as vision]
            [tapalakbot.purchase-advice :as advice]
            [tapalakbot.purchase-tools :as purchase]
            [tapalakbot.render-advice :as render-advice]
            [tapalakbot.result-order :as order]
            [tapalakbot.result-store :as store]
            [tapalakbot.search :as search]
            [tapalakbot.advice-grounding :as grounding]))

(def source {:title "Repair guide" :url "https://example.org/repair" :text "Check the belt."})
(def card {:id "photo-only" :title "Тренажер" :price 17000
           :desc "Продам за 17 000 сом https://seller.example/item. Полотно 50 см."
           :currency "KGS" :url "https://lalafo.kg/item/photo-only"
           :image "https://img5.lalafo.com/i/posters/original/example.jpeg"
           :params [{:name "Ширина полотна" :value "50 см"}
                    {:name "Цена" :value 17000}
                    {:Цена 17000}
                    {:currency "KGS" :value 17000}
                    {:url "https://seller.example/item"}]})
(def evidence {:status :ok :listing-id "photo-only" :image-urls [(:image card)]
               :observations [{:kind :brand :text "VisibleBrand" :confidence :clear :image-index 0}]
               :limitations ["Исправность не проверена"] :condition-verified? false})
(def search-result {:query "тренажер" :cards [] :candidates [card]
                    :evaluated-ids ["photo-only"] :search-status :ok
                    :qb-result {:price-max 30000} :selection {:input-count 1}})

(defn execute [name args]
  (json/parse-string ((:execute (some #(when (= name (:name %)) %) core/tools)) args) true))

(deftest photo-rescue-followup-preserves-original-card-and-owned-citations
  (let [session (atom {}) owner (str (random-uuid)) calls (atom 0)]
    (conversation/remember-search! session {:user-want "тренажер" :new-request true} search-result)
    (advice/add-sources! session [source])
    (store/cache-search! owner (:task-id (conversation/current session)) search-result)
    (try
      (binding [core/*current-session* session core/*current-user-id* owner
                core/*captured-search* (atom nil) core/*captured-cards* (atom [])
                core/*captured-stats* (atom nil) core/*captured-query* (atom nil)]
        (with-redefs [vision/inspect-card (fn [_ _] (swap! calls inc) evidence)
                      jev/select-cards (fn [_ cards _]
                                         {:cards (mapv #(assoc % :decision {:fit :possible}) cards)})
                      grounding/validate-recommendations (fn [_ cards]
                                                          {:status :ok :cards cards})]
          (is (= "invalid" (:status (execute "inspect_listings" {"listing_ids" ["foreign"] "focus" "logo"}))))
          (is (zero? @calls))
          (let [inspection (execute "inspect_listings" {"listing_ids" ["photo-only"] "focus" "logo"})]
            (is (= "ok" (:status inspection)))
            (is (not (re-find #"17 000|17000|seller\.example"
                              (json/generate-string (get-in inspection [:accepted :inspected])))))
            (is (= "50 см" (get-in inspection [:accepted :inspected 0 :params 0 :value]))))
          (is (= card (select-keys (first @core/*captured-cards*) (keys card))))
          (is (= 17000 (get @core/*captured-stats* :min)))
          (is (= "тренажер" @core/*captured-query*))
          (is (= "photo-only" (get-in (conversation/current session) [:shown-cards 0 :id])))
          (purchase/inspect! session [card] "logo")
          (is (= 1 @calls))
          (let [result (execute "recommend_results"
                                {"recommendations" [{"listing_id" "photo-only"
                                                     "reason" "Стоит проверить видимую модель"
                                                     "evidence" ["r1e1"]
                                                     "checks" ["Проверить под нагрузкой"]}]})
                chosen (first @core/*captured-cards*)]
            (is (= "ok" (:status result)))
            (is (= card (select-keys chosen (keys card))))
            (is (= [(:url source)] (mapv :url (:research-sources chosen))))
            (is (re-find #"href=\"https://example.org/repair\"" (render-advice/card-notes chosen)))
            (is (not (re-find #"17000|https://" (json/generate-string (:view result))))))))
      (finally (swap! store/ad-cache dissoc owner)))))

(deftest unavailable-photo-inspection-still-exposes-owned-seller-facts
  (let [session (atom {}) owner (str (random-uuid))]
    (conversation/remember-search! session {:user-want "тренажер" :new-request true} search-result)
    (try
      (binding [core/*current-session* session core/*current-user-id* owner
                core/*captured-search* (atom search-result)]
        (with-redefs [vision/inspect-card (fn [_ _]
                                            {:status :unavailable :listing-id "photo-only"
                                             :error "offline"})]
          (let [result (execute "inspect_listings" {"listing_ids" ["photo-only"] "focus" "logo"})]
            (is (= "ok" (:status result)))
            (is (not (re-find #"17 000|17000|seller\.example"
                              (json/generate-string (get-in result [:accepted :inspected])))))
            (is (= "50 см" (get-in result [:accepted :inspected 0 :params 0 :value]))))))
      (finally (swap! store/ad-cache dissoc owner)))))

(deftest unsupported-recommendation-does-not-update-selection
  (let [session (atom {}) owner (str (random-uuid)) selected (assoc search-result :cards [card])]
    (conversation/remember-search! session {:user-want "тренажер" :new-request true} selected)
    (try
      (binding [core/*current-session* session core/*current-user-id* owner
                core/*captured-search* (atom selected)]
        (with-redefs [grounding/validate-recommendations
                      (fn [_ _] {:status :unsupported
                                 :issues [{:target :reason :id "photo-only" :text "Нет подтверждения"}]})]
          (let [result (execute "recommend_results"
                                {"recommendations" [{"listing_id" "photo-only"
                                                     "reason" "Надёжный вариант"
                                                     "evidence" [] "checks" []}]})]
            (is (= "unsupported" (:status result)))
            (is (re-find #"50 см" (get-in result [:source-context 0 :desc])))
            (is (not (re-find #"17 000|17000|seller\.example"
                              (json/generate-string (:source-context result)))))
            (is (= selected @core/*captured-search*)))))
      (finally (swap! store/ad-cache dissoc owner)))))

(deftest expired-or-different-task-cannot-inspect-or-recommend
  (let [session (atom {}) owner (str (random-uuid))]
    (conversation/remember-search! session {:user-want "old" :new-request true} search-result)
    (store/cache-search! owner (:task-id (conversation/current session)) search-result)
    (try
      (swap! store/ad-cache assoc-in [owner :search-created-at] 0)
      (is (nil? (store/cached-search owner (:task-id (conversation/current session)))))
      (store/cache-search! owner "different-task" search-result)
      (binding [core/*current-session* session core/*current-user-id* owner
                core/*captured-search* (atom nil)]
        (with-redefs [vision/inspect-card (fn [& _] (throw (ex-info "Must not inspect" {})))]
          (is (= "invalid" (:status (execute "inspect_listings" {"listing_ids" ["photo-only"] "focus" "logo"}))))))
      (finally (swap! store/ad-cache dissoc owner)))))

(deftest refinements-keep-strategy-new-products-clear-it
  (let [session (atom {})]
    (conversation/remember-search! session {:user-want "дорожка" :new-request true} search-result)
    (advice/add-sources! session [source])
    (purchase/strategy! session {"summary" "Проверить ремонтопригодность"
                                 "directions" [{"name" "Серия" "rationale" "Есть руководство"
                                                "evidence" ["r1e1"]}]
                                 "checks" [] "unknowns" ["Состояние неизвестно"]})
    (let [id (:task-id (conversation/current session))]
      (conversation/remember-search! session {:user-want "дорожка до 20000"} (assoc-in search-result [:qb-result :price-max] 20000))
      (is (= id (:task-id (conversation/current session))))
      (is (= "Проверить ремонтопригодность" (get-in (advice/current session) [:strategy :summary])))
      (is (= "r1" (get-in (advice/current session) [:sources 0 :id])))
      (conversation/remember-search! session {:user-want "ноутбук" :new-request true} search-result)
      (is (not= id (:task-id (conversation/current session))))
      (is (nil? (advice/current session)))
      (is (nil? (:photo-evidence (conversation/current session)))))))

(deftest recommendation-is-an-owned-view-not-a-new-price-or-filter
  (let [session (atom {}) cards [card (assoc card :id "other" :price 8000)]
        choose (fn [id source-id]
                 (purchase/recommend session cards
                                     {"recommendations" [{"listing_id" id "reason" "Проверить модель"
                                                          "evidence" [(str source-id "e1")]
                                                          "checks" []}]}))]
    (conversation/remember-search! session {:user-want "товар" :new-request true} search-result)
    (advice/add-sources! session [source])
    (is (= :invalid (:status (choose "foreign" "r1"))))
    (is (= :invalid (:status (choose "photo-only" "r99"))))
    (let [selected (:cards (choose "photo-only" "r1"))]
      (is (= ["photo-only" "other"] (mapv :id (order/order-cards selected :recommended))))
      (is (= ["other" "photo-only"] (mapv :id (order/order-cards selected :price-asc))))
      (is (= cards (mapv #(select-keys % (keys card)) selected))))))

(deftest changed-photo-evidence-revokes-an-earlier-note-approval
  (let [approved (assoc card :recommendation {:reason "Предыдущая гипотеза" :grounded? true})]
    (with-redefs [vision/inspect-card (constantly evidence)]
      (let [inspected (first (:cards (purchase/inspect! (atom {}) [approved] "маркировка")))]
        (is (nil? (get-in inspected [:recommendation :grounded?])))
        (is (= "Предыдущая гипотеза" (get-in inspected [:recommendation :reason])))
        (is (= card (select-keys inspected (keys card))))))))

(deftest inline-citations-cannot-bypass-owned-evidence
  (let [session (atom {})]
    (conversation/remember-search! session {:user-want "товар" :new-request true} search-result)
    (advice/add-sources! session [source])
    (doseq [field ["reason" "checks"] marker ["[[r1]]" "[[r1e1]]" "[[r99e1]]"]]
      (let [item (assoc {"listing_id" "photo-only" "reason" "По словам продавца"
                        "evidence" ["r1e1"] "checks" []}
                       field (if (= field "checks") [(str "Проверить " marker)]
                                 (str "Слова продавца " marker)))
            result (purchase/recommend session [card] {"recommendations" [item]})]
        (is (= :invalid (:status result)))
        (is (not (contains? result :cards)))))))

(deftest advisor-preview-prefers-semantic-fit-without-changing-price-view
  (let [cheap (assoc card :id "cheap-defective" :price 1000 :rank-score 30
                    :decision {:fit :possible :condition :damaged :risk :missing})
        suitable (assoc card :id "suitable" :price 20000 :rank-score 68
                       :decision {:fit :possible :condition :used :risk :none})
        result (assoc search-result :cards [cheap suitable] :evaluated-ids ["cheap-defective" "suitable"])
        captured (atom nil)]
    (binding [core/*current-session* (atom {}) core/*captured-search* captured]
      (with-redefs [search/search (fn [& _] result)]
        (let [preview (execute "search" {"user_want" "качественная дорожка для ходьбы"
                                         "queries" ["беговая дорожка"] "new_request" true})]
          (is (= ["suitable" "cheap-defective"] (mapv :id (:accepted preview))))
          (is (= ["cheap-defective" "suitable"] (mapv :id (:cards @captured))))
          (is (not-any? #(or (contains? % :price) (contains? % :url))
                        (:accepted preview))))))))
