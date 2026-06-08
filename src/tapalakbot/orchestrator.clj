(ns tapalakbot.orchestrator
  "The orchestrator — glues policy, search, LLM, and render.
   This is the tg-agent turn policy + agent + deterministic rails, all in one."
  (:require [tapalakbot.policy :as policy]
            [tapalakbot.search :as search]
            [tapalakbot.render :as render]
            [tapalakbot.monitor.store :as monitor-store]
            [tapalakbot.query-builder :as qb]
            [clj-harness.llm :as llm]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════ SESSION STATE ════════════════════

(defn get-session-data
  "Get structured state from session data map."
  [session]
  (when session
    (get @session "data" {})))

(defn patch-session!
  "Merge state patches into session data."
  [session patch]
  (when (and session (map? patch))
    (swap! session update "data" merge patch)))

;; ════════════════════ MARKET ENRICHMENT ════════════════════

(defn- get-market-context
  "Get market stats for a product category from monitor DB."
  [product-type]
  (try
    (let [categories (monitor-store/get-category-summary)
          match (some #(when (str/includes?
                              (str/lower-case (or (:name %) ""))
                              (str/lower-case (or product-type "")))
                         %)
                      categories)]
      (when match
        {:avg  (:avg_price match)
         :min  (:min_price match)
         :max  (:max_price match)
         :count (:item_count match)
         :category (:name match)}))
    (catch Exception _ nil)))

;; ════════════════════ LLM CURATOR ════════════════════

