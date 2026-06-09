(ns tapalakbot.orchestrator
  "The orchestrator — glues policy, search, LLM, and render.
   This is the tg-agent turn policy + agent + deterministic rails, all in one."
  (:require [tapalakbot.policy :as policy]
            [tapalakbot.intent :as intent]
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

(def ^:private session-ttl-ms
  "Session conversation context expires after 30 minutes of inactivity."
  (* 30 60 1000))

(defn get-session-data
  "Get structured state from session data map.
   Returns empty map if session has been idle > 30 minutes (context expired)."
  [session]
  (when session
    (let [data (get @session "data" {})
          last-active (:last-active data 0)
          now (System/currentTimeMillis)]
      (if (and (pos? last-active)
               (> (- now last-active) session-ttl-ms))
        {}   ;; expired — return empty state so old context doesn't confuse intent classifier
        data))))

(defn patch-session!
  "Merge state patches into session data. Always updates last-active timestamp."
  [session patch]
  (when (and session (map? patch))
    (swap! session update "data" merge patch {:last-active (System/currentTimeMillis)})))

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
      {:mode  :compare
       :intro (or (sanitize-intro (:intro parsed))
                  (str "Comparing " (or item1 "items") " vs " (or item2 "alternatives")))
       :comparison (:comparison_points parsed)
       :verdict (:verdict parsed)
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
                                 :last-mode        :search
                                 :last-platforms   platforms
                                 :last-price-max   (:price-max result)
                                 :last-price-min   (:price-min result)
                                 :last-category    (cond
                                                     (:is-auto? (:qb-result result)) :auto
                                                     (:is-electronics? (:qb-result result)) :electronics
                                                     (:is-real-estate? (:qb-result result)) :real-estate
                                                     :else :general)
                                 :last-card-count  (count final-cards)
                                 :last-items       (mapv #(select-keys % [:title :price :currency :platform])
                                                        final-cards)})
        (when status-cb (status-cb "✨ Curating best picks..."))
        {:mode           :shortlist
         :intro          (:intro curated)
         :cards          final-cards
         :cta            (:cta curated)
         :assumptions    (:assumptions curated)
         :platforms-used platforms
         :query          query}))))

;; ════════════════════ RESEARCH ════════════════════

