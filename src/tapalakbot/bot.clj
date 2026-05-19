(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2.

  Streaming: real-time LLM output via clj-harness.stream/stream-agent.
  Single status message → progressive edits → final HTML."
  (:require [tapalakbot.core :as t]
            [clj-harness.telegram :as tg]
            [clj-harness.telegram.format :as fmt]
            [clj-harness.core :as hc]
            [clj-harness.stream :as stream]
            [clj-harness.heap :as heap]
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
                   "Просто напиши что хочешь найти.")))

(defn- handle-help [{:keys [chat-id]}]
  (tg/send-md chat-id
              (str "**TapalakBot** — поиск на Lalafo.kg\n\n"
                   "Вот что я умею:\n"
                   "🔍 Находить товары с фильтрами по цене\n"
                   "💡 Консультировать по характеристикам\n"
                   "⚠️ Предупреждать о подозрительных объявлениях\n"
                   "📊 Сравнивать модели и бренды\n\n"
                   "Просто опиши что ищешь — я найду!")))

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
        tools t/tools
        tool-map (into {} (map (fn [t] [(:name t) t]) tools))
        tool-schemas (mapv (fn [t]
                             {"type" "function"
                              "function" {"name" (:name t)
                                          "description" (:description t "")
                                          "parameters" (or (:schema t) {"type" "object" "properties" {}})}})
                           tools)]
    ;; Send placeholder
    (let [status-resp (tg/send-message chat-id "💭 ..." :parse-mode nil)]
      (if-let [msg-id (some-> status-resp (get "result") (get "message_id"))]
        (let [buf (StringBuilder.)
              edit-ch (chan 512)
              last-edit-ms (atom (System/currentTimeMillis))]

          ;; Consumer Thread
          (.start
           (Thread.
            (reify Runnable
              (run [_]
                (try (edit-loop chat-id msg-id buf edit-ch last-edit-ms 250 5)
                     (catch Exception e
                       (log/error e :edit-thread-error)))))
            (str "tg-edit-" user-id)))

          (let [stream-cb (fn [text] (>!! edit-ch {:delta text}))]
            (try
              ;; Build session + run streaming agent
              (let [session (hc/get-or-create-session bot uid)
                    _ (hc/session-add! session "user" text)
                    ;; Session-scoped heap for tool result storage
                    session-heap (or (get-in @session ["data" "heap"])
                                     (let [h (heap/create-heap)]
                                       (swap! session assoc-in ["data" "heap"] h)
                                       h))
                    extra-context (when-let [hook (:pre-hook bot)]
                                    (hook uid text session))
                    base-prompt (str (-> bot :config :prompt)
                                     (when extra-context (str "\n\n" extra-context)))
                    history (get @session "messages" [])
                    ;; Only keep user + assistant messages (strip tool_calls to avoid LLM confusion)
                    clean-msgs (keep (fn [m]
                                       (case (get m "role")
                                         "user" (select-keys m ["role" "content"])
                                         "assistant" (when (get m "content")  ;; skip tool-only decisions
                                                       (select-keys m ["role" "content"]))
                                         nil))
                                     history)
                    compacted (hc/compact-history bot clean-msgs)
                    msgs (vec (cons {"role" "system" "content" base-prompt}
                                    (take-last 20 compacted)))
                    result (stream/stream-agent
                            :model :deepseek-v4
                            :messages msgs
                            :tool-map tool-map
                            :tool-schemas tool-schemas
                            :stream-cb stream-cb
                            :provider :deepseek
                            :max-turns 8
                            :max-tokens 8000
                            :heap session-heap)
                    result (or result "⚠️ Не удалось получить ответ.")]

                ;; Signal done
                (>!! edit-ch {:done true})
                (Thread/sleep 500) ;; let consumer finish

                ;; Final HTML edit
                (let [safe-text (str/replace (str buf) #"👉 Смотреть\b" "🔗")
                      html (fmt/md->html safe-text)]
                  (try
                    (tg/edit-message chat-id msg-id html :parse-mode "HTML")
                    (catch Exception e
                      (log/error e :final-edit-fail)
                      (tg/send-message chat-id html :parse-mode "HTML"))))

                ;; Save to session
                (hc/session-add! session "assistant" result)
                (when-let [save-fn (:on-save bot)]
                  (save-fn uid session))
                ;; GC expired heap entries
                (heap/gc! session-heap))

              (catch Exception e
                (log/error e :stream-handler-error {:user-id uid})
                (>!! edit-ch {:done true})
                (tg/edit-message chat-id msg-id "❌ Ошибка. Попробуйте ещё раз." :parse-mode nil)))))

        ;; No message_id — fallback
        (let [result (t/ask uid text)]
          (tg/send-message chat-id (fmt/md->html result) :parse-mode "HTML"))))))

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
          (handle-agent-streaming msg)

          :else nil)))))

(defn start-polling
  "Start Telegram long-polling loop."
  [& {:keys [interval-ms] :or {interval-ms 1500}}]
  (tg/poll-loop handler :interval-ms interval-ms))
