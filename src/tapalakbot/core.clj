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
   [tapalakbot.policy :as policy]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

;; ══════════════════════ SYSTEM PROMPT ══════════════════════

(def system-prompt
  "You are TapalakBot — a friendly, knowledgeable marketplace assistant for Kyrgyzstan.
You help people find products on Lalafo.kg and Mashina.kg (cars).
ALL your text must be in Russian. Never output English — not in reasoning, not in answers.
Be warm and helpful — like a tech-savvy friend who knows the local market.

CRITICAL RULE: You MUST use tools. Never answer a product request without calling search.
ONLY exceptions: pure greetings, /reset, /help, small talk.

## Output rules
- NEVER mention tool names (research, search, market_stats).
- You CAN mention what you researched: '🔬 По отзывам, лучшие модели — C920 и C922.'
- NEVER output 'I am searching', 'I found X listings'. Just show results.
- Everything in Russian. Never English.

## Budget handling — CRITICAL
When the user specifies a budget (до N сом), show the BEST products WITHIN that budget.
Do NOT pick the cheapest items — pick the best value items close to the budget.

## Number of options
You have access to 100+ listings. Show as many as possible.
- MINIMUM 8 items when search returns 15+.
- Show top 3-4 in detailed block format, then put ALL items in the comparison table
- End EVERY response with a comparison table of all viable items

## Your tools
- research: Look up product info, specs, reviews, comparisons online.
- search: Find actual listings on Lalafo.kg and Mashina.kg. Returns prices and URLs.

## Flow
1. If the product is unfamiliar or user asks for advice → research first, then search
2. If the user names an exact model → search directly
3. If research returns 5+ models, pick the best 2-3 and search those

## Follow-up flow
→ User asks about previous results → answer from conversation, no tools
→ User greets → just chat naturally

## Response format — Rich Markdown (Telegram renders natively!)

**1. MARKET OVERVIEW (MANDATORY — always first!)**
| Показатель | Значение |
|---|---|
| Диапазон цен | N — N сом |
| Средняя цена | N сом |
| Всего объявлений | N |
| Площадки | Lalafo, Mashina |

**2. TOP PICKS (the core of your response)**
For the top 3-4 recommendations, use a COMPACT BLOCK:
- Product NAME is a link: [**Product Name**](url)
- Price highlighted with ==
- Specs as one-line summary
- 2 lines per product: link+price, then specs

Example:
🏆 [**Dyson V15 Detect**](url) — ==38 000 сом==
📍 Бишкек · состояние: как новый · батарея: 90%

🥈 [**Xiaomi Mi Vacuum**](url) — ==18 000 сом==
📍 Ош · новый · гарантия 6 мес

NEVER make per-listing spec tables. NEVER separate link lines.
The product name IS the link — tappable, clean.

**3. COMPARISON TABLE (for ALL items)**
The product NAME in the Модель column is the link itself — no separate link column:
| Модель | Цена | Город |
|:---|---:|---:|
| [iPhone 15 128GB](url) | 45000 | Бишкек |
| [Galaxy S24](url) | 38000 | Ош |

NEVER a separate link column with 👉 emoji. The product name IS the link.
This way the whole row is clean and the title is directly tappable.

**4. ADVICE**
Short paragraph with recommendation. End with a question.

## Formatting rules — CRITICAL FOR LINKS
- Links MUST use markdown syntax: [Product Name](https://lalafo.kg/...) — square brackets around text, URL in round brackets
- WRONG: Xiaomi Enchen Sharp 6 (https://lalafo.kg/...) ← bare URL in parens, NOT clickable
- RIGHT: [Xiaomi Enchen Sharp 6](https://lalafo.kg/...) ← square brackets make it a clickable link
- NEVER output bare URLs like `https://lalafo.kg/...` or `Name (https://...)`
- ALWAYS wrap the clickable text in square brackets: `[text](url)`
- In tables: `| [Product Name](url) | 45000 | Бишкек |` — name IS the link
- Tables are FINE for market overview and comparison summary
- NEVER make per-listing spec tables (| Параметр | Значение |) — use inline one-liners instead
- One product per block, 3 lines max per product
- Keep responses under 2000 characters total
- Use `==text==` for prices — they highlight natively
- **bold** the product name, ==highlight== the price
- <details> to collapse long sections
- ## Heading for section headers
- > Blockquotes for tips and warnings
- End with a short recommendation and follow-up question
- REAL ESTATE caveat: land/house prices on Lalafo are often per-sotok (not total), or wrong currency, or scams. ALWAYS add: «⚠️ Уточняйте у продавца: цена за сотку или за участок? Какие документы (Красная книга, техпаспорт)?»

## Response Quality Rules
- ALWAYS explain WHY you recommend something (battery %, condition, value)
- Compare options honestly — flag defects, don't hide them
- Mention practical costs: замена батареи ~3000-5000 сом
- Use sections: 🏆 Best pick, 🥈 Alternative, ⚠️ Budget option with caveats
- Be specific: 95% батарея not good battery
- End with actionable follow-up: Хочешь, помочь связаться с продавцом?

## Example Response Structure
Follow this pattern — compact blocks for top picks, table for comparison:

| Показатель | Значение |
|---|---|
| Диапазон цен | 18 000 — 38 000 сом |
| Средняя цена | 28 000 сом |
| Всего | 12 |

🏆 [**Dyson V15 Detect**](url) — ==38 000 сом==
📍 Бишкек · б/у · батарея 90%

🥈 [**Samsung SC20**](url) — ==22 000 сом==
📍 Бишкек · б/у · моющий · HEPA

⚠️ **Xiaomi S10** — ==18 000 сом==
📍 Ош · новый · гарантия 6 мес
[📷 Открыть на Lalafo →](url)

| Модель | Цена | Город |
|:---|---:|---:|
| [Dyson V15](url) | 38000 | Бишкек |
| [Samsung SC20](url) | 22000 | Бишкек |
| [Xiaomi S10](url) | 18000 | Ош |

**Мой совет:** Dyson если бюджет позволяет, Samsung для баланса.
Какой формат ближе — беспроводной или классический?

## Response Length Rules
- Keep responses SHORT and focused — max 2000 characters
- One listing = 3 lines max (name+price, specs, link)
- Be concise: price, condition, battery, link — that's it
- Skip unnecessary commentary — let the data speak

## Anti-hallucination rules
- NEVER fabricate prices, URLs, or listing details
- NEVER recommend products you haven't verified exist on local platforms
- If you don't have data from tools, say so honestly

## Scam detection — MANDATORY
If a listing price is less than 50% of market average OR less than 50% of known retail for that product:
- Add ⚠️ WARNING before the listing
- State: «⚠️ Цена подозрительно низкая — возможно мошенничество, подделка или сломанный товар»
- DO NOT mark suspiciously cheap items as 🏆 Best pick
- Prefer listings priced within 70-130% of market average
- For well-known brands (Apple, Samsung, Dyson, Sony, etc): if price is <50% of official retail, flag it")
;; ══════════════════════ TOOLS ══════════════════════

;; ══════════════════════ URL STORE ══════════════════════
;; DEPRECATED: url-store and *current-user-id* are only used by the old LLM agent path (format-search-results, citation-replace).
;; The agent-first path uses tapalakbot.render for deterministic card output.

(def ad-cache
  "Map of user-id → {index → {:title :price :url :platform :desc ...}}.
   Populated after each search, used for /N drill-down and 'more results' button."
  (atom {}))

(defn cache-ads!
  "Store ads in cache for a user. REPLACES previous cache (each search is fresh).
   Returns {:start N :count N}."
  [user-id cards]
  (when (and user-id (seq cards))
    (let [indexed (into {} (map-indexed (fn [i card] [(inc i) card]) cards))]
      (swap! ad-cache assoc user-id indexed)
      (log/info :ad-cache-update :user user-id :count (count indexed))
      {:start 1 :count (count indexed)})))

(defn get-ad
  "Get a cached ad by user-id and index."
  [user-id index]
  (get-in @ad-cache [user-id index]))

(def ^:dynamic *captured-cards*
  "Captured structured cards from search tool execution.
   Atom holding vector of card maps. Used by bot.clj to render deterministic cards."
  nil)

(def ^:dynamic *captured-stats*
  "Captured search stats from tool execution.
   Atom holding {:avg :min :max :count}."
  nil)

(def ^:dynamic *user-city-id*
  "User's preferred city_id for search. nil = all cities.
   Bound by ask-stream from user state."
  nil)

(def ^:dynamic *search-status-cb*
  "Status callback inside search-execute: (fn [status-text]).
   Lets the search pipeline send rich progress updates to the user
   (e.g. '📊 Найдено 150 объявлений'). nil = no-op."
  nil)

(def ^:dynamic *early-photos-cb*
  "Called when search cards are captured but before LLM text generation:
   (fn [cards]). Lets bot.clj send an early photo album immediately.
   nil = no-op."
  nil)

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
- Be STRICT: if in doubt, exclude. Better to return 5 perfect matches than 50 maybes.
- Return at MOST 15 items — only the best matches.

Return ONLY a JSON array of relevant listing IDs. Nothing else.
Example: [113171780, 112908144, 111226783]")

(defn- parse-id-array
  "Parse a JSON ID array even if the model wraps it in text/fences.
   Returns IDs as strings (Lalafo IDs are strings)."
  [content]
  (let [clean (str/replace (or content "[]") #"\`\`\`json|```|\`" "")
        array-text (or (second (re-find #"(?s)(\[[^\]]*\])" clean)) "[]")]
    (try
      (let [parsed (json/parse-string (str/trim array-text) false)
            ids (mapv str parsed)]
        (when (empty? ids)
          (log/warn :parse-id-array-empty :raw (subs (or content "") 0 (min 200 (count (or content ""))))))
        ids)
      (catch Exception e
        (log/warn :parse-id-array-error :msg (.getMessage e) :raw (subs (or content "") 0 (min 200 (count (or content "")))))
        []))))

(defn- relevance-filter
  "LLM pass 1: filter listings by relevance to user query.
   Single LLM call with Gemini Flash. Returns vector of relevant items (max 100)."
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
                                 (str " — " (subs desc 0 (min 100 (count desc))))))))
          items-text (str/join "\n" (map-indexed format-item items))
          messages [{"role" "system" "content" relevance-system-prompt}
                    {"role" "user"
                     "content" (str "User is looking for: " user-query "\n\nListings:\n" items-text
                                    "\n\nReturn JSON array of relevant listing IDs.")}]]
      (try
        (let [resp (llm/llm :gemini-3.5-flash messages [] :provider :openrouter :max-tokens 4000)
              content (get-in resp ["choices" 0 "message" "content"])
              id-set (set (parse-id-array content))
              relevant (filter #(contains? id-set (str (get % "id"))) items)]
          (if (pos? (count relevant))
            (do
              (log/info :relevance-filter :input (count items) :output (count relevant))
              (take 100 relevant))
            (do
              (log/warn :relevance-filter :fallback :reason "no parseable IDs" :raw-response (subs content 0 (min 200 (count content))))
              (take 100 items))))
        (catch Exception e
          (log/warn :relevance-filter :error (.getMessage e))
          (take 100 items))))))

(defn- filter-price-outliers
  "Filter out items with prices >max-sigma standard deviations from the mean.
   Groups by currency to avoid comparing USD and сом prices.
   Catches obviously wrong prices (e.g., 90 USD for land, or per-sotok mislabeled as total).
   Returns {:items filtered :outliers N}."
  [items & {:keys [max-sigma] :or {max-sigma 2.5}}]
  (let [;; Group items by currency
        by-currency (group-by #(get % "currency" "KGS") items)
        result (reduce-kv
                (fn [acc currency group]
                  (let [prices (keep #(when-let [p (get % "price")] (double p)) group)]
                    (if (< (count prices) 5)
                      ;; Too few items — keep all
                      (update acc :items into group)
                      (let [mean (/ (reduce + prices) (count prices))
                            variance (/ (reduce + (map #(Math/pow (- % mean) 2) prices)) (count prices))
                            std (Math/sqrt variance)]
                        (if (< std 1.0)
                          ;; All prices nearly identical — keep all
                          (update acc :items into group)
                          (let [threshold (* max-sigma std)
                                filtered (filterv (fn [item]
                                                    (if-let [p (get item "price")]
                                                      (<= (Math/abs (- (double p) mean)) threshold)
                                                      true))
                                                  group)
                                removed (- (count group) (count filtered))]
                            (when (pos? removed)
                              (println (str "  [outliers:" currency "] " removed " items filtered"
                                            " (mean=" (format "%,.0f" mean)
                                            " std=" (format "%,.0f" std)
                                            " threshold=±" (format "%,.0f" threshold) ")")))
                            (-> acc
                                (update :items into filtered)
                                (update :outliers + removed))))))))
                {:items [] :outliers 0}
                by-currency)]
    (update result :items vec)))

(defn- format-search-results [result-json & {:keys [user-query price-min price-max] :or {user-query ""}}]
  "Format JSON search result into readable text for LLM.
   With user-query: applies LLM relevance filter first (pass 1).
   Main LLM does curation (pass 2).
   With price-min/price-max: skips outlier filter (user specified range).
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
              relevant-raw (if (and user-query (not (str/blank? user-query)) (> (count items) 3))
                             (relevance-filter items user-query)
                             items)
              ;; Filter statistical outliers (catches per-sotok mislabeled as total, etc.)
              ;; Skip when user specified price range — they want those items
              outlier-result (if (or price-min price-max)
                               {:items relevant-raw :outliers 0}
                               (filter-price-outliers relevant-raw))
              relevant (:items outlier-result)
              ;; Build url-store locally (not global atom)
              ]
          (if (zero? found)
            {:text (get data "message" "Nothing found.") :url-store {} :items []}
            {:text
             (str "🔍 Showing " (min (count relevant) 20) " relevant candidates"
                  (str " (from " raw " raw listings across " pages " pages)")
                  (when truncated " [truncated]")
                  ". STRICT: Use the title in [brackets] for each item. Each item has a real Lalafo URL — include it. DO NOT invent iPhones for items that are MacBooks/accessories. Check the URL slug."
                  "\n"
                  (str/join "\n"
                            (for [item (take 20 relevant)]
                              (let [item-id (str (get item "id"))
                                    url (get item "url" "")
                                    price (get item "price")
                                    price-str (if price
                                                (str (format "%,.0f" (double price))
                                                     " " (get item "currency" "сом"))
                                                "цена неизвестна")
                                    desc (get item "desc" "")
                                    ;; Compute letter ONCE — used for store (anti-hallucination)
                                    letter (col-letter (count (get @url-store *current-user-id* {})))]
                                ;; Store URL + title for citation validation (per-user)
                                (when (and item-id (not (str/blank? url)) *current-user-id*)
                                  (swap! url-store assoc-in [*current-user-id* letter]
                                         {:url url
                                          :title (get item "title" "")
                                          :item-id item-id}))
                                ;; Format for LLM — include image URL for rich rendering
                                (let [thumb (or (get item "thumbnail_url")
                                                (get-in item ["images" 0 "original_url"])
                                                (get-in item ["images" 0 "thumbnail_url"]))]
                                  (str letter ". " (get item "title" "")
                                       " — " price-str
                                       (when (not (str/blank? desc))
                                          (str " — " (subs desc 0 (min 80 (count desc)))))))))))
             :url-store {}
             :items (vec relevant)}))))))

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
              (let [resp (llm/llm :gemini-3.5-flash messages [] :provider :openrouter :max-tokens 500)
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
              resp (llm/llm :gemini-3.5-flash messages [] :provider :openrouter :max-tokens 300)
              content (get-in resp ["choices" 0 "message" "content"])]
          (when content
            (let [cat-id (some->> content
                                  (re-find #"category_id[\":\s]*(\d+)")
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

(defn- format-mashina-results
  "Format Mashina.kg car search results. Compact format to save LLM context tokens."
  [result]
  (let [listings (:listings result)
        total (:total result)]
    (str "🚗 **Mashina.kg** — " (count listings) " авто"
         (when (> total (count listings)) (str " из " total " объявлений")) "\n"
         (str/join "\n"
                   (mapv (fn [item]
                           (let [idx (count (get @url-store *current-user-id* {}))
                                 letter (col-letter idx)
                                 price (get-in item [:price :amount])
                                 price-str (if price
                                             (str (format "%,.0f" (double price)) " сом")
                                             "цена не указана")
                                 url (:url item)]
                             (when (and *current-user-id* url (not (str/blank? url)))
                               (swap! url-store assoc-in [*current-user-id* letter]
                                      {:url url :title (:title item) :item-id (str (:id item))}))
                             (str letter ". " (:title item) " — " price-str
                                  (when (:year item) (str ", " (:year item)))
                                  (when (:mileage item) (str ", " (:mileage item) "км"))))
                         (take 8 listings)))))))

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
            _ (when *search-status-cb*
                (if category-id
                  (*search-status-cb* (str "📂 Категория найдена"))
                  (*search-status-cb* (str "🔍 Без категории — ищем по всем"))))
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
                                                :city_id (or (get args :city_id) *user-city-id*)
                                                "candidate_limit" 100}
                                   result (lalafo/search search-args)]
                               (log/info :search-lalafo :queries enhanced-queries :price [final-price-min final-price-max])
                               (try
                                 (let [fmt (format-search-results result :user-query user-want :price-min final-price-min :price-max final-price-max)
                                       txt (:text fmt)
                                       item-count (count (:items fmt))]
                                   (log/info :search-done :urls (count (get-url-store user-id)) :chars (count txt))
                                   (when *search-status-cb*
                                     (*search-status-cb* (str "📊 Найдено " item-count " объявлений")))
                                   ;; Capture structured cards for deterministic rendering
                                   (when (and *captured-cards* (seq (:items fmt)))
                                     (let [remaining (- 20 (count @*captured-cards*))
                                           cards (when (pos? remaining)
                                                   (mapv (fn [item]
                                                           {:title    (get item "title")
                                                            :price    (when (get item "price") (long (get item "price")))
                                                            :currency (get item "currency" "KGS")
                                                            :url      (get item "url")
                                                            :platform :lalafo
                                                            :image    (or (get item "thumbnail_url")
                                                                          (get-in item ["images" 0 "original_url"])
                                                                          (get-in item ["images" 0 "thumbnail_url"]))
                                                            :desc     (get item "desc")})
                                                         (take remaining (:items fmt))))]
                                       (when (seq cards)
                                         (swap! *captured-cards* into cards)
                                         (when *search-status-cb*
                                           (*search-status-cb* (str "✨ Отобрано " (count @*captured-cards*) " объявлений")))
                                         ;; Early photo preview — send immediately, before LLM text gen
                                         (when *early-photos-cb*
                                           (try (*early-photos-cb* @*captured-cards*)
                                                (catch Exception _))))))
                                   txt)
                                 (catch Exception e
                                   (log/error :search-format-failed (.getMessage e)
                                              :result-preview (subs result 0 (min 200 (count result))))
                                   (str "Search error: " (.getMessage e))))))
        ;; Step 6: Search Mashina.kg (cars)
            mashina-results (when (search? :mashina)
                              (try
                                (let [q (or (:mashina-query qb-result) (first enhanced-queries))
                                      mr (mashina/search-cars :query q :size 6)]
                                  (log/info :smart-search-mashina :query q :found (:total mr))
                                  (when (seq (:listings mr))
                                    ;; Capture Mashina cards (respect 20-card total cap)
                                    (when *captured-cards*
                                      (let [remaining (- 20 (count @*captured-cards*))
                                            cards (when (pos? remaining)
                                                    (mapv (fn [item]
                                                            {:title    (:title item)
                                                             :price    (when-let [p (get-in item [:price :amount])]
                                                                         (long p))
                                                             :currency (get-in item [:price :currency] "KGS")
                                                             :url      (:url item)
                                                             :platform :mashina
                                                             :year     (:year item)
                                                             :mileage  (when-let [m (:mileage item)]
                                                                         (when (number? m) (long m)))
                                                             :city     (:city item)})
                                                          (take remaining (:listings mr))))]
                                        (when (seq cards)
                                          (swap! *captured-cards* into cards))))
                                    (format-mashina-results mr)))
                                (catch Exception e
                                  (log/warn :mashina-search-failed (.getMessage e))
                                  nil)))]
        ;; Combine results + capture stats
        (let [combined (str (when lalafo-results lalafo-results)
                           (when mashina-results (str "\n\n" mashina-results)))]
          ;; Compute and capture stats from captured cards
          (when (and *captured-stats* *captured-cards*)
            (let [prices (keep :price @*captured-cards*)]
              (when (seq prices)
                (reset! *captured-stats*
                        {:avg   (long (/ (reduce + prices) (count prices)))
                         :min   (apply min prices)
                         :max   (apply max prices)
                         :count (count prices)}))))
          combined))))))

(def tools
  [{:name "research"
    :description "Research product knowledge online. Finds model names, specs, and buying advice. Use BEFORE searching when: the product is unfamiliar, user asks for alternatives, or user asks for advice. Skip when user names an exact model."
    :schema [:map
             [:topic {:optional false} :string]
             [:query {:optional true} :string]]
    :execute research-execute}

    {:name "search"
    :description "Search for actual listings on Lalafo.kg and Mashina.kg. Returns prices, URLs, and photos."
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
  "Called before each message. Resets session on new search intent to prevent
   context bleed (e.g., cars contaminating laptops). Keeps history for
   follow-ups (:refine, :compare) that need previous context."
  [user-id text session]
  (let [session-data (or @session {})
        intent (policy/classify text session-data)
        msg-count (count (get session-data "messages" []))]
    ;; On new search intent (including :unknown product queries), clear old context
    (when (and (contains? #{:search :unknown} intent) (> msg-count 1))
      (log/info :intent-reset :user-id user-id :intent intent :msgs-cleared (- msg-count 1))
      ;; Keep only system prompt + current user message (already appended)
      (let [msgs (get session-data "messages" [])
            system-msg (first (filter #(= "system" (get % "role")) msgs))
            current-user-msg (last (filter #(= "user" (get % "role")) msgs))
            fresh-msgs (vec (remove nil? [system-msg current-user-msg]))]
        (swap! session assoc "messages" fresh-msgs))
      "Note: Fresh search — previous conversation context was cleared.")))

;; ══════════════════════ BOT FACTORY ══════════════════════

(def tapalakbot
  (delay
    (h/create-bot
     {:name "tapalakbot"
      :prompt system-prompt
      :tools tools
      :model :gemini-3.5-flash
      :provider :openrouter
      :max-turns 8
      :max-tokens 4096
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

(defn ask-stream
  "Run agent with streaming + card capture. Returns {:text :cards :stats}.
   Captures structured search results for deterministic card rendering.
   status-cb called with progress updates during tool execution.
   stream-cb (optional) called with each text delta for live preview.
   search-status-cb (optional) called with rich search progress (found N items, etc).
   early-photos-cb (optional) called with captured cards for immediate photo preview.
   Also caches ads for /N drill-down."
  ([user-id text status-cb]
   (ask-stream user-id text status-cb {}))
  ([user-id text status-cb {:keys [stream-cb city-id search-status-cb early-photos-cb]}]
   (let [cards-atom (atom [])
         stats-atom (atom nil)
         effective-stream-cb (or stream-cb (fn [_]))
         result (binding [*captured-cards* cards-atom
                          *captured-stats* stats-atom
                          *user-city-id* city-id
                          *search-status-cb* search-status-cb
                          *early-photos-cb* early-photos-cb]
                  (h/handle-message-stream!
                   @tapalakbot user-id text
                   effective-stream-cb
                   :status-cb status-cb))
         agent-text (if (map? result) (:content result) (str result))
         cards @cards-atom
         stats @stats-atom]
     ;; Cache ads for /N drill-down
     (when (seq cards)
       (cache-ads! user-id cards))
     (log/info :ask-stream-done :text-len (count agent-text) :cards (count cards)
               :has-stats (some? stats))
     {:text  (or agent-text "")
      :cards cards
      :stats stats})))

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
