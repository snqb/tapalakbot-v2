(ns tapalakbot.tracking-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [tapalakbot.monitor.store :as store]
            [clj-harness.telegram :as tg]
            [tapalakbot.bot-state :as state]
            [tapalakbot.tracking-ui :as ui]
            [tapalakbot.monitor.tracker :as tracker]
            [tapalakbot.agent-tracking :as agent]
            [tapalakbot.conversation :as conversation]
            [tapalakbot.core :as core]
            [tapalakbot.tracking :as tracking]))

(defn- temp-path []
  (.getAbsolutePath (java.io.File/createTempFile "tapalakbot-track-" ".db")))

(defn- with-db [f]
  (let [path (temp-path)
        datasource (atom nil)]
    (try
      (with-redefs-fn {#'store/db-path path #'store/ds datasource}
        #(do
           (store/init-db!)
           (f)))
      (finally
        (reset! datasource nil)
        (.delete (io/file path))))))

(def owner {:id "conversation-a" :chat-id -100 :thread-id 17})
(def other-topic {:id "conversation-a" :chat-id -100 :thread-id 18})
(def other-owner {:id "conversation-b" :chat-id -100 :thread-id 17})
(def spec {:title "iPhone 13" :queries ["iPhone 13"]
           :excluded-ids ["rejected-1"] :seed-ids [101 102 "source-17"]})

(deftest invalid-destination-and-spec-do-not-create
  (with-db
    #(do
       (is (= :invalid (:status (tracking/manage! (dissoc owner :chat-id)
                                                   :create spec))))
       (is (= :invalid (:status (tracking/manage! owner :create
                                                   (assoc spec :queries [])))))
       (is (empty? (:tracks (tracking/manage! owner :list nil)))))))

(deftest create-seeds-and-persists-null-bounds
  (with-db
    #(let [created (tracking/manage! owner :create spec)
           row (:track created)]
       (is (= :created (:status created)))
       (is (= "conversation-a" (:user_id row)))
       (is (= "-100" (:chat_id row)))
       (is (= 17 (:thread_id row)))
       (is (nil? (:price_min row)))
       (is (nil? (:price_max row)))
       (is (nil? (:city_id row)))
       (is (= ["rejected-1"] (store/parse-track-excluded-ids
                               (:excluded_ids row))))
       (is (store/seen-item? (:id row) 101))
       (is (store/seen-item? (:id row) 102))
       (is (store/seen-item? (:id row) "source-17")))))

(deftest exact-owner-and-topic-isolation
  (with-db
    #(let [created (tracking/manage! owner :create spec)
           id (get-in created [:track :id])]
       (is (= 1 (count (:tracks (tracking/manage! owner :list nil)))))
       (is (empty? (:tracks (tracking/manage! other-topic :list nil))))
       (is (= :not-found (:status (tracking/manage! other-owner :pause {:id id}))))
       (is (= :not-found (:status (tracking/manage! other-topic :stop {:id id})))))))

(deftest duplicate-create-and-lifecycle
  (with-db
    #(let [first (tracking/manage! owner :create spec)
           second (tracking/manage! owner :create spec)
           id (get-in first [:track :id])]
       (is (= :existing (:status second)))
       (is (= id (get-in second [:track :id])))
       (is (= :paused (:status (tracking/manage! owner :pause {:id id}))))
       (is (= 1 (count (:tracks (tracking/manage! owner :list nil)))))
       (is (= :resumed (:status (tracking/manage! owner :resume {:id id}))))
       (is (= :updated (:status (tracking/manage! owner :update
                                                  {:id id :price-min 100
                                                   :price-max 200 :city-id nil}))))
       (let [row (:track (tracking/manage! owner :update
                                           {:id id :price-min nil
                                            :price-max nil :city-id nil}))]
         (is (nil? (:price_min row)))
         (is (nil? (:price_max row)))
         (is (nil? (:city_id row))))
       (is (= :stopped (:status (tracking/manage! owner :stop {:id id}))))
       (is (empty? (:tracks (tracking/manage! owner :list nil)))))))

