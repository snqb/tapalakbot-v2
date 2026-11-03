(ns tapalakbot.bot
  "Telegram bot for TapalakBot v2.
  Agent-first architecture — clj-harness agent with tools handles intent + response.
  Button-based tracking UI via inline keyboards.
  Persistent menu: [🔄 Новый диалог] [🔔 Отслеживание]"
  (:require [tapalakbot.core :as t]
            [tapalakbot.bot-state :as state]
            [tapalakbot.render :as render]
            [tapalakbot.monitor.client :as monitor]
            [tapalakbot.telegram-conversation :as conversation]
            [tapalakbot.tracking-ui :as tracking-ui]
            [tapalakbot.locations :as locations]
             [tapalakbot.result-order :as result-order]
             [tapalakbot.result-store :as result-store]
             [tapalakbot.result-delivery :as delivery]
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


;; ══════════════════════ CALLBACK ROUTER ══════════════════════


(defn- set-city! [uid city-id]
  (let [st (state/get-user-state uid)]
    (reset! (:city-id st) city-id)))

(defn- answer-stale-result! [callback-id]
  (tg/answer-callback-query
   callback-id :show-alert true
   :text "Эта выдача устарела. Откройте последний список вариантов или повторите поиск."))

(defn- handle-callback
  "Handle inline keyboard callback queries."
  [{:keys [callback-id data user-id chat-id msg-id thread-id] :as message}]
  (log/info :callback-received :data data :user user-id :chat chat-id :msg msg-id)
  (try
    (cond
    ;; === CITY SELECTION ===
      (str/starts-with? data "city:")
      (let [value (subs data 5)
            city-id (when-not (= "nil" value) (Long/parseLong value))]
        (tg/answer-callback-query callback-id :text (if city-id "✅ Город выбран!" "✅ Весь Кыргызстан"))
        (set-city! (str "tg-" user-id) city-id))

      (tracking-ui/handle-callback! message) nil

     ;; === SORT CACHED RESULTS ===
      (re-matches #"sort:(price-asc|price-desc|relevance|recommended):([A-Za-z0-9]+)" data)
      (let [[_ mode-token cursor-token]
            (re-matches #"sort:(price-asc|price-desc|relevance|recommended):([A-Za-z0-9]+)" data)
            mode (keyword mode-token)
            owner-id (state/conversation-id {:chat-id chat-id
                                             :user-id user-id
                                             :thread-id thread-id})]
        (if-let [{:keys [cards pool query context cursor-id sort-mode total has-more]}
                 (result-store/sort-result-pool! owner-id cursor-token mode
                                                 result-store/page-size)]
          (do
            (tg/answer-callback-query callback-id
                                      :text (str "Сортировка: "
                                                 (result-order/mode-label sort-mode)))
            (result-store/cache-ads! owner-id pool)
            (let [context (assoc (or context {}) :query query :cards pool
                                 :sort-mode sort-mode)]
              (delivery/send-reply!
               chat-id
               {:intro ""
                :cards cards
                :selection (:selection context)
                :collection (:collection context)
                :sort-mode sort-mode
                :pool-count total}
               {:keyboard (delivery/results-keyboard
                           query cursor-id has-more owner-id context)
                :thread-id thread-id}))
            (t/remember-view! owner-id cards))
          (answer-stale-result! callback-id)))

    ;; === NEXT CACHED RESULT PAGE ===
      (re-matches #"page:([A-Za-z0-9]+)" data)
      (let [[_ cursor-id] (re-matches #"page:([A-Za-z0-9]+)" data)
            owner-id (state/conversation-id {:chat-id chat-id
                                             :user-id user-id
                                             :thread-id thread-id})
            {:keys [status cards query start end total has-more context sort-mode]}
            (result-store/next-result-page! owner-id cursor-id result-store/page-size)]
        (case status
          :page
          (do
            (tg/answer-callback-query callback-id
                                      :text (str "Показываю " start "–" end " из " total))
            (delivery/send-reply!
             chat-id
             {:intro (str "## Ещё варианты\nПозиции " start "–" end " из " total ".")
              :cards cards :selection (:selection context) :collection (:collection context)
              :sort-mode sort-mode :pool-count total}
             {:keyboard (delivery/results-keyboard
                         query cursor-id has-more owner-id
                         (or context {:query query :cards cards}))
              :thread-id thread-id})
            (t/remember-view! owner-id cards)
            (log/info :result-page-sent :user owner-id :start start :end end
                      :total total :has-more has-more))
          :exhausted
          (tg/answer-callback-query
           callback-id :text (str "Все " total " вариантов уже показаны. "
                                  "Смените сортировку или уточните запрос."))
          :stale (answer-stale-result! callback-id)))

    ;; === MORE RESULTS ===
      (re-matches #"more:(.+)" data)
      (let [[_ query] (re-matches #"more:(.+)" data)]
        (tg/answer-callback-query callback-id :text "Ищу ещё...")
        (conversation/handle-message! {:chat-id chat-id :user-id user-id
                                       :thread-id thread-id
                                       :text (str "найди ещё " query)}))

    ;; === PRICE REFINEMENTS ===
      (re-matches #"(cheaper|dearer):(.+)" data)
      (let [[_ direction-token callback-token] (re-matches #"(.+?):(.+)" data)
            direction (keyword direction-token)
            owner-id (state/conversation-id {:chat-id chat-id
                                             :user-id user-id
                                             :thread-id thread-id})]
        (if-let [refined (delivery/refinement-message owner-id direction callback-token)]
          (do
            (tg/answer-callback-query callback-id
                                      :text (str "Ищу "
                                                 (if (= direction :cheaper) "до " "от ")
                                                 (render/format-price
                                                  (or (:price-max refined) (:price-min refined)))
                                                 " сом…"))
            (conversation/handle-message! (merge {:chat-id chat-id :user-id user-id
                                         :thread-id thread-id}
                                        refined)))
          (answer-stale-result! callback-id)))

    ;; === DRILL-DOWN: Show detailed ad ===
      (re-matches #"ad:(\d+)" data)
      (let [[_ idx-str] (re-matches #"ad:(\d+)" data)
            idx (Integer/parseInt idx-str)
            owner-id (state/conversation-id {:chat-id chat-id
                                             :user-id user-id
                                             :thread-id thread-id})
            ad (result-store/get-ad owner-id idx)]
        (tg/answer-callback-query callback-id)
        (if ad
          (let [card-text (str "<b>" (render/escape-html (:title ad)) "</b>\n\n"
                               "💰 " (render/format-money (:price ad) (:currency ad)) "\n"
                               (when (:desc ad) (str "\n" (render/escape-html (:desc ad)) "\n")) "\n"
                               "📍 " (render/escape-html (or (:city ad) "Город не указан")) "\n\n"
                               "<a href=\"" (render/escape-html (:url ad)) "\">🔗 Открыть на площадке</a>")
                kb {"inline_keyboard"
                    [[{"text" "🔗 Открыть на Lalafo" "url" (:url ad)}]]}]
            (try
              (doseq [chunk (tg/split-message card-text)]
                (tg/send-message chat-id chunk :parse-mode "HTML"
                                 :reply_markup kb :thread-id thread-id))
              (catch Exception e
                (log/error e :drilldown-send-failed))))
          (tg/send-md chat-id "❌ Объявление не найдено в кеше. Попробуйте новый поиск."
                      :thread-id thread-id)))

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
                        (conversation/ensure-topic! chat-id user-uid topic-name)))
        session-id (state/conversation-id (assoc msg :thread-id thread-id))]
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
        current-id (state/conversation-id msg)]
    (hc/reset-session! @t/tapalakbot current-id)
    (state/store-pending! current-id nil)
    (let [topic-name (str "🔄 " (.format (java.time.LocalDateTime/now)
                                         (java.time.format.DateTimeFormatter/ofPattern "HH:mm")))
          thread-id (when-not (pos? chat-id)
                      (conversation/ensure-topic! chat-id user-uid topic-name))
          session-id (state/conversation-id (assoc msg :thread-id thread-id))]
      (when (not= current-id session-id)
        (hc/reset-session! @t/tapalakbot session-id))
      (send-menu! chat-id "🗑️ Контекст очищен. Начнём заново!"
                  (when thread-id {:thread-id thread-id}))))
  nil)


(defn- handle-city [{:keys [chat-id thread-id]}]
  (tg/send-message chat-id "📍 Выберите город для поиска:"
                   :reply_markup (locations/keyboard)
                   :thread-id thread-id)
  nil)

(defn- handle-tracking [message]
  (tracking-ui/show-list! message)
  nil)

(defn- handle-help [{:keys [chat-id thread-id]}]
  (send-menu!
   chat-id
   "*TapalakBot* — поиск на Lalafo.kg\n\n🔍 Поиск товаров\n🔔 Отслеживание новых объявлений\n📊 Рыночные цены\n\nПросто опиши что ищешь!"
   {:thread-id thread-id})
  nil)

(defn- handle-prices
  [{:keys [chat-id text thread-id]}]
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
            (tg/send-md chat-id (str/join "\n" lines) :thread-id thread-id))
          (tg/send-md chat-id "⚠️ Мониторинг пока не запущен." :thread-id thread-id)))
      (let [results (monitor/search-items query)]
        (if (and results (pos? (:count results)))
          (tg/send-md chat-id (monitor/format-search-results results) :thread-id thread-id)
          (tg/send-md chat-id (str "🔍 Ничего не найдено по запросу «" query "»")
                      :thread-id thread-id))))))


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
          (conversation/handle-message! parsed)

          :else nil)))))

(declare dispatch-conversation!)

(defn- process-conversation!
  [initial]
  (let [id (state/conversation-id initial)]
    (try
      (loop [current initial]
        (handle-update-now current)
        (when-let [pending (state/take-pending! id)]
          (recur pending)))
      (finally
        (state/release! id)
        ;; Close the handoff race: an update can arrive after the loop's last
        ;; take but before release.
        (when-let [pending (state/take-pending! id)]
          (dispatch-conversation! pending))))))

(defn- dispatch-conversation!
  "Run at most one update per conversation. While busy, retain the newest
   pending update instead of corrupting session ordering or growing a queue."
  [parsed]
  (let [id (state/conversation-id parsed)]
    (if (state/try-acquire! id)
      (when-not (handler-future #(process-conversation! parsed))
        (state/release! id))
      (do
        (state/store-pending! id parsed)
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

