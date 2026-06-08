(ns tapalakbot.core
  "TapalakBot v2 — Multi-platform marketplace intelligence agent.
   Uses clj-harness with direct HTTP clients for Lalafo.kg and Mashina.kg.
     Agent (Clojure harness) → research · market_stats · search"
  (:require
   [clj-harness.core :as h]
   [clj-harness.llm :as llm]
   [clj-harness.session.sqlite :as sess]
   [tapalakbot.lalafo :as lalafo]
   [tapalakbot.mashina :as mashina]
   [tapalakbot.query-builder :as qb]
   [tapalakbot.monitor.store :as monitor-store]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

;; ══════════════════════ SYSTEM PROMPT ══════════════════════

(def system-prompt
  "You are TapalakBot — Multi-platform marketplace search assistant for Kyrgyzstan.
Searches Lalafo.kg and Mashina.kg (cars).
Speak Russian.

## TOOL WORKFLOW — MANDATORY

⚠️ **YOU CANNOT ANSWER WITHOUT TOOLS**. Any answer that doesn't use tools will be REJECTED.

For EVERY purchase query:

1. **FIRST call market_stats** — always. Even if you think you know the market.
2. **THEN call search** — always. Your training data has NO listings.
3. **research** — only for unfamiliar products.

Do NOT answer until tools return data. Do NOT use your own knowledge for prices.
The only exception: pure greetings, pure advice questions.

## Response format

Show 5-8 listings. Respond in Russian. Max 3000 chars.

Structure:
1. One-line intro (what you found, how many)
2. Listings grouped by price tier with emoji headers
3. Each listing: title, price, brief detail
4. Reference items by their token (#A, #B, #C) — links added automatically. Each item in the search results has a letter token. Use THAT letter, not a different one.

Example:

📱 Нашёл iPhone 13 на Lalafo.kg — 12 вариантов!

🔥 Хорошая цена (до 30 000 сом)
• iPhone 13 128GB — #A — 25 000 сом, хороший
• iPhone 13 64GB — #B — 28 000 сом, с чехлом

💰 Средний диапазон (30 000–45 000 сом)
• iPhone 13 Pro 128GB — #C — 35 000 сом, отличное
• iPhone 13 Pro Max 256GB — #D — 42 000 сом

💎 Премиум
• iPhone 13 Pro Max 512GB — #E — 55 000 сом, новый

## ANTI-HALLUCINATION RULES

**NEVER fabricate:**
- URLs or links (lalafo.kg/..., https://...)
- Listing IDs not in tool results
- Prices not shown in tool output
- Seller names, locations, or conditions not in data

**Letter tokens (#A, #B, #C):**
- Copy EXACTLY as shown in search results
- NEVER change, renumber, or reassign letters
- If no token in results → don't add one

**If you don't have data → say so. Do not invent.**

NEVER use markdown tables (| --- |). NEVER write URLs or link emojis. Use bold for prices. Respond in Russian.

⚠️ CRITICAL: Use the EXACT letter tokens (#A, #B, #C) from the search results you received. Do NOT invent, change, or reassign letters. Links will only work with the exact tokens from the tool.")
;; ══════════════════════ TOOLS ══════════════════════

;; ══════════════════════ URL STORE ══════════════════════

(def url-store
  "Map of user-id → {letter-token {:url :title :item-id}}. Populated by format-search-results, consumed by bot.clj.
   Per-user for concurrent searches. Tokens: A-Z, AA-AZ, BA-BZ, ... (base-26 like spreadsheet columns)."
  (atom {}))

(defn- col-letter
  "Convert 0-based index to spreadsheet-style column letter. 0→A, 25→Z, 26→AA, ..."
  [n]
  (loop [n n
         result []]
    (let [r (rem n 26)
          c (char (+ 65 r))
          q (quot n 26)]
      (if (zero? q)
        (apply str (cons c result))
        (recur (dec q) (cons c result))))))

(defn get-item-url
  "Get the real URL for an item ID. Returns nil if not found."
  [user-id item-id]
  (get-in @url-store [user-id (str item-id)]))

(defn get-url-store
  "Get the URL store for a specific user."
  [user-id]
  (get @url-store user-id {}))

(def ^:dynamic *current-user-id*
  "Dynamic var holding current user-id during search execution."
  nil)

(def ^:private thread-user-id
  "Map of thread-id → user-id for passing context through clj-harness."
  (atom {}))

(defn set-thread-user-id!
  "Set user-id for current thread."
  [uid]
  (swap! thread-user-id assoc (Thread/currentThread) uid))

(defn clear-thread-user-id!
  "Clear user-id for current thread."
  []
  (swap! thread-user-id dissoc (Thread/currentThread)))

(defn get-thread-user-id
  "Get user-id for current thread."
  []
  (get @thread-user-id (Thread/currentThread)))

;; ══════════════════════ TWO-PASS LLM ══════════════════════

(def ^:private relevance-system-prompt
  "You are a listing relevance filter for Lalafo.kg marketplace.
Given a list of listings and what the user is looking for, identify
which listings are ACTUALLY the product the user wants — not accessories,
chargers, cases, parts, services, or unrelated items.

Rules:
- Judge title AND description. Lalafo titles are often generic or wrong; the description may reveal the real product.
- Accessories (chargers, cables, cases, stylus-only) → NOT relevant
- Boxes, packaging, parts → NOT relevant
- Different product category entirely → NOT relevant
- Wrong brand/model when user asked for specific → NOT relevant
- Services/repairs → NOT relevant
- If user asked for router: chargers, antennas, modems → NOT relevant (unless they ARE routers)
- If user asked for phone: cases, screen protectors, chargers, boxes → NOT relevant
- If user asked for laptop: RAM sticks, chargers, bags, stickers → NOT relevant
- If user asked for iPad: ONLY actual Apple iPad tablets are relevant. Android tablets, graphic tablets, children tablets, keyboards, Apple Pencil/stylus-only, cases, glass, cables, hubs, monitors, phones → NOT relevant unless the description clearly says an actual Apple iPad tablet is included
- If user asked for IQOS/айкос/electronic cigarette: ONLY actual heating devices (Iluma, 3 Duo, Originals, IQOS device) are relevant. Cases, holders, chargers, lighters, power banks, cigarette cases, accessories → NOT relevant
- If user asked for generic tablet: actual tablets are relevant; accessories, graphic tablets, cases, cables, stylus-only → NOT relevant

Return ONLY a JSON array of relevant listing IDs. Nothing else.
Example: [113171780, 112908144, 111226783]")

(defn- parse-id-array
  "Parse a JSON ID array even if the model wraps it in text/fences."
  [content]
  (let [clean (str/replace (or content "[]") #"```json|```" "")
        array-text (or (second (re-find #"(?s)(\[[^\]]*\])" clean)) "[]")]
    (try
      (json/parse-string (str/trim array-text) false)
      (catch Exception _ []))))

(defn- relevance-filter
  "LLM pass 1: filter listings by relevance to user query.
   Returns vector of relevant items (max 60)."
  [items user-query]
  (if (<= (count items) 12)
    ;; Very few items — no need for relevance pass
    items
    (let [format-item (fn [i item]
                        (let [desc (get item "desc" "")]
                          (str (inc i) ". [#" (get item "id") "] "
                               (get item "title" "") " — "
                               (when-let [p (get item "price")]
                                 (str (format "%,.0f" (double p)) " KGS"))
                               (when (not (str/blank? desc))
                                 (str " — " desc)))))
          score-chunk (fn [chunk]
                        (let [items-text (str/join "\n" (map-indexed format-item chunk))
                              messages [{"role" "system" "content" relevance-system-prompt}
                                        {"role" "user"
                                         "content" (str "User is looking for: " user-query "\n\nListings:\n" items-text
                                                        "\n\nReturn JSON array of relevant listing IDs.")}]]
                          (try
                            (let [resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 2000)
                                  content (get-in resp ["choices" 0 "message" "content"])
                                  id-set (set (parse-id-array content))]
                              (filter #(contains? id-set (get % "id")) chunk))
                            (catch Exception e
                              (println "[relevance] LLM chunk failed:" (.getMessage e))
                              []))))
          chunks (partition-all 80 items)
          relevant (doall (mapcat score-chunk chunks))]
      (if (pos? (count relevant))
        (do
          (println (str "  [relevance] " (count items) " → " (count relevant) " items"))
          (take 60 relevant))
        (do
          (println "[relevance] no parseable relevant IDs — showing first 60 candidates")
          (take 60 items))))))

(defn- format-search-results [result-json & {:keys [user-query] :or {user-query ""}}]
  "Format JSON search result into readable text for LLM.
   With user-query: applies LLM relevance filter first (pass 1).
   Main LLM does curation (pass 2).
   Returns {:text formatted-text :url-store {item-id url}}."
  (let [data (if (string? result-json)
               (try (json/parse-string result-json false) (catch Exception _ nil))
               result-json)]
    (if (not (map? data))
      {:text (str result-json) :url-store {}}
      (if-let [err (get data "error")]
        {:text (str "Search error: " err) :url-store {}}
        (let [found (get data "found" 0)
              raw (get-in data ["stats" "raw"] found)
              pages (get-in data ["stats" "pages"] 0)
              truncated (get data "truncated" false)
              items (get data "items" [])
              ;; Use LLM-based relevance filter (not regexes) — LLM understands context better
              relevant (if (and user-query (not (str/blank? user-query)) (> (count items) 3))
                         (relevance-filter items user-query)
                         items)
              ;; Build url-store locally (not global atom)
              ]
          (if (zero? found)
            {:text (get data "message" "Nothing found.") :url-store {}}
            {:text
             (str "🔍 Showing " (count relevant) " relevant candidates"
                  (str " (from " raw " raw listings across " pages " pages)")
                  (when truncated " [truncated]")
                  ". STRICT: Use the title in [brackets] for each item. Each item has a real Lalafo URL — include it. DO NOT invent iPhones for items that are MacBooks/accessories. Check the URL slug."
                  "\n"
                  (str/join "\n"
                            (for [item relevant]
                              (let [item-id (str (get item "id"))
                                    url (get item "url" "")
                                    price (get item "price")
                                    price-str (if price
                                                (str (format "%,.0f" (double price)) " " (get item "currency" "KGS"))
                                                "price unknown")
                                    desc (get item "desc" "")
                                    ;; Compute letter ONCE — used for both store and display (fixes off-by-one)
                                    letter (col-letter (count (get @url-store *current-user-id* {})))]
                                ;; Store URL + title for post-LLM citation (per-user)
                                (when (and item-id (not (str/blank? url)) *current-user-id*)
                                  (swap! url-store assoc-in [*current-user-id* letter]
                                         {:url url
                                          :title (get item "title" "")
                                          :item-id item-id}))
                                ;; Format with SAME letter token — no second read of the atom
                                (str "- #" letter " [" (get item "title" "") "]"
                                     " | " price-str
                                     (when (not (str/blank? url))
                                       (str " | " url))
                                     (when (not (str/blank? desc))
                                       (str " | " desc)))))))
             :url-store {}}))))))

(defn- format-research-results
  "Format web research results."
  [result-json]
  (let [data (if (string? result-json)
               (try (json/parse-string result-json false) (catch Exception _ nil))
               result-json)]
    (if (or (not (map? data)) (get data "error"))
      (str "Research error: " (get data "error" "unknown"))
      (let [results (get data "results" [])]
        (if (empty? results)
          "Nothing found."
          (str/join "\n" (map-indexed
                          (fn [i r]
                            (str (inc i) ". " (get r "title" "")
                                 (when-let [s (get r "snippet")]
                                   (str " — " (subs s 0 (min 250 (count s)))))))
                          results)))))))

(def ^:private query-gen-system-prompt
  "You are a search query generator for Lalafo.kg marketplace in Kyrgyzstan.\nGiven what a user wants to buy, generate optimal search queries.\n\nRules:\n1. Generate 4-6 query variants with EXACT MODEL NAMES (not generic terms)\n2. Include both English and Russian/Cyrillic variants when relevant\n3. For well-known brands: use model numbers and names\n   - iPhone 13 → [\"iPhone 13\", \"айфон 13\", \"Apple iPhone 13\"]\n4. For niche products: research what models exist first\n5. Price filters: extract min/max price if mentioned\n6. Return ONLY a JSON object: {\"queries\": [...], \"price_min\": N|null, \"price_max\": N|null, \"needs_research\": bool, \"research_query\": \"...\"}\n7. Set needs_research=true only for genuinely niche products where model names are unknown")

(defn- generate-search-queries
  "Use LLM to generate optimal search queries from user intent."
  [user-want]
  (let [messages [{:role "system" :content query-gen-system-prompt}
                  {:role "user" :content (str "User wants to buy: " user-want)}]]
    (loop [attempts 0]
      (when (>= attempts 3)
        (log/warn :query-gen-all-attempts-failed :user-want user-want))
      (let [result
            (try
              (let [resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 500)
                    content (get-in resp ["choices" 0 "message" "content"])]
                (if (or (nil? content) (str/blank? content))
                  (do (log/warn :query-gen-empty-content :attempt attempts)
                      nil)
                  (let [json-str (or (re-find #"(?s)\{.*\}" content) "{}")
                        parsed (try (json/parse-string json-str true)
                                    (catch Exception _ {}))]
                    (when (seq (:queries parsed))
                      {:queries (vec (:queries parsed))
                       :price-min (:price_min parsed)
                       :price-max (:price_max parsed)
                       :needs-research (:needs_research parsed false)
                       :research-query (:research_query parsed)}))))
              (catch Exception e
                (log/warn e :query-gen-failed :attempt attempts)
                nil))]
        (if result
          result
          (if (< attempts 2)
            (recur (inc attempts))
            {:queries [user-want]}))))))

(defn- do-research
  "Quick research to find model names for niche products."
  [research-query]
  (try
    (let [result (lalafo/exa-research research-query)
          data (if (string? result) (json/parse-string result false) result)
          results (get data "results" [])]
      (str/join " " (map #(get % "title" "") (take 3 results))))
    (catch Exception _ "")))

(def ^:private category-picker-prompt
  "You are a category matcher for Lalafo.kg classifieds in Kyrgyzstan.
Given a user's search intent and available categories, return the EXACT category_id.

Rules:
1. Pick the MOST SPECIFIC matching category (e.g. 'Mobile Phones', not 'Electronics')
2. If no good match, return null
3. Return ONLY: {\"category_id\": N} or {\"category_id\": null}")

(defn- resolve-category
  "Use LLM to find the right Lalafo category for a user query."
  [user-query]
  (when (and user-query (not (str/blank? user-query)))
    (try
      (let [categories (lalafo/search-categories user-query)
            categories-str (or categories "No categories available")]
        (let [messages [{:role "system" :content category-picker-prompt}
                        {:role "user" :content (str "Query: " user-query "\n\n" categories-str)}]
              resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 300)
              content (get-in resp ["choices" 0 "message" "content"])]
          (when content
            (let [cat-id (some-> content
                                 (re-find #"\"category_id\":\s*(\d+)\"")
                                 second
                                 parse-long)]
              (when cat-id
                (log/info :category-resolved :query user-query :category-id cat-id)
                cat-id)))))
      (catch Exception e
        (log/warn :category-resolution-failed :error (ex-message e))
        nil))))

(defn- research-execute
  "Tool: research product knowledge online."
  [args]
  (let [topic (get args "topic")
        query (get args "query" topic)]
    (try
      (let [result (lalafo/exa-research query)
            data (if (string? result) (json/parse-string result false) result)
            results (get data "results" [])]
        (if (seq results)
          (str "🔬 Research for " " topic " ":\n\n"
               (str/join "\n"
                         (map-indexed
                          (fn [i r]
                            (str (inc i) ". " (get r "title" "")
                                 (when-let [s (get r "snippet")]
                                   (str " — " (subs s 0 (min 200 (count s)))))))
                          (take 5 results))))
          "No research results found. Proceed."))
      (catch Exception e
        (str "Research error: " (.getMessage e))))))

(def ^:private category-name-map
  {"ноутбук" "Ноутбуки" "ноутбуки" "Ноутбуки" "laptop" "Ноутбуки"
   "телефон" "Телефоны" "телефоны" "Телефоны" "смартфон" "Телефоны"
   "phone" "Телефоны" "iphone" "Телефоны" "айфон" "Телефоны"
   "планшет" "Планшеты" "планшеты" "Планшеты" "tablet" "Планшеты" "ipad" "Планшеты"
   "телевизор" "Телевизоры" "телевизоры" "Телевизоры" "tv" "Телевизоры"
   "авто" "Автомобили" "автомобиль" "Автомобили" "car" "Автомобили"
   "фотоаппарат" "Фототехника" "камера" "Фототехника" "camera" "Фототехника"
   "наушники" "Наушники" "headphones" "Наушники"
   "роутер" "Роутеры" "роутеры" "Роутеры" "router" "Роутеры"
   "монитор" "Мониторы" "мониторы" "Мониторы" "monitor" "Мониторы"})

(defn- find-matching-category
  [product-type]
  (when product-type
    (let [lower (str/lower-case product-type)]
      (or (get category-name-map lower)
          (some (fn [[k v]] (when (or (str/includes? lower k) (str/includes? k lower)) v))
                category-name-map)))))

(defn- market-stats-execute
  "Tool: get real-time market price data from monitor DB."
  [args]
  (let [product-type (get args "product_type")
        budget-max (get args "budget_max")
        category-name (find-matching-category product-type)]
    (try
      (let [category-summaries (monitor-store/get-category-summary)
            matching (when category-name
                       (some #(when (= (:name %) category-name) %) category-summaries))]
        (if matching
          (let [avg-price (:avg_price matching)
                min-price (:min_price matching)
                max-price (:max_price matching)
                item-count (:item_count matching)]
            (str "📊 Market: " category-name "\n"
                 "• Avg price: " (format "%,.0f" (double avg-price)) " KGS\n"
                 "• Range: " (format "%,.0f" (double min-price)) " — " (format "%,.0f" (double max-price)) " KGS\n"
                 "• Items: " item-count "\n"
                 "Use this data to assess deal quality."))
          (str "📊 No exact market data for \"" product-type "\".\n"
               "Available categories: " (str/join ", " (map :name category-summaries)))))
      (catch Exception e
        (str "Market stats unavailable: " (.getMessage e))))))

(defn- format-mashina-results
  "Format Mashina.kg car search results."
  [result]
  (let [listings (:listings result)
        total (:total result)]
    (str "🚗 **Mashina.kg** — " (count listings) " авто"
         (when (> total (count listings)) (str " из " total " объявлений")) "\n\n"
         (str/join "\n"
                   (mapv (fn [item]
                           (let [idx (count (get @url-store *current-user-id* {}))
                                 letter (col-letter idx)
                                 price (get-in item [:price :amount])
                                 currency (get-in item [:price :currency] "KGS")
                                 price-str (if price
                                             (str (format "%,.0f" (double price)) " " currency)
                                             "цена не указана")
                                 url (:url item)]
                             ;; Store in url-store for anti-hallucination citation validation
                             (when (and *current-user-id* url (not (str/blank? url)))
                               (swap! url-store assoc-in [*current-user-id* letter]
                                      {:url url :title (:title item) :item-id (str (:id item))}))
                             (str "• " (:title item)
                                  " — #" letter " " price-str
                                  (when (:year item) (str ", " (:year item) " г."))
                                  (when (:mileage item) (str ", " (:mileage item) " км"))
                                  (when (:city item) (str " | " (:city item)))
                                  (when (and url (not (str/blank? url)))
                                    (str "\n  " url)))))
                         (take 8 listings))))))

(defn- search-execute
  "Smart search pipeline: QueryBuilder → platform routing → multi-platform search."
  [args]
  (let [user-id (or (get-thread-user-id) (get args "_user_id") "anonymous")
        user-want (get args "user_want")]
    ;; Guard: if user_want is nil/blank, return error immediately
    (if (or (nil? user-want) (str/blank? user-want))
      (str "ERROR: search requires a user_want parameter — the product you want to find. "
           "Use the exact text the user asked about.")
      (binding [*current-user-id* user-id]
        (let [_ (swap! url-store dissoc user-id)
            ;; Step 0: LLM-based category resolution
            category-id (resolve-category user-want)
            ;; Step 1: Parse user intent with QueryBuilder (price, platform)
            qb-result (qb/build user-want :use-llm? true)
        ;; Helper: check if platform should be searched (handles :all)
            platforms (:platforms qb-result)
            search? (fn [p] (or (some #{p} platforms) (some #{:all} platforms)))
        ;; Step 2: Generate optimal queries (LLM-based)
            {:keys [queries needs-research research-query]}
            (generate-search-queries user-want)
        ;; Merge QueryBuilder price with generated price
            final-price-min (or (get args "price_min") (:price-min qb-result))
            final-price-max (or (get args "price_max") (:price-max qb-result))
        ;; Step 3: Optional research for niche products
            extra-context (when (and needs-research research-query)
                            (do-research research-query))
        ;; Step 4: If research found more models, add them to queries
            enhanced-queries (if (and extra-context (not (str/blank? extra-context)))
                               (let [research-terms (str/split (str extra-context) #"\s+" 3)]
                                 (vec (concat queries (filter #(> (count %) 2) research-terms))))
                               queries)
        ;; Step 5: Search Lalafo (if in platforms)
            lalafo-results (when (search? :lalafo)
                             (let [search-args {"queries" (take 6 enhanced-queries)
                                                "category_id" (or category-id
                                                                  (get args "category_id")
                                                                  (:lalafo-category-id qb-result))
                                                "price_min" final-price-min
                                                "price_max" final-price-max
                                                "city_id" (get args "city_id")
                                                "candidate_limit" 100}
                                   result (lalafo/search search-args)]
                               (log/info :search-lalafo :queries enhanced-queries :price [final-price-min final-price-max])
                               (try
                                 (let [fmt (format-search-results result :user-query user-want)
                                       txt (:text fmt)]
                                   (log/info :search-done :urls (count (get-url-store user-id)) :chars (count txt))
                                   txt)
                                 (catch Exception e
                                   (log/error :search-format-failed (.getMessage e)
                                              :result-preview (subs result 0 (min 200 (count result))))
                                   (str "Search error: " (.getMessage e))))))
        ;; Step 6: Search Mashina.kg (cars)
            mashina-results (when (search? :mashina)
                              (try
                                (let [q (or (:mashina-query qb-result) (first enhanced-queries))
                                      mr (mashina/search-cars :query q :size 10)]
                                  (log/info :smart-search-mashina :query q :found (:total mr))
                                  (when (seq (:listings mr))
                                    (format-mashina-results mr)))
                                (catch Exception e
                                  (log/warn :mashina-search-failed (.getMessage e))
                                  nil)))]
        ;; Combine results
        (str (when lalafo-results lalafo-results)
             (when mashina-results (str "\n\n" mashina-results))))))))

(def tools
  [{:name "research"
    :description "Research product knowledge online. Finds model names, specs, and buying advice. Use BEFORE searching when: the product is unfamiliar, user asks for alternatives, or user asks for advice. Skip when user names an exact model."
    :schema [:map
             [:topic {:optional false} :string]
             [:query {:optional true} :string]]
    :execute research-execute}

   {:name "market_stats"
    :description "Get real-time market price data from Kyrgyzstan marketplaces. Shows average price, price range, and item counts. Use for EVERY purchase query to assess value."
    :schema [:map
             [:product_type {:optional false} :string]
             [:budget_max {:optional true} :int]]
    :execute market-stats-execute}

   {:name "search"
    :description "Search for actual listings on Lalafo.kg and Mashina.kg. Returns curated results with letter tokens (#A, #B, #C). Use after research and market_stats."
    :schema [:map
             [:user_want {:optional false} :string]
             [:price_min {:optional true} :int]
             [:price_max {:optional true} :int]
             [:category_id {:optional true} :int]
             [:city_id {:optional true} :int]]
    :execute search-execute}])

;; ══════════════════════ PRE-HOOK ══════════════════════

;; Cached category tree — fetched once, reused across sessions
(def ^:private categories-cache
  (delay (lalafo/format-categories-prompt (lalafo/fetch-categories-raw))))

(def ^:private purchase-intent-pattern
  "Patterns indicating a purchase/search intent."
  #"(?i)(найди|ищ[уе]|купи[ть]|сколько стоит|цена|в продаже|покажи|хочу|ищу|надо|нужен|нужна|нужно|прода[ею]|до \d+|от \d+|б/у|подерж|бу\b|нов[аы]я|планшет|айпад|ipad|ноут|телефон|айфо|iphone|samsung|xiaomi|макбук|пылесос|роутер|телевиз|монитор|наушник|мышк[аи]|клавиатур|видеокарт|процессор|холодильник|стирал|велосипед|самокат|hyundai|toyota|honda|bmw|mercedes|lexus|квартир|участ[ко])")

(def ^:private greeting-pattern
  "Patterns that are just greetings (skip auto-search)."
  #"(?i)^\s*(привет|здрав|добр[оы]й|хай|hello|hi|/start|/help)\s*$")

(defn pre-hook
  "Called before each message. Auto-executes search for purchase queries
   and appends real marketplace data to the system prompt."
  [user-id text session]
  (try
    (let [is-purchase? (and (re-find purchase-intent-pattern text)
                            (not (re-find greeting-pattern text)))]
      (if is-purchase?
        (try
          (log/info :pre-hook-auto-search :user-id user-id :query text)
          (let [search-result (search-execute {"user_want" text})
                has-data? (and search-result (> (count search-result) 20))]
            (if has-data?
              (do
                (log/info :pre-hook-search-done :chars (count search-result))
                (str "\n\n[REAL MARKETPLACE DATA — use ONLY these listings, do NOT invent]\n\n"
                     search-result))
              (do
                (log/warn :pre-hook-search-empty :query text)
                nil)))
          (catch Exception e
            (log/warn :pre-hook-search-failed (.getMessage e))
            nil))
        nil))
    (catch Exception _ nil)))

;; ══════════════════════ BOT FACTORY ══════════════════════

(def tapalakbot
  (delay
    (h/create-bot
     {:name "tapalakbot"
      :prompt system-prompt
      :tools tools
      :model :kimi-k2
      :provider :openrouter
      :max-turns 20
      :nudges {:max-step-blocks 3
               :recover-tool-errors? true}
      :pre-hook pre-hook
      :persistence (sess/create "/tmp/tapalakbot-sessions.db")
      :effects? true})))

(defn ask
  "Ask TapalakBot a question. Returns response string."
  ([text] (ask "terminal" text))
  ([user-id text]
   (h/handle-message @tapalakbot user-id text)))

;; ══════════════════════ MAIN ══════════════════════

(defn- run-interactive []
  (println "╔═════════════════════════════════════════════════╗")
  (println "║  🔍 TapalakBot v2 — Marketplace Intelligence Agent      ║")
  (println "║  Tools: research · market_stats · search ║")
  (println "╚═════════════════════════════════════════════════╝")
  (println)
  (println "Type your question or /q to quit.")
  (println)

  ;; Init bot
  @tapalakbot
  (println "✅ Bot ready.")
  (println)

  ;; Pre-load categories
  (print "Loading categories... ")
  (flush)
  (try
    (let [cats @categories-cache]
      (println (str (count cats) " chars")))
    (catch Exception e (println "skip:" (.getMessage e))))

  (loop []
    (print "> ") (flush)
    (let [line (read-line)]
      (when line
        (when (not (#{"/q" "/quit" "exit"} (str/trim line)))
          (println)
          (try
            (let [result (ask (str/trim line))]
              (println result)
              (println)
              (println (apply str (repeat 60 "─"))))
            (catch Exception e
              (println "❌ Error:" (.getMessage e))))
          (println)
          (recur))))))

(defn -main [& args]
  (if (seq args)
    ;; One-shot mode
    (let [query (str/join " " args)]
      (println "🔍" query)
      (println)
      @tapalakbot
      (let [result (ask query)]
        ;; Anti-hallucination: check url-store
        (let [url-count (count (get-url-store "terminal"))
              has-listings? (re-find #"(?:•|-)\s+[^#]*#" result)]
          (when (and has-listings? (zero? url-count))
            (println "⚠️ WARNING: Response contains listings but no search was performed. May contain hallucinations."))
          (println result))))
    ;; Interactive mode
    (run-interactive)))

;; ══════════════════════ REPL ══════════════════════

(comment
  ;; Load and test
  (require '[tapalakbot.core :as t])
  (t/ask "роутер до 4000")
  (t/ask "macbook m1")
  (t/ask "айфон 13 про макс")

  ;; Test raw search/categories/research (all native Clojure now)
  (require '[tapalakbot.lalafo :as l])
  (println (l/search {"queries" ["router" "роутер"] "price_max" 4000}))
  (println (l/search-categories "headphones"))
  (println (l/exa-research "iPad Air 3 release date"))

  ;; Inspect sessions
  @(:sessions @tapalakbot))
