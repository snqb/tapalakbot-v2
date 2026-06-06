(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2.
  Uses progressive streaming for real-time text display.
  Button-based tracking UI via inline keyboards.
  Persistent menu: [🔄 Новый диалог] [🔔 Отслеживание]"
  (:require [tapalakbot.core :as t]
            [tapalakbot.monitor.client :as monitor]
            [tapalakbot.monitor.store :as store]
            [tapalakbot.monitor.tracker :as tracker]
            [tapalakbot.query-builder :as qb]
            [clj-harness.telegram :as tg]
            [clj-harness.telegram.format :as fmt]
            [clj-harness.core :as hc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ══════════════════════ THREAD POOL ══════════════════════

(def ^:private handler-pool
  "Fixed thread pool for handler futures — prevents thread leak."
  (java.util.concurrent.Executors/newFixedThreadPool
   (.availableProcessors (Runtime/getRuntime))))

(defn- handler-future
  "Submit work to the handler pool instead of bare future."
  [f]
  (.submit handler-pool
           (reify java.util.concurrent.Callable
             (call [_] (try (f) (catch Exception e (log/error e :handler-error)))))))

;; ══════════════════════ PER-USER LOCK + PENDING ══════════════════════

(def ^:private user-state
  "Map of user-id → {:lock atom, :pending atom, :last-seen atom}."
  (atom {}))

(defn- get-user-state [uid]
  (or (get @user-state uid)
      (let [s {:lock (atom :idle) :pending (atom nil) :last-seen (atom (System/currentTimeMillis))}]
        (swap! user-state assoc uid s)
        s)))

(defn- cleanup-stale-users!
  "Remove user states inactive for >30 minutes to prevent memory leak."
  []
  (let [now (System/currentTimeMillis)
        stale-ids (->> @user-state
                       (filter (fn [[_ v]] (> (- now @(:last-seen v)) 1800000)))
                       (mapv first))]
    (when (seq stale-ids)
      (doseq [uid stale-ids]
        (swap! user-state dissoc uid)))))

(defn- try-acquire! [uid]
  (let [{:keys [lock] :as state} (get-user-state uid)]
    (reset! (:last-seen state) (System/currentTimeMillis))
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
  "Build InlineKeyboardMarkup from rows of buttons."
  [rows]
  {"inline_keyboard"
   (mapv (fn [row]
           (mapv (fn [{:keys [text callback_data]}]
                   {"text" text "callback_data" callback_data})
                 row))
         rows)})

(defn- answer-callback
  "Answer callback query to remove loading spinner.
   Uses #'tg/call (private) because clj-harness has no public answerCallbackQuery."
  [callback-id & {:keys [text]}]
  (when callback-id
    (try
      (let [body (cond-> {"callback_query_id" callback-id}
                   text (assoc "text" text))]
        (@#'tg/call "answerCallbackQuery" body))
      (catch Exception _ nil))))

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

;; ══════════════════════ PERSISTENT MENU ══════════════════════

(defn- persistent-menu
  "Create ReplyKeyboardMarkup with persistent buttons."
  []
  {"keyboard"
   [[{"text" "🔄 Новый диалог"} {"text" "🔔 Отслеживание"}]]
   "resize_keyboard" true
   "one_time" false})

(defn- send-menu!
  "Send message with persistent menu."
  [chat-id text]
  (tg/send-md chat-id text :reply_markup (persistent-menu)))

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

;; ══════════════════════ TRACKING — CONTEXTUAL BUTTON ══════════════════════

(defn- format-interval [hours]
  (case (int hours)
    3 "каждые 3ч"
    24 "каждые 24ч"
    72 "каждые 72ч"
    (str "каждые " hours "ч")))

(def ^:private pending-track-queries
  "Map of user-id → query string (for contextual tracking button)."
  (atom {}))

(defn- track-context-button
  "Create inline button for tracking after search results.
   Stores query in atom, uses short ID in callback_data."
  [user-id query]
  (let [short-id (str (java.util.UUID/randomUUID))]
    (swap! pending-track-queries assoc user-id query)
    (inline-keyboard
     [[{:text (str "🔔 Отслеживать «" query "»")
        :callback_data (str "track_quick:" short-id)}]])))

(defn- handle-track-quick
  "Handle quick track button — create filter with 24h default.
   Uses QueryBuilder to extract price constraints and platform routing."
  [chat-id msg-id user-id short-id]
  (let [uid (str "tg-" user-id)
        query (get @pending-track-queries user-id)
        _ (swap! pending-track-queries dissoc user-id)
        query (or query "товар")  ;; fallback if atom was cleared
        ;; Parse with QueryBuilder to extract price constraints
        qb-result (qb/build query :use-llm? true)
        price-min (:price-min qb-result)
        price-max (:price-max qb-result)
        ;; Determine platform for display
        platform-str (cond
                       (:is-auto? qb-result) "🚗 Mashina.kg"
                       (some #{:bazar} (:platforms qb-result)) "🏪 Lalafo + Bazar"
                       :else "🔍 Lalafo.kg")]
    ;; Show immediate feedback with price info
    (edit-with-buttons chat-id msg-id
                       (str "⏳ Подписываю на «" query "»..."
                            (when (or price-min price-max)
                              (str "\n💰 Бюджет: "
                                   (when price-min (str "от " price-min " сом"))
                                   (when (and price-min price-max) " \u2014 ")
                                   (when price-max (str "до " price-max " сом")))))
                       (inline-keyboard
                        [[{:text "📋 Мои подписки" :callback_data "track_list"}]]))
    ;; Do LLM category match + DB write in background
    (future
      (try
        (let [category (tracker/match-category query)
              category-id (:category-id category)
              category-name (:category-name category)
              ;; Store with price constraints
              track (store/create-track! {:user-id uid
                                          :title query
                                          :queries [query]
                                          :price-min price-min
                                          :price-max price-max
                                          :category-id category-id
                                          :notify-interval 24})]
          (log/info :track-created :user uid :track-id (:id track) :query query
                    :category-id category-id :category-name category-name
                    :price [price-min price-max] :platforms (:platforms qb-result))
          (edit-with-buttons chat-id msg-id
                             (str "✅ Подписался на «" query "»\n\n"
                                  (when category-name
                                    (str "📂 Категория: " category-name "\n"))
                                  (when (or price-min price-max)
                                    (str "💰 Бюджет: "
                                         (when price-min (str "от " price-min " сом"))
                                         (when (and price-min price-max) " — ")
                                         (when price-max (str "до " price-max " сом"))
                                         "\n"))
                                  "📅 Проверяю каждые 24 часа\n"
                                  "Уведомлю когда появятся новые объявления")
                             (inline-keyboard
                              [[{:text "📋 Мои подписки" :callback_data "track_list"}
                                {:text "⚙ Настроить" :callback_data (str "track_settings:" (:id track))}]])))
        (catch Exception e
          (log/error e :track-create-failed :query query)
          (edit-with-buttons chat-id msg-id
                             (str "❌ Ошибка подписки на «" query "»")
                             (inline-keyboard
                              [[{:text "🔄 Попробовать снова" :callback_data (str "track_quick:" short-id)}]])))))))

;; ══════════════════════ TRACKING — SUBSCRIPTION LIST ══════════════════════

(defn- show-tracking-list
  "Show user's tracking subscriptions with frequency controls."
  [chat-id user-id]
  (let [uid (str "tg-" user-id)
        tracks (store/get-user-tracks uid)]
    (if (empty? tracks)
      (send-with-buttons chat-id
                         "📋 *Ваши подписки пусты*\n\nНайдите товар и нажмите «🔔 Отслеживать»"
                         (inline-keyboard
                          [[{:text "🔍 Поиск товаров" :callback_data "open_search"}]]))
      (let [track-rows (mapv (fn [t]
                               (let [freq (format-interval (:notify_interval t))]
                                 [{:text (str "🔍 " (:title t))
                                   :callback_data (str "track_info:" (:id t))}
                                  {:text (str "📅 " freq)
                                   :callback_data (str "track_freq:" (:id t))}
                                  {:text "❌"
                                   :callback_data (str "track_del_ask:" (:id t))}]))
                             tracks)]
        (send-with-buttons chat-id
                           (str "📋 *Ваши подписки* (" (count tracks) ")\n\n"
                                "📅 — частота уведомлений\n❌ — удалить")
                           (inline-keyboard track-rows))))))

(defn- show-track-settings
  "Show frequency settings for a track."
  [chat-id msg-id track-id]
  (let [track (store/get-track track-id)]
    (when track
      (edit-with-buttons chat-id msg-id
                         (str "⚙ *Настройки:* «" (:title track) "»\n\n"
                              "📅 Частота уведомлений:")
                         (inline-keyboard
                          [[{:text "⏰ Каждые 3 часа"
                             :callback_data (str "track_set_freq:" track-id ":3")}
                            {:text "📅 Каждые 24 часа"
                             :callback_data (str "track_set_freq:" track-id ":24")}
                            {:text "📆 Каждые 72 часа"
                             :callback_data (str "track_set_freq:" track-id ":72")}]])))))

(defn- confirm-delete-track
  "Ask for delete confirmation."
  [chat-id msg-id track-id title]
  (edit-with-buttons chat-id msg-id
                     (str "🗑 *Удалить подписку?*\n\n«" title "»")
                     (inline-keyboard
                      [[{:text "Да, удалить" :callback_data (str "track_del_yes:" track-id)}
                        {:text "← Назад" :callback_data "track_list"}]])))

;; ══════════════════════ CALLBACK ROUTER ══════════════════════

(defn- handle-callback
  "Handle inline keyboard callback queries."
  [{:keys [callback-id data user-id chat-id msg-id]}]
  (log/info :callback-received :data data :user user-id :chat chat-id :msg msg-id)
  (try
    (cond
    ;; === TRACKING: Quick create from search result ===
      (re-matches #"track_quick:(.+)" data)
      (let [[_ query] (re-matches #"track_quick:(.+)" data)]
        (answer-callback callback-id)
        (handle-track-quick chat-id msg-id user-id query))

    ;; === TRACKING: Show subscription list ===
      (= data "track_list")
      (do (answer-callback callback-id)
          (show-tracking-list chat-id user-id))

    ;; === TRACKING: Show settings for a track ===
      (re-matches #"track_settings:(\d+)" data)
      (let [[_ id-str] (re-matches #"track_settings:(\d+)" data)]
        (answer-callback callback-id)
        (show-track-settings chat-id msg-id (Long/parseLong id-str)))

    ;; === TRACKING: Show frequency picker ===
      (re-matches #"track_freq:(\d+)" data)
      (let [[_ id-str] (re-matches #"track_freq:(\d+)" data)]
        (answer-callback callback-id)
        (show-track-settings chat-id msg-id (Long/parseLong id-str)))

    ;; === TRACKING: Set frequency ===
      (re-matches #"track_set_freq:(\d+):(\d+)" data)
      (let [[_ id-str interval] (re-matches #"track_set_freq:(\d+):(\d+)" data)
            track-id (Long/parseLong id-str)
            interval-h (Long/parseLong interval)
            track (store/get-track track-id)]
        (answer-callback callback-id :text (str "✅ " (format-interval interval-h)))
        (when (and track (= (:user_id track) (str "tg-" user-id)))
          (store/update-track-interval! track-id interval-h)
          (log/info :track-interval-updated :track-id track-id :interval interval-h)
          (edit-with-buttons chat-id msg-id
                             (str "✅ Частота обновлена\n\n"
                                  "«" (:title track) "» → " (format-interval interval-h))
                             (inline-keyboard
                              [[{:text "📋 Назад к списку" :callback_data "track_list"}]]))))

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
                             (inline-keyboard
                              [[{:text "📋 К списку" :callback_data "track_list"}]]))))

    ;; === TRACKING: Open search (from empty list) ===
      (= data "open_search")
      (do (answer-callback callback-id)
          (tg/send-md chat-id "🔍 Напишите что ищете, и в конце будет кнопка «🔔 Отслеживать»"))

    ;; Unknown callback
      :else
      (do (log/warn :unknown-callback :data data)
          (answer-callback callback-id)))

    (catch Exception e
      (log/error e :callback-error :data data)
      (try (answer-callback callback-id) (catch Exception _ nil)))))

;; ══════════════════════ HANDLERS ══════════════════════

(defn- handle-start [{:keys [chat-id user-id first-name]}]
  (hc/reset-session! @t/tapalakbot (str "tg-" user-id))
  (let [stats (monitor/fetch-categories)
        cats (:categories stats)
        total-items (reduce + 0 (map :item_count cats))
        cat-count (count cats)]
    (send-menu!
     chat-id
     (str "👋 Салам, " first-name "!\n\n"
          "Я *TapalakBot* — умный помощник по покупкам на Lalafo.kg 🇰🇬\n\n"
          "━━━━━━━━━━━━━━━━━━━━\n"
          "📊 *Рынок сейчас:*\n"
          "• " cat-count " категорий отслеживается\n"
          "• " total-items " товаров в базе\n"
          "━━━━━━━━━━━━━━━━━━━━\n\n"
          "Просто напиши что ищешь! 🔍")))
  nil)

(defn- handle-reset [{:keys [chat-id user-id]}]
  (let [uid (str "tg-" user-id)]
    (hc/reset-session! @t/tapalakbot uid)
    (release! uid)
    (store-pending! uid nil)
    (send-menu! chat-id "🗑️ Контекст очищен. Начнём заново!"))
  nil)

(defn- handle-tracking [{:keys [chat-id user-id]}]
  (show-tracking-list chat-id user-id)
  nil)

(defn- handle-help [{:keys [chat-id]}]
  (send-menu!
   chat-id
   "*TapalakBot* — поиск на Lalafo.kg\n\n🔍 Поиск товаров\n🔔 Отслеживание новых объявлений\n📊 Рыночные цены\n\nПросто опиши что ищешь!")
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
      (str/replace #"\|[^\n]*\|" "")))

(defn- strip-fake-urls
  "Remove any URL that is not from a known marketplace.
   Catches URLs with or without 🔗 prefix. LLMs hallucinate fake links."
  [text]
  (str/replace text #"(🔗\s*)?https?://[^\s)\]>]+"
               (fn [[full-match _prefix]]
                 (if (re-find #"lalafo\.kg|mashina\.kg|bazar\.kg" full-match)
                   full-match
                   "🔗 [ссылка недоступна]"))))

(defn- citation-replace
  "Replace #A, #B, #C letter tokens with clickable links from url-store.
   Strips any tokens not in url-store (LLM hallucination prevention).
   str/replace with capturing group passes a vector [full-match group1]."
  [text user-id]
  (let [url-store (t/get-url-store user-id)
        store-count (count url-store)
        letter-count (count (re-seq #"#[A-Z]" text))]
    (log/info :citation-replace :store-size store-count :tokens-in-text letter-count)
    (when (pos? store-count)
      (log/info :citation-sample :first-3 (take 3 url-store)))
    (if (empty? url-store)
      text
      (let [strip-bold (fn [s] (str/replace s #"\*\*([^*]+)\*\*" "$1"))
            clean-suffix (fn [s] (str/replace s #"[—–,\s-]+$" ""))
            missing-ids (atom [])
            invented-ids (atom [])]
        (let [result
              ;; Replace #A, #B, #C etc. with clickable links
              (str/replace text #"(?:•\s+)([^\n]*?)\s*#([A-Z])"
                           (fn [[_ prefix letter]]
                             (let [entry (get url-store letter)
                                   entry (when entry (if (string? entry) {:url entry} entry))
                                   url (:url entry)
                                   cp (-> prefix str/trimr strip-bold clean-suffix)]
                               (if url
                                 (str "• <a href='" url "'>" cp "</a>")
                                 (do (swap! missing-ids conj letter)
                                     (str "• " prefix " #" letter))))))
              ;; Pass 2: strip any #X tokens not in url-store (LLM invented them)
              final-result (str/replace result #"#[A-Z]"
                                        (fn [token]
                                          (let [letter (subs token 1)]
                                            (if (contains? url-store letter)
                                              token
                                              (do (swap! invented-ids conj letter)
                                                  "[нет данных]")))))]
          (when (seq @missing-ids)
            (log/warn :citation-missing-ids :ids @missing-ids))
          (when (seq @invented-ids)
            (log/warn :citation-hallucination-detected :invented-tokens @invented-ids))
          final-result)))))

(defn- extract-search-query
  "Try to extract the original search query from agent response.
   Looks for patterns like 'Поиск по запросу: ...' or the user's original text."
  [result-text user-text]
  ;; Use the user's text as the query (it's what they searched for)
  (when (and user-text (not (str/blank? user-text)))
    (str/trim user-text)))

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
                              (let [preview (-> (.toString buf)
                                                strip-tables
                                                (citation-replace uid))
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
            result (do
                     (t/set-thread-user-id! uid)
                     (try
                       (hc/handle-message-stream! bot uid text stream-cb :status-cb status-cb)
                       (finally
                         (t/clear-thread-user-id!))))]
        (reset! phase :done)
        (if-let [msg-id @thinking-msg-id]
          (let [safe-text (-> (str result)
                              (str/replace #"👉 Смотри\b" "🔗")
                              strip-tables
                              (citation-replace uid)
                              strip-fake-urls)
                html (fmt/md->html safe-text)]
            (try
              ;; Edit with search results
              (tg/edit-message chat-id msg-id html :parse-mode "HTML")
              ;; Add contextual track button after a short delay
              (when-let [query (extract-search-query (str result) text)]
                (log/info :track-button-prepare :query query :user user-id)
                (try
                  (Thread/sleep 500)
                  (let [track-btn (track-context-button user-id query)
                        resp (tg/send-message chat-id (str "🔔 Хотите отслеживать «" query "»?")
                                              :reply_markup track-btn)]
                    (log/info :track-button-sent :resp (when resp "ok")))
                  (catch Exception e
                    (log/warn e :track-button-fail))))
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
    (if (seq stats)
      (let [lines (mapv (fn [c]
                          (str "• *" (:name c) "* — "
                               (:item_count c) " об'яв"))
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
      (log/info :callback-received :data (get cb "data") :from-id (get from "id") :has-message (some? msg))
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
  (if (:callback-id parsed)
    (log/info :extended-handler :callback (:callback-id parsed) :data (:data parsed))
    (log/info :extended-handler :text (:text parsed)))
  (cond
    ;; Callback query (inline keyboard button pressed) — process async
    (:callback-id parsed)
    (do (handler-future
         (fn [] (handle-callback parsed)))
        nil)

    ;; Regular text message
    (:text parsed)
    (let [text (str/trim (:text parsed))]
      (cond
        ;; Persistent menu buttons
        (= text "🔄 Новый диалог")
        (do (handler-future (fn [] (handle-reset parsed))) nil)

        (= text "🔔 Отслеживание")
        (do (handler-future (fn [] (handle-tracking parsed))) nil)

        ;; Commands
        (str/starts-with? text "/")
        (let [cmd (first (str/split text #"\s+"))]
          (log/info :command-detected :cmd cmd)
          (do (handler-future
               (fn []
                 (case cmd
                   "/start"    (handle-start parsed)
                   "/help"     (handle-help parsed)
                   "/prices"   (handle-prices parsed)
                   "/reset"    (handle-reset parsed)
                   "/tracking" (handle-tracking parsed)
                   nil)))
              nil))

        ;; Fast-path words
        (get fast-responses (str/lower-case text))
        (do (handler-future (fn [] (tg/send-md (:chat-id parsed) (get fast-responses (str/lower-case text)))))
            nil)

        ;; Agent — process in separate thread to unblock poll loop
        (and (not (str/blank? text))
             (:text parsed))
        (do (handler-future
             (fn [] (handle-agent parsed)))
            nil)  ;; return nil immediately to unblock poll loop

        :else nil))

    :else nil))

(defn- get-updates-extended
  "getUpdates with allowed_updates for messages + callbacks.
   Uses #'tg/call (private) because clj-harness get-updates doesn't pass allowed_updates."
  [& {:keys [offset timeout limit]
      :or {timeout 1 limit 10}}]
  (let [body (cond-> {"timeout" timeout "limit" limit "allowed_updates" ["message" "callback_query"]}
               offset (assoc "offset" offset))]
    (@#'tg/call "getUpdates" body :timeout-ms 70000)))

(defn start-polling
  "Start polling loop. ALL handlers run in futures to never block poll loop."
  [& {:keys [interval-ms] :or {interval-ms 1500}}]
  (let [init (get-updates-extended :offset -1 :limit 1 :timeout 1)
        offset (atom (if-let [u (first (get init "result" []))]
                       (inc (get u "update_id")) 0))
        cleanup-counter (atom 0)]
    (log/info :poll-start :offset @offset :mode :extended-v4)
    (while true
      (try
        (let [resp (get-updates-extended :offset @offset :timeout 1)
              updates (get resp "result" [])]
          (when (seq updates)
            (log/info :poll-got-updates :count (count updates)))
          (doseq [u updates]
            (when-let [parsed (parse-update-extended u)]
              (handler-future
               (fn [] (extended-handler parsed))))
            (reset! offset (inc (get u "update_id")))))
        (catch Exception e (log/error e :poll-error)))
      ;; Cleanup stale user states every 10 minutes
      (when (>= (swap! cleanup-counter inc) 400)
        (reset! cleanup-counter 0)
        (cleanup-stale-users!))
      (Thread/sleep interval-ms))))
