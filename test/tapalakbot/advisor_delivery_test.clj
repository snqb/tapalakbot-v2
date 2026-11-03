(ns tapalakbot.advisor-delivery-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clj-harness.core :as harness]
            [clj-harness.infra :as infra]
            [tapalakbot.conversation :as conversation]
            [tapalakbot.core :as core]
            [tapalakbot.listing-vision :as vision]
            [tapalakbot.purchase-research :as research]
            [tapalakbot.purchase-tools :as purchase]
            [tapalakbot.result-store :as store]
            [tapalakbot.search :as search]))

(defn- typed-response [supported]
  {"model" "typesafe/jev-1.13"
   "answers"
   (into {}
         (map-indexed
          (fn [index supported?]
            [(str "c" index)
             {"type" "choice"
              "confidence" 0.99
              "choice" (if supported? "supported" "unsupported")
              "probabilities" {"supported" (if supported? 0.99 0.01)
                               "unsupported" (if supported? 0.01 0.99)}}])
          supported))})

(defn- run-with-session [state f]
  (let [user-id (str "advisor-delivery-" (random-uuid))
        session (atom state) persisted (atom [])
        bot {:sessions (atom {user-id session})
             :on-save (fn [_ saved]
                        (swap! persisted conj (get (peek (vec (get @saved "messages"))) "content")))}]
    (with-redefs [core/tapalakbot (delay bot)]
      (f user-id session persisted))))

