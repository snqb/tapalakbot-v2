(ns tapalakbot.bot-test
  "Unit tests for bot formatting, text processing, and message handling.
   Tests the private function: parse-update-extended. No LLM calls — pure function tests."
  (:require [clj-harness.core :as harness]
            [clj-harness.telegram :as tg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tapalakbot.bot :as bot]
            [tapalakbot.bot-state :as state]
            [tapalakbot.core :as t]
            [tapalakbot.result-delivery :as delivery]
            [tapalakbot.monitor.client :as monitor]
            [tapalakbot.monitor.store :as store]
            [tapalakbot.result-store :as result-store]))

;; Production polling and webhooks share the parent parser.
(def parse-update-extended* tg/parse-update)

;; ════════════════════════════ PARSE-UPDATE-EXTENDED ════════════════════════════

(deftest parse-update-extended-test
  (testing "parses regular text message"
    (let [update {"message" {"chat" {"id" 123}
                             "from" {"id" 456 "first_name" "Test"}
                             "text" "hello"
                             "message_id" 789}}
          result (parse-update-extended* update)]
      (is (= 123 (:chat-id result)))
      (is (= 456 (:user-id result)))
      (is (= "hello" (:text result)))
      (is (= 789 (:message-id result)))
      (is (= "Test" (:first-name result)))
      (is (nil? (:callback-id result)))))

  (testing "parses callback query"
    (let [update {"callback_query" {"id" "cb-123"
                                    "data" "track:1234"
                                    "from" {"id" 789}
                                    "message" {"chat" {"id" 100}
                                               "message_id" 200}}}
          result (parse-update-extended* update)]
      (is (= "cb-123" (:callback-id result)))
      (is (= "track:1234" (:data result)))
      (is (= 789 (:user-id result)))
      (is (= 100 (:chat-id result)))
      (is (= 200 (:msg-id result)))
      (is (nil? (:text result)))))

  (testing "handles missing first_name gracefully"
    (let [update {"message" {"chat" {"id" 1}
                             "from" {"id" 2}
                             "message_id" 3}}
          result (parse-update-extended* update)]
      (is (= "друг" (:first-name result)))))

  (testing "returns nil for unknown update type"
    (let [update {"unknown_type" {"foo" "bar"}}
          result (parse-update-extended* update)]
      (is (nil? result))))

  (testing "parses message with location"
    (let [update {"message" {"chat" {"id" 1}
                             "from" {"id" 2}
                             "message_id" 3
                             "location" {"latitude" 42.87 "longitude" 74.59}}}
          result (parse-update-extended* update)]
      (is (some? (:location result)))
      (is (= 42.87 (:lat (:location result))))
      (is (= 74.59 (:lon (:location result)))))))

(deftest conversation-id-isolates-forum-topics
  (is (= "tg-42"
         (state/conversation-id {:chat-id 42 :user-id 42})))
  (is (= "tg-42:-100123:17"
         (state/conversation-id {:chat-id -100123 :user-id 42 :thread-id 17})))
  (is (not= (state/conversation-id {:chat-id -100123 :user-id 42 :thread-id 17})
            (state/conversation-id {:chat-id -100123 :user-id 42 :thread-id 18}))))

(deftest result-cards-are-rendered-from-structured-data
  (let [sent (atom [])
        send-reply delivery/send-reply!
        reply {:mode :shortlist
               :intro "Короткий анализ"
               :cards [{:title "iPhone 13"
                        :price 35000
                        :currency "KGS"
                        :url "https://lalafo.kg/iphone-13"
                        :image "https://img.example/iphone-13.jpg"
                        :platform :lalafo}]
               :assumptions []}]
    (with-redefs [tg/send-rich-message
                  (fn [chat-id & options]
                    (swap! sent conj {:chat-id chat-id
                                      :options (apply hash-map options)})
                    {"ok" true
                     "result" {"message_id" (count @sent)
                               "rich_message" {}}})]
      (send-reply -100123 reply {:thread-id 17}))
    (is (= 2 (count @sent)) "analysis and cards are separate rich messages")
    (is (= "Короткий анализ" (get-in @sent [0 :options :markdown])))
    (is (nil? (get-in @sent [0 :options :html])))
    (is (= -100123 (get-in @sent [1 :chat-id])))
    (is (= 17 (get-in @sent [1 :options :thread-id])))
    (let [html (get-in @sent [1 :options :html])]
      (is (str/includes? html "<tg-slideshow>"))
      (is (not (str/includes? html "<table")))
      (is (str/includes? html "35 000"))
      (is (str/includes? html
                         "<a href=\"https://lalafo.kg/iphone-13\">Открыть на Lalafo.kg →</a>")))))

