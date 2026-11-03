(ns tapalakbot.purchase-advice-test
  (:require [clojure.test :refer [deftest is testing]]
            [tapalakbot.purchase-advice :as advice]))

(defn- session []
  (atom {"data" {"buying-request"
                  {:user-want "беговая дорожка"
                   :queries ["беговая дорожка"]
                   :price-max 30000
                   :excluded-ids ["seller-1"]
                   :pool-ids ["listing-1"]}}}))

(def source-a
  {:title "Motorized treadmill guide"
   :url "https://example.org/treadmill-guide"
   :text "A source discussing motor series, repairability, and checks."})

(deftest source-references-are-owned-and-citations-are-explicit
  (let [state (session)
        registered (advice/add-sources! state [source-a])]
    (is (= :ok (:status registered)))
    (is (= "r1" (-> registered :sources first :id)))
    (is (= :invalid (:status
                     (advice/save-strategy!
                      state
                      {:summary "Compare repairable series"
                       :directions [{:name "Series A" :rationale "Source-backed"
                                     :evidence ["r9e1"]}]
                       :checks [] :unknowns ["Seller maintenance is unknown"]}))))
    (is (= :ok (:status
                (advice/save-strategy!
                 state
                 {:summary "Compare repairable series"
                  :directions [{:name "Series A" :rationale "Source-backed"
                                :evidence ["r1e1"]}]
                  :checks ["Ask for service history"]
                  :unknowns ["Seller maintenance is unknown"]}))))
    (is (re-find #"https://example.org/treadmill-guide"
                 (advice/cite-text state "See [[r1]].")))
    (is (re-find #"источник не проверен"
                 (advice/cite-text state "See [[r9]].")))))

(deftest stale-evidence-does-not-cross-a-new-buying-task
  (let [state (session)]
    (advice/add-sources! state [source-a])
    (swap! state assoc-in ["data" "buying-request" :user-want] "смартфон")
    (is (empty? (:sources (advice/current state))))
    (is (= "r1" (-> (advice/add-sources!
                     state
                     [{:title "Phone repair guide"
                       :url "https://example.org/phone-guide"
                       :text "Parts and repair checks."}])
                    :sources first :id)))
    (is (= "смартфон" (get-in @state ["data" "buying-request" :user-want])))))

(deftest advice-preserves-unrelated-canonical-goal-fields
  (let [state (session)
        before (select-keys (get-in @state ["data" "buying-request"])
                            [:user-want :queries :price-max :excluded-ids :pool-ids])]
    (advice/add-sources! state [source-a])
    (let [after (select-keys (get-in @state ["data" "buying-request"])
                             [:user-want :queries :price-max :excluded-ids :pool-ids])]
      (is (= before after)))))

(deftest malformed-sources-are-rejected-without-partial-registration
  (let [state (session)]
    (is (= :invalid (:status
                     (advice/add-sources!
                      state
                      [source-a {:title "Unsafe" :url "http://example.org/x"
                                 :text "not accepted"}]))))
    (is (nil? (advice/current state)))))

(deftest only-owned-excerpts-can-be-cited
  (let [sources [(assoc source-a :id "r1")]]
    (is (nil? (advice/grounded-evidence sources ["r1"])))
    (is (nil? (advice/grounded-evidence sources ["r1e99"])))
    (is (= [{:source-id "r1" :quote (:text source-a)}]
           (advice/grounded-evidence sources ["r1e1"])))
    (is (= [] (advice/grounded-evidence sources [])))))

(deftest repeated-source-preserves-original-quotation-and-stable-id
  (let [state (session)]
    (advice/add-sources! state [source-a source-a])
    (advice/add-sources! state [(assoc source-a :text "Different later excerpt")])
    (is (= [(assoc source-a :id "r1")] (:sources (advice/current state))))))

(deftest long-source-excerpts-remain-exact-and-individually-addressable
  (let [text (apply str (repeat 1601 "я"))
        sources [(assoc source-a :id "r1" :text text)]
        excerpts (advice/source-excerpts (first sources))
        evidence (advice/grounded-evidence sources (mapv :id excerpts))]
    (is (= text (apply str (map :quote evidence))))
    (is (every? #(<= (count (:quote %)) 800) evidence))
    (is (= [{:source-id "r1" :quote "я"}]
           (advice/grounded-evidence sources ["r1e3"])))))