(deftest filter-change-clears-rejected-and-unknown-seen-rows
  (with-db
    #(let [created (tracking/manage! owner :create spec)
           id (get-in created [:track :id])]
       (store/mark-item-rejected! id "rejected-before-edit")
       (store/mark-item-seen! id "delivered-before-edit")
       (jdbc/execute! (store/get-datasource)
                      ["INSERT INTO track_seen_items (track_id, item_id, rejected)
                        VALUES (?, ?, -1)" id "legacy-before-edit"])
       (store/mark-track-checked! id)
       (let [updated (tracking/manage! owner :update
                                       {:id id :title "iPhone 14"})]
         (is (= :updated (:status updated)))
         (is (= [101 102 "source-17" "delivered-before-edit"]
                (store/seen-item-ids id)))
         (is (nil? (:last_checked_at (:track updated))))))))

(deftest semantic-filter-edit-clears-stale-category
  (with-db
    #(let [created (tracking/manage! owner :create spec)
           id (get-in created [:track :id])]
       (jdbc/execute! (store/get-datasource)
                      ["UPDATE user_tracks SET category_id = 7 WHERE id = ?" id])
       (tracking/manage! owner :update {:id id :queries ["iPhone 14"]})
       (is (nil? (:category_id (store/get-track id))))
       (jdbc/execute! (store/get-datasource)
                      ["UPDATE user_tracks SET category_id = 8 WHERE id = ?" id])
       (tracking/manage! owner :update {:id id :notify-interval 12})
       (is (= 8 (:category_id (store/get-track id)))))))

(deftest reopening-retains-managed-row
  (with-db
    #(do
       (tracking/manage! owner :create spec)
       (reset! @#'store/ds nil)
       (store/init-db!)
       (is (= 1 (count (:tracks (tracking/manage! owner :list nil)))))
       (is (store/seen-item? (get-in (tracking/manage! owner :list nil)
                                     [:tracks 0 :id]) 101)))))
