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

;; ══════════════════════ BOUNDED HANDLER EXECUTOR ══════════════════════

(def ^:private handler-pool
  "Bounded executor: production load gets backpressure instead of an
   ever-growing queue or one raw thread per update."
  (let [workers (max 4 (.availableProcessors (Runtime/getRuntime)))]
    (java.util.concurrent.ThreadPoolExecutor.
     workers workers 0 java.util.concurrent.TimeUnit/MILLISECONDS
     (java.util.concurrent.ArrayBlockingQueue. 256)
     (java.util.concurrent.Executors/defaultThreadFactory)
     (java.util.concurrent.ThreadPoolExecutor$AbortPolicy.))))

(defn- handler-future
  "Submit work to the bounded handler pool. Returns nil when saturated."
  [f]
  (try
    (.submit handler-pool
             ^java.util.concurrent.Callable
             (reify java.util.concurrent.Callable
               (call [_]
                 (try
                   (f)
                   (catch Exception e
                     (log/error e :handler-error))))))
    (catch java.util.concurrent.RejectedExecutionException _
      (log/warn :handler-rejected :active (.getActiveCount handler-pool)
                :queued (.size (.getQueue handler-pool)))
      nil)))

;; ══════════════════════ USER + CONVERSATION STATE ══════════════════════

(def ^:private user-state
  "Per-user preferences. Conversation serialization lives separately because
   one user can own several independent forum topics."
  (atom {}))

