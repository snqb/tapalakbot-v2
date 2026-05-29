(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2.

  Streaming: real-time LLM output via clj-harness.stream/stream-agent.
  Single status message → progressive edits → final HTML."
  (:require [tapalakbot.core :as t]
            [clj-harness.telegram :as tg]
            [clj-harness.telegram.format :as fmt]
            [clj-harness.core :as hc]
            [clj-harness.stream :as stream]
            [clojure.core.async :refer [chan <!! >!!]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ══════════════════════ FAST RESPONSES ══════════════════════

(def ^:private greeting-resp
  "👋 Салам! Я TapalakBot — умный помощник по Lalafo.kg\n\nРасскажите что ищете, и я помогу:\n• Разберусь в товаре (что лучше, на что смотреть)\n• Найду лучшие варианты на Lalafo\n• Проверю рыночные цены\n• Предупрежу о подвохах\n\nПросто напишите, что вам нужно 🔍")

(def ^:private thanks-resp "Пожалуйста! 😊 Если нужно найти что-то ещё — пишите.")
(def ^:private ok-resp     "👌 Если нужна помощь с поиском — пишите!")

(def fast-responses
  {"привет"        greeting-resp
   "салам"         greeting-resp
   "хай"           greeting-resp
   "здравствуйте"  greeting-resp
   "hello"         greeting-resp
   "hi"            greeting-resp
   "спасибо"       thanks-resp
   "спс"           thanks-resp
   "благодарю"     thanks-resp
   "thanks"        thanks-resp
   "ок"            ok-resp
   "окей"          ok-resp
   "ладно"         ok-resp
   "понял"         ok-resp})

;; ══════════════════════ COMMAND HANDLERS ══════════════════════

(defn- handle-start [{:keys [chat-id first-name]}]
  (tg/send-md chat-id
              (str "🔍 Привет, " first-name "!\n\n"
                   "Я **TapalakBot** — ищу товары на Lalafo.kg и помогаю с выбором.\n\n"
                   "Просто напиши что хочешь найти."))
  nil)

(defn- handle-help [{:keys [chat-id]}]
  (tg/send-md chat-id
              (str "**TapalakBot** — поиск на Lalafo.kg\n\n"
                   "Вот что я умею:\n"
                   "🔍 Находить товары с фильтрами по цене\n"
                   "💡 Консультировать по характеристикам\n"
                   "⚠️ Предупреждать о подозрительных объявлениях\n"
                   "📊 Сравнивать модели и бренды\n\n"
                   "Просто опиши что ищешь — я найду!"))
  nil)

;; ══════════════════════ STREAMING HANDLER ══════════════════════

(defn- edit-loop [chat-id msg-id buf edit-ch last-edit-ms throttle-ms min-chars]
  "Recursive edit loop — reads from channel and edits progressively."
  (let [msg (<!! edit-ch)]
    (when-not (or (nil? msg) (:done msg))
      (when (:delta msg)
        (.append buf (:delta msg)))
      (let [now (System/currentTimeMillis)]
        ;; Only edit if enough chars have accumulated
        (when (and (>= (- now @last-edit-ms) throttle-ms)
                   (> (.length buf) min-chars))
          (try
            (tg/edit-message chat-id msg-id (str buf) :parse-mode nil)
            (reset! last-edit-ms (System/currentTimeMillis))
            (catch Exception e
              (log/warn e :edit-fail msg-id)))))
      (recur chat-id msg-id buf edit-ch last-edit-ms throttle-ms min-chars))))

(defn- handle-agent-streaming [{:keys [chat-id user-id text]}]
  "Stream agent response to Telegram with progressive edits."
  (let [uid (str "tg-" user-id)
        bot @t/tapalakbot
        search-done? (atom false)
        resp-ch (chan)
        edit-ch (chan 16)]

    ;; 1. Send initial thinking indicator
    (tg/send-typing chat-id)
    (Thread/sleep 200)
    (let [status-resp (tg/send-message chat-id "🧠 Думаю..." :parse-mode nil)]
      (when-let [msg-id (some-> status-resp (get "result") (get "message_id"))]
        ;; 2. Start edit loop in background thread
        (let [edit-thread
              (Thread.
               (fn []
                 (try (edit-loop chat-id msg-id (StringBuilder.) edit-ch
                                 (atom 0) 500 2)
                      (catch Exception e
                        (log/warn e :edit-loop-error))))
               "tapalakbot-edit-loop")]

          (.start edit-thread)

          ;; 3. Call stream-agent
          (try
            (let [result (stream/stream-agent
                          (hc/get-session bot uid)
                          (map? bot)  ;; stream/stream-agent expects session
                          identity   ;; on-think
                          (fn [delta _]
                            (>!! edit-ch {:delta delta}))
                          (fn [chunk]
                            (>!! edit-ch {:delta chunk}))
                          (fn [token]
                            (>!! edit-ch {:delta token})))]
              ;; 4. Send done signal, wait for edit loop to finish
              (>!! edit-ch {:done true})
              (Thread/sleep 200)

              ;; 5. Send final HTML
              (let [safe-text (str/replace (str result) #"👉 Смотри\b" "🔗")
                    html (fmt/md->html safe-text)]
                (try
                  (tg/edit-message chat-id msg-id html :parse-mode "HTML")
                  (catch Exception e
                    (log/error e :final-edit-fail)
                    (tg/send-message chat-id html :parse-mode "HTML")))))

            (catch Exception e
              (log/error e :agent-streaming-error {:user-id uid})
              (>!! edit-ch {:done true})
              (tg/edit-message chat-id msg-id "❌ Произошла ошибка." :parse-mode nil))

            (finally
              (close! edit-ch)
              (close! resp-ch))))))))

(defn- handle-agent [{:keys [chat-id user-id text]}]
  "Handle agent request. Sends thinking indicator + progressive edits + final HTML."
  (let [uid (str "tg-" user-id)
        bot @t/tapalakbot
        thinking-msg-id (atom nil)]

    ;; Send thinking placeholder
    (let [msg (tg/send-message chat-id "💭 ..." :parse-mode nil)]
      (reset! thinking-msg-id (some-> msg (get "result") (get "message_id"))))

    ;; Run agent
    (try
      (let [result (hc/handle-message bot uid text)]
        (Thread/sleep 100)

        ;; Edit placeholder with final result
        (if-let [msg-id @thinking-msg-id]
          (let [safe-text (str/replace (str result) #"👉 Смотри\b" "🔗")
                html (fmt/md->html safe-text)]
            (try
              (tg/edit-message chat-id msg-id html :parse-mode "HTML")
              (catch Exception e
                (log/error e :final-edit-fail)
                (tg/send-message chat-id html :parse-mode "HTML"))))
          ;; No placeholder → send as new message
          (tg/send-md chat-id (str result))))

      (catch Exception e
        (log/error e :agent-error {:user-id uid})
        (if-let [msg-id @thinking-msg-id]
          (tg/edit-message chat-id msg-id "❌ Произошла ошибка." :parse-mode nil)
          (tg/send-message chat-id "❌ Произошла ошибка." :parse-mode nil))))))

;; ══════════════════════ BOT ══════════════════════

(def handler
  (let [std-handler (tg/make-handler
                     {:commands {"/start" #'handle-start
                                 "/help"  #'handle-help}
                      :fast-path fast-responses})]
    (fn [msg]
      (let [text (str/trim (:text msg))]
        (cond
          (str/starts-with? text "/")
          (std-handler msg)

          (get fast-responses (str/lower-case text))
          (std-handler msg)

          (not (str/blank? text))
          (handle-agent msg)

          :else nil)))))

(defn start-polling
  "Start Telegram long-polling loop."
  [& {:keys [interval-ms] :or {interval-ms 1500}}]
  (tg/poll-loop handler :interval-ms interval-ms))
