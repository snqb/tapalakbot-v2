(ns tapalakbot.tracker-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [tapalakbot.monitor.store :as store]
            [tapalakbot.monitor.tracker :as tracker]
            [clj-harness.telegram :as tg])
  (:import [java.time LocalDateTime]))

(def base-track
  {:id 7 :user_id "conversation-7" :chat_id -100987 :thread_id 42
   :title "iPhone 13 до 40 000 сом" :queries ["iphone 13" "айфон 13"]
   :price_min nil :price_max 40000 :city_id 103184
   :notify_interval 24 :excluded_ids []})

(use-fixtures :each
  (fn [test-fn]
    (with-redefs [store/get-track (fn [id] (assoc base-track :id id :enabled 1))]
      (test-fn))))

(defn source-card
  ([id] (source-card id {}))
  ([id extra]
   (merge {:id id :title (str "iPhone " id) :desc "Телефон" :price 35000
           :currency "KGS" :price-kgs 35000 :url (str "https://lalafo.kg/ad/" id)
           :image (str "https://img.example/" id ".jpg") :images [(str "https://img.example/" id ".jpg")]
           :platform :lalafo
           :decision {:fit :direct :warnings []}}
          extra)))

(defn ok-search [cards]
  {:search-status :ok :cards (vec cards)
   :collection {:failed-queries 0} :selection {:input-count (count cards)}})

(deftest subscription-interval-controls-due-time
  (let [now (LocalDateTime/of 2026 7 12 12 0)]
    (is (true? (tracker/track-due? {:last_checked_at nil :notify_interval 24} now)))
    (is (false? (tracker/track-due? {:last_checked_at "2026-07-12 11:00:00"
                                      :notify_interval 3} now)))
    (is (true? (tracker/track-due? {:last_checked_at "2026-07-12 08:00:00"
                                     :notify_interval 3} now)))))


(deftest successful-notification-keeps-source-and-jev-fields
  (let [seen (atom [])
        checked (atom [])
        increments (atom 0)
        sent (atom nil)
        card (source-card 99 {:decision {:fit :possible
                                         :warnings ["Проверьте состояние"]}})]
    (with-redefs [tracker/search-track (fn [_ _] (ok-search [card]))
                  store/mark-item-seen! (fn [_ item-id] (swap! seen conj item-id))
                  store/mark-track-checked! (fn [id] (swap! checked conj id))
                  store/increment-notify-count! (fn [_] (swap! increments inc))
                  tg/send-rich-message (fn [chat-id & options]
                                          (let [opts (apply hash-map options)]
                                            (reset! sent {:chat-id chat-id
                                                          :message (:html opts)
                                                          :options opts})
                                            {"message_id" 1}))]
      (let [result (tracker/check-track base-track)]
        (is (true? (:notified? result)))
        (is (= [99] @seen))
        (is (= [7] @checked))
        (is (= 1 @increments))
        (is (= -100987 (:chat-id @sent)))
        (is (= 42 (get-in @sent [:options :thread-id])))
        (is (clojure.string/includes? (:message @sent) "Проверьте состояние"))
        (is (clojure.string/includes? (:message @sent) "https://lalafo.kg/ad/99"))
        (is (clojure.string/includes? (:message @sent) "https://img.example/99.jpg"))))))


