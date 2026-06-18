(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2.
  Agent-first architecture — clj-harness agent with tools handles intent + response.
  Button-based tracking UI via inline keyboards.
  Persistent menu: [🔄 Новый диалог] [🔔 Отслеживание]"
  (:require [tapalakbot.core :as t]
            [tapalakbot.render :as render]
            [tapalakbot.monitor.client :as monitor]
            [tapalakbot.monitor.store :as store]
            [tapalakbot.monitor.tracker :as tracker]
            [tapalakbot.query-builder :as qb]
            [cheshire.core :as json]
            [clj-harness.telegram :as tg]
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
;; Uses tg/inline-keyboard with VECTOR form [label {:callback_data ...}] —
;; the map form {:text ... :callback_data ...} is buggy in the pinned clj-harness
;; (nests the whole map under "text"). Always use vector form.
;; Uses tg/answer-callback-query (public API)

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
    (tg/inline-keyboard
     [[(str "🔔 Отслеживать «" query "»") {:callback_data (str "track_quick:" short-id)}]])))

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
                       false nil
                       :else "🔍 Lalafo.kg")]
    ;; Show immediate feedback with price info
    (edit-with-buttons chat-id msg-id
                       (str "⏳ Подписываю на «" query "»..."
                            (when (or price-min price-max)
                              (str "\n💰 Бюджет: "
                                   (when price-min (str "от " price-min " сом"))
                                   (when (and price-min price-max) " \u2014 ")
                                   (when price-max (str "до " price-max " сом")))))
                       (tg/inline-keyboard
                        [["📋 Мои подписки" {:callback_data "track_list"}]]))
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
                             (tg/inline-keyboard
                              [["📋 Мои подписки" {:callback_data "track_list"}]
                               ["⚙ Настроить" {:callback_data (str "track_settings:" (:id track))}]])))
        (catch Exception e
          (log/error e :track-create-failed :query query)
          (edit-with-buttons chat-id msg-id
                             (str "❌ Ошибка подписки на «" query "»")
                             (tg/inline-keyboard
                              [["🔄 Попробовать снова" {:callback_data (str "track_quick:" short-id)}]])))))))

;; ══════════════════════ TRACKING — SUBSCRIPTION LIST ══════════════════════

(defn- show-tracking-list
  "Show user's tracking subscriptions with frequency controls."
  [chat-id user-id]
  (let [uid (str "tg-" user-id)
        tracks (store/get-user-tracks uid)]
    (if (empty? tracks)
      (send-with-buttons chat-id
                         "📋 *Ваши подписки пусты*\n\nНайдите товар и нажмите «🔔 Отслеживать»"
                         (tg/inline-keyboard
                          [["🔍 Поиск товаров" {:callback_data "open_search"}]]))
      (let [track-rows (mapv (fn [t]
                               (let [freq (format-interval (:notify_interval t))]
                                 [[(str "🔍 " (:title t)) {:callback_data (str "track_info:" (:id t))}]
                                  [(str "📅 " freq) {:callback_data (str "track_freq:" (:id t))}]
                                  ["❌" {:callback_data (str "track_del_ask:" (:id t))}]]))
                             tracks)]
        (send-with-buttons chat-id
                           (str "📋 *Ваши подписки* (" (count tracks) ")\n\n"
                                "📅 — частота уведомлений\n❌ — удалить")
                           (apply tg/inline-keyboard track-rows))))))

(defn- show-track-settings
  "Show frequency settings for a track."
  [chat-id msg-id track-id]
  (let [track (store/get-track track-id)]
    (when track
      (edit-with-buttons chat-id msg-id
                         (str "⚙ *Настройки:* «" (:title track) "»\n\n"
                              "📅 Частота уведомлений:")
                         (tg/inline-keyboard
                          [["⏰ Каждые 3 часа" {:callback_data (str "track_set_freq:" track-id ":3")}]
                           ["📅 Каждые 24 часа" {:callback_data (str "track_set_freq:" track-id ":24")}]
                           ["📆 Каждые 72 часа" {:callback_data (str "track_set_freq:" track-id ":72")}]])))))