(deftest tracking-buttons-keep-their-own-query
  (let [make-button @#'bot/track-context-button
        take-query state/take-track-query!
        first-kb (make-button 42 "iphone 13")
        second-kb (make-button 42 "macbook air")
        callback-id (fn [kb]
                      (-> kb
                          (get "inline_keyboard")
                          first first
                          (get "callback_data")
                          (str/split #":" 2)
                          second))]
    (is (some? take-query))
    (when take-query
      (is (= "iphone 13" (take-query 42 (callback-id first-kb))))
      (is (= "macbook air" (take-query 42 (callback-id second-kb))))
      (is (nil? (take-query 99 (callback-id second-kb)))))))

(deftest busy-conversation-coalesces-to-latest-update
  (let [dispatch @#'bot/dispatch-conversation!
        started (promise)
        release-first (promise)
        finished (promise)
        processed (atom [])
        base {:chat-id 424242 :user-id 424242}
        fake-handler (fn [{:keys [text]}]
                       (swap! processed conj text)
                       (when (= text "first")
                         (deliver started true)
                         @release-first)
                       (when (= text "third")
                         (deliver finished true)))]
    (with-redefs-fn {#'bot/handle-update-now fake-handler}
      (fn []
        (dispatch (assoc base :text "first"))
        (is (= true (deref started 2000 :timeout)))
        (dispatch (assoc base :text "second"))
        (dispatch (assoc base :text "third"))
        (deliver release-first true)
        (is (= true (deref finished 2000 :timeout)))))
    (is (= ["first" "third"] @processed))))

(deftest reset-command-clears-session-and-responds
  (let [handle-reset @#'bot/handle-reset
        replies (atom [])]
    (with-redefs-fn {#'harness/reset-session! (fn [& _])
                     #'state/store-pending! (fn [& _])
                     #'bot/send-menu! (fn [chat-id text & options]
                                        (swap! replies conj {:chat-id chat-id
                                                             :text text
                                                             :options options}))}
      #(handle-reset {:chat-id 42 :user-id 42}))
    (is (= 42 (:chat-id (first @replies))))
    (is (str/includes? (:text (first @replies)) "Контекст очищен"))))

(deftest fallback-search-only-runs-for-an-empty-agent-result
  (let [needs-fallback? @#'bot/needs-fallback-search?]
    (is (true? (needs-fallback? {:text "" :cards []} "пылесос")))
    (is (false? (needs-fallback? {:text "Ничего не найдено" :cards []} "пылесос")))
    (is (false? (needs-fallback? {:text "" :cards [] :search-status :unavailable} "пылесос")))
    (is (false? (needs-fallback? {:text "" :cards [] :search-status :ok} "пылесос")))
    (is (false? (needs-fallback? {:text "" :cards [{:title "Пылесос"}]} "пылесос")))))


(deftest page-callback-delivers-each-cached-result-once
  (let [handle-callback @#'bot/handle-callback
        rich-sends (atom [])
        cards (mapv (fn [i]
                      {:id i :title (str "Item " i) :price (* i 1000) :currency "KGS"
                       :url (str "https://lalafo.kg/ad/" i)})
                    (range 1 46))]
    (with-redefs [result-store/result-pools (atom {})
                  tg/answer-callback-query (fn [& _] {"ok" true})
                  tg/send-md (fn [& _] {"ok" true})
                  tg/send-rich-message
                  (fn [_ & args]
                    (swap! rich-sends conj (apply hash-map args))
                    {"ok" true})]
      (let [cursor (result-store/cache-result-pool!
                    "tg-42:-100:17" "query" cards result-store/page-size)
            callback {:data (str "page:" cursor) :callback-id "page-cb"
                      :user-id 42 :chat-id -100 :msg-id 7 :thread-id 17}]
        (dotimes [_ 3] (handle-callback callback))
        (is (= 2 (count @rich-sends)))
        (let [[first-page last-page] @rich-sends]
          (is (every? #(str/includes? (:html first-page) (:url %)) (subvec cards 20 40)))
          (is (every? #(str/includes? (:html last-page) (:url %)) (subvec cards 40 45)))
          (is (not (str/includes? (:html first-page) (:url (nth cards 40)))))
          (is (some? (:reply_markup last-page))))))))

(deftest sort-callback-uses-cache-without-agent-call
  (let [handle-callback @#'bot/handle-callback
        sent (atom [])
        cards [{:id :low :price 100 :currency "KGS"}
               {:id :high :price 300 :currency "KGS"}
               {:id :mid :price 200 :currency "KGS"}]]
    (with-redefs [result-store/result-pools (atom {})
                  t/ask-stream (fn [& _] (throw (ex-info "Sorting must not call the agent" {})))
                  tg/answer-callback-query (fn [& _] {"ok" true})
                  delivery/send-reply! (fn [_ reply _] (swap! sent conj reply))]
      (let [cursor (result-store/cache-result-pool!
                    "tg-42:-100:17" "query" cards 3 {:cards cards :sort-mode :price-asc})]
        (handle-callback {:data (str "sort:price-desc:" cursor) :callback-id "sort-cb"
                          :user-id 42 :chat-id -100 :thread-id 17})
        (is (= [:high :mid :low] (mapv :id (:cards (first @sent)))))
        (is (= :price-desc (:sort-mode (first @sent))))))))


(deftest concurrent-forum-topics-rename-their-own-thread
  (let [handle-orchestrated @#'bot/handle-orchestrated
        first-started (promise)
        second-started (promise)
        release-first (promise)
        sessions (atom [])
        renamed (atom [])
        next-message-id (atom 0)
        fake-agent (fn [session-id text _status-cb _options]
                     (swap! sessions conj session-id)
                     (cond
                       (str/ends-with? session-id ":11")
                       (do
                         (deliver first-started true)
                         @release-first)

                       (str/ends-with? session-id ":22")
                       (deliver second-started true))
                     {:text "Готово" :cards [] :pool [] :query text})
        fake-send (fn [& _]
                    {"message_id" (swap! next-message-id inc)})]
    (with-redefs-fn {#'t/ask-stream fake-agent
                     #'delivery/send-reply! (fn [& _])
                     #'tg/send-message fake-send
                     #'tg/edit-message (fn [& _])
                     #'tg/delete-message (fn [& _])
                     #'tg/edit-forum-topic
                     (fn [chat-id thread-id & options]
                       (swap! renamed conj
                              [chat-id thread-id (:name (apply hash-map options))]))}
      (fn []
        (let [first-run (future
                          (handle-orchestrated
                           {:chat-id -100 :user-id 42 :thread-id 11 :text "iphone"}))]
          (is (= true (deref first-started 2000 :timeout)))
          (let [second-run (future
                             (handle-orchestrated
                              {:chat-id -100 :user-id 42 :thread-id 22 :text "macbook"}))]
            (is (= true (deref second-started 2000 :timeout)))
            (deliver release-first true)
            (is (nil? (deref first-run 2000 :timeout)))
            (is (nil? (deref second-run 2000 :timeout)))))))
    (is (= #{"tg-42:-100:11" "tg-42:-100:22"} (set @sessions)))
    (is (= #{[-100 11 "iphone"] [-100 22 "macbook"]} (set @renamed)))))

(deftest fast-path-messages-preserve-forum-thread
  (let [handle-update @#'bot/handle-update-now
        handle-callback @#'bot/handle-callback
        sent (atom [])
        capture (fn [kind]
                  (fn [chat-id text & options]
                    (swap! sent conj {:kind kind
                                      :chat-id chat-id
                                      :text text
                                      :options (apply hash-map options)})
                    {"message_id" (count @sent)}))
        base {:chat-id -100 :user-id 42 :thread-id 17}]
    (reset! result-store/ad-cache {})
    (result-store/cache-ads!
     "tg-42:-100:17"
     [{:title "iPhone 13" :price 35000 :currency "KGS"
       :url "https://lalafo.kg/ad/1" :platform :lalafo}])
    (with-redefs-fn {#'tg/send-message (capture :message)
                     #'tg/send-md (capture :markdown)
                     #'tg/answer-callback-query (fn [& _])
                     #'monitor/fetch-categories (constantly {:categories []})
                     #'store/get-user-tracks (constantly [])}
      (fn []
        (handle-update (assoc base :text "📍 Город"))
        (handle-update (assoc base :text "/help"))
        (handle-update (assoc base :text "/prices"))
        (handle-update (assoc base :text "/tracking"))
        (handle-update (assoc base :text "привет"))
        (handle-callback
         (assoc base :callback-id "cb-ad" :data "ad:1" :msg-id 9))))
    (is (= 6 (count @sent)))
    (is (every? #(= 17 (get-in % [:options :thread-id])) @sent))))