(deftest accepted-cards-deliver-in-confirmed-batches
  (let [seen (atom [])
        checked (atom [])
        increments (atom 0)
        sent (atom [])
        cards (mapv source-card (range 1 26))]
    (with-redefs [tracker/search-track
                  (fn [_ _]
                    (ok-search (remove #(contains? (set @seen) (:id %)) cards)))
                  store/mark-item-seen! (fn [_ item-id] (swap! seen conj item-id))
                  store/mark-track-checked! (fn [id] (swap! checked conj id))
                  store/increment-notify-count! (fn [_] (swap! increments inc))
                  tg/send-rich-message
                  (fn [_ & options]
                    (swap! sent conj (:html (apply hash-map options)))
                    {"message_id" 1})]
      (let [first-result (tracker/check-track base-track)
            second-result (tracker/check-track base-track)]
        (is (true? (:notified? first-result)))
        (is (= 25 (:new-items first-result)))
        (is (true? (:notified? second-result)))
        (is (= 5 (:new-items second-result)))
        (is (= (vec (range 1 26)) @seen))
        (is (= [7 7] @checked))
        (is (= 2 @increments))
        (is (= 2 (count @sent)))
        (is (clojure.string/includes? (first @sent) "20 новых"))
        (is (not (clojure.string/includes? (first @sent)
                                           "https://lalafo.kg/ad/21")))
        (is (clojure.string/includes? (second @sent) "5 новых"))
        (is (not (clojure.string/includes? (second @sent)
                                           "https://lalafo.kg/ad/20")))))))

(deftest route-isolation-supports-topic-and-legacy-private-target
  (let [calls (atom [])
        card (source-card 1)]
    (with-redefs [tracker/search-track (fn [_ _] (ok-search [card]))
                  store/mark-item-seen! (constantly nil)
                  store/mark-track-checked! (constantly nil)
                  store/increment-notify-count! (constantly nil)
                  tg/send-rich-message (fn [chat-id _format _content & options]
                                          (swap! calls conj [chat-id (apply hash-map options)])
                                          {"message_id" 1})]
      (tracker/check-track base-track)
      (tracker/check-track (assoc base-track :id 8 :chat_id nil :thread_id 999
                                  :user_id "tg-42"))
      (tracker/check-track (assoc base-track :id 9 :chat_id nil :thread_id nil
                                  :user_id "tg-not-a-number"))
      (is (= 2 (count @calls)))
      (is (= -100987 (ffirst @calls)))
      (is (= 42 (get-in (first @calls) [1 :thread-id])))
      (is (= "42" (first (second @calls))))
      (is (nil? (get-in (second @calls) [1 :thread-id]))))))


(deftest successful-selection-retires-evaluated-noise
  (let [seen (atom [])
        sent (atom [])
        result (assoc (ok-search [(source-card 3)])
                      :evaluated-ids [1 2 3])]
    (with-redefs [tracker/search-track (fn [_ _] result)
                  store/mark-item-rejected! (fn [_ item-id] (swap! seen conj item-id))
                  store/mark-item-seen! (fn [_ item-id] (swap! seen conj item-id))
                  store/mark-track-checked! (constantly nil)
                  store/increment-notify-count! (constantly nil)
                  tg/send-rich-message (fn [_ & options]
                                          (swap! sent conj (:html (apply hash-map options)))
                                          {"message_id" 1})]
      (is (true? (:notified? (tracker/check-track base-track))))
      (is (= ["1" "2" 3] @seen))
      (is (= 1 (count @sent)))
      (is (clojure.string/includes? (first @sent) "ad/3")))))

(deftest confirmed-delivery-deduplicates-repeat-scan
  (let [seen (atom #{})
        sends (atom 0)
        card (source-card 44)]
    (with-redefs [tracker/search-track
                  (fn [_ _] (if (contains? @seen 44)
                              (ok-search [])
                              (ok-search [card])))
                  store/mark-item-seen! (fn [_ item-id] (swap! seen conj item-id))
                  store/mark-track-checked! (constantly nil)
                  store/increment-notify-count! (constantly nil)
                  tg/send-rich-message (fn [& _] (swap! sends inc) {"message_id" 1})]
      (is (true? (:notified? (tracker/check-track base-track))))
      (is (false? (:notified? (tracker/check-track base-track))))
      (is (= 1 @sends)))))

(deftest delivery-envelope-requires-message-id
  (let [seen (atom [])
        checked (atom [])
        increments (atom 0)]
    (with-redefs [tracker/search-track (fn [_ _] (ok-search [(source-card 45)]))
                  store/mark-item-seen! (fn [_ item-id] (swap! seen conj item-id))
                  store/mark-track-checked! (fn [id] (swap! checked conj id))
                  store/increment-notify-count! (fn [_] (swap! increments inc))
                  tg/send-rich-message (constantly {"ok" true})]
      (is (false? (:notified? (tracker/check-track base-track))))
      (is (empty? @seen))
      (is (empty? @checked))
      (is (zero? @increments)))))

(deftest unsuccessful-send-and-failed-check-remain-retryable
  (testing "an unsuccessful Telegram result does not retire candidates"
    (let [seen (atom []) checked (atom []) increments (atom 0)]
      (with-redefs [tracker/search-track (fn [_ _] (ok-search [(source-card 5)]))
                    store/mark-item-seen! (fn [& args] (swap! seen conj args))
                    store/mark-track-checked! (fn [& args] (swap! checked conj args))
                    store/increment-notify-count! (fn [& _] (swap! increments inc))
                    tg/send-rich-message (constantly nil)]
        (is (false? (:notified? (tracker/check-track base-track))))
        (is (empty? @seen))
        (is (empty? @checked))
        (is (zero? @increments)))))
  (testing "provider/JEV failure is not an empty successful check"
    (let [seen (atom []) checked (atom [])]
      (with-redefs [tracker/search-track (fn [_ _]
                                          {:search-status :unavailable
                                           :error "deferred"})
                    store/mark-item-seen! (fn [& args] (swap! seen conj args))
                    store/mark-track-checked! (fn [& args] (swap! checked conj args))]
        (is (false? (:notified? (tracker/check-track base-track))))
        (is (empty? @seen))
        (is (empty? @checked))))))

(deftest tracker-cycle-failure-survives-until-interrupted
  (let [sleep-calls (atom [])
        cycles (atom 0)
        interval-ms (* 60 1000)
        sleep-fn (fn [millis]
                   (swap! sleep-calls conj millis)
                   (when (= 3 (count @sleep-calls))
                     (throw (InterruptedException.))))
        loop-fn (ns-resolve 'tapalakbot.monitor.tracker 'run-tracker-loop!)]
    (with-redefs [tracker/run-check-cycle!
                  (fn []
                    (swap! cycles inc)
                    (when (= 1 @cycles)
                      (throw (java.sql.SQLException. "database is busy"))))]
      (is (thrown? InterruptedException (loop-fn sleep-fn))))
    (is (= 2 @cycles))
    (is (= [(* 5 60 1000) interval-ms interval-ms] @sleep-calls))))