(defn- confirm-delete-track
  "Ask for delete confirmation."
  [chat-id msg-id track-id title]
  (edit-with-buttons chat-id msg-id
                     (str "🗑 *Удалить подписку?*\n\n«" title "»")
                     (tg/inline-keyboard
                      [["Да, удалить" {:callback_data (str "track_del_yes:" track-id)}]
                       ["← Назад" {:callback_data "track_list"}]])))

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
        (tg/answer-callback-query callback-id)
        (handle-track-quick chat-id msg-id user-id query))

    ;; === TRACKING: Show subscription list ===
      (= data "track_list")
      (do (tg/answer-callback-query callback-id)
          (show-tracking-list chat-id user-id))

    ;; === TRACKING: Show settings for a track ===
      (re-matches #"track_settings:(\d+)" data)
      (let [[_ id-str] (re-matches #"track_settings:(\d+)" data)]
        (tg/answer-callback-query callback-id)
        (show-track-settings chat-id msg-id (Long/parseLong id-str)))

    ;; === TRACKING: Show frequency picker ===
      (re-matches #"track_freq:(\d+)" data)
      (let [[_ id-str] (re-matches #"track_freq:(\d+)" data)]
        (tg/answer-callback-query callback-id)
        (show-track-settings chat-id msg-id (Long/parseLong id-str)))

    ;; === TRACKING: Set frequency ===
      (re-matches #"track_set_freq:(\d+):(\d+)" data)
      (let [[_ id-str interval] (re-matches #"track_set_freq:(\d+):(\d+)" data)
            track-id (Long/parseLong id-str)
            interval-h (Long/parseLong interval)
            track (store/get-track track-id)]
        (tg/answer-callback-query callback-id :text (str "✅ " (format-interval interval-h)))
        (when (and track (= (:user_id track) (str "tg-" user-id)))
          (store/update-track-interval! track-id interval-h)
          (log/info :track-interval-updated :track-id track-id :interval interval-h)
          (edit-with-buttons chat-id msg-id
                             (str "✅ Частота обновлена\n\n"
                                  "«" (:title track) "» → " (format-interval interval-h))
                             (tg/inline-keyboard
                              [["📋 Назад к списку" {:callback_data "track_list"}]]))))

    ;; === TRACKING: Delete confirmation ===
      (re-matches #"track_del_ask:(\d+)" data)
      (let [[_ id-str] (re-matches #"track_del_ask:(\d+)" data)
            track (store/get-track (Long/parseLong id-str))]
        (tg/answer-callback-query callback-id)
        (when track
          (confirm-delete-track chat-id msg-id (:id track) (:title track))))

    ;; === TRACKING: Delete yes ===
      (re-matches #"track_del_yes:(\d+)" data)
      (let [[_ id-str] (re-matches #"track_del_yes:(\d+)" data)
            track-id (Long/parseLong id-str)
            track (store/get-track track-id)]
        (tg/answer-callback-query callback-id)
        (when (and track (= (:user_id track) (str "tg-" user-id)))
          (store/delete-track! track-id)
          (log/info :track-deleted :user user-id :track-id track-id)
          (edit-with-buttons chat-id msg-id
                             (str "🗑 *Удалено:* «" (:title track) "»")
                             (tg/inline-keyboard
                              [["📋 К списку" {:callback_data "track_list"}]]))))

    ;; === TRACKING: Open search (from empty list) ===
      (= data "open_search")
      (do (tg/answer-callback-query callback-id)
          (tg/send-md chat-id "🔍 Напишите что ищете, и в конце будет кнопка «🔔 Отслеживать»"))

    ;; === MORE RESULTS: Show more search results with live streaming ===
      (re-matches #"more:(.+)" data)
      (let [[_ query] (re-matches #"more:(.+)" data)]
        (tg/answer-callback-query callback-id :text "Ищу ещё...")
        ;; Run search with streaming in background via Rich Message Drafts
        (future
          (let [uid (str "tg-" user-id)
                draft-id (int (rand-int 999999))
                buf (StringBuilder.)
                last-draft (atom 0)
                last-preview (atom "")
                _ (try (tg/send-rich-message-draft chat-id draft-id :markdown "🔄 Ищу ещё варианты...")
                       (catch Exception _))
                stream-cb (fn [delta]
                            (.append buf delta)
                            (let [now (System/currentTimeMillis)]
                              (when (and (> (- now @last-draft) 1200)
                                         (> (.length buf) 30))
                                (reset! last-draft now)
                                (try
                                  (let [preview (.toString buf)]
                                    (when (not= preview @last-preview)
                                      (reset! last-preview preview)
                                      (tg/send-rich-message-draft chat-id draft-id :markdown preview)))
                                  (catch Exception _)))))
                status-cb (fn [status]
                            (.setLength buf 0)
                            (reset! last-preview "")
                            (try (tg/send-rich-message-draft chat-id draft-id :markdown status)
                                 (catch Exception _)))
                result (t/ask-stream uid (str "найди ещё " query) status-cb {:stream-cb stream-cb})]
            (when (seq (:cards result))
              (t/cache-ads! uid (:cards result)))
            ;; Send final agent text via Rich Messages (draft auto-expires)
            (let [agent-text (:text result)
                  track-btn (track-context-button user-id query)]
              (try
                (if (seq agent-text)
                  (tg/send-md chat-id agent-text :reply_markup track-btn)
                  (tg/send-message chat-id "🔄 Больше вариантов не нашлось." :parse-mode nil))
                (catch Exception e
                  ;; Final delivery failed (e.g. markdown entity parse error) —
                  ;; log it AND fall back to plain text so the user gets something.
                  (log/error e :more-results-send-failed :text-len (count agent-text))
                  (try (tg/send-message chat-id (or agent-text "🔄 Готово.") :parse-mode nil)
                       (catch Exception _))))))))

    ;; === DRILL-DOWN: Show detailed ad ===
      (re-matches #"ad:(\d+)" data)
      (let [[_ idx-str] (re-matches #"ad:(\d+)" data)
            idx (Integer/parseInt idx-str)
            uid (str "tg-" user-id)
            ad (t/get-ad uid idx)]
        (tg/answer-callback-query callback-id)
        (if ad
          (let [card-text (str "<b>" (render/escape-html (:title ad)) "</b>\n\n"
                              "💰 " (when (:price ad) (format "%,d" (:price ad)))
                              " " (:currency ad "KGS") "\n"
                              (when (:desc ad) (str "\n" (render/escape-html (:desc ad)) "\n")) "\n"
                              "📍 " (get ad :platform "lalafo") "\n\n"
                              "<a href=\"" (:url ad) "\">🔗 Открыть на площадке</a>")
                kb {"inline_keyboard"
                    [[{"text" "🔗 Открыть на Lalafo"
                       "url" (:url ad)}]
                     [{"text" "◀️ Назад к результатам"
                       "callback_data" "back_to_results"}]]}]
            (try
              (tg/send-message chat-id card-text :parse-mode "HTML"
                               :reply_markup kb)
              (catch Exception e
                (log/error e :drilldown-send-failed))))
          (tg/send-md chat-id "❌ Объявление не найдено в кеше. Попробуйте новый поиск.")))

    ;; Unknown callback
      :else
      (do (log/warn :unknown-callback :data data)
          (tg/answer-callback-query callback-id)))

    (catch Exception e
      (log/error e :callback-error :data data)
      (try (tg/answer-callback-query callback-id) (catch Exception _ nil)))))

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




(defn- extract-search-query
  "Try to extract the original search query from agent response.
   Looks for patterns like 'Поиск по запросу: ...' or the user's original text."
  [result-text user-text]
  ;; Use the user's text as the query (it's what they searched for)
  (when (and user-text (not (str/blank? user-text)))
    (str/trim user-text)))

(defn- render-orchestrated
  "Render a reply to Telegram HTML + buttons."
  [chat-id msg-id reply user-id query]
  (let [html (render/render-reply reply)
        track-btn (when (and (seq (:cards reply)) query)
                    (track-context-button user-id query))]
    (if msg-id
      (try
        (tg/edit-message chat-id msg-id html :parse-mode "HTML")
        (catch Exception e
          (log/error e :orchestrated-edit-fail)
          (tg/send-message chat-id html :parse-mode "HTML")))
      ;; No thinking message — send directly
      (tg/send-message chat-id html :parse-mode "HTML"))
    (when track-btn
      (try
        (Thread/sleep 300)
        (tg/send-message chat-id
                         (str "🔔 Хотите отслеживать «" query "»?")
                         :reply_markup track-btn)
        (catch Exception e
          (log/warn e :track-button-fail))))))

(defn- truncate-cb
  "Truncate string to fit within max-bytes when UTF-8 encoded (for Telegram callback_data).
   Walks back from truncation point to avoid splitting UTF-8 multi-byte sequences."
  [s max-bytes]
  (let [bs (.getBytes (str s) "UTF-8")]
    (if (<= (alength bs) max-bytes)
      (str s)
      ;; Walk back from max-bytes until bs[n] is NOT a continuation byte (10xxxxxx).
      ;; Continuation bytes mean we're inside a multi-byte character — keep walking.
      (let [n (loop [n max-bytes]
                (if (and (pos? n)
                         (< n (alength bs))
                         (= (bit-and (aget bs n) 0xC0) 0x80))
                  (recur (dec n))
                  n))]
        (String. bs 0 n "UTF-8")))))

(defn- log-transcript!
  "Log a transcript entry for later review."
  [user-id user-text reply]
  (try
    (log/info :transcript {:user-id    user-id
                           :user-text  user-text
                           :mode       (:mode reply)
                           :intro      (:intro reply)
                           :card-count (count (:cards reply))
                           :cta        (:cta reply)
                           :timestamp  (System/currentTimeMillis)})
    (catch Exception _)))

(defn- render-and-send
  "Send reply to Telegram. When the reply is pure agent text (no cards),
   sends raw markdown via Rich Messages API so Telegram renders tables,
   headings, etc. natively. When cards are present, uses deterministic HTML.
   Optional :keyboard overrides the default track keyboard."
  [chat-id user-id text reply & {:keys [keyboard]}]
  (let [default-kb (when (seq (:cards reply)) (track-context-button user-id text))
        kb (or keyboard default-kb)
        intro (:intro reply)]
    (log/info :render-and-send :text-len (count intro) :has-kb (boolean kb) :has-cards (seq (:cards reply)))
    (try
      (if (and (seq intro) (empty? (:cards reply)))
        ;; Pure agent text → Rich Messages (native tables/headings/code)
        (do
          (log/info :send-md-start :text-len (count intro))
          (tg/send-md chat-id intro :reply_markup kb)
          (log/info :send-md-done))
        ;; Cards present → deterministic HTML render
        (let [html (render/render-reply reply)]
          (log/info :send-html-start :html-len (count html))
          (if kb
            (tg/send-message chat-id html :parse-mode "HTML" :reply_markup kb)
            (tg/send-message chat-id html :parse-mode "HTML"))
          (log/info :send-html-done)))
      (catch Exception e
        (log/error e :tg-send-failed :msg (.getMessage e))
        (try (tg/send-message chat-id (or intro "Ошибка — попробуйте ещё раз.") :parse-mode nil)
             (catch Exception _))))))

(defn- humanize-status
  "Map harness status text to warm Russian phrases. Rotates variants
   to keep the draft alive and feeling responsive during long phases."
  [status-text user-query variant]
  (let [phase (cond
                (re-find #"Анализ" status-text) :analyzing
                (re-find #"research" status-text) :researching
                (re-find #"Выполня" status-text) :tooling
                (re-find #"Обрабат" status-text) :processing
                :else :working)
        variants {:analyzing  ["🧠 Так, давай разберёмся что тебе нужно..."
                               "🤔 Понял запрос, прикидываю варианты..."]
                  :researching ["📚 Смотрю что вообще стоит брать..."
                                "🔬 Изучаю какие модели хорошие..."]
                  :tooling    ["🔍 Ищу на Lalafo и Mashina..."
                               "👀 Прочёсываю объявления..."
                               "🔎 Копаюсь в свежих лотах..."]
                  :processing ["📊 Нашёл варианты, отбираю лучшие..."
                               "⚖️ Сравниваю цены и состояние..."
                               "🧮 Считаю что выгоднее..."]
                  :working    ["⏳ Секунду, почти готово..."
                               "💭 Думаю над лучшим ответом..."]}
        opts (get variants phase ["⏳ Работаю..."])]
    (nth opts (mod variant (count opts)))))

(defn- handle-orchestrated
  "Handle message via agent-first pipeline with LIVE streaming.

   Activity-aware design: the model's own streamed text IS the best draft
   content. The drafter thread only fills silence — it activates when
   stream-cb hasn't fired for >3 seconds (during tool execution), and
   yields instantly when new text arrives. This is correct for reasoning
   models (GLM-5.2) that stream text alongside tool calls, AND for
   non-reasoning models (DeepSeek) that only emit text on the final turn."
  [{:keys [chat-id user-id text] :as msg}]
  (let [uid (str "tg-" user-id)
        draft-id (int (rand-int 999999))
        buf (StringBuilder.)
        last-draft (atom 0)
        last-typing (atom 0)
        last-preview (atom "")
        draft-count (atom 0)
        ;; Track stream activity — drafter only fires during silence
        last-stream-activity (atom (System/currentTimeMillis))
        ;; Current phase status from harness (for humanize-status)
        current-status (atom "🧠 Анализирую запрос...")
        status-variant (atom 0)
        ;; Drafter thread — fires only after 3s of stream silence
        drafter-running (atom true)
        drafter-thread (Thread.
                        (fn []
                          (while @drafter-running
                            (try
                              (Thread/sleep 2000)  ; check every 2s
                              (when @drafter-running
                                (let [silence (- (System/currentTimeMillis) @last-stream-activity)]
                                  ;; Only show status if: no text flowing for >3s
                                  (when (> silence 3000)
                                    (swap! status-variant inc)
                                    (let [status (humanize-status @current-status text @status-variant)]
                                      (tg/send-rich-message-draft chat-id draft-id :markdown status)
                                      (log/info :drafter-status :text status :silence-ms silence)))))
                              (catch Exception _)))))
        stream-cb (fn [delta]
                    ;; Mark stream activity — drafter yields when this fires
                    (reset! last-stream-activity (System/currentTimeMillis))
                    (.append buf delta)
                    (let [now (System/currentTimeMillis)]
                      (when (> (- now @last-typing) 4000)
                        (reset! last-typing now)
                        (try (tg/send-typing chat-id) (catch Exception _)))
                      ;; Animated draft preview (throttled 1200ms)
                      (when (and (> (- now @last-draft) 1200)
                                 (> (.length buf) 30))
                        (reset! last-draft now)
                        (try
                          (let [preview (.toString buf)]
                            (when (not= preview @last-preview)
                              (reset! last-preview preview)
                              (swap! draft-count inc)
                              (tg/send-rich-message-draft chat-id draft-id :markdown preview)))
                          (catch Exception e
                            (log/warn e :stream-draft-fail))))))
        status-cb (fn [status-text]
                    ;; Real phase change from harness — update current status
                    ;; for the drafter to use during silence. Do NOT clear buf —
                    ;; reasoning models stream text we want the user to see.
                    (reset! current-status status-text)
                    (swap! status-variant inc)
                    (log/info :phase-change :status status-text))]
    ;; Initial draft — instant feedback
    (try (tg/send-rich-message-draft chat-id draft-id :markdown "🧠 Так, сейчас гляну...")
         (catch Exception _))
    ;; Start the phase-aware drafter thread (keeps draft alive + shows status)
    (.start drafter-thread)
    (try
      ;; Run agent with REAL streaming + card capture
      (let [result (t/ask-stream uid text status-cb {:stream-cb stream-cb})
            ;; Fallback: retry with explicit search prefix if no cards
            result* (if (and (not (seq (:cards result)))
                            (> (count text) 3)
                            (not (re-find #"(?i)^\s*(привет|здрав|спасибо|ок|да|нет|/reset|/start)" text)))
                      (do
                        (log/info :fallback-auto-search :query text)
                        (.setLength buf 0)
                        (reset! last-preview "")
                        (reset! last-stream-activity (System/currentTimeMillis))
                        (try (tg/send-rich-message-draft chat-id draft-id :markdown "🔍 Ищу подробнее...")
                             (catch Exception _))
                        (t/ask-stream uid (str "найди " text) status-cb {:stream-cb stream-cb}))
                      result)
            agent-text (:text result*)
            all-cards (:cards result*)
            ;; Reply: agent text only, no card dump. Agent already formats listings in its response.
            reply {:mode (if (seq all-cards) :shortlist :no-results)
                   :intro (or agent-text "Ничего не нашлось. Попробуйте переформулировать запрос 🔍")
                   :cards []  ;; Don't render cards — agent text already has curated listings
                   :cta nil
                   :assumptions []}
            ;; Build inline keyboard: "Ещё результаты" only
            more-btn (when (seq all-cards)
                       {"inline_keyboard" [[{"text" "🔄 Ещё результаты"
                                             "callback_data" (str "more:" (truncate-cb text 58))}]]})]
        (log/info :stream-summary :drafts-sent @draft-count :final-len (count agent-text) :cards (count all-cards))
        (render-and-send chat-id user-id text reply :keyboard more-btn)
        ;; Send track button after a short delay
        (when-let [query (when (and text (> (count text) 3)) (str/trim text))]
          (try
            (Thread/sleep 500)
            (let [track-btn (track-context-button user-id query)]
              (when (seq all-cards)
                (tg/send-message chat-id (str "🔔 Хотите отслеживать «" query "»?")
                                 :reply_markup track-btn)))
            (catch Exception e
              (log/warn e :track-button-fail)))))
      (catch Exception e
        (log/error e :agent-error {:user-id uid})
        (try (tg/send-message chat-id "❌ Ошибка. Попробуйте ещё раз." :parse-mode nil)
             (catch Exception _)))
      (finally
        ;; Always stop the drafter — both success and error paths
        (reset! drafter-running false)
        (.interrupt drafter-thread)))))

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

;; ══════════════════════ POLLING (delegated to clj-harness) ══════════════════════

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

(defn extended-handler
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
             (fn [] (handle-orchestrated parsed)))
            nil)  ;; return nil immediately to unblock poll loop

        :else nil))

    :else nil))

(defn start-polling
  "Start polling loop using clj-harness tg/poll-loop.
   ALL handlers run in futures to never block poll loop.
   Uses :allowed-updates to receive both messages and callback queries."
  [& {:keys [interval-ms] :or {interval-ms 1500}}]
  (tg/poll-loop
   (fn [parsed]
     (handler-future (fn [] (extended-handler parsed))))
   :interval-ms interval-ms
   :allowed-updates ["message" "callback_query"]))