(defn- get-user-state [uid]
  (or (get @user-state uid)
      (let [fresh {:last-seen (atom (System/currentTimeMillis))
                   :city-id (atom nil)
                   :thread-id (atom nil)}]
        (get (swap! user-state #(if (contains? % uid) % (assoc % uid fresh))) uid))))

(defn conversation-id
  "Stable clj-harness session key. Forum topics are independent dialogues;
   ordinary DMs retain the existing per-user identity."
  [{:keys [chat-id user-id thread-id]}]
  (if thread-id
    (str "tg-" user-id ":" chat-id ":" thread-id)
    (str "tg-" user-id)))

(def ^:private conversation-state
  "Conversation id → single-flight lock plus latest pending update."
  (atom {}))

(defn- get-conversation-state [id]
  (or (get @conversation-state id)
      (let [fresh {:lock (atom :idle)
                   :pending (atom nil)
                   :last-seen (atom (System/currentTimeMillis))}]
        (get (swap! conversation-state
                    #(if (contains? % id) % (assoc % id fresh)))
             id))))

(defn- try-acquire! [id]
  (let [{:keys [lock last-seen]} (get-conversation-state id)]
    (reset! last-seen (System/currentTimeMillis))
    (compare-and-set! lock :idle :busy)))

(defn- release! [id]
  (reset! (:lock (get-conversation-state id)) :idle))

(defn- store-pending! [id msg]
  (reset! (:pending (get-conversation-state id)) msg))

(defn- take-pending! [id]
  (let [pending (:pending (get-conversation-state id))]
    (loop []
      (let [current @pending]
        (if (compare-and-set! pending current nil)
          current
          (recur))))))

;; ══════════════════════ THREAD MANAGEMENT ══════════════════════

(defn- get-thread-id
  "Get current thread-id for user. Returns message_thread_id or nil."
  [uid]
  (let [st (get-user-state uid)]
    @(:thread-id st)))

(defn- set-thread-id!
  "Set current thread-id for user."
  [uid thread-id]
  (let [st (get-user-state uid)]
    (reset! (:thread-id st) thread-id)))

(defn- ensure-topic!
  "Create a NEW forum topic EVERY time — never reuse old thread-ids.
   Stores the message_thread_id in user-state. Returns thread-id or nil.
   Returns nil gracefully if the chat doesn't support forum topics (e.g. private DMs)."
  ([chat-id uid] (ensure-topic! chat-id uid "Новый диалог"))
  ([chat-id uid topic-name]
   (when-let [envelope (try (tg/create-forum-topic chat-id topic-name :icon-color 0x6FB9F0)
                            (catch Exception e
                              (log/info :topic-create-skipped :chat chat-id :reason (.getMessage e))
                              nil))]
     ;; tg/call returns {"ok":true, "result": {"message_thread_id": N}} — unwrap result first!
     (let [result (get envelope "result" envelope)
           thread-id (get result "message_thread_id")]
       (set-thread-id! uid thread-id)
       (log/info :topic-created :user uid :thread-id thread-id :name topic-name)
       thread-id))))

(defn- rename-topic!
  "Rename the user's active forum topic. Logs errors."
  [chat-id uid new-name]
  (when-let [thread-id (get-thread-id uid)]
    (try (let [r (tg/edit-forum-topic chat-id thread-id :name new-name)]
           (log/info :topic-renamed :user uid :thread-id thread-id :name new-name))
         (catch Exception e
           (log/warn e :topic-rename-failed :user uid :thread-id thread-id :name new-name
                     :error (.getMessage e))))))

(defn- topic-name-for-query
  "Generate a short topic name from the user's query text."
  [query]
  (let [clean (-> query str/trim (subs 0 (min 60 (count query))))]
    (if (empty? clean) "Новый диалог" clean)))

;; ══════════════════════ INLINE KEYBOARD HELPERS ══════════════════════
;; Uses tg/inline-keyboard with VECTOR form [label {:callback_data ...}] —
;; the map form {:text ... :callback_data ...} is buggy in the pinned clj-harness
;; (nests the whole map under "text"). Always use vector form.
;; Uses tg/answer-callback-query (public API)

(defn- edit-with-buttons
  "Edit message to show new text + inline keyboard. Optional :thread-id."
  ([chat-id msg-id text keyboard] (edit-with-buttons chat-id msg-id text keyboard nil))
  ([chat-id msg-id text keyboard opts]
   (let [thread-id (:thread-id opts)]
     (try
       (if thread-id
         (tg/edit-message chat-id msg-id text :reply_markup keyboard :thread-id thread-id)
         (tg/edit-message chat-id msg-id text :reply_markup keyboard))
       (catch Exception e
         (log/warn e :edit-with-buttons-fail))))))

(defn- send-with-buttons
  "Send message with inline keyboard. Optional :thread-id."
  ([chat-id text keyboard] (send-with-buttons chat-id text keyboard nil))
  ([chat-id text keyboard opts]
   (let [thread-id (:thread-id opts)]
     (try
       (if thread-id
         (tg/send-md chat-id text :reply_markup keyboard :thread-id thread-id)
         (tg/send-md chat-id text :reply_markup keyboard))
       (catch Exception e
         (log/warn e :send-with-buttons-fail))))))

;; ══════════════════════ PERSISTENT MENU ══════════════════════

(defn- persistent-menu
  "Create ReplyKeyboardMarkup with persistent buttons."
  []
  {"keyboard"
   [[{"text" "🔄 Новый диалог"} {"text" "📍 Город"} {"text" "🔔 Отслеживание"}]]
   "resize_keyboard" true
   "one_time" false})

(defn- send-menu!
  "Send message with persistent menu. Optional :thread-id for forum topics."
  ([chat-id text] (send-menu! chat-id text nil))
  ([chat-id text opts]
   (let [thread-id (:thread-id opts)]
     (if thread-id
       (tg/send-md chat-id text :reply_markup (persistent-menu) :thread-id thread-id)
       (tg/send-md chat-id text :reply_markup (persistent-menu))))))

;; ══════════════════════ RESPONSES ══════════════════════

(def ^:private greeting-resp
  "👋 Салам! Я TapalakBot — умный помощник по Lalafo.kg

Расскажите что ищете, и я помогу:
• Разберусь в товаре
• Найду лучшие варианты на Lalafo
• Проверю рыночные цены

Просто напишите, что вам нужно 🔍")

(def fast-responses
  {"привет" greeting-resp "салам" greeting-resp "хай" greeting-resp
   "здравствуйте" greeting-resp "hello" greeting-resp "hi" greeting-resp
   "спасибо" "Пожалуйста! 😊 Если нужно найти что-то ещё — пишите."
   "спс" "Пожалуйста! 😊" "thanks" "You're welcome! 😊"
   "ок" "👌" "окей" "👌" "ладно" "👌" "понял" "👌"})

;; ══════════════════════ TRACKING — CONTEXTUAL BUTTON ══════════════════════

(defn- format-interval [hours]
  (case (int hours)
    3 "каждые 3ч"
    24 "каждые 24ч"
    72 "каждые 72ч"
    (str "каждые " hours "ч")))

(def ^:private pending-track-queries
  "Callback id → immutable query ownership. Multiple result messages from the
   same user remain independently actionable."
  (atom {}))

(defn- track-context-button
  [user-id query]
  (let [short-id (str (java.util.UUID/randomUUID))]
    (swap! pending-track-queries assoc short-id
           {:user-id user-id
            :query query
            :created-at (System/currentTimeMillis)})
    (tg/inline-keyboard
     [[(str "🔔 Отслеживать «" query "»")
       {:callback_data (str "track_quick:" short-id)}]])))

(defn take-track-query!
  "Consume a tracking callback only when it belongs to the clicking user and
   has not expired."
  [user-id short-id]
  (let [{owner :user-id query :query created-at :created-at :as entry}
        (get @pending-track-queries short-id)
        fresh? (and created-at (< (- (System/currentTimeMillis) created-at)
                                  (* 2 60 60 1000)))]
    (when (and entry (= owner user-id) fresh?)
      (swap! pending-track-queries dissoc short-id)
      query)))

(defn- handle-track-quick
  "Handle quick track button — create filter with 24h default.
   Uses QueryBuilder to extract price constraints and platform routing."
  [chat-id msg-id user-id query]
  (let [uid (str "tg-" user-id)
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
    ;; This callback already runs on the bounded conversation worker.
    (try
      (let [category (tracker/match-category query)
            category-id (:category-id category)
            category-name (:category-name category)
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
                           (track-context-button user-id query))))))

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

(declare handle-orchestrated)  ;; Forward declaration — defined below

(defn- set-city! [uid city-id]
  (let [st (get-user-state uid)]
    (reset! (:city-id st) (when city-id (Integer/parseInt city-id)))))

(defn- handle-callback
  "Handle inline keyboard callback queries."
  [{:keys [callback-id data user-id chat-id msg-id thread-id]}]
  (log/info :callback-received :data data :user user-id :chat chat-id :msg msg-id)
  (try
    (cond
    ;; === CITY SELECTION ===
      (str/starts-with? data "city:")
      (let [city-id (some-> (subs data 5) (not= "nil") (#(when % (Integer/parseInt %))))]
        (tg/answer-callback-query callback-id :text (if city-id "✅ Город выбран!" "✅ Весь Кыргызстан"))
        (set-city! (str "tg-" user-id) city-id))

    ;; === TRACKING: Quick create from search result ===
      (re-matches #"track_quick:(.+)" data)
      (let [[_ short-id] (re-matches #"track_quick:(.+)" data)]
        (tg/answer-callback-query callback-id)
        (if-let [query (take-track-query! user-id short-id)]
          (handle-track-quick chat-id msg-id user-id query)
          (edit-with-buttons chat-id msg-id
                             "⌛ Эта кнопка устарела. Выполните поиск ещё раз."
                             (tg/inline-keyboard
                              [["🔍 Новый поиск" {:callback_data "open_search"}]]))))

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

    ;; === MORE RESULTS ===
      (re-matches #"more:(.+)" data)
      (let [[_ query] (re-matches #"more:(.+)" data)]
        (tg/answer-callback-query callback-id :text "Ищу ещё...")
        (handle-orchestrated {:chat-id chat-id :user-id user-id
                              :thread-id thread-id
                              :text (str "найди ещё " query)}))

    ;; === CHEAPER ===
      (re-matches #"cheaper:(.+)" data)
      (let [[_ query] (re-matches #"cheaper:(.+)" data)]
        (tg/answer-callback-query callback-id :text "Ищу дешевле...")
        (handle-orchestrated {:chat-id chat-id :user-id user-id
                              :thread-id thread-id
                              :text (str query " подешевле")}))

    ;; === DEARER ===
      (re-matches #"dearer:(.+)" data)
      (let [[_ query] (re-matches #"dearer:(.+)" data)]
        (tg/answer-callback-query callback-id :text "Ищу дороже...")
        (handle-orchestrated {:chat-id chat-id :user-id user-id
                              :thread-id thread-id
                              :text (str query " подороже")}))

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

(defn- handle-start [{:keys [chat-id user-id first-name] :as msg}]
  (let [user-uid (str "tg-" user-id)
        topic-name (str "👋 " first-name)
        thread-id (if (pos? chat-id)
                    (:thread-id msg)
                    (or (:thread-id msg)
                        (ensure-topic! chat-id user-uid topic-name)))
        session-id (conversation-id (assoc msg :thread-id thread-id))]
    (when thread-id (set-thread-id! user-uid thread-id))
    (hc/reset-session! @t/tapalakbot session-id)
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
            "Просто напиши что ищешь! 🔍\n\n"
            "💡 Каждый поиск — в отдельном топике. Кнопка «🔄 Новый диалог» создаст новую тему.")
       (when thread-id {:thread-id thread-id}))))
  nil)

(defn- handle-reset [{:keys [chat-id user-id] :as msg}]
  (let [user-uid (str "tg-" user-id)
        current-id (conversation-id msg)]
    (hc/reset-session! @t/tapalakbot current-id)
    (store-pending! current-id nil)
    (set-thread-id! user-uid nil)
    (let [topic-name (str "🔄 " (java.time.format.DateTimeFormatter/ofPattern "HH:mm")
                          (.format (java.time.LocalDateTime/now)))
          thread-id (when-not (pos? chat-id)
                      (ensure-topic! chat-id user-uid topic-name))
          session-id (conversation-id (assoc msg :thread-id thread-id))]
      (when (not= current-id session-id)
        (hc/reset-session! @t/tapalakbot session-id))
      (send-menu! chat-id "🗑️ Контекст очищен. Начнём заново!"
                  (when thread-id {:thread-id thread-id}))))
  nil)

(def cities
  "City name → Lalafo city_id map."
  [{"text" "🇰🇬 Весь Кыргызстан" "callback_data" "city:nil"}
   {"text" "🏙️ Бишкек" "callback_data" "city:103184"}
   {"text" "🏔️ Ош" "callback_data" "city:103185"}
   {"text" "🌊 Каракол" "callback_data" "city:103202"}
   {"text" "🏭 Джалал-Абад" "callback_data" "city:103199"}
   {"text" "🏛️ Нарын" "callback_data" "city:103203"}
   {"text" "🌿 Талас" "callback_data" "city:103207"}
   {"text" "🕌 Баткен" "callback_data" "city:103206"}])

(defn- handle-city [{:keys [chat-id user-id]}]
  (tg/send-message chat-id "📍 Выберите город для поиска:"
                   :reply_markup {"inline_keyboard" (partition-all 1 cities)})
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

(defn- suggest-alternatives
  "Generate smart suggestions when search returns nothing.
   Analyzes the query and suggests broader or alternative terms."
  [query]
  (when query
    (let [q (str/lower-case (str/trim query))
          ;; Extract the core "product" part (strip common modifiers)
          clean (-> q
                    (str/replace #"\b(купи|ищи|ищу|найди|покажи|хочу|нужен|надо|ищем)\b" "")
                    (str/replace #"\b(дешев|дорог|новы|бу|б/у|подешевле|подороже)\b" "")
                    (str/replace #"\bот\s+\d+\b" "")
                    (str/replace #"\bдо\s+\d+\b" "")
                    (str/replace #"\s{2,}" " ")
                    str/trim)
          suggestions (cond-> []
                        ;; If query has specific model → suggest brand-level
                        (re-find #"(iphone|айфон)\s*\d+" q)
                        (conj "айфон" "смартфон")

                        (re-find #"(samsung|самсунг)\s*(galaxy|галакси)?" q)
                        (conj "смартфон" "телефон")

                        (re-find #"(macbook|макбук)" q)
                        (conj "ноутбук" "laptop")

                        ;; If query is very specific → suggest broader
                        (> (count (str/split clean #"\s+")) 2)
                        (conj (str/join " " (take 2 (str/split clean #"\s+"))))

                        ;; Generic fallback — just the cleaned core
                        (and (seq clean) (not (str/blank? clean)))
                        (conj clean)

                        ;; Auto-related → suggest Mashina
                        (re-find #"(машин|авто|hyundai|toyota|honda|bmw|mercedes|lexus|kia|nissan)" q)
                        (conj "mashina.kg"))]
      (when (seq suggestions)
        (distinct suggestions)))))

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
    (log/info :transcript {:user-id user-id
                           :user-text user-text
                           :mode (:mode reply)
                           :intro (:intro reply)
                           :card-count (count (:cards reply))
                           :cta (:cta reply)
                           :timestamp (System/currentTimeMillis)})
    (catch Exception _)))

(defn- render-and-send
  "Send agent analysis plus trust-critical cards. Structured cards are rendered
   deterministically to Rich HTML; pure conversational replies keep native
   Rich Markdown."
  [chat-id user-id text reply & {:keys [keyboard photos-already-sent? thread-id]}]
  (let [default-kb (when (seq (:cards reply))
                     (track-context-button user-id text))
        kb (or keyboard default-kb)
        intro (:intro reply)
        cards (:cards reply)]
    (log/info :render-and-send :text-len (count (or intro ""))
              :cards (count cards) :has-kb (boolean kb) :thread-id thread-id)
    (try
      (if (seq cards)
        (let [html (render/render-reply reply)
              sent (tg/send-rich-message chat-id
                                         :html html
                                         :preview false
                                         :reply-markup kb
                                         :thread-id thread-id)]
          (when-not sent
            (doseq [chunk (tg/split-message html)]
              (tg/send-message chat-id chunk :parse-mode "HTML"
                               :preview false :reply_markup kb
                               :thread-id thread-id))))
        (when (and intro (not (str/blank? intro)))
          (tg/send-md chat-id intro :reply_markup kb :thread-id thread-id)))
      (catch Exception e
        (log/error e :tg-send-failed :msg (.getMessage e))
        (try
          (tg/send-message chat-id (or intro "Ошибка — попробуйте ещё раз.")
                           :parse-mode nil :thread-id thread-id)
          (catch Exception _))))))

(defn- handle-orchestrated
  "Handle message via agent-first pipeline.
   Loading: sendMessage status → editMessageText progress → final Rich Message after."
  [{:keys [chat-id user-id text thread-id] :as msg}]
  (let [user-uid (str "tg-" user-id)
        is-dm? (pos? chat-id)
        thread-id (if is-dm?
                    thread-id
                    (or thread-id
                        (ensure-topic! chat-id user-uid
                                       (str "🔍 " (subs text 0 (min 30 (count text)))))))
        _ (when thread-id (set-thread-id! user-uid thread-id))
        session-id (conversation-id (assoc msg :thread-id thread-id))
        buf (StringBuilder.)
        last-edit (atom 0)
        last-typing (atom 0)
        draft-id (when is-dm? (long (+ (System/currentTimeMillis) (rand-int 10000))))
        status-msg (when-not is-dm? (atom nil))
        ;; DM mode: sendRichMessageDraft animated preview (throttled 800ms)
        send-draft!
        (when is-dm?
          (fn [preview]
            (let [now (System/currentTimeMillis)]
              (when (> (- now @last-edit) 800)
                (reset! last-edit now)
                (try (tg/send-rich-message-draft chat-id draft-id :markdown preview :thread-id thread-id)
                     (catch Exception _))))))
        ;; Group mode: send/edit status message
        show-status!
        (when-not is-dm?
          (fn [status-text]
            (if-not @status-msg
              ;; First call — send the message
              (let [result (try (tg/send-message chat-id status-text :parse-mode nil :thread-id thread-id)
                                (catch Exception _))]
                (reset! status-msg result))
              ;; Subsequent calls — edit it
              (when-let [mid (get @status-msg "message_id")]
                (try (tg/edit-message chat-id mid status-text :parse-mode nil :thread-id thread-id)
                     (catch Exception _))))))
        stream-cb (fn [delta]
                    (.append buf delta)
                    (let [now (System/currentTimeMillis)]
                      ;; Typing indicator every 3s (avoids 5s expiry gap)
                      (when (> (- now @last-typing) 3000)
                        (reset! last-typing now)
                        (try (tg/send-typing chat-id) (catch Exception _)))
                      ;; Streaming preview (throttled 1500ms / 800ms)
                      (when (> (- now @last-edit) (if is-dm? 800 1500))
                        (reset! last-edit now)
                        (when (and (> (.length buf) 50) (not (str/blank? (.toString buf))))
                          (let [preview (str/trimr (subs (.toString buf) 0 (min (count (.toString buf)) 800)))]
                            (if is-dm?
                              (send-draft! preview)
                              (show-status! preview)))))))
        status-cb (fn [status]
                    (when status
                      (if is-dm? (send-draft! status) (show-status! status))))]
    ;; Initial feedback
    (if is-dm?
      (try (tg/send-typing chat-id) (catch Exception _))
      (show-status! (str "🔍 Ищу «" (subs text 0 (min 40 (count text))) "»...")))
    (try
      (let [city-id @(:city-id (get-user-state user-uid))
            result (t/ask-stream session-id text status-cb
                                 {:stream-cb stream-cb
                                  :city-id city-id
                                  :search-status-cb status-cb})
            result* (if (and (not (seq (:cards result)))
                             (> (count text) 3)
                             (not (re-find #"(?i)^\s*(привет|здрав|спасибо|ок|да|нет|/reset|/start)" text)))
                      (do
                        (log/info :fallback-auto-search :query text)
                        (.setLength buf 0)
                        (when-not is-dm? (show-status! "🔍 Ищу подробнее..."))
                        (t/ask-stream session-id (str "найди " text) status-cb
                                      {:stream-cb stream-cb
                                       :city-id city-id
                                       :search-status-cb status-cb}))
                      result)
            effective-query (or (:query result*) text)
            all-cards (:cards result*)
            ;; URL verification: replace any hallucinated link with warning
            agent-text (if (seq all-cards)
                         (let [valid-urls (set (keep :url all-cards))]
                           (clojure.string/replace (or (:text result*) "")
                             ;; Match [text](url) and replace if URL not in valid set
                             #"\[([^\]]*)\]\(([^)]+)\)"
                             (fn [[_ label url]]
                               (if (valid-urls url)
                                 (str "[" label "](" url ")")
                                 (do (log/warn :url-hallucination-replaced :url url)
                                     (str "⚠️ " label " (ссылка недоступна)"))))))
                         (:text result*))
            suggestions (when (empty? all-cards) (suggest-alternatives text))
            no-results-intro (if (seq agent-text)
                               agent-text
                               (if (seq suggestions)
                                 (str "Ничего не нашлось по запросу «" text "».\n\n"
                                      "Попробуйте:\n"
                                      (str/join "\n" (map #(str "• " %) (take 4 suggestions))))
                                 (str "Ничего не нашлось по запросу «" text "».\n\n"
                                      "Попробуйте переформулировать или убрать лишние детали.")))
            reply {:mode (if (seq all-cards) :shortlist :no-results)
                   :intro (if (seq all-cards) (or agent-text "") no-results-intro)
                   :cards (vec all-cards)
                   :cta nil
                   :assumptions []}
            more-btn (cond
                       (seq all-cards)
                       (let [max-q (- 64 8)]
                         {"inline_keyboard"
                          [[{"text" "🔄 Ещё результаты" "callback_data" (str "more:" (truncate-cb effective-query (- 64 5)))}]
                           [{"text" "⬇️ Дешевле" "callback_data" (str "cheaper:" (truncate-cb effective-query max-q))}
                            {"text" "⬆️ Дороже" "callback_data" (str "dearer:" (truncate-cb effective-query (- 64 7)))}]]})
                       (seq suggestions)
                       {"inline_keyboard"
                        (mapv (fn [s]
                                [{"text" (str "🔍 " s) "callback_data" (str "more:" (truncate-cb s 58))}])
                              (take 3 suggestions))})]
        (log/info :stream-summary :final-len (count agent-text) :cards (count all-cards) :thread-id thread-id)
        ;; Delete status message now — we have the real result
        (when-let [mid (some-> status-msg deref (get "message_id"))]
          (try (tg/delete-message chat-id mid) (catch Exception _)))
        ;; Send final Rich Message (text analysis with links)
        (render-and-send chat-id user-id text reply :keyboard more-btn
                         :photos-already-sent? true
                         :thread-id thread-id)
        ;; Photos AFTER text
        (when (seq all-cards)
          (let [photo-items (mapv (fn [c]
                                    {:url (:image c)
                                     :caption (str "<b>" (render/escape-html (:title c "")) "</b>"
                                                   (when-let [p (:price c)]
                                                     (str " — " (render/format-price p) " " (or (:currency c) "сом"))))})
                                  (take 5 (filter #(not (str/blank? (:image %)))
                                                  (filter :image all-cards))))]
            (when (seq photo-items)
              (log/info :send-photos :count (count photo-items))
              (try (tg/send-media-group chat-id photo-items :thread-id thread-id)
                   (catch Exception _)))))
        ;; Keep numeric drill-down callbacks user-scoped while the LLM session
        ;; remains topic-scoped.
        (when (seq all-cards)
          (t/cache-ads! user-uid all-cards))
        ;; Rename topic
        (let [topic-name (topic-name-for-query effective-query)]
          (rename-topic! chat-id user-uid topic-name))
        ;; Track button
        (when (and (seq all-cards) (> (count effective-query) 3))
          (try
            (Thread/sleep 500)
            (let [track-btn (track-context-button user-id effective-query)]
              (tg/send-message chat-id (str "🔔 Хотите отслеживать «" effective-query "»?")
                               :reply_markup track-btn :thread-id thread-id))
            (catch Exception e
              (log/warn e :track-button-fail)))))
      (catch Exception e
        (log/error e :agent-error {:user-id user-uid :session-id session-id})
        (when-let [mid (some-> status-msg deref (get "message_id"))]
          (try (tg/delete-message chat-id mid) (catch Exception _)))
        (try (tg/send-message chat-id "❌ Ошибка. Попробуйте ещё раз." :parse-mode nil :thread-id thread-id)
             (catch Exception _))))))

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


(defn- handle-update-now
  "Process one normalized Telegram update on its conversation's single-flight
   worker. This function is intentionally synchronous."
  [parsed]
  (if (:callback-id parsed)
    (handle-callback parsed)
    (when-let [raw-text (:text parsed)]
      (let [text (str/trim raw-text)]
        (cond
          (= text "🔄 Новый диалог") (handle-reset parsed)
          (= text "📍 Город") (handle-city parsed)
          (= text "🔔 Отслеживание") (handle-tracking parsed)

          (str/starts-with? text "/")
          (let [cmd (first (str/split text #"\s+"))]
            (log/info :command-detected :cmd cmd)
            (case cmd
              "/start" (handle-start parsed)
              "/help" (handle-help parsed)
              "/prices" (handle-prices parsed)
              "/reset" (handle-reset parsed)
              "/tracking" (handle-tracking parsed)
              nil))

          (get fast-responses (str/lower-case text))
          (tg/send-md (:chat-id parsed)
                      (get fast-responses (str/lower-case text))
                      :thread-id (:thread-id parsed))

          (not (str/blank? text))
          (handle-orchestrated parsed)

          :else nil)))))

(declare dispatch-conversation!)

(defn- process-conversation!
  [initial]
  (let [id (conversation-id initial)]
    (try
      (loop [current initial]
        (handle-update-now current)
        (when-let [pending (take-pending! id)]
          (recur pending)))
      (finally
        (release! id)
        ;; Close the handoff race: an update can arrive after the loop's last
        ;; take but before release.
        (when-let [pending (take-pending! id)]
          (dispatch-conversation! pending))))))

(defn- dispatch-conversation!
  "Run at most one update per conversation. While busy, retain the newest
   pending update instead of corrupting session ordering or growing a queue."
  [parsed]
  (let [id (conversation-id parsed)]
    (if (try-acquire! id)
      (when-not (handler-future #(process-conversation! parsed))
        (release! id))
      (do
        (store-pending! id parsed)
        (log/info :conversation-update-coalesced :conversation-id id)))))

(defn extended-handler
  "Normalize transport concurrency for messages and callback queries."
  [parsed]
  (if (:callback-id parsed)
    (log/info :extended-handler :callback (:callback-id parsed)
              :data (:data parsed) :thread-id (:thread-id parsed))
    (log/info :extended-handler :text (:text parsed)
              :chat-id (:chat-id parsed) :thread-id (:thread-id parsed)))
  (dispatch-conversation! parsed)
  nil)

(defn start-polling
  "Start polling. clj-harness parses raw updates; extended-handler performs
   exactly one bounded async dispatch."
  [& {:keys [interval-ms] :or {interval-ms 1500}}]
  (tg/poll-loop extended-handler
                :interval-ms interval-ms
                :allowed-updates ["message" "callback_query"]))