(deftest legacy-schema-migration-preserves-rows
  (let [path (temp-path)
        datasource (jdbc/get-datasource (str "jdbc:sqlite:" path))]
    (try
      (jdbc/execute! datasource ["CREATE TABLE user_tracks
        (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL,
         title TEXT NOT NULL, queries TEXT NOT NULL, price_min INTEGER,
         price_max INTEGER, city_id INTEGER, enabled INTEGER DEFAULT 1,
         created_at TEXT, last_checked_at TEXT, notify_count INTEGER,
         notify_interval INTEGER)"])
      (jdbc/execute! datasource ["INSERT INTO user_tracks
        (user_id,title,queries,price_min,price_max,city_id)
        VALUES ('tg-123','old','[\"old\"]',1,2,3)"])
      (jdbc/execute! datasource ["CREATE TABLE track_seen_items
        (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id INTEGER NOT NULL,
         item_id INTEGER NOT NULL, notified_at TEXT,
         UNIQUE(track_id, item_id))"])
      (jdbc/execute! datasource ["INSERT INTO track_seen_items
        (track_id, item_id) VALUES (1, 'legacy-unknown')"])
      (with-redefs-fn {#'store/db-path path #'store/ds (atom nil)}
        #(do
           (store/init-db!)
           (is (= -1 (:rejected
                      (jdbc/execute-one! (store/get-datasource)
                                         ["SELECT rejected FROM track_seen_items
                                           WHERE track_id = 1 AND item_id = 'legacy-unknown'"]
                                         {:builder-fn rs/as-unqualified-maps}))))
           (jdbc/execute! (store/get-datasource)
                          ["INSERT INTO track_seen_items (track_id, item_id)
                            VALUES (1, 'new-positive')"])
           (is (= 0 (:rejected
                     (jdbc/execute-one! (store/get-datasource)
                                        ["SELECT rejected FROM track_seen_items
                                          WHERE track_id = 1 AND item_id = 'new-positive'"]
                                        {:builder-fn rs/as-unqualified-maps}))))
           (store/init-db!)
           (is (= -1 (:rejected
                      (jdbc/execute-one! (store/get-datasource)
                                         ["SELECT rejected FROM track_seen_items
                                           WHERE track_id = 1 AND item_id = 'legacy-unknown'"]
                                         {:builder-fn rs/as-unqualified-maps}))))
           (let [row (store/get-track 1)]
             (is (= "tg-123" (:user_id row)))
             (is (contains? row :chat_id))
             (is (contains? row :thread_id))
             (is (contains? row :excluded_ids))
             (is (= 1 (count (:tracks (tracking/manage!
                                        {:id "tg-123" :chat-id 123 :thread-id nil}
                                        :list nil)))))
             (is (= 1 (count (:tracks (tracking/manage!
                                        {:id "tg-123:123" :chat-id 123 :thread-id nil}
                                        :list nil)))))
             (is (empty? (:tracks (tracking/manage!
                                    {:id "tg-123:7" :chat-id 123 :thread-id nil}
                                    :list nil))))
             (is (empty? (:tracks (tracking/manage!
                                    {:id "tg-123:7" :chat-id 123 :thread-id 7}
                                    :list nil))))
             (is (empty? (:tracks (tracking/manage!
                                    {:id "tg-999" :chat-id 999 :thread-id nil}
                                    :list nil))))
             (is (= :updated (:status (tracking/manage!
                                       {:id "tg-123" :chat-id 123 :thread-id nil}
                                       :update {:id 1 :title "old updated"}))))
             (is (= 24 (:notify_interval (store/get-track 1))))
             (is (= :paused (:status (tracking/manage!
                                      {:id "tg-123" :chat-id 123 :thread-id nil}
                                      :pause {:id 1}))))
             (is (= :resumed (:status (tracking/manage!
                                       {:id "tg-123" :chat-id 123 :thread-id nil}
                                       :resume {:id 1}))))
             (is (= :stopped (:status (tracking/manage!
                                       {:id "tg-123" :chat-id 123 :thread-id nil}
                                       :stop {:id 1})))))))
      (finally
        (.delete (io/file path))))))

(deftest tracking-controls-cannot-cross-a-topic-or-consume-its-request
  (with-db
    (fn []
      (with-redefs [state/pending-track-queries (atom {})
                    tg/answer-callback-query (fn [& _])
                    tg/send-rich-message (fn [& _] {"message_id" 1})]
        (let [message {:chat-id -100 :user-id 42 :thread-id 17 :callback-id "callback"}
              destination {:id (state/conversation-id message) :chat-id -100 :thread-id 17}
              button (ui/context-button (:id destination)
                                        {:user-want "iPhone 13" :queries ["iphone 13"]
                                         :price-max 20000 :pool-ids [101]})
              data (get-in button ["inline_keyboard" 0 0 "callback_data"])
              _ (ui/context-button (:id destination)
                                   {:user-want "Macbook" :queries ["macbook"] :price-max 50000})
              foreign (assoc message :thread-id 18)]
          (ui/handle-callback! (assoc foreign :data data))
          (is (empty? (:tracks (tracking/manage! destination :list {}))))
          (ui/handle-callback! (assoc message :data data))
          (let [track (first (:tracks (tracking/manage! destination :list {})))
                pause (str "track_pause:" (:id track))]
            (is (= "iPhone 13" (:title track)))
            (is (= 20000 (:price_max track)))
            (is (store/seen-item? (:id track) 101))
            (ui/handle-callback! (assoc foreign :data pause))
            (is (= 1 (:enabled (store/get-track (:id track)))))
            (ui/handle-callback! (assoc message :data pause))
            (is (= 0 (:enabled (store/get-track (:id track)))))))))))

(deftest an-in-flight-check-cannot-deliver-after-pause-stop-or-filter-edit
  (doseq [[action patch] [[:pause {}] [:stop {}] [:update {:price-max 100}]]]
    (with-db
      (fn []
        (let [row (:track (tracking/manage! owner :create spec))
              sent (atom 0)]
          (with-redefs [tracker/search-track
                        (fn [_ _]
                          (tracking/manage! owner action (assoc patch :id (:id row)))
                          {:search-status :ok :collection {:failed-queries 0}
                           :cards [{:id "fresh" :title "iPhone" :price 10000 :currency "KGS"}]})
                        tg/send-rich-message (fn [& _] (swap! sent inc) {"message_id" 1})]
            (is (false? (:notified? (tracker/check-track row))))
            (is (zero? @sent))
            (is (not (store/seen-item? (:id row) "fresh")))))))))

(deftest directly-tracking-a-new-product-does-not-inherit-the-old-task
  (with-db
    (fn []
      (let [session (atom {})]
        (conversation/remember-search! session {:user-want "дорожка"}
                                       {:cards [{:id "old" :title "Дорожка"}]
                                        :qb-result {:price-max 20000}})
        (conversation/reject! session {:numbers [1] :reason "не подходит"})
        (let [result (agent/execute session owner
                                    {"action" "create" "new_request" true
                                     "user_want" "двухдиапазонный роутер"
                                     "queries" ["роутер"]})
              request (conversation/current session)]
          (is (= :created (:status result)))
          (is (nil? (:price-max request)))
          (is (empty? (:feedback request)))
          (is (empty? (:excluded-ids request)))
          (is (empty? (:shown-cards request)))
          (is (not (store/seen-item? (get-in result [:track :id]) "old"))))))))

(deftest inactive-saved-tracks-do-not-start-another-source-request
  (doseq [action [:pause :stop]]
    (with-db
      (fn []
        (let [row (:track (tracking/manage! owner :create spec))
              searches (atom 0)]
          (tracking/manage! owner action {:id (:id row)})
          (with-redefs [tracker/search-track (fn [& _] (swap! searches inc) {:search-status :ok :cards []})]
            (is (false? (:notified? (tracker/check-track row))))
            (is (zero? @searches))))))))

(deftest managing-another-subscription-keeps-the-active-buying-task
  (with-db
    (fn []
      (let [session (atom {})
            created (agent/execute session owner
                                   {"action" "create" "new_request" true
                                    "user_want" "дорожка" "queries" ["дорожка"]})
            id (get-in created [:track :id])]
        (conversation/remember-search! session
                                       {:user-want "роутер" :queries ["роутер"] :new-request true}
                                       {:cards [{:id "router" :title "Роутер"}]
                                        :qb-result {:price-min nil :price-max nil}})
        (let [request (conversation/current session)]
          (doseq [[action fields] [["pause" {}] ["resume" {}]
                                   ["update" {"price_max" 15000}] ["stop" {}]]]
            (agent/execute session owner (merge {"action" action "id" id} fields))
            (is (= request (conversation/current session)) action)))))))

(deftest a-button-created-watch-keeps-followup-budget-changes-in-sync
  (with-db
    (fn []
      (let [message {:chat-id -100 :user-id 42 :thread-id 17 :callback-id "create"}
            id (state/conversation-id message)
            destination {:id id :chat-id -100 :thread-id 17}
            session (atom {})
            bot (delay {:sessions (atom {id session})})]
        (conversation/remember-search! session {:user-want "iPhone 13" :queries ["iphone 13"]}
                                       {:cards [] :qb-result {:price-max 20000}})
        (force bot)
        (with-redefs [core/tapalakbot bot state/pending-track-queries (atom {})
                      tg/answer-callback-query (fn [& _])
                      tg/send-rich-message (fn [& _] {"message_id" 1})]
          (let [button (ui/context-button id (conversation/current session))
                data (get-in button ["inline_keyboard" 0 0 "callback_data"])]
            (ui/handle-callback! (assoc message :data data))
            (let [track (first (:tracks (tracking/manage! destination :list {})))]
              (agent/execute session destination {"action" "update" "id" (:id track)
                                                  "user_want" "iPhone 13 до 15000 сом"
                                                  "price_max" 15000})
              (is (= 15000 (:price-max (conversation/current session))))
              (is (= 15000 (:price_max (store/get-track (:id track))))))))))))
