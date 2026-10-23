(ns tapalakbot.tracker-test
  (:require [clj-harness.telegram :as tg]
            [clojure.test :refer [deftest is]]
            [tapalakbot.monitor.store :as store]
            [tapalakbot.monitor.tracker :as tracker]
            [tapalakbot.listing-filter :as listing-filter])
  (:import [java.time LocalDateTime]))

(deftest subscription-interval-controls-due-time
  (let [due? (ns-resolve 'tapalakbot.monitor.tracker 'track-due?)
        now (LocalDateTime/of 2026 7 12 12 0)]
    (is (some? due?))
    (when due?
      (is (true? (due? {:last_checked_at nil :notify_interval 24} now)))
      (is (false? (due? {:last_checked_at "2026-07-12 11:00:00" :notify_interval 3} now)))
      (is (true? (due? {:last_checked_at "2026-07-12 08:00:00" :notify_interval 3} now))))))

(def test-track {:id 7 :user_id "tg-42" :title "iphone"
                 :notify_interval 24})
(def test-item {"id" 99 "title" "iPhone 13" "price" 35000
                "url" "/iphone-13"})

(deftest failed-notification-is-retried-later
  (let [seen (atom [])]
    (with-redefs [tracker/search-track (fn [& _] [test-item])
                  store/seen-item? (constantly false)
                  listing-filter/filter-accessories (fn [items _] items)
                  tracker/filter-relevant (fn [_ items] (vec items))
                  store/mark-item-seen! (fn [_ item-id] (swap! seen conj item-id))
                  store/mark-track-checked! (constantly nil)
                  store/increment-notify-count! (constantly nil)
                  tg/send-message (constantly nil)]
      (let [result (tracker/check-track test-track)]
        (is (false? (:notified? result)))
        (is (empty? @seen))))))

(deftest successful-notification-commits-seen-items
  (let [seen (atom [])
        increments (atom 0)]
    (with-redefs [tracker/search-track (fn [& _] [test-item])
                  store/seen-item? (constantly false)
                  listing-filter/filter-accessories (fn [items _] items)
                  tracker/filter-relevant (fn [_ items] (vec items))
                  store/mark-item-seen! (fn [_ item-id] (swap! seen conj item-id))
                  store/mark-track-checked! (constantly nil)
                  store/increment-notify-count! (fn [_] (swap! increments inc))
                  tg/send-message (constantly {"message_id" 1})]
      (let [result (tracker/check-track test-track)]
        (is (true? (:notified? result)))
        (is (= [99] @seen))
        (is (= 1 @increments))))))

(deftest failed-selection-defers-check-and-keeps-candidates-unseen
  (let [seen (atom [])
        checked (atom [])]
    (with-redefs [tracker/search-track (fn [& _] [test-item])
                  store/seen-item? (constantly false)
                  tracker/filter-relevant (fn [& _] nil)
                  store/mark-item-seen! (fn [& args] (swap! seen conj args))
                  store/mark-track-checked! (fn [& args] (swap! checked conj args))
                  tg/send-message (fn [& _] (throw (ex-info "Must not notify" {})))]
      (is (false? (:notified? (tracker/check-track test-track))))
      (is (= [] @seen))
      (is (= [] @checked)))))

(deftest provider-failure-does-not-retire-the-check-as-empty
  (let [checked (atom [])]
    (with-redefs [tracker/search-track (fn [& _] (throw (ex-info "Provider unavailable" {})))
                  store/mark-track-checked! (fn [& args] (swap! checked conj args))]
      (is (false? (:notified? (tracker/check-track test-track))))
      (is (= [] @checked)))))
