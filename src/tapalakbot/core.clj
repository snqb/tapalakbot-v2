(ns tapalakbot.core
  "TapalakBot v2 — Multi-platform marketplace search assistant.
   Uses clj-harness with direct HTTP clients for Lalafo.kg, Mashina.kg, Bazar.kg.
     Agent (Clojure harness) → tapalakbot.lalafo/search (direct HTTP)
                                → smart_search (query gen + search)"
  (:require
   [clj-harness.core :as h]
   [clj-harness.llm :as llm]
   [clj-harness.session.sqlite :as sess]
   [tapalakbot.lalafo :as lalafo]
   [tapalakbot.mashina :as mashina]
   [tapalakbot.bazar :as bazar]
   [tapalakbot.query-builder :as qb]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

;; ══════════════════════ SYSTEM PROMPT ══════════════════════

(def system-prompt
  "You are TapalakBot — Multi-platform marketplace search assistant for Kyrgyzstan.
Searches Lalafo.kg, Mashina.kg (cars), and Bazar.kg.
Speak Russian.

## CRITICAL RULE — you MUST follow this

When user wants to BUY something (any product: phone, laptop, clothes, etc.) — you MUST call the smart_search tool FIRST. Do NOT answer from your own knowledge. Do NOT say 'I will help you find...' without calling the tool. The tool searches real Lalafo.kg listings with actual prices and links. Without it, you cannot show real products.

## Rules

1. **Purchase queries** (user wants to buy something) → IMMEDIATELY call smart_search with what user wants. No preamble, no questions if brand/model given.
2. **Advice questions** (not buying, just asking) → answer from knowledge. Don't call smart_search.
3. **Vague queries** (no brand/model at all) → ask ONE clarifying question.

## Response format

Show 5-8 listings. Respond in Russian. Max 3000 chars.

Structure:
1. One-line intro (what you found, how many)
2. Listings grouped by price tier with emoji headers
3. Each listing: title, price, brief detail
4. Reference items by their EXACT ID from the tool results — copy the #ID numbers exactly as shown. NEVER invent fake IDs. Links are added automatically from the real IDs you provide.

Example:

📱 Нашёл iPhone 13 на Lalafo.kg — 12 вариантов!

🔥 Хорошая цена (до 30 000 сом)
• iPhone 13 128GB — #112345678 — 25 000 сом, хороший
• iPhone 13 64GB — #112345679 — 28 000 сом, с чехлом

💰 Средний диапазон (30 000–45 000 сом)
• iPhone 13 Pro 128GB — #112345680 — 35 000 сом, отличное
• iPhone 13 Pro Max 256GB — #112345681 — 42 000 сом

💎 Премиум
• iPhone 13 Pro Max 512GB — #112345682 — 55 000 сом, новый

NEVER use markdown tables (| --- |). NEVER write URLs or link emojis. Use bold for prices. Respond in Russian.

⚠️ CRITICAL: Copy #ID numbers EXACTLY from the search results you received. Do NOT invent, renumber, or make up IDs. If the tool gave you #112488913, use #112488913 — not #126789012 or any other number you imagined. Links will only work with the exact IDs from the tool.")
;; ══════════════════════ TOOLS ══════════════════════

;; ══════════════════════ URL STORE ══════════════════════

(def ^:private url-store
  "Map of user-id → {item-id url}. Populated by format-search-results, consumed by bot.clj post-processing.
   Per-user to prevent race conditions between concurrent searches."
  (atom {}))

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
                            (let [resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 1000)
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
              ;; Pre-filter: remove obvious accessories/services deterministically
              safe-items (if (and user-query (not (str/blank? user-query)))
                           (qb/filter-accessories items user-query)
                           items)
              ;; Apply relevance filter if we have many items
              relevant (if (and user-query (not (str/blank? user-query)) (> (count safe-items) 100))
                         (relevance-filter safe-items user-query)
                         safe-items)
              ;; Build url-store locally (not global atom)
              ]
          (if (zero? found)
            {:text (get data "message" "Nothing found.") :url-store {}}
            {:text
             (str "🔍 Showing " (count relevant) " relevant candidates"
                  (str " (from " raw " raw listings across " pages " pages)")
                  (when truncated " [truncated]")
                  (if (>= (count relevant) 7)
                    "\nSTRICT: show 15-25 listings from these candidates in the final answer. Include older/budget actual items with caveats if needed. Cover as many relevant listings as possible — users want to see the full market."
                    (str "\nNOTE: Only " (count relevant) " relevant candidates remained after filtering; tell the user the market is thin instead of pretending there are many."))
                  "\n"
                  (str/join "\n"
                            (for [item relevant]
                              (let [item-id (str (get item "id"))
                                    url (get item "url" "")
                                    price (get item "price")
                                    price-str (if price
                                                (str (format "%,.0f" (double price)) " " (get item "currency" "KGS"))
                                                "price unknown")
                                    desc (get item "desc" "")]
                              ;; Store URL for post-LLM citation (per-user)
                                (when (and item-id (not (str/blank? url)) *current-user-id*)
                                  (swap! url-store assoc-in [*current-user-id* item-id] url))
                              ;; Format WITHOUT URL — LLM doesn't see it
                                (str "- #" item-id " " (get item "title" "")
                                     " | " price-str
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

(defn- format-mashina-results
  "Format Mashina.kg car search results."
  [result]
  (let [listings (:listings result)
        total (:total result)]
    (str "🚗 **Mashina.kg** — " (count listings) " авто"
         (when (> total (count listings)) (str " из " total " объявлений")) "\n\n"
         (str/join "\n"
                   (mapv (fn [item]
                           (let [price (get-in item [:price :amount])
                                 currency (get-in item [:price :currency] "KGS")
                                 price-str (if price
                                             (str (format "%,.0f" (double price)) " " currency)
                                             "цена не указана")]
                             (str "• " (:title item)
                                  " — " price-str
                                  (when (:year item) (str ", " (:year item) " г."))
                                  (when (:mileage item) (str ", " (:mileage item) " км"))
                                  (when (:city item) (str " | " (:city item)))
                                  "\n  " (:url item))))
                         (take 8 listings))))))

(defn- format-bazar-results
  "Format Bazar.kg search results."
  [result]
  (let [listings (:listings result)]
    (str "🏪 **Bazar.kg** — " (count listings) " объявлений\n\n"
         (str/join "\n"
                   (mapv (fn [item]
                           (let [price (:price item)
                                 currency (:currency item "KGS")
                                 price-str (if price
                                             (str (format "%,.0f" (double price)) " " currency)
                                             "цена не указана")]
                             (str "• " (:title item)
                                  " — " price-str
                                  (when (:url item) (str "\n  " (:url item))))))
                         (take 8 listings))))))

(defn- smart-search-execute
  "Smart search pipeline: QueryBuilder → platform routing → multi-platform search."
  [args]
  (let [user-id (or (get-thread-user-id) (get args "_user_id") "anonymous")
        user-want (get args "user_want")]
    ;; Bind dynamic var BEFORE let bindings so format-search-results can store URLs
    (binding [*current-user-id* user-id]
      (let [        ;; Step 1: Parse user intent with QueryBuilder
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
                                                "category_id" (or (get args "category_id")
                                                                  (:lalafo-category-id qb-result))
                                                "price_min" final-price-min
                                                "price_max" final-price-max
                                                "city_id" (get args "city_id")
                                                "candidate_limit" 100}
                                   result (lalafo/search search-args)]
                               (log/info :smart-search-lalafo :queries enhanced-queries :price [final-price-min final-price-max])
                               (let [fmt (format-search-results result :user-query user-want)
                                     txt (:text fmt)]
                                 (log/info :search-done :urls (count (get-url-store user-id)) :chars (count txt))
                                 txt)))
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
                                  nil)))
        ;; Step 7: Search Bazar.kg (goods)
            bazar-results (when (search? :bazar)
                            (try
                              (let [q (first enhanced-queries)
                                    br (bazar/search :category (:bazar-category qb-result) :brand q)]
                                (log/info :smart-search-bazar :query q :category (:bazar-category qb-result) :found (count (:listings br)))
                                (when (seq (:listings br))
                                  (format-bazar-results br)))
                              (catch Exception e
                                (log/warn :bazar-search-failed (.getMessage e))
                                nil)))]
        ;; Combine results
        (str (when lalafo-results lalafo-results)
             (when mashina-results (str "\n\n" mashina-results))
             (when bazar-results (str "\n\n" bazar-results)))))))

(def tools
  [{:name "smart_search"
    :description "Multi-platform marketplace search. Searches Lalafo.kg + Mashina.kg + Bazar.kg simultaneously. Takes what user wants to buy, generates optimal search queries, and returns curated results from all Kyrgyz marketplaces. Use for ANY purchase/search intent."
    :schema [:map
             [:user_want {:optional false} :string]
             [:price_min {:optional true} :int]
             [:price_max {:optional true} :int]
             [:category_id {:optional true} :int]
             [:city_id {:optional true} :int]]
    :execute smart-search-execute}])

;; ══════════════════════ PRE-HOOK ══════════════════════

;; Cached category tree — fetched once, reused across sessions
(def ^:private categories-cache
  (delay (lalafo/format-categories-prompt (lalafo/fetch-categories-raw))))

(defn pre-hook
  "Called before each message. Adds category info to system prompt."
  [user-id text session]
  (try
    (let [categories @categories-cache]
      categories)
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
      :max-turns 12
      :nudges {:required-steps ["smart_search"]
               :max-step-blocks 1
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
  (println "║  🔍 TapalakBot v2 — Lalafo.kg AI Assistant      ║")
  (println "║  Model: Claude Sonnet 4 | Tools: search, browse, research ║")
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
        (println result)))
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