(defn- do-research
  "Research mode: market intelligence + curated picks.
   Falls back to search with market context enrichment when LLM synthesis unavailable."
  [text query session {:keys [status-cb model provider]}]
  (when status-cb (status-cb "📊 Анализирую рынок..."))
  (let [market-ctx (get-market-context query)
        search-result (search/search (or query text) {:use-llm? true})
        cards (:cards search-result)
        stats (:stats search-result)]
    (if (empty? cards)
      {:mode :no-results
       :intro (str "По «" (or query text) "» пока нет данных. "
                   "Попробуйте более общий запрос или другую категорию.")
       :cards [] :cta nil :assumptions []}
      (let [_ (when status-cb (status-cb (str "📊 " (count cards) " вариантов, анализирую...")))
            context (str "User query: " query "\n"
                         (when market-ctx
                           (str "MARKET DATA: " (:category market-ctx) "\n"
                                "  Price range: " (render/format-price (long (:min market-ctx)))
                                " – " (render/format-price (long (:max market-ctx))) " KGS\n"
                                "  Average: " (render/format-price (long (:avg market-ctx))) " KGS\n"
                                "  Items tracked: " (:count market-ctx) "\n\n"))
                         "LIVE RESULTS (" (count cards) " items):\n"
                         (str/join "\n"
                           (map-indexed
                            (fn [i c]
                              (str i ". " (:title c) " — " (:price c) " " (or (:currency c) "KGS")
                                   (when (:year c) (str " | " (:year c) " yr"))
                                   (when (:city c) (str " | " (:city c)))))
                            (take 20 cards))))
            messages [{"role" "system"
                       "content" "You are a marketplace research assistant for Kyrgyzstan. The user is exploring a product category. Give a SHORT market overview, pick 5-6 best items by index. Return ONLY JSON: {\"selected\":[0,2,4],\"intro\":\"your 1-2 sentence market overview with price range\",\"cta\":\"helpful follow-up question\",\"market_note\":\"brief stat\",\"assumptions\":[]}. NO markdown."}
                      {"role" "user" "content" context}]
            resp (try
                   (llm/llm (or model default-model) messages []
                            :provider (or provider default-provider)
                            :max-tokens 500 :timeout-ms 30000)
                   (catch Exception e
                     (log/warn :research-llm-failed (.getMessage e))
                     nil))
            content (get-in resp ["choices" 0 "message" "content"])
            parsed (try
                     (let [json-str (or (re-find #"(?s)\{.*\}" (or content "{}")) "{}")]
                       (cheshire.core/parse-string json-str true))
                     (catch Exception _ {}))
            raw-selected (:selected parsed)
            selected-idx (if (and (vector? raw-selected) (seq raw-selected))
                          (vec (take 6 raw-selected))
                          (vec (range (min 6 (count cards)))))
            selected-cards (mapv #(get cards %) selected-idx)
            final-cards (mapv (fn [card]
                               (let [tier (render/assign-tier (:price card) (:avg stats))]
                                 (assoc card :tier (or tier :good))))
                             selected-cards)]
        (patch-session! session {:last-search query
                                 :last-mode :research
                                 :last-card-count (count final-cards)
                                 :last-price-max (:price-max search-result)
                                 :last-price-min (:price-min search-result)})
        (when status-cb (status-cb "✨ Готовлю обзор..."))
        {:mode :research
         :intro (or (when (:intro parsed) (sanitize-intro (:intro parsed)))
                    (str "📊 " (or (:category market-ctx) query)
                         (when market-ctx
                           (str ": цены от " (render/format-price (long (:min market-ctx)))
                                " до " (render/format-price (long (:max market-ctx))) " сом"))
                         ". Вот лучшие варианты:"))
         :cards final-cards
         :cta (:cta parsed "Уточните бюджет или характеристики?")
         :assumptions (or (:assumptions parsed) [])
         :market-note (:market_note parsed)
         :platforms-used (:platforms search-result)
         :query query}))))

;; ════════════════════ FOLLOWUP ════════════════════

(defn- do-followup
  "Followup mode: answer questions about previously shown items."
  [text state {:keys [status-cb model provider]}]
  (let [last-search (or (:last-search state) "предыдущий запрос")
        items (:last-items state)
        item-count (or (:last-card-count state) 0)]
    (when status-cb (status-cb "💭 ..."))
    (if (empty? items)
      ;; No items to reference — generic response
      {:mode :shortlist
       :intro (str "По «" last-search "» я показывал " item-count " вариантов. "
                   "Уточните, что именно интересует?")
       :cards [] :cta nil :assumptions []}
      ;; Build context with actual items
      (let [item-details (str/join "\n"
                           (map-indexed
                            (fn [i item]
                              (str (inc i) ". " (:title item) " — " (:price item) " "
                                   (or (:currency item) "сом")))
                            items))
            messages [{"role" "system"
                       "content" (str "You are TapalakBot, a marketplace assistant. "
                                      "The user is asking about items you previously showed.\n\n"
                                      "Previous search: " last-search "\n"
                                      "Items shown:\n" item-details "\n\n"
                                      "Answer their question helpfully. Be concise (under 200 chars). "
                                      "Return ONLY JSON: {\"answer\":\"your answer\",\"cta\":\"optional follow-up\"}")}
                      {"role" "user" "content" text}]
            resp (try
                   (llm/llm (or model default-model) messages []
                            :provider (or provider default-provider)
                            :max-tokens 300 :timeout-ms 20000)
                   (catch Exception e
                     (log/warn :followup-llm-failed (.getMessage e))
                     nil))
            content (get-in resp ["choices" 0 "message" "content"])
            parsed (try
                     (let [json-str (or (re-find #"(?s)\{.*\}" (or content "{}")) "{}")]
                       (cheshire.core/parse-string json-str true))
                     (catch Exception _ {}))]
        {:mode :followup
         :intro (or (:answer parsed)
                    (str "Вот что я нашёл по «" last-search "». Уточните запрос?"))
         :cards []
         :cta (:cta parsed)
         :assumptions []}))))

;; ════════════════════ CHAT ════════════════════

(defn- do-chat
  "Chat mode: small talk, general conversation."
  [text {:keys [model provider]}]
  (try
    (let [messages [{"role" "system"
                     "content" "You are TapalakBot, a marketplace assistant for Kyrgyzstan (Lalafo.kg, Mashina.kg). Be friendly, concise, helpful. Speak Russian. Keep responses under 300 chars. If the user asks what you can do, explain you help find products and compare prices on Lalafo.kg."}
                    {"role" "user" "content" text}]
          resp (llm/llm (or model default-model) messages []
                        :provider (or provider default-provider)
                        :max-tokens 200 :timeout-ms 15000)
          content (get-in resp ["choices" 0 "message" "content"])]
      {:mode :shortlist
       :intro (or content "Чем могу помочь? Напишите что ищете!")
       :cards [] :cta nil :assumptions []})
    (catch Exception e
      (log/warn :chat-failed (.getMessage e))
      {:mode :shortlist
       :intro "Чем могу помочь? Просто напишите что ищете на Lalafo.kg 🔍"
       :cards [] :cta nil :assumptions []})))

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
      ;; Regex couldn't classify. Use LLM to understand conversational intent.
      ;; This handles follow-ups, research queries, chat, and any natural language
      ;; the regex can't match. Fixes the "search for literal text" comedy.
      (if (and text (> (count (str/trim text)) 3))
        (let [{:keys [intent query]} (intent/classify-intent text state)]
          (log/info :llm-intent :text text :intent intent :query query)
          (case intent
            ;; Search — user wants direct results
            :search
            (do-search (or query text) session
                       {:status-cb status-cb :model model :provider provider})

            ;; Research — user wants market intelligence + guidance
            :research
            (do-research text (or query text) session
                         {:status-cb status-cb :model model :provider provider})

            ;; Followup — user asks about previous results
            :followup
            (do-followup text state
                         {:status-cb status-cb :model model :provider provider})

            ;; Compare — explicit comparison
            :compare
            (compare-products (or query text) model provider)

            ;; Refine — filter/narrow previous search
            :refine
            (let [last-search (or (:last-search state) query)
                  refined     (apply-refine last-search (or query text) state)
                  result      (do-search (:query refined) session
                                        {:status-cb status-cb :model model :provider provider})]
              (-> result
                  (assoc :mode :refine)
                  (update :assumptions into (vec (:assumptions refined)))
                  (assoc :query (:query refined))))

            ;; Chat — small talk
            :chat
            (do-chat text {:status-cb status-cb :model model :provider provider})

            ;; Fallback — shouldn't happen, but route to search
            (do-search text session
                       {:status-cb status-cb :model model :provider provider})))
        ;; Very short/gibberish — show help
        {:mode  :shortlist
         :intro "🤔 Напишите, что ищете — например, «найди iphone 13»."
         :cards [] :cta nil :assumptions []}))))
