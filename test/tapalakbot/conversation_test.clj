(ns tapalakbot.conversation-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [tapalakbot.conversation :as conversation]))

(defn- search-result [cards]
  {:query "дорожка до 20000" :cards cards :search-status :ok
   :qb-result {:price-min nil :price-max 20000}})

(deftest buying-request-and-source-safe-view-survive-session-serialization
  (let [session (atom {})
        card {:id 101 :title "Дорожка" :city "Бишкек" :condition "used"
              :price 17001 :currency "KGS" :url "https://lalafo.kg/private-source-url"
              :decision {:fit :direct :condition :used :risk :none}}]
    (conversation/remember-search! session
                                   {:user-want "дорожка" :queries ["беговая дорожка"] :city-id 103184}
                                   (search-result [card]))
    (let [restored (atom (json/parse-string (json/generate-string @session)))
          goal (conversation/current restored)
          shown (first (:shown-cards goal))]
      (is (= ["беговая дорожка"] (:queries goal)))
      (is (= 20000 (:price-max goal)))
      (is (= 103184 (:city-id goal)))
      (is (= "101" (:id shown)))
      (is (= 1 (:number shown)))
      (is (= "Бишкек" (:city shown)))
      (is (not (contains? shown :price)))
      (is (not (contains? shown :url))))))

(deftest feedback-rejects-only-actually-shown-owned-listings
  (let [session (atom {})]
    (conversation/remember-search! session {:user-want "дорожка"}
                                   (search-result [{:id 1 :title "A"} {:id 2 :title "B"}]))
    (is (= :invalid (:status (conversation/reject! session {:numbers [99] :reason "не подходит"}))))
    (is (empty? (:excluded-ids (conversation/current session))))
    (is (= :recorded (:status (conversation/reject! session {:numbers [2] :reason "нужна складная"}))))
    (is (= ["2"] (:excluded-ids (conversation/current session))))
    (conversation/remember-search! session {:user-want "складная дорожка"}
                                   (search-result [{:id 3 :title "C"}]))
    (is (= ["2"] (:excluded-ids (conversation/current session))))
    (is (= ["нужна складная"] (:feedback (conversation/current session))))
    (is (= :recorded (:status (conversation/reject! session {:all-shown true :reason "слишком большая"}))))
    (is (= #{"2" "3"} (set (:excluded-ids (conversation/current session)))))))

(deftest explicit-new-request-clears-prior-rejections-and-budget
  (let [session (atom {})]
    (conversation/remember-search! session {:user-want "дорожка"}
                                   (search-result [{:id 1 :title "A"}]))
    (conversation/reject! session {:all-shown true :reason "не детская"})
    (conversation/remember-search! session {:user-want "роутер" :new-request true}
                                   {:query "роутер" :cards [] :search-status :ok
                                    :qb-result {:price-min nil :price-max nil}})
    (let [goal (conversation/current session)]
      (is (= "роутер" (:user-want goal)))
      (is (nil? (:price-max goal)))
      (is (nil? (:city-id goal)))
      (is (empty? (:feedback goal)))
      (is (empty? (:excluded-ids goal)))
      (is (empty? (:shown-cards goal))))))

(deftest latest-view-keeps-real-global-result-numbers-for-feedback
  (let [session (atom {})]
    (conversation/remember-search! session {:user-want "товар"}
                                   (search-result [{:id 1 :title "A"} {:id 2 :title "B"}]))
    (conversation/remember-view! session [{:id 7 :title "G" :result-index 21}])
    (is (= :invalid (:status (conversation/reject! session {:numbers [1]}))))
    (conversation/reject! session {:numbers [21] :reason "не тот вариант"})
    (is (= ["7"] (:excluded-ids (conversation/current session))))))