(defn- tool [name]
  (:execute (some #(when (= name (:name %)) %) core/tools)))


(deftest checked-advisor-text-replaces-the-persisted-assistant-draft
  (let [state {"data" {"buying-request"
                        {:task-id "task-grounding"
                         :user-want "дорожка"
                         :advice {:task-key {:task-id "task-grounding"}
                                  :sources [{:id "r1" :title "Guide"
                                             :url "https://example.org/guide"
                                             :text "Evidence"}]}}}}
        raw "Это подтверждённо надёжный вариант."
        drafts (atom [])]
    (with-redefs [infra/read-api-key (constantly "test")
                  infra/http-post (fn [& _] (typed-response [false]))
                  harness/handle-message-stream!
                  (fn [bot user-id _ stream-cb & _]
                    (stream-cb raw)
                    (swap! core/*current-session* update "messages" conj
                           {"role" "assistant" "content" raw})
                    (when-let [save (:on-save bot)] (save user-id core/*current-session*))
                    {:content raw})]
      (run-with-session state
        (fn [user-id session persisted]
          (let [result (core/ask-stream user-id "покажи варианты" nil
                                        {:stream-cb (fn [text]
                                                      (is (= [text] @persisted))
                                                      (swap! drafts conj text))})]
            (is (= [(:text result)] @drafts))
            (is (= (:text result) (get-in @session ["messages" 0 "content"])))
            (is (not= raw (:text result)))))))))

(deftest photo-only-turns-are-checked-even-without-an-admitted-card
  (doseq [admitted? [true false]
          [owned? photo-ok?] [[true true] [true false] [false false]]]
    (let [card {:id "a" :title "Дорожка" :price 100 :currency "KGS"}
          evidence {:status :ok :observations [{:text "На корпусе надпись A."}]}
          inspected (assoc card :visual-evidence evidence)
          selected {:query "дорожка" :cards (if admitted? [card] [])
                    :candidates [card] :search-status :ok}
          state {"data" {"buying-request" {:task-id "photo-only" :user-want "дорожка"
                                           :known-ids ["a"] :pool-ids (if admitted? ["a"] [])}}}
          raw "Фото подтверждает исправность."
          drafts (atom [])]
      (with-redefs [infra/read-api-key (constantly "test")
                    infra/http-post (fn [& _] (typed-response [false]))
                    purchase/inspect! (fn [& _] {:cards (if photo-ok? [inspected] [])
                                                 :results []})
                    search/reselect-cards (fn [& _]
                                            (assoc selected :cards (if admitted? [inspected] [])
                                                            :candidates [inspected]))
                    harness/handle-message-stream!
                    (fn [_ _ _ stream-cb & _]
                      (reset! core/*captured-search* selected)
                      (reset! core/*captured-cards* (:cards selected))
                      ((tool "inspect_listings") {"listing_ids" [(if owned? "a" "foreign")]
                                                   "focus" "Прочитай надпись"})
                      (stream-cb raw)
                      (swap! core/*current-session* update "messages" conj
                             {"role" "assistant" "content" raw})
                      {:content raw})]
        (run-with-session state
          (fn [user-id _ persisted]
            (let [result (core/ask-stream user-id "что видно на фото?" nil
                                          {:stream-cb #(swap! drafts conj %)})]
              (is (not (str/includes? (:text result) raw)))
              (is (= [(:text result)] @drafts @persisted))
              (is (= (if admitted? ["a"] []) (mapv :id (:cards result)))))))))))

(deftest first-research-request-wins-over-later-invented-search-constraints
  (let [session (atom {})
        researched (atom nil)
        observed-query (atom nil)
        result {:query "дорожка для ходьбы"
                :cards [] :candidates [] :evaluated-ids []
                :selection {:input-count 0 :accepted-count 0}
                :search-status :ok}]
    (with-redefs [research/search (fn [_]
                                    {:status :ok
                                     :sources [{:title "Guide"
                                                :url "https://example.org/guide"
                                                :text "Evidence"}]})
                  search/search (fn [query _]
                                  (reset! observed-query query)
                                  (assoc result :query query))]
      (binding [core/*current-session* session
                core/*researched-intent* researched
                core/*captured-cards* (atom [])
                core/*captured-stats* (atom nil)
                core/*captured-query* (atom nil)
                core/*captured-search* (atom nil)]
        ((tool "research") {"query" "как выбрать дорожку"
                             "user_want" "дорожка для ходьбы под столом"
                             "new_request" true
                             "price_min" nil "price_max" nil "city_id" nil})
        ((tool "search") {"user_want" "дорожка с вымышленной обязательной маркой"
                           "queries" ["дорожка"] "new_request" false
                           "price_min" nil "price_max" nil "city_id" nil}))
      (is (= "дорожка для ходьбы под столом" @researched))
      (is (= @researched @observed-query))
      (is (= @researched (:user-want (conversation/current session)))))))

(deftest writer-title-redaction-does-not-mutate-source-card
  (let [card {:id "phone-1"
              :title "Телефон 13 — 12 500 сом https://lalafo.kg/ad/phone-1"
              :desc "Полное описание источника"
              :price 12500 :currency "KGS"
              :url "https://lalafo.kg/ad/phone-1"
              :params [{:name "memory" :value "128 GB"}]}
        result {:query "телефон"
                :cards [card] :candidates [card] :evaluated-ids ["phone-1"]
                :selection {:input-count 1 :accepted-count 1}
                :search-status :ok}]
    (with-redefs [search/search (fn [_ _] result)]
      (let [captured (atom [])]
        (binding [core/*current-session* (atom {})
                  core/*captured-cards* captured
                  core/*captured-stats* (atom nil)
                  core/*captured-query* (atom nil)
                  core/*captured-search* (atom nil)]
          (let [context (json/parse-string
                         ((tool "search") {"user_want" "телефон"
                                             "queries" ["телефон"]
                                             "new_request" true
                                             "price_min" nil "price_max" nil
                                             "city_id" nil}) true)
                title (get-in context [:accepted 0 :title])]
            (is (not (str/includes? title "12 500 сом")))
            (is (not (str/includes? title "https://lalafo.kg")))
            (is (= [card] @captured))))))))

(deftest persisted-photo-enums-are-reused-before-provider-call
  (let [image "https://img5.lalafo.com/i/posters/original/photo.jpg"
        card {:id "photo-1" :title "Тренажёр" :image image :images [image]}
        session (atom {"data" {"buying-request"
                                {"task-id" "task-photo"
                                 "user-want" "тренажёр"
                                 "photo-evidence"
                                 {"photo-1"
                                  {"status" "ok"
                                   "listing-id" "photo-1"
                                   "image-urls" [image]
                                   "observations"
                                   [{"kind" "brand" "text" "VisibleBrand"
                                     "confidence" "clear" "image-index" 0}]
                                   "limitations" []
                                   "condition-verified?" false}}}}})
        provider-calls (atom 0)]
    (with-redefs [vision/inspect-card (fn [& _]
                                        (swap! provider-calls inc)
                                        (throw (ex-info "provider must not run" {})))]
      (let [result (purchase/inspect! session [card] "прочитай надпись")
            evidence (first (:results result))]
        (is (zero? @provider-calls))
        (is (= :ok (:status evidence)))
        (is (= :brand (get-in evidence [:observations 0 :kind])))
        (is (= :clear (get-in evidence [:observations 0 :confidence])))))))

(deftest photo-follow-up-keeps-original-search-cache-creation-time
  (let [owner (str (random-uuid))
        task-id "task-cache"
        original-created-at (- (System/currentTimeMillis) 60000)
        initial {:query "тренажёр" :cards [{:id "one"}]
                 :search-status :ok :cache-created-at original-created-at}]
    (with-redefs [store/ad-cache (atom {})]
      (store/cache-search! owner task-id initial)
      (let [follow-up (assoc (store/cached-search owner task-id)
                             :cards [{:id "one" :visual-evidence {:status :ok}}])]
        (store/cache-search! owner task-id follow-up)
        (is (= original-created-at
               (:cache-created-at (store/cached-search owner task-id))))
        (is (= {:status :ok}
               (get-in (store/cached-search owner task-id) [:cards 0 :visual-evidence])))))))

(deftest restarting-with-only-durable-references-does-not-recommend-stale-ids
  (let [session (atom {"data" {"buying-request" {:task-id "old" :pool-ids ["one"]}}})]
    (binding [core/*current-session* session core/*current-user-id* "reopened"
              core/*captured-search* (atom nil)]
      (with-redefs [store/ad-cache (atom {})]
        (let [result (json/parse-string
                      ((tool "recommend_results")
                       {"recommendations" [{"listing_id" "one" "reason" "Предложение"
                                            "evidence" [] "checks" []}]}) true)]
          (is (= "stale" (:status result)))
          (is (nil? (:view result))))))))
