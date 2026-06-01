(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2.
  Uses progressive streaming for real-time text display.

  Session safety:
  - Per-user lock prevents concurrent handler execution
  - If user sends a new message while bot is busy, the queued msg is skipped
    (bot will respond to the latest message instead)
  - /reset clears conversation history"
  (:require [tapalakbot.core :as t]
            [tapalakbot.monitor.client :as monitor]
            [clj-harness.telegram :as tg]
            [clj-harness.telegram.format :as fmt]
            [clj-harness.core :as hc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ══════════════════════ PER-USER LOCK ══════════════════════

(def ^:private user-locks
  "Map of user-id → atom holding :idle or :busy.
   Prevents concurrent message processing per user."
  (atom {}))

(defn- get-lock [uid]
  (or (get @user-locks uid)
      (let [lock (atom :idle)]
        (swap! user-locks assoc uid lock)
        lock)))

(defn- try-acquire! [uid]
  (let [lock (get-lock uid)]
    (compare-and-set! lock :idle :busy)))

(defn- release! [uid]
  (when-let [lock (get @user-locks uid)]
    (reset! lock :idle)))

;; ══════════════════════ RESPONSES ══════════════════════

(def ^:private greeting-resp
  "👋 Салам! Я TapalakBot — умный помощник по Lalafo.kg

Расскажите что ищете, и я помогу:
• Разберусь в товаре
• Найду лучшие варианты на Lalafo
• Проверю рыночные цены

Просто напишите, что вам нужно 🔍")

(def fast-responses
  {"привет"   greeting-resp "салам"   greeting-resp "хай"     greeting-resp
   "здравствуйте" greeting-resp "hello" greeting-resp "hi" greeting-resp
   "спасибо"  "Пожалуйста! 😊 Если нужно найти что-то ещё — пишите."
   "спс"      "Пожалуйста! 😊" "thanks" "You're welcome! 😊"
   "ок"       "👌" "окей" "👌" "ладно" "👌" "понял" "👌"})

;; ══════════════════════ HANDLERS ══════════════════════

(defn- handle-start [{:keys [chat-id user-id first-name]}]
  ;; Reset session on /start
  (hc/reset-session! @t/tapalakbot (str "tg-" user-id))
  (let [stats (monitor/fetch-categories)
        cats (:categories stats)
        total-items (reduce + 0 (map :item_count cats))
        cat-count (count cats)
        greeting (str "👋 Салам, " first-name "!\n\n"
                      "Я **TapalakBot** — умный помощник по покупкам на Lalafo.kg 🇰🇬\n\n"
                      "━━━━━━━━━━━━━━━━━━━━\n"
                      "📊 **Рынок сейчас:**\n"
                      "• " cat-count " категорий отслеживается\n"
                      "• " total-items " товаров в базе\n"
                      "━━━━━━━━━━━━━━━━━━━━\n\n"
                      "**Что я умею:**\n"
                      "🔍 Искать товары по описанию\n"
                      "💰 Показывать рыночные цены\n"
                      "📋 Сравнивать варианты\n"
                      "⚠️ Предупреждать о подозрительных ценах\n\n"
                      "**Попробуй:**\n"
                      "• \"iPhone 13 до 30000\" — поиск с бюджетом\n"
                      "• \"Стоит ли брать б/у MacBook?\" — совет\n"
                      "• \"Samsung или Xiaomi?\" — сравнение\n\n"
                      "Просто напиши что ищешь! 🔍")
        keyboard (tg/reset-keyboard)]
    (tg/send-md chat-id greeting :reply_markup keyboard))
  nil)

(defn- handle-reset [{:keys [chat-id user-id]}]
  (let [uid (str "tg-" user-id)]
    (hc/reset-session! @t/tapalakbot uid)
    (release! uid)
    (tg/send-md chat-id "🗑️ История очищена. Начнём заново!"))
  nil)

(defn- handle-help [{:keys [chat-id]}]
  (tg/send-md chat-id (str "**TapalakBot** — поиск на Lalafo.kg\n\n"
                           "🔍 Поиск товаров\n"
                           "📊 Рыночные цены (/prices)\n"
                           "💡 Консультации\n"
                           "🗑️ /reset — очистить историю\n"
                           "⚠️ Предупреждения о подозрительных объявлениях\n\n"
                           "Просто опиши что ищешь!"))
  nil)

(defn- handle-prices
  "Handle /prices command — show market overview or search."
  [{:keys [chat-id text]}]
  (let [parts (str/split text #"\s+" 2)
        query (when (> (count parts) 1) (str/trim (second parts)))]
    (if (str/blank? query)
      (let [cats (monitor/fetch-categories)
            stats (:categories cats)]
        (if (seq stats)
          (let [lines (concat
                       ["📊 *Рынок Lalafo.kg*\n"]
                       (map (fn [c]
                              (str "• *" (:name c) "* — "
                                   (:item_count c) " объявлений, "
                                   (when-let [p (:avg_price c)]
                                     (str "ср. " (format "%,.0f" (double p)) " сом"))))
                            stats))]
            (tg/send-md chat-id (str/join "\n" lines)))
          (tg/send-md chat-id "⚠️ Мониторинг пока не запущен.")))
      (let [results (monitor/search-items query)]
        (if (and results (pos? (:count results)))
          (tg/send-md chat-id (monitor/format-search-results results))
          (tg/send-md chat-id (str "🔍 Ничего не найдено по запросу «" query "»")))))))

(defn- strip-tables
  "Strip markdown tables from text."
  [text]
  (-> text
      (str/replace #"\|[-:| ]+\|" "")
      (str/replace (re-pattern "\\|[^\\n]*\\|") "")))

(defn- handle-agent [{:keys [chat-id user-id text]}]
  (let [uid (str "tg-" user-id)]
    ;; Per-user lock: skip if bot is already processing for this user
    (if-not (try-acquire! uid)
      (do
        (log/info :skip-queued :user-id uid)
        (tg/send-message chat-id "⏳ Подождите — обрабатываю предыдущий запрос..." :parse-mode nil)
        nil)
      ;; Process message
      (let [bot @t/tapalakbot
            thinking-msg-id (atom nil)
            buf (StringBuilder.)
            last-edit (atom 0)
            last-typing (atom 0)
            phase (atom :initial)]
        ;; Send thinking placeholder
        (let [msg (tg/send-message chat-id "💭 ..." :parse-mode nil)]
          (reset! thinking-msg-id (some-> msg (get "result") (get "message_id"))))
        ;; Run agent with streaming
        (try
          (let [stream-cb (fn [delta]
                            (.append buf delta)
                            (log/info :stream-delta :len (count delta) :total (.length buf))
                            (reset! phase :streaming)
                            ;; Send typing indicator every 4s
                            (let [now (System/currentTimeMillis)]
                              (when (> (- now @last-typing) 4000)
                                (reset! last-typing now)
                                (try (tg/send-typing chat-id) (catch Exception _))))
                            (let [now (System/currentTimeMillis)
                                  elapsed (- now @last-edit)
                                  msg-id @thinking-msg-id]
                              (log/info :stream-check :elapsed elapsed :buf-len (.length buf) :phase @phase :msg-id msg-id)
                              (when (and (> elapsed 1500)
                                         (> (.length buf) 30)
                                         msg-id)
                                (reset! last-edit now)
                                (log/info :stream-edit-triggered)
                                (try
                                  (let [preview (strip-tables (.toString buf))
                                        html (fmt/md->html preview)]
                                    (tg/edit-message chat-id msg-id html :parse-mode "HTML")
                                    (log/info :stream-edit-success))
                                  (catch Exception e
                                    (log/warn e :stream-edit-fail))))))
                status-cb (fn [status]
                            (reset! phase :tool)
                            (.setLength buf 0)
                            (when-let [msg-id @thinking-msg-id]
                              (try
                                (tg/edit-message chat-id msg-id status :parse-mode nil)
                                (catch Exception e
                                  (log/warn e :status-edit-fail)))))
                result (hc/handle-message-stream! bot uid text stream-cb :status-cb status-cb)]
            (reset! phase :done)
            (if-let [msg-id @thinking-msg-id]
              (let [safe-text (-> (str result)
                                  (str/replace #"👉 Смотри\b" "🔗")
                                  strip-tables)
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
              (tg/send-message chat-id "❌ Ошибка. Попробуйте ещё раз." :parse-mode nil)))
          (finally
            (release! uid)))))))

;; ══════════════════════ ROUTER ══════════════════════

(def handler
  (let [std-handler (tg/make-handler
                     {:commands {"/start" #'handle-start
                                 "/help" #'handle-help
                                 "/prices" #'handle-prices
                                 "/reset" #'handle-reset}
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
