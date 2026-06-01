(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2.
  Uses progressive streaming for real-time text display.
  Button-based tracking UI via inline keyboards.

  Session safety:
  - Per-user lock prevents concurrent handler execution
  - Last-write-wins pending queue
  - /reset clears conversation history"
  (:require [tapalakbot.core :as t]
            [tapalakbot.monitor.client :as monitor]
            [tapalakbot.monitor.store :as store]
            [clj-harness.telegram :as tg]
            [clj-harness.telegram.format :as fmt]
            [clj-harness.core :as hc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]))

;; ══════════════════════ PER-USER LOCK + PENDING ══════════════════════

(def ^:private user-state
  "Map of user-id → {:lock atom, :pending atom}."
  (atom {}))

(defn- get-user-state [uid]
  (or (get @user-state uid)
      (let [s {:lock (atom :idle) :pending (atom nil)}]
        (swap! user-state assoc uid s)
        s)))

(defn- try-acquire! [uid]
  (let [{:keys [lock]} (get-user-state uid)]
    (compare-and-set! lock :idle :busy)))

(defn- release! [uid]
  (let [{:keys [lock]} (get-user-state uid)]
    (reset! lock :idle)))

(defn- store-pending! [uid msg]
  (let [{:keys [pending]} (get-user-state uid)]
    (reset! pending msg)))

(defn- take-pending! [uid]
  (let [{:keys [pending]} (get-user-state uid)]
    (let [m @pending]
      (reset! pending nil)
      m)))

;; ══════════════════════ INLINE KEYBOARD HELPERS ══════════════════════

