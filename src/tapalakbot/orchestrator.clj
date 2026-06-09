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

;; ════════════════════ DEFAULT CONFIG ════════════════════

(def ^:private default-model :kimi-k2)
(def ^:private default-provider :openrouter)

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
        {:avg      (:avg_price match)
         :min      (:min_price match)
         :max      (:max_price match)
         :count    (:item_count match)
         :category (:name match)}))
    (catch Exception _ nil)))

;; ════════════════════ LLM CURATOR ════════════════════

(def ^:private curator-prompt
  "You are a marketplace curator for a Telegram bot. Return ONLY valid JSON — no markdown, no commentary.

Format:
{\"selected\":[0,2,4],\"intro\":\"Found 5 routers on Lalafo.kg\",\"cta\":\"Filter by price?\",\"assumptions\":[\"Prices in KGS\"]}

Rules:
- selected: array of 4-6 indices (0-based). Pick only the best items. MAX 6 items.
- intro: 1 sentence, up to 80 chars. Mention count and platform. Be specific.
- cta: 1 short question/suggestion, up to 50 chars
- assumptions: 0-1 line about what you assumed
- NO markdown, NO bullet points, NO emoji lists. ONLY JSON.")

(defn- sanitize-intro
  "Strip markdown, bullets, and excessive formatting from curator intro.
   Returns a clean single-line string."
  [s]
  (when s
    (-> (str s)
        (str/replace #"\*\*" "")              ;; remove **bold**
        (str/replace #"\*" "")                ;; remove *italic*
        (str/replace #"^[•\-\d\.]+\s*" "")    ;; remove leading bullets/numbers
        (str/replace #"\n+" " ")              ;; collapse newlines
        str/trim
        (subs 0 (min 100 (count s))))))       ;; cap at 100 chars

(defn- parse-curated-response
  "Parse LLM curator response into structured data."
  [content cards-count]
  (try
    (let [stripped (-> (or content "")
                       (str/replace #"```json\s*" "")
                       (str/replace #"```\s*" ""))
          json-str (or (re-find #"(?s)\{.*\}" stripped) "{}")
          parsed (try
                   (cheshire.core/parse-string json-str true)
                   (catch Exception _ {}))
          ;; Cap at 6 items max
          raw-selected (:selected parsed)
          selected-idx (if (and (vector? raw-selected) (seq raw-selected))
                         (vec (take 6 raw-selected))
                         (vec (range (min 6 cards-count))))]
      {:intro       (or (sanitize-intro (:intro parsed)) "Нашёл варианты")
       :cta         (:cta parsed "Хотите уточнить?")
       :assumptions (or (:assumptions parsed) [])
       :selected-idx selected-idx})
    (catch Exception e
      (log/warn :curator-parse-failed (.getMessage e))
      {:intro        "Нашёл варианты"
       :cta          "Хотите уточнить?"
       :assumptions  []
       :selected-idx (vec (range (min 6 cards-count)))})))

(defn- call-curator
  "Call LLM to curate search results. Returns curated reply map."
  [user-query cards stats model provider]
  (try
    (let [market-ctx  (get-market-context user-query)
          results-text (str/join "\n"
                        (map-indexed
                         (fn [i c]
                           (str i ". " (:title c)
                                " — " (:price c) " " (or (:currency c) "KGS")
                                (when (:url c) (str " | " (:url c)))
                                (when (:year c) (str " | " (:year c) " yr"))
                                (when (:city c) (str " | " (:city c)))))
                         cards))
          context (str "User query: " user-query "\n"
                       (when market-ctx
                         (str "Market avg: " (long (:avg market-ctx)) " KGS\n"))
                       "Results (" (count cards) " items):\n" results-text)
          messages [{"role" "system" "content" curator-prompt}
                    {"role" "user" "content" context}]
          resp    (llm/llm model messages [] :provider provider :max-tokens 500 :timeout-ms 30000)
          content (get-in resp ["choices" 0 "message" "content"])]
      (parse-curated-response content (count cards)))
    (catch Exception e
      (log/warn :curator-call-failed (.getMessage e))
      {:intro        (str "Нашёл " (count cards) " вариантов")
       :cta          "Хотите уточнить?"
       :assumptions  []
       :selected-idx (vec (range (min 6 (count cards))))})))

;; ════════════════════ COMPARISON ════════════════════

(def ^:private compare-prompt
  "You are a marketplace assistant. Compare two products based on search results.
Return ONLY valid JSON:
{\"intro\":\"Comparing iPhone 13 vs Samsung S21…\",\"comparison_points\":[\"iPhone is cheaper\",\"Samsung has better camera\"],\"verdict\":\"For budget, pick iPhone. For camera, pick Samsung.\",\"cta\":\"Want to search for one of these?\"}")

(defn- compare-products
  "Run searches for both items and produce a comparison."
  [text model provider]
  (try
    ;; Extract two items from comparison query
    (let [parts (str/split text #"\s+(?:vs|или|versus|and|и|vs\.|против)\s+" 2)
          ;; If no vs/or detected, search the whole text
          [item1 item2] (if (= (count parts) 2)
                          [(first parts) (second parts)]
                          [text (re-find #"и\s+(\S+)" (str/lower-case text))])
          _ (when item2 nil) ;; ensure item2 binding
          ;; Search both
          _ (log/info :compare :item1 item1 :item2 item2)
          result1 (search/search (or item1 text) {:use-llm? false})
          result2 (search/search (or item2 item1) {:use-llm? false})
          context (str "Comparison requested: " text "\n\n"
                       "Results for \"" (or item1 text) "\" (" (count (:cards result1)) " items):\n"
                       (str/join "\n" (map #(str (:title %) " — " (:price %) " " (:currency %))
                                            (take 5 (:cards result1)))) "\n\n"
                       "Results for \"" (or item2 item1) "\" (" (count (:cards result2)) " items):\n"
                       (str/join "\n" (map #(str (:title %) " — " (:price %) " " (:currency %))
                                            (take 5 (:cards result2)))))
          messages [{"role" "system" "content" compare-prompt}
                    {"role" "user" "content" context}]
          resp    (llm/llm model messages [] :provider provider :max-tokens 500 :timeout-ms 30000)
          content (get-in resp ["choices" 0 "message" "content"])
          json-str (or (re-find #"(?s)\{.*\}" (or content "{}")) "{}")
          parsed (try (cheshire.core/parse-string json-str true) (catch Exception _ {}))]
      {:mode  :shortlist
       :intro (or (sanitize-intro (:intro parsed))
                  (str "Comparing " (or item1 "items") " vs " (or item2 "alternatives")))
       :cards []
       :cta   (:cta parsed "Want to search for one of these?")
       :assumptions (or (:assumptions parsed) [])})
    (catch Exception e
      (log/warn :compare-failed (.getMessage e))
      {:mode :shortlist
       :intro "🔍 Воспользуйтесь поиском — напишите что ищете, и я покажу варианты для сравнения."
       :cards [] :cta nil :assumptions []})))

;; ════════════════════ FAST PATH REPLIES ════════════════════

(def ^:private greeting-reply
  {:mode :shortlist
   :intro (str "👋 Салам! Я TapalakBot — помогу найти товары на Lalafo.kg\n\n"
               "Просто напишите что ищете! 🔍")
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

;; ════════════════════ REFINE ════════════════════

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

(defn- do-search
  "Run full search+curate+render pipeline for a text query.
   Returns {:mode :shortlist ...} or {:mode :no-results ...}."
  [text session {:keys [status-cb model provider]}]
  (let [{:keys [cards stats platforms query] :as result}
        (search/search text {:use-llm? true})]
    (if (empty? cards)
      {:mode :no-results
       :intro (str "Ничего не нашёл по «" (subs text 0 (min 50 (count text))) "». Попробуйте другой запрос.")
       :cards [] :cta nil :assumptions []}
      (let [_           (when status-cb (status-cb (str "📊 Processing " (count cards) " results...")))
            curated     (call-curator query cards stats model provider)
            selected    (mapv #(get cards %) (:selected-idx curated))
            final-cards (mapv (fn [card]
                                (let [tier (render/assign-tier (:price card) (:avg stats))]
                                  (assoc card :tier (or tier :good))))
                              selected)]
        (patch-session! session {:last-search     query
                                 :last-platforms   platforms
                                 :last-price-max   (:price-max result)
                                 :last-price-min   (:price-min result)
                                 :last-category    (cond
                                                     (:is-auto? (:qb-result result)) :auto
                                                     (:is-electronics? (:qb-result result)) :electronics
                                                     (:is-real-estate? (:qb-result result)) :real-estate
                                                     :else :general)
                                 :last-card-count  (count final-cards)})
        (when status-cb (status-cb "✨ Curating best picks..."))
        {:mode           :shortlist
         :intro          (:intro curated)
         :cards          final-cards
         :cta            (:cta curated)
         :assumptions    (:assumptions curated)
         :platforms-used platforms
         :query          query}))))

(defn orchestrate
  "Main entry point. Takes user message + session, returns structured reply.
   Options: :model :provider :status-cb"
  [text session & {:keys [model provider status-cb]
                   :or {model default-model provider default-provider}}]
  (let [state (get-session-data session)
        mode  (policy/classify text state)]
    (log/info :orchestrate :mode mode :text (let [t (or text "")]
                                              (subs t 0 (min (count t) 50))))
    (case mode

      ;; ── Fast paths (no search, no LLM) ──
      :greeting
      (if (:last-search state)
        {:mode :shortlist
         :intro (str "👋 Салам! Returning to «" (:last-search state) "»?\n\n"
                     "Or write a new query!")
         :cards [] :cta nil :assumptions []}
        greeting-reply)
      :thanks    thanks-reply
      :help      help-reply
      :reset     {:mode :reset}
      :tracking  {:mode :tracking}

      ;; ── Search ──
      :search
      (do-search text session {:status-cb status-cb :model model :provider provider})

      ;; ── Refine ──
      :refine
      (let [last-search  (or (:last-search state) text)
            refined      (apply-refine last-search text state)
            result       (do-search (:query refined) session
                                    {:status-cb status-cb :model model :provider provider})]
        (-> result
            (assoc :mode :refine)
            (update :assumptions into (vec (:assumptions refined)))
            (assoc :query (:query refined))))

      ;; ── Compare ──
      :compare
      (compare-products text model provider)

      ;; ── Unknown ──
      ;; Try searching anyway — users type typos, brands, model names that
      ;; the regex won't catch. Only show help for very short/gibberish text.
      (if (and text (> (count (str/trim text)) 3))
        (do (log/info :unknown-but-trying-search :text text)
            (do-search text session {:status-cb status-cb :model model :provider provider}))
        {:mode  :no-results
         :intro "🤔 Напишите, что ищете — например, «найди iphone 13»."
         :cards [] :cta nil :assumptions []}))))
