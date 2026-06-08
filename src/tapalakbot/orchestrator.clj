(ns tapalakbot.orchestrator
  "The orchestrator — glues policy, search, LLM, and render.
   This is the tg-agent turn policy + agent + deterministic rails, all in one."
  (:require [tapalakbot.policy :as policy]
            [tapalakbot.search :as search]
            [tapalakbot.render :as render]
            [tapalakbot.monitor.store :as monitor-store]
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
  "You are a marketplace assistant curator. Given search results, pick the best 5-8 items
and write a brief Russian intro + CTA.

Return ONLY a JSON object:
{
  \"selected\": [0, 2, 4, 5, 7],
  \"tiers\": {\"0\": \"great\", \"2\": \"good\", \"4\": \"good\", \"5\": \"premium\", \"7\": \"premium\"},
  \"intro\": \"Нашёл iPhone 13 на Lalafo.kg — 8 вариантов!\",
  \"cta\": \"Хотите сузить по бюджету или состоянию?\",
  \"assumptions\": [\"Цены в сомах\"]
}

Rules:
- selected: indices of best items (0-based) from the results list
- tiers: \"great\" (best price), \"good\" (fair), \"premium\" (expensive)
- intro: 1 line, Russian, include platform name and count
- cta: 1 line suggestion for next action
- assumptions: 0-2 lines about what you assumed (price currency, condition, etc.)
- Keep intro under 100 chars, CTA under 60 chars")

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
                           (vec (range (min 8 cards-count))))
          tiers (:tiers parsed {})]
      {:intro       (:intro parsed "Нашёл варианты")
       :cta         (:cta parsed "Хотите уточнить?")
       :assumptions (or (:assumptions parsed) [])
       :selected-idx selected-idx
       :tiers       (into {}
                          (map (fn [[k v]]
                                 (let [key-long (cond
                                                  (string? k) (parse-long k)
                                                  (keyword? k) (parse-long (name k))
                                                  (number? k) (long k)
                                                  :else nil)]
                                   [key-long (keyword (str v))]))
                               tiers))})
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

;; ════════════════════ ORCHESTRATOR ════════════════════

(defn orchestrate
  "Main entry point. Takes user message + session, returns structured reply.

   Returns: {:mode :shortlist :intro \"...\" :cards [...] :cta \"...\" :assumptions [...]}
   Or for fast paths: {:mode :shortlist :intro \"...\" :cards []}"
  [text session]
  (let [state (get-session-data session)
        mode  (policy/classify text state)]
    (log/info :orchestrate :mode mode :text (let [t (or text "")]
                                              (subs t 0 (min (count t) 50))))
    (case mode

      ;; ── Fast paths (no search, no LLM) ──
      :greeting  greeting-reply
      :thanks    thanks-reply
      :help      help-reply
      :reset     {:mode :reset}
      :tracking  {:mode :tracking}

      ;; ── Search paths ──
      :search
      (let [{:keys [cards stats platforms query]}
            (search/search text {:use-llm? true})]
        (if (empty? cards)
          no-results-reply
          (let [curated    (call-curator query cards stats)
                selected   (mapv #(get cards %) (:selected-idx curated))
                ;; Apply tier overrides from curator
                final-cards (mapv
                             (fn [i card]
                               (if-let [tier (get (:tiers curated) i)]
                                 (assoc card :tier tier)
                                 card))
                             (:selected-idx curated)
                             selected)]
            (patch-session! session {:last-search  query
                                    :last-platforms platforms})
            {:mode           :shortlist
             :intro          (:intro curated)
             :cards          final-cards
             :cta            (:cta curated)
             :assumptions    (:assumptions curated)
             :platforms-used platforms
             :query          query})))

      :refine
      (let [last-search   (or (:last-search state) text)
            refined-query (str last-search " " text)
            {:keys [cards stats platforms query]}
            (search/search refined-query {:use-llm? true})]
        (if (empty? cards)
          no-results-reply
          (let [curated  (call-curator query cards stats)
                selected (mapv #(get cards %) (:selected-idx curated))]
            (patch-session! session {:last-search refined-query})
            {:mode           :refine
             :intro          (:intro curated)
             :cards          selected
             :cta            (:cta curated)
             :assumptions    (conj (vec (:assumptions curated))
                                   (str "Поиск: " refined-query))
             :platforms-used platforms
             :query          refined-query})))

      :compare
      {:mode  :shortlist
       :intro "🔍 Воспользуйтесь поиском — напишите что ищете, и я покажу варианты для сравнения."
       :cards [] :cta nil :assumptions []}

      ;; ── Unknown: signal to bot.clj to use LLM agent ──
      {:mode  :unknown
       :llm-context {:text text :session-state state}})))