(defn- inline-keyboard
  "Build InlineKeyboardMarkup from rows of buttons.
   Each row is a vector of [{:text \"...\" :callback_data \"...\"} ...]"
  [rows]
  {"inline_keyboard"
   (mapv (fn [row]
           (mapv (fn [{:keys [text callback_data]}]
                   {"text" text "callback_data" callback_data})
                 row))
         rows)})

(defn- answer-callback
  "Answer callback query to remove loading spinner."
  [callback-id & {:keys [text]}]
  (try
    (let [body (cond-> {"callback_query_id" callback-id}
                 text (assoc "text" text))]
      (@#'tg/call "answerCallbackQuery" body))
    (catch Exception _ nil)))

(defn- edit-with-buttons
  "Edit message to show new text + inline keyboard."
  [chat-id msg-id text keyboard]
  (try
    (tg/edit-message chat-id msg-id text :reply_markup keyboard)
    (catch Exception e
      (log/warn e :edit-with-buttons-fail))))

(defn- send-with-buttons
  "Send message with inline keyboard."
  [chat-id text keyboard]
  (try
    (tg/send-md chat-id text :reply_markup keyboard)
    (catch Exception e
      (log/warn e :send-with-buttons-fail))))

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

;; ══════════════════════ TRACKING — STEP 1: CATEGORY PICK ══════════════════════

(def ^:private track-categories
  "Category buttons for tracking."
  [["📱 Телефоны" "track_cat:телефон"]
   ["💻 Ноутбуки" "track_cat:ноутбук"]
   ["🎮 Приставки" "track_cat:приставка"]
   ["📺 Телевизоры" "track_cat:телевизор"]
   ["🎧 Наушники" "track_cat:наушники"]
   ["⌚ Часы" "track_cat:часы"]
   ["🚲 Велосипеды" "track_cat:велосипед"]
   ["✏️ Свой запрос" "track_cat:custom"]])

(def ^:private track-price-buttons
  "Price range buttons."
  [["До 10 000 ₽" "track_price:10000"]
   ["До 20 000 ₽" "track_price:20000"]
   ["До 30 000 ₽" "track_price:30000"]
   ["До 50 000 ₽" "track_price:50000"]
   ["До 100 000 ₽" "track_price:100000"]
   ["💰 Любая цена" "track_price:any"]])

(defn- show-track-categories
  "Show category selection keyboard."
  [chat-id msg-id]
  (let [keyboard (inline-keyboard
                  (mapv (fn [[text data]] [{:text text :callback_data data}])
                        track-categories))]
    (if msg-id
      (edit-with-buttons chat-id msg-id
                         "🔍 *Что хотите отслеживать?*\n\nВыберите категорию или напишите свой запрос:"
                         keyboard)
      (send-with-buttons chat-id
                         "🔍 *Что хотите отслеживать?*\n\nВыберите категорию или напишите свой запрос:"
                         keyboard))))

(defn- show-track-price
  "Show price range selection keyboard."
  [chat-id msg-id query]
  (let [keyboard (inline-keyboard
                  (mapv (fn [[text data]]
                          [{:text text :callback_data (str data ":" query)}])
                        track-price-buttons))]
    (edit-with-buttons chat-id msg-id
                       (str "🔍 *Отслеживание:* «" query "»\n\n"
                            "💰 Какой максимальный бюджет?")
                       keyboard)))

(defn- confirm-track-created
  "Show confirmation after track is created."
  [chat-id msg-id title price-max]
  (edit-with-buttons chat-id msg-id
                     (str "✅ *Фильтр создан!*\n\n"
                          "🔍 «" title "»"
                          (when price-max
                            (str "\n💰 Бюджет: до " (format "%,d" price-max) " ₽"))
                          "\n\n🔔 Уведомлю когда появятся новые объявления.")
                     (inline-keyboard [[{:text "📋 Мои фильтры" :callback_data "track_list"}
                                        {:text "➕ Ещё фильтр" :callback_data "track_new"}]])))

;; ══════════════════════ TRACKING — STEP 2: LIST / DELETE ══════════════════════

(defn- show-track-list
  "Show user's tracking filters with delete buttons."
  [chat-id user-id]
  (let [uid (str "tg-" user-id)
        tracks (store/get-user-tracks uid)]
    (if (empty? tracks)
      (send-with-buttons chat-id
                         "📋 *У вас нет активных фильтров.*\n\nСоздайте первый:"
                         (inline-keyboard [[{:text "➕ Создать фильтр" :callback_data "track_new"}]]))
      (let [track-rows (mapv (fn [t]
                               (let [title (if (> (count (:title t)) 30)
                                             (str (subs (:title t) 0 27) "...")
                                             (:title t))
                                     price (when-let [p (:price_max t)]
                                             (str " до " (format "%,d" p) "₽"))]
                                 [{:text (str "🔍 " title (or price ""))
                                   :callback_data (str "track_info:" (:id t))}
                                  {:text "❌" :callback_data (str "track_del_ask:" (:id t))}]))
                             tracks)
            all-rows (conj track-rows
                           [{:text "➕ Новый фильтр" :callback_data "track_new"}
                            {:text "🗑 Удалить все" :callback_data "track_del_all"}])]
        (send-with-buttons chat-id
                           (str "📋 *Ваши фильтры* (" (count tracks) ")\n\n"
                                "Нажмите ❌ чтобы удалить")
                           (inline-keyboard all-rows))))))

(defn- confirm-delete-track
  "Ask for delete confirmation."
  [chat-id msg-id track-id title]
  (edit-with-buttons chat-id msg-id
                     (str "🗑 *Удалить фильтр?*\n\n«" title "»")
                     (inline-keyboard
                      [[{:text "Да, удалить" :callback_data (str "track_del_yes:" track-id)}
                        {:text "Отмена" :callback_data "track_list"}]])))

(defn- confirm-delete-all
  "Ask for delete all confirmation."
  [chat-id msg-id count]
  (edit-with-buttons chat-id msg-id
                     (str "🗑 *Удалить все фильтры?*\n\nВсего: " count)
                     (inline-keyboard
                      [[{:text "Да, удалить все" :callback_data "track_del_all_yes"}
                        {:text "Отмена" :callback_data "track_list"}]])))

;; ══════════════════════ CALLBACK ROUTER ══════════════════════

(defn- handle-callback
  "Handle inline keyboard callback queries."
  [{:keys [callback-id data user-id chat-id msg-id]}]
  (log/info :callback :data data :user user-id)
  (cond
    ;; === TRACKING: Category selection ===
    (= data "track_new")
    (do (answer-callback callback-id)
        (show-track-categories chat-id msg-id))

    (re-matches #"track_cat:(.+)" data)
    (let [[_ cat] (re-matches #"track_cat:(.+)" data)]
      (answer-callback callback-id)
      (if (= cat "custom")
        ;; Custom query — ask user to type
        (do (edit-with-buttons chat-id msg-id
                               "✏️ *Напишите что ищете:*\n\nНапример: «iPhone 13», «MacBook Air», «PS5»"
                               (inline-keyboard [[{:text "← Назад" :callback_data "track_new"}]]))
            ;; Mark user as waiting for custom query
            (swap! user-state assoc-in [(str "tg-" user-id) :waiting-track] true))
        ;; Category selected — show price buttons
        (show-track-price chat-id msg-id cat)))

    ;; === TRACKING: Price selection ===
    (re-matches #"track_price:(.+):(.+)" data)
    (let [[_ price-str query] (re-matches #"track_price:(.+):(.+)" data)
          price-max (when-not (= price-str "any") (Long/parseLong price-str))
          uid (str "tg-" user-id)
          track (store/create-track! {:user-id uid
                                      :title (if price-max
                                               (str query " до " (format "%,d" price-max))
                                               query)
                                      :queries [query]
                                      :price-max price-max})]
      (answer-callback callback-id)
      (log/info :track-created :user uid :track-id (:id track) :query query)
      (confirm-track-created chat-id msg-id query price-max))

    ;; === TRACKING: List ===
    (= data "track_list")
    (do (answer-callback callback-id)
        (show-track-list chat-id user-id))

    ;; === TRACKING: Info ===
    (re-matches #"track_info:(\d+)" data)
    (let [[_ id-str] (re-matches #"track_info:(\d+)" data)
          track (store/get-track (Long/parseLong id-str))]
      (answer-callback callback-id)
      (if track
        (edit-with-buttons chat-id msg-id
                           (str "🔍 *Фильтр #" (:id track) "*\n\n"
                                "📝 " (:title track) "\n"
                                (when-let [p (:price_max track)]
                                  (str "💰 Бюджет: до " (format "%,d" p) " ₽\n"))
                                "🔔 Уведомлений: " (:notify_count track) "\n"
                                "📅 Создан: " (:created_at track))
                           (inline-keyboard
                            [[{:text "❌ Удалить" :callback_data (str "track_del_ask:" (:id track))}
                              {:text "← Назад" :callback_data "track_list"}]]))
        (answer-callback callback-id :text "Фильтр не найден")))

    ;; === TRACKING: Delete confirmation ===
    (re-matches #"track_del_ask:(\d+)" data)
    (let [[_ id-str] (re-matches #"track_del_ask:(\d+)" data)
          track (store/get-track (Long/parseLong id-str))]
      (answer-callback callback-id)
      (when track
        (confirm-delete-track chat-id msg-id (:id track) (:title track))))

    ;; === TRACKING: Delete yes ===
    (re-matches #"track_del_yes:(\d+)" data)
    (let [[_ id-str] (re-matches #"track_del_yes:(\d+)" data)
          track-id (Long/parseLong id-str)
          track (store/get-track track-id)]
      (answer-callback callback-id)
      (when (and track (= (:user_id track) (str "tg-" user-id)))
        (store/delete-track! track-id)
        (log/info :track-deleted :user user-id :track-id track-id)
        (edit-with-buttons chat-id msg-id
                           (str "🗑 *Удалено:* «" (:title track) "»")
                           (inline-keyboard [[{:text "📋 Мои фильтры" :callback_data "track_list"}
                                              {:text "➕ Новый" :callback_data "track_new"}]]))))

    ;; === TRACKING: Delete all confirmation ===
    (= data "track_del_all")
    (let [uid (str "tg-" user-id)
          tracks (store/get-user-tracks uid)]
      (answer-callback callback-id)
      (when (seq tracks)
        (confirm-delete-all chat-id msg-id (count tracks))))

    ;; === TRACKING: Delete all yes ===
    (= data "track_del_all_yes")
    (let [uid (str "tg-" user-id)
          n (store/delete-user-tracks! uid)]
      (answer-callback callback-id)
      (edit-with-buttons chat-id msg-id
                         (str "🗑 *Удалено фильтров:* " n)
                         (inline-keyboard [[{:text "➕ Создать фильтр" :callback_data "track_new"}]])))

    ;; Unknown callback
    :else
    (answer-callback callback-id)))

;; ══════════════════════ HANDLERS ══════════════════════

(defn- handle-start [{:keys [chat-id user-id first-name]}]
  (hc/reset-session! @t/tapalakbot (str "tg-" user-id))
  (let [stats (monitor/fetch-categories)
        cats (:categories stats)
        total-items (reduce + 0 (map :item_count cats))
        cat-count (count cats)]
    (send-with-buttons
     chat-id
     (str "👋 Салам, " first-name "!\n\n"
          "Я *TapalakBot* — умный помощник по покупкам на Lalafo.kg 🇰🇬\n\n"
          "━━━━━━━━━━━━━━━━━━━━\n"
          "📊 *Рынок сейчас:*\n"
          "• " cat-count " категорий отслеживается\n"
          "• " total-items " товаров в базе\n"
          "━━━━━━━━━━━━━━━━━━━━\n\n"
          "Просто напиши что ищешь! 🔍")
     (inline-keyboard
      [[{:text "🔔 Отслеживать" :callback_data "track_new"}
        {:text "📋 Фильтры" :callback_data "track_list"}]
       [{:text "📊 Рынок" :callback_data "market_stats"}]])))
  nil)

(defn- handle-reset [{:keys [chat-id user-id]}]
  (let [uid (str "tg-" user-id)]
    (hc/reset-session! @t/tapalakbot uid)
    (release! uid)
    (store-pending! uid nil)
    (tg/send-md chat-id "🗑️ История очищена. Начнём заново!"))
  nil)

(defn- handle-help [{:keys [chat-id]}]
  (send-with-buttons
   chat-id
   "*TapalakBot* — поиск на Lalafo.kg\n\n🔍 Поиск товаров\n🔔 Отслеживание новых объявлений\n📊 Рыночные цены\n\nПросто опиши что ищешь!"
   (inline-keyboard
    [[{:text "🔔 Отслеживать" :callback_data "track_new"}
      {:text "📋 Фильтры" :callback_data "track_list"}]]))
  nil)

(defn- handle-prices
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

(defn- strip-tables [text]
  (-> text
      (str/replace #"\|[-:| ]+\|" "")
      (str/replace (re-pattern "\\|[^\\n]*\\|") "")))

(defn- process-agent-message
  "Process a single agent message with streaming. Returns nil."
  [{:keys [chat-id user-id text]}]
  (let [uid (str "tg-" user-id)
        bot @t/tapalakbot
        thinking-msg-id (atom nil)
        buf (StringBuilder.)
        last-edit (atom 0)
        last-typing (atom 0)
        phase (atom :initial)]
    (let [msg (tg/send-message chat-id "💭 ..." :parse-mode nil)]
      (reset! thinking-msg-id (some-> msg (get "result") (get "message_id"))))
    (try
      (let [stream-cb (fn [delta]
                        (.append buf delta)
                        (log/info :stream-delta :len (count delta) :total (.length buf))
                        (reset! phase :streaming)
                        (let [now (System/currentTimeMillis)]
                          (when (> (- now @last-typing) 4000)
                            (reset! last-typing now)
                            (try (tg/send-typing chat-id) (catch Exception _))))
                        (let [now (System/currentTimeMillis)
                              elapsed (- now @last-edit)
                              msg-id @thinking-msg-id]
                          (when (and (> elapsed 1500)
                                     (> (.length buf) 30)
                                     msg-id)
                            (reset! last-edit now)
                            (try
                              (let [preview (strip-tables (.toString buf))
                                    html (fmt/md->html preview)]
                                (tg/edit-message chat-id msg-id html :parse-mode "HTML"))
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
          (tg/send-message chat-id "❌ Ошибка. Попробуйте ещё раз." :parse-mode nil))))))

(defn- handle-agent
  "Handle agent message with per-user lock and pending queue."
  [{:keys [chat-id user-id] :as msg}]
  (let [uid (str "tg-" user-id)]
    (if-not (try-acquire! uid)
      (do
        (log/info :msg-queued :user-id uid :text (:text msg))
        (store-pending! uid msg)
        (try (tg/send-message chat-id "⏳ Обрабатываю предыдущий запрос..." :parse-mode nil)
             (catch Exception _))
        nil)
      (try
        (loop [current msg]
          (process-agent-message current)
          (if-let [next-msg (take-pending! uid)]
            (do
              (log/info :process-pending :user-id uid :text (:text next-msg))
              (recur next-msg))
            nil))
        (finally
          (release! uid))))))

(defn- handle-market-stats [{:keys [chat-id]}]
  (let [cats (monitor/fetch-categories)
        stats (:categories cats)]
    (answer-callback nil) ;; no callback id for this
    (if (seq stats)
      (let [lines (mapv (fn [c]
                          (str "• *" (:name c) "* — "
                               (:item_count c) " об\\'яв"))
                        stats)]
        (tg/send-md chat-id (str "📊 *Рынок Lalafo.kg*\n\n"
                                 (str/join "\n" lines))))
      (tg/send-md chat-id "⚠️ Нет данных"))
    nil))

;; ══════════════════════ CUSTOM POLL LOOP ══════════════════════

(defn- parse-update-extended
  "Parse update including callback_query."
  [update]
  (if-let [cb (get update "callback_query")]
    (let [from (get cb "from")
          msg (get cb "message")]
      {:callback-id (get cb "id")
       :data (get cb "data")
       :user-id (get from "id")
       :chat-id (get-in msg ["chat" "id"])
       :msg-id (get msg "message_id")})
    ;; Regular message
    (when-let [msg (or (get update "message") (get update "edited_message"))]
      (let [chat (get msg "chat")
            user (get msg "from")
            loc (get msg "location")]
        (cond->
         {:chat-id    (get chat "id")
          :user-id    (get user "id")
          :first-name (get user "first_name" "друг")
          :text       (get msg "text")
          :message-id (get msg "message_id")}
          loc
          (assoc :location {:lat (get loc "latitude")
                            :lon (get loc "longitude")}))))))

(defn- extended-handler
  "Handler that processes both messages and callback queries."
  [parsed]
  (cond
    ;; Callback query (inline keyboard button pressed)
    (:callback-id parsed)
    (handle-callback parsed)

    ;; Custom track query (user typing after category = custom)
    (let [uid (str "tg-" (:user-id parsed))
          waiting? (get-in @user-state [uid :waiting-track])]
      (and waiting? (:text parsed)))
    (let [uid (str "tg-" (:user-id parsed))
          text (str/trim (:text parsed))
          chat-id (:chat-id parsed)]
      ;; Clear waiting state
      (swap! user-state assoc-in [uid :waiting-track] nil)
      ;; Show price selection for custom query
      (show-track-price chat-id nil text))

    ;; Regular text message
    (:text parsed)
    (let [text (str/trim (:text parsed))]
      (cond
        (str/starts-with? text "/")
        (let [cmd (first (str/split text #"\s+"))]
          (case cmd
            "/start"  (handle-start parsed)
            "/help"   (handle-help parsed)
            "/prices" (handle-prices parsed)
            "/reset"  (handle-reset parsed)
            "/track"  (show-track-categories (:chat-id parsed) nil)
            "/tracks" (show-track-list (:chat-id parsed) (:user-id parsed))
            nil))

        (get fast-responses (str/lower-case text))
        (tg/send-md (:chat-id parsed) (get fast-responses (str/lower-case text)))

        (not (str/blank? text))
        (handle-agent parsed)

        :else nil))

    :else nil))

(defn start-polling
  "Start custom polling loop that handles both messages and callback queries."
  [& {:keys [interval-ms] :or {interval-ms 1500}}]
  (let [init (tg/get-updates :offset -1 :limit 1 :timeout 1)
        offset (atom (if-let [u (first (get init "result" []))]
                       (inc (get u "update_id")) 0))]
    (log/info :poll-start :offset @offset :mode :extended)
    (while true
      (try
        (let [resp (tg/get-updates :offset @offset)]
          (doseq [u (get resp "result" [])]
            (try
              (when-let [parsed (parse-update-extended u)]
                (extended-handler parsed))
              (catch Exception e (log/error e :handler-error)))
            (reset! offset (inc (get u "update_id")))))
        (catch Exception e (log/error e :poll-error)))
      (Thread/sleep interval-ms))))