(def ^:private curator-prompt
  "Ты куратор маркетплейса для бота по поиску товаров на Lalafo.kg (Кыргызстан).

Даны результаты поиска. Выбери лучшие 5-8 товаров и напиши краткое вступление + CTA на русском.

Верни ТОЛЬКО JSON:
{
  \"selected\": [0, 2, 4, 5, 7],
  \"intro\": \"Нашёл iPhone 13 на Lalafo.kg — 8 вариантов!\",
  \"cta\": \"Хотите сузить по бюджету или состоянию?\",
  \"assumptions\": [\"Цены в сомах\"]
}

Правила:
- selected: индексы лучших товаров (0-based). Пропускай аксессуары и мусор.
- intro: 1 строка, русский, упомяни платформу и количество. Будь конкретен.
- cta: 1 строка — предложение следующего действия (фильтр по цене, состоянию, локации)
- assumptions: 0-2 строки о предположениях (валюта, состояние, регион)
- Вступление до 100 символов, CTA до 60 символов")

(defn- parse-curated-response
  "Parse LLM curator response into structured data."
  [content cards-count]
  (try
    (let [;; Strip markdown code fences (```json ... ```) before extracting JSON
          stripped (-> (or content "")
                       (str/replace #"```json\s*" "")
                       (str/replace #"```\s*" ""))
          json-str (or (re-find #"(?s)\{.*\}" stripped) "{}")
          parsed (try
                   (cheshire.core/parse-string json-str true)
                   (catch Exception _
                     {}))
          selected-idx (or (:selected parsed)
                           (vec (range (min 8 cards-count))))]
     {:intro       (:intro parsed "Нашёл варианты")
      :cta         (:cta parsed "Хотите уточнить?")
      :assumptions (or (:assumptions parsed) [])
      :selected-idx selected-idx})
    (catch Exception e
      (log/warn :curator-parse-failed (.getMessage e))
      {:intro         "Нашёл варианты"
       :cta           "Хотите уточнить?"
       :assumptions   []
       :selected-idx  (vec (range (min 8 cards-count)))
       :tiers         {}})))

(defn- call-curator
  "Call LLM to curate search results. Returns curated reply map."
  [user-query cards stats]
  (try
    (let [market-ctx (get-market-context user-query)
          results-text (str/join "\n"
                                 (map-indexed
                                  (fn [i c]
                                    (str i ". " (:title c)
                                         " — " (:price c) " " (or (:currency c) "KGS")
                                         (when (:url c) (str " | " (:url c)))
                                         (when (:year c) (str " | " (:year c) " г."))
                                         (when (:city c) (str " | " (:city c)))))
                                  cards))
          context (str "User query: " user-query "\n"
                       (when market-ctx
                         (str "Market avg: " (long (:avg market-ctx)) " KGS\n"))
                       "Results (" (count cards) " items):\n" results-text)
          messages [{"role" "system" "content" curator-prompt}
                    {"role" "user" "content" context}]
          resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 500)
          content (get-in resp ["choices" 0 "message" "content"])]
      (parse-curated-response content (count cards)))
    (catch Exception e
      (log/warn :curator-call-failed (.getMessage e))
      {:intro         (str "Нашёл " (count cards) " вариантов")
       :cta           "Хотите уточнить?"
       :assumptions   []
       :selected-idx  (vec (range (min 8 (count cards))))
       :tiers         {}})))

;; ════════════════════ FAST PATH REPLIES ════════════════════

(def ^:private greeting-reply
  {:mode :shortlist
   :intro (str "👋 Салам! Я TapalakBot — помогу найти товары на Lalafo.kg\n\n"
               "Напишите что ищете, и я:\n"
               "• Разберусь в товаре\n"
               "• Найду лучшие варианты\n"
               "• Проверю рыночные цены 🔍")
   :cards [] :cta nil :assumptions []})

(def ^:private thanks-reply
  {:mode :shortlist
   :intro "Пожалуйста! 😊 Если нужно найти что-то ещё — пишите."
   :cards [] :cta nil :assumptions []})

(def ^:private help-reply
  {:mode :shortlist
   :intro (str "🔍 <b>TapalakBot</b> — поиск на Lalafo.kg\n\n"
               "Просто напишите что ищете!\n\n"
               "🔔 После поиска нажмите «Отслеживать» — буду проверять каждые 24ч\n"
               "📊 /prices — рыночные цены\n"
               "🔄 Новый диалог — сбросить контекст")
   :cards [] :cta nil :assumptions []})

(def ^:private no-results-reply
  {:mode :no-results
   :intro "Ничего не найдено. Попробуйте изменить запрос."
   :cards [] :cta nil :assumptions []})


(defn- apply-refine
  "Apply refine keyword to existing search state."
  [last-search refine-text state]
  (let [t (str/lower-case refine-text)]
    (cond
      ;; Price down
      (some #(str/includes? t %) ["дешевле" "подешевле" "поменьше"])
      (let [old-max (or (:last-price-max state) 999999)
            new-max (long (* old-max 0.7))]
        {:query last-search :price-max new-max
         :assumptions [(str "Снизил бюджет до " (render/format-price new-max) " сом")]})

      ;; Price up
      (some #(str/includes? t %) ["дороже" "подороже" "получше"])
      (let [old-max (or (:last-price-max state) 999999)
            new-max (long (* old-max 1.5))]
        {:query last-search :price-max new-max
         :assumptions [(str "Поднял бюджет до " (render/format-price new-max) " сом")]})

      ;; Location
      (some #(str/includes? t %) ["в бишкеке" "bishkek"])
      {:query (str last-search " Бишкек") :assumptions ["Фильтр: Бишкек"]}

      (some #(str/includes? t %) ["в оше" "osh"])
      {:query (str last-search " Ош") :assumptions ["Фильтр: Ош"]}

      ;; Condition
      (some #(str/includes? t %) ["только новые" "новые" "новый"])
      {:query (str last-search " новый") :assumptions ["Фильтр: новые"]}

      (some #(str/includes? t %) ["только б/у" "только бу" "б/у" "бу"])
      {:query (str last-search " б/у") :assumptions ["Фильтр: б/у"]}

      ;; Default
      :else {:query (str last-search " " refine-text) :assumptions []})))

;; ════════════════════ ORCHESTRATOR ════════════════════

(defn orchestrate
  "Main entry point. Takes user message + session, returns structured reply.

   Returns: {:mode :shortlist :intro \"...\" :cards [...] :cta \"...\" :assumptions [...]}
   Or for fast paths: {:mode :shortlist :intro \"...\" :cards []}"
  [text session & [status-cb]]
  (let [state (get-session-data session)
        mode  (policy/classify text state)]
    (log/info :orchestrate :mode mode :text (let [t (or text "")]
                                              (subs t 0 (min (count t) 50))))
    (case mode

      ;; ── Fast paths (no search, no LLM) ──
      :greeting
      (if (:last-search state)
        {:mode :shortlist
         :intro (str "👋 Салам! Возвращаемся к «" (:last-search state) "»?\n\n"
                      "Или напишите новый запрос!")
         :cards [] :cta nil :assumptions []}
        greeting-reply)
      :thanks    thanks-reply
      :help      help-reply
      :reset     {:mode :reset}
      :tracking  {:mode :tracking}

      ;; ── Search paths ──
      :search
      (let [_ (when status-cb (status-cb "🔍 Ищу на Lalafo.kg..."))
            qb-result (try (tapalakbot.query-builder/build text :use-llm? true) (catch Exception _ {}))
            {:keys [cards stats platforms query]}
            (search/search text {:use-llm? true})]
        (if (empty? cards)
          no-results-reply
          (let [_ (when status-cb (status-cb (str "📊 Обрабатываю " (count cards) " результатов...")))
                curated    (call-curator query cards stats)
                selected   (mapv #(get cards %) (:selected-idx curated))
                ;; Deterministic tier assignment from stats
                final-cards (mapv
                             (fn [card]
                               (let [tier (render/assign-tier (:price card) (:avg stats))]
                                 (assoc card :tier (or tier :good))))
                             selected)]
            (patch-session! session {:last-search     query
                                    :last-platforms   platforms
                                    :last-price-max   (:price-max qb-result)
                                    :last-price-min   (:price-min qb-result)
                                    :last-category    (cond
                                                        (:is-auto? qb-result) :auto
                                                        (:is-electronics? qb-result) :electronics
                                                        (:is-real-estate? qb-result) :real-estate
                                                        :else :general)
                                    :last-card-count  (count final-cards)})
            (when status-cb (status-cb "✨ Подбираю лучшие..."))
            {:mode           :shortlist
             :intro          (:intro curated)
             :cards          final-cards
             :cta            (:cta curated)
             :assumptions    (:assumptions curated)
             :platforms-used platforms
             :query          query})))

      :refine
      (let [_ (when status-cb (status-cb "🔍 Ищу на Lalafo.kg..."))
            last-search (or (:last-search state) text)
            refined (apply-refine last-search text state)
            {:keys [cards stats platforms query]}
            (search/search (:query refined) {:use-llm? true})]
        (if (empty? cards)
          no-results-reply
          (let [_ (when status-cb (status-cb (str "📊 Обрабатываю " (count cards) " результатов...")))
                curated  (call-curator query cards stats)
                selected (mapv
                          (fn [card]
                            (let [tier (render/assign-tier (:price card) (:avg stats))]
                              (assoc card :tier (or tier :good))))
                          (mapv #(get cards %) (:selected-idx curated)))]
            (patch-session! session {:last-search (:query refined)
                                     :last-price-max (:price-max refined)})
            (when status-cb (status-cb "✨ Подбираю лучшие..."))
            {:mode           :refine
             :intro          (:intro curated)
             :cards          selected
             :cta            (:cta curated)
             :assumptions    (into (vec (:assumptions curated)) (:assumptions refined))
             :platforms-used platforms
             :query          (:query refined)})))

      :compare
      {:mode  :shortlist
       :intro "🔍 Воспользуйтесь поиском — напишите что ищете, и я покажу варианты для сравнения."
       :cards [] :cta nil :assumptions []}

      ;; ── Unknown: signal to bot.clj to use LLM agent ──
      {:mode  :unknown
       :llm-context {:text text :session-state state}})))
