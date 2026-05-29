(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2. Simple: thinking indicator → agent → edit."
  (:require [tapalakbot.core :as t]
            [clj-harness.telegram :as tg]
            [clj-harness.telegram.format :as fmt]
            [clj-harness.core :as hc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private greeting-resp
  "👋 Салам! Я TapalakBot — умный помощник по Lalafo.kg\n\nРасскажите что ищете, и я помогу:\n• Разберусь в товаре\n• Найду лучшие варианты на Lalafo\n• Проверю рыночные цены\n\nПросто напишите, что вам нужно 🔍")

(def fast-responses
  {"привет"   greeting-resp "салам"   greeting-resp "хай"     greeting-resp
   "здравствуйте" greeting-resp "hello" greeting-resp "hi" greeting-resp
   "спасибо"  "Пожалуйста! 😊 Если нужно найти что-то ещё — пишите."
   "спс"      "Пожалуйста! 😊" "thanks" "You're welcome! 😊"
   "ок"       "👌" "окей" "👌" "ладно" "👌" "понял" "👌"})

(defn- handle-start [{:keys [chat-id first-name]}]
  (tg/send-md chat-id (str "🔍 Привет, " first-name "!\n\nЯ **TapalakBot** — ищу товары на Lalafo.kg и помогаю с выбором.\n\nПросто напиши что хочешь найти."))
  nil)

(defn- handle-help [{:keys [chat-id]}]
  (tg/send-md chat-id "**TapalakBot** — поиск на Lalafo.kg\n\n🔍 Поиск товаров\n💡 Консультации\n⚠️ Предупреждения о подозрительных объявлениях\n📊 Сравнение моделей\n\nПросто опиши что ищешь!")
  nil)

(defn- handle-agent [{:keys [chat-id user-id text]}]
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
        (if-let [msg-id @thinking-msg-id]
          (let [safe-text (str/replace (str result) #"👉 Смотри\b" "🔗")
                html (fmt/md->html safe-text)]
            (try
              (tg/edit-message chat-id msg-id html :parse-mode "HTML")
              (catch Exception e
                (log/error e :final-edit-fail)
                (tg/send-message chat-id html :parse-mode "HTML"))))
          (tg/send-md chat-id (str result))))
      (catch Exception e
        (log/error e :agent-error {:user-id uid})
        (if-let [msg-id @thinking-msg-id]
          (tg/edit-message chat-id msg-id "❌ Ошибка. Попробуйте ещё раз." :parse-mode nil)
          (tg/send-message chat-id "❌ Ошибка. Попробуйте ещё раз." :parse-mode nil))))))

(def handler
  (let [std-handler (tg/make-handler
                     {:commands {"/start" #'handle-start "/help" #'handle-help}
                      :fast-path fast-responses})]
    (fn [msg]
      (let [text (str/trim (:text msg))]
        (cond
          (str/starts-with? text "/") (std-handler msg)
          (get fast-responses (str/lower-case text)) (std-handler msg)
          (not (str/blank? text)) (handle-agent msg)
          :else nil)))))

(defn start-polling
  [& {:keys [interval-ms] :or {interval-ms 1500}}]
  (tg/poll-loop handler :interval-ms interval-ms))
