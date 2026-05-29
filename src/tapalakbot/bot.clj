(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2.

  Effect-driven: uses clj-harness effect system with event bus for status updates.
  Status message → progressive edits from events → final HTML."
  (:require [tapalakbot.core :as t]
            [clj-harness.telegram :as tg]
            [clj-harness.telegram.format :as fmt]
            [clj-harness.core :as hc]
            [clj-harness.effects :as fx]
            [clojure.core.async :refer [chan <!! >!! poll! close! sliding-buffer]]
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

;; ══════════════════════ EVENT-DRIVEN HANDLER ══════════════════════

(defn- handle-agent [{:keys [chat-id user-id text]}]
  "Handle agent request with event-driven status updates.
   Uses effect system's event bus for status messages (thinking, tool execution)."
  (let [uid (str "tg-" user-id)
        bot @t/tapalakbot
        ;; Create events channel
        events> (chan (sliding-buffer 64))
        ;; Track current status message for editing
        status-msg-id (atom nil)]

    ;; Send initial placeholder
    (let [status-resp (tg/send-message chat-id "💭 ..." :parse-mode nil)]
      (reset! status-msg-id (some-> status-resp (get "result") (get "message_id"))))

    ;; Subscribe to events for status updates
    (fx/subscribe-events events>
                         (fn [status]
                           (when-let [msg-id @status-msg-id]
                             (try
                               (tg/edit-message chat-id msg-id status :parse-mode nil)
                               (catch Exception e
                                 (log/warn e :status-edit-fail))))))

    ;; Run agent with events>
    (try
      (let [result (hc/handle-message bot uid text :events> events>)]
        ;; Close events channel
        (close! events>)
        ;; Small delay to let subscriber finish
        (Thread/sleep 100)

        ;; Final HTML edit
        (when-let [msg-id @status-msg-id]
          (let [safe-text (str/replace (str result) #"👉 Смотри\b" "🔗")
                html (fmt/md->html safe-text)]
            (try
              (tg/edit-message chat-id msg-id html :parse-mode "HTML")
              (catch Exception e
                (log/error e :final-edit-fail)
                ;; Fallback: send as new message
                (tg/send-message chat-id html :parse-mode "HTML"))))))

      (catch Exception e
        (log/error e :agent-error {:user-id uid})
        (close! events>)
        (when-let [msg-id @status-msg-id]
          (tg/edit-message chat-id msg-id "❌ Ошибка. Попробуйте ещё раз." :parse-mode nil))))))

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
