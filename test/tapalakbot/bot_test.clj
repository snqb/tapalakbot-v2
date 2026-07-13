(ns tapalakbot.bot-test
  "Unit tests for bot formatting, text processing, and message handling.
   Tests the private function: parse-update-extended. No LLM calls — pure function tests."
  (:require [clojure.test :refer [deftest is testing are]]
            [clj-harness.telegram :as tg]
            [tapalakbot.bot :as bot]
            [tapalakbot.core :as t]
            [clojure.string :as str]))

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
  (let [conversation-id (ns-resolve 'tapalakbot.bot 'conversation-id)]
    (is (some? conversation-id))
    (when conversation-id
      (is (= "tg-42"
             (conversation-id {:chat-id 42 :user-id 42})))
      (is (= "tg-42:-100123:17"
             (conversation-id {:chat-id -100123 :user-id 42 :thread-id 17})))
      (is (not= (conversation-id {:chat-id -100123 :user-id 42 :thread-id 17})
                (conversation-id {:chat-id -100123 :user-id 42 :thread-id 18}))))))

(deftest result-cards-are-rendered-from-structured-data
  (let [sent (atom [])
        render-and-send @#'bot/render-and-send
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
      (render-and-send -100123 42 "iphone" reply :thread-id 17))
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
        take-query (ns-resolve 'tapalakbot.bot 'take-track-query!)
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
    (with-redefs-fn {#'clj-harness.core/reset-session! (fn [& _])
                     #'bot/store-pending! (fn [& _])
                     #'bot/set-thread-id! (fn [& _])
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
    (is (false? (needs-fallback? {:text "" :cards [{:title "Пылесос"}]} "пылесос")))))

(deftest result-keyboard-prefers-a-cursor-over-a-new-search
  (let [results-keyboard @#'bot/results-keyboard]
    (is (some? results-keyboard))
    (when results-keyboard
      (let [keyboard (results-keyboard "toyota camry 70" "cursor123" true)
            buttons (get keyboard "inline_keyboard")]
        (is (= "page:cursor123" (get-in buttons [0 0 "callback_data"])))
        (is (= "🔄 Ещё 20 вариантов" (get-in buttons [0 0 "text"])))
        (is (= "cheaper:toyota camry 70" (get-in buttons [1 0 "callback_data"])))
        (is (= "dearer:toyota camry 70" (get-in buttons [1 1 "callback_data"])))))
    (when results-keyboard
      (let [keyboard (results-keyboard "toyota camry 70" nil false)]
        (is (= 1 (count (get keyboard "inline_keyboard"))))))))

(deftest page-callback-delivers-each-cached-result-once
  (let [handle-callback @#'bot/handle-callback
        pools (atom {})
        rich-sends (atom [])
        md-sends (atom [])
        callback-answers (atom [])
        cards (mapv (fn [i]
                      {:id i
                       :title (str "Item " i)
                       :price (* i 1000)
                       :currency "KGS"
                       :url (str "https://lalafo.kg/ad/" i)})
                    (range 1 46))]
    (with-redefs [tapalakbot.core/result-pools pools
                  tg/answer-callback-query
                  (fn [& args] (swap! callback-answers conj args) {"ok" true})
                  tg/send-md
                  (fn [chat-id text & args]
                    (swap! md-sends conj [chat-id text (apply hash-map args)])
                    {"ok" true})
                  tg/send-rich-message
                  (fn [chat-id & args]
                    (swap! rich-sends conj [chat-id (apply hash-map args)])
                    {"ok" true "result" {"message_id" (count @rich-sends)}})]
      (let [cursor (tapalakbot.core/cache-result-pool!
                    "tg-42" "query" cards tapalakbot.core/result-page-size)
            callback-base {:data (str "page:" cursor)
                           :user-id 42 :chat-id -100 :msg-id 7 :thread-id 17}]
        (handle-callback (assoc callback-base :callback-id "cb-1"))
        (handle-callback (assoc callback-base :callback-id "cb-2"))
        (handle-callback (assoc callback-base :callback-id "cb-3"))
        (is (= 2 (count @rich-sends)))
        (is (= 2 (count @md-sends)))
        (let [[_ first-rich] (first @rich-sends)
              [_ second-rich] (second @rich-sends)
              [_ _ first-intro] (first @md-sends)
              [_ _ second-intro] (second @md-sends)]
          (is (str/includes? (:html first-rich) "Item 21"))
          (is (str/includes? (:html first-rich) "Item 40"))
          (is (not (str/includes? (:html first-rich) "Item 41")))
          (is (str/includes? (:html second-rich) "Item 41"))
          (is (str/includes? (:html second-rich) "Item 45"))
          (is (nil? (:reply-markup first-rich)))
          (is (nil? (:reply-markup second-rich)))
          (is (= (str "page:" cursor)
                 (get-in first-intro [:reply_markup "inline_keyboard" 0 0 "callback_data"])))
          (is (= "cheaper:query"
                 (get-in second-intro [:reply_markup "inline_keyboard" 0 0 "callback_data"]))))
        (is (= "Больше сохранённых вариантов нет."
               (:text (apply hash-map (rest (last @callback-answers)))))))))
  )
