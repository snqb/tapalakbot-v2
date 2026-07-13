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
   [tapalakbot.search :as search]
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

## Output contract
- NEVER mention tool names or narrate that you are searching.
- ALL prose must be Russian.
- Search results are rendered after your prose as deterministic cards. Those cards
  already contain every trusted title, price, condition, city, image, and URL.
- NEVER repeat or enumerate individual listings in your prose.
- NEVER output listing URLs, prices, product tables, or separate top-pick blocks.
- NEVER write Markdown tables (`| ... |`), `==highlight==`, `<details>`, or raw HTML.
- Supported formatting only: short `##` headings, **bold**, *italic*, and `-` bullets.
- Keep the prose under 900 characters so the cards remain the main result.

## What to write after search
1. Optional one-sentence market summary, without inventing numbers.
2. Two or three concise observations explaining which characteristics matter.
3. A warning only when there is a concrete risk: suspicious condition, counterfeit,
   missing documents, or a price anomaly reported by the search data.
4. One short recommendation and one actionable follow-up question.

Good shape:
## Что важно
- Для тяжёлого кода важнее 16 ГБ памяти и активное охлаждение.
- Новый базовый чип выгоднее, если приоритет — автономность.

**Мой совет:** сначала сравнить состояние батареи и гарантию.
Что важнее — максимальная производительность или новый аппарат?

## Budget handling
When the user specifies a budget, prioritize the best value within it rather than
the cheapest item. Do not mention items outside the requested range.

## Tool flow
- Exact model request → search directly.
- Unfamiliar product or advice request → research, then search.
- Follow-up about prior results → use conversation context.
- Pure greeting → answer naturally without tools.

## Quality rules
- Explain recommendation criteria, not listing facts already visible in cards.
- Compare product classes honestly and mention practical ownership costs when known.
- Never fabricate prices, URLs, stock, specifications, or market statistics.
- If verified results are absent, say so and suggest one or two broader query terms.
- For land or houses, remind the user to verify whether the price is per sotka or
  for the whole plot and to check the Красная книга and technical passport.
- If search data marks a price below 50% of the market or known retail level, warn
  that it may indicate fraud, a counterfeit, or a broken product.")
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

(def ^:private candidate-pool-limit 200)
(def result-page-size 20)
(def ^:private result-pool-ttl-ms (* 30 60 1000))

(def result-pools
  "Short cursor ID to an immutable ranked candidate pool and current offset."
  (atom {}))

(defn cache-result-pool!
  "Cache a ranked pool for deterministic, user-scoped pagination.
   Returns a short cursor ID only when unseen cards remain."
  [user-id query cards shown-count]
  (let [cards (vec cards)]
    (when (< shown-count (count cards))
      (let [now (System/currentTimeMillis)
            cursor-id (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 10)]
        (swap! result-pools
               (fn [pools]
                 (let [active (into {}
                                    (remove (fn [[_ state]]
                                              (> (- now (:created-at state))
                                                 result-pool-ttl-ms)))
                                    pools)]
                   (assoc active cursor-id {:user-id user-id
                                            :query query
                                            :cards cards
                                            :offset shown-count
                                            :created-at now}))))
        cursor-id))))

(defn next-result-page!
  "Atomically advance a cursor and return the next unseen page for its owner."
  [user-id cursor-id page-size]
  (loop []
    (let [pools @result-pools
          state (get pools cursor-id)
          now (System/currentTimeMillis)]
      (when (and state
                 (= user-id (:user-id state))
                 (<= (- now (:created-at state)) result-pool-ttl-ms)
                 (< (:offset state) (count (:cards state))))
        (let [start (:offset state)
              end (min (count (:cards state)) (+ start page-size))
              page {:cards (subvec (:cards state) start end)
                    :query (:query state)
                    :start (inc start)
                    :end end
                    :total (count (:cards state))
                    :has-more (< end (count (:cards state)))}
              updated (assoc state :offset end)]
          (if (compare-and-set! result-pools pools (assoc pools cursor-id updated))
            page
            (recur)))))))

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

(def ^:dynamic *captured-query*
  "Exact marketplace query passed to the search tool during this turn."
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
   Returns IDs as strings, or nil when no valid array can be parsed.
   An empty vector is a valid decision that no listing is relevant."
  [content]
  (let [clean (str/replace (or content "") #"\`\`\`json|```|\`" "")
        array-text (second (re-find #"(?s)(\[[^\]]*\])" clean))]
    (when array-text
      (try
        (mapv str (json/parse-string (str/trim array-text) false))
        (catch Exception e
          (log/warn :parse-id-array-error :msg (.getMessage e)
                    :raw (subs (or content "") 0 (min 200 (count (or content "")))))
          nil)))))

(defn- relevance-filter
  "LLM pass 1: filter listings by relevance to user query.
   A valid empty model decision stays empty; only malformed/error responses
   fall back to the unfiltered listings."
  [items user-query]
  (if (empty? items)
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
              parsed-ids (parse-id-array content)]
          (if (nil? parsed-ids)
            (do
              (log/warn :relevance-filter :fallback :reason "no parseable ID array"
                        :raw-response (subs (or content "") 0 (min 200 (count (or content "")))))
              (take 100 items))
            (let [id-set (set parsed-ids)
                  relevant (filter #(contains? id-set (str (get % "id"))) items)]
              (log/info :relevance-filter :input (count items) :output (count relevant))
              (take 100 relevant))))
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

(defn preferred-lalafo-image
  "Choose the highest-resolution image exposed by a Lalafo item."
  [item]
  (or (get-in item ["images" 0 "original_url"])
      (get item "original_url")
      (get item "thumbnail_url")
      (get-in item ["images" 0 "thumbnail_url"])))

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
                  ". CRITICAL: Each item includes its real Lalafo URL after 🔗. Use this exact URL in your markdown links — DO NOT invent or modify the URL. The 🔗 URL is the ONLY valid link for that item."
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
                                       " 🔗 " url
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
  "Format Mashina.kg car results with the exact source URL available to the
   answer model. Compact enough for tool context."
  [result]
  (let [listings (:listings result)
        total (:total result)]
    (str "🚗 **Mashina.kg** — " (count listings) " авто"
         (when (> total (count listings)) (str " из " total " объявлений"))
         "\n"
         (str/join
          "\n"
          (mapv
           (fn [item]
             (let [idx (count (get @url-store *current-user-id* {}))
                   letter (col-letter idx)
                   raw-price (:price item)
                   price (if (map? raw-price) (:amount raw-price) raw-price)
                   currency (if (map? raw-price)
                              (or (:currency raw-price) "KGS")
                              (or (:currency item) "KGS"))
                   price-str (if price
                               (str (format "%,.0f" (double price)) " " currency)
                               "цена не указана")
                   url (:url item)]
               (when (and *current-user-id* url (not (str/blank? url)))
                 (swap! url-store assoc-in [*current-user-id* letter]
                        {:url url :title (:title item) :item-id (str (:id item))}))
               (str letter ". " (:title item) " — " price-str
                    (when (:year item) (str ", " (:year item)))
                    (when (:mileage item) (str ", " (:mileage item) "км"))
                    " 🔗 " url)))
           (take 8 listings))))))

(defn- mashina-listing->card
  [item]
  (search/mashina-item->card item))

(defn- capture-mashina-cards!
  [listings]
  (when *captured-cards*
    (let [remaining (- candidate-pool-limit (count @*captured-cards*))
          cards (if (pos? remaining)
                  (mapv mashina-listing->card (take remaining listings))
                  [])]
      (when (seq cards)
        (swap! *captured-cards* into cards))
      cards)))

(def ^:private generic-auto-query-tokens
  #{"авто" "машина" "автомобиль" "car" "cars" "auto"
    "кроссовер" "внедорожник" "седан" "универсал" "минивэн"
    "семейный" "семейная" "новый" "новая" "бу"})

(defn- specific-auto-query?
  "True when deterministic parsing identified a concrete automotive model query.
   Generic car-advice requests still use LLM enrichment and research."
  [_user-want parsed]
  (let [tokens (->> (re-seq #"[\p{L}\p{N}]+" (str/lower-case (or (:query parsed) "")))
                    (remove generic-auto-query-tokens)
                    (remove (fn [token]
                              (when-let [n (try (Long/parseLong token)
                                                (catch Exception _ nil))]
                                (> n 9999))))
                    vec)]
    (and (:is-auto? parsed) (>= (count tokens) 2))))

(defn- search-execute
  "Smart search pipeline: QueryBuilder → platform routing → multi-platform search."
  [args]
  (let [user-id (or *current-user-id* (get args "_user_id") "anonymous")
        user-want (get args "user_want")]
    ;; Guard: if user_want is nil/blank, return error immediately
    (if (or (nil? user-want) (str/blank? user-want))
      (str "ERROR: search requires a user_want parameter — the product you want to find. "
           "Use the exact text the user asked about.")
      (binding [*current-user-id* user-id]
        (when *captured-query*
          (reset! *captured-query* user-want))
        (let [_ (swap! url-store dissoc user-id)
              deterministic-qb (qb/parse user-want)
              fast-auto? (specific-auto-query? user-want deterministic-qb)
              qb-result (if fast-auto?
                          (assoc deterministic-qb :mashina-query (:query deterministic-qb))
                          (qb/build user-want :use-llm? true))
              ;; Exact model searches do not need category/query-generation LLM calls.
              category-id (when-not fast-auto? (resolve-category user-want))
              _ (when *search-status-cb*
                  (if category-id
                    (*search-status-cb* (str "📂 Категория найдена"))
                    (*search-status-cb* (str "🔍 Без категории — ищем по всем"))))
              platforms (:platforms qb-result)
              search? (fn [p] (or (some #{p} platforms) (some #{:all} platforms)))
              {:keys [queries needs-research research-query]}
              (if fast-auto?
                {:queries [(:query qb-result)]
                 :needs-research false
                 :research-query nil}
                (generate-search-queries user-want))
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
                                                  "candidate_limit" 250}
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
                                       (let [remaining (- candidate-pool-limit
                                                          (count @*captured-cards*))
                                             cards (if (pos? remaining)
                                                     (mapv
                                                      (fn [item]
                                                        (assoc (search/lalafo-item->card item)
                                                               :image
                                                               (preferred-lalafo-image item)))
                                                      (take remaining (:items fmt)))
                                                     [])]
                                         (when (seq cards)
                                           (swap! *captured-cards* into cards)
                                           (when *search-status-cb*
                                             (*search-status-cb*
                                              (str "✨ Кандидатов для анализа "
                                                   (count @*captured-cards*)))))))
                                     txt)
                                   (catch Exception e
                                     (log/error :search-format-failed (.getMessage e)
                                                :result-preview (subs result 0 (min 200 (count result))))
                                     (str "Search error: " (.getMessage e))))))
        ;; Step 6: Search Mashina.kg (cars)
              mashina-results (when (search? :mashina)
                                (try
                                  (let [q (or (:mashina-query qb-result) (first enhanced-queries))
                                        mr (mashina/search-cars :query q :size 100)]
                                    (log/info :smart-search-mashina :query q
                                              :fetched (count (:listings mr))
                                              :reported-total (:total mr))
                                    (when (seq (:listings mr))
                                      (capture-mashina-cards! (:listings mr))
                                      (let [ranked (search/rank-marketplace-cards
                                                    (mapv mashina-listing->card (:listings mr))
                                                    {:query q
                                                     :price-min final-price-min
                                                     :price-max final-price-max})]
                                        (format-mashina-results
                                         (assoc mr :listings ranked)))))
                                  (catch Exception e
                                    (log/warn :mashina-search-failed (.getMessage e))
                                    nil)))]
        ;; Combine results, then filter/rank the complete candidate pool.
          (let [ranking-query (or (:mashina-query qb-result)
                                  (:query qb-result)
                                  user-want)
                ranked-cards (when *captured-cards*
                               (search/rank-marketplace-cards
                                @*captured-cards*
                                {:query ranking-query
                                 :price-min final-price-min
                                 :price-max final-price-max}))
                _ (when *captured-cards*
                    (reset! *captured-cards* ranked-cards))
                combined (str (when lalafo-results lalafo-results)
                              (when mashina-results (str "\n\n" mashina-results)))]
            (log/info :candidate-ranking
                      :query ranking-query
                      :ranked (count ranked-cards)
                      :price [final-price-min final-price-max])
          ;; Compute stats from the normalized ranked pool.
            (when (and *captured-stats* (seq ranked-cards))
              (let [prices (keep search/card-price-kgs ranked-cards)]
                (when (seq prices)
                  (reset! *captured-stats*
                          {:avg (long (/ (reduce + prices) (count prices)))
                           :min (apply min prices)
                           :max (apply max prices)
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
    ;; On new search intent (including :unknown product queries), clear old context + heap
    (when (and (contains? #{:search :unknown} intent) (> msg-count 1))
      (log/info :intent-reset :user-id user-id :intent intent :msgs-cleared (- msg-count 1))
      ;; Keep only current user message (system prompt is prepended by harness)
      (let [msgs (get session-data "messages" [])
            current-user-msg (last (filter #(= "user" (get % "role")) msgs))
            fresh-msgs (vec (remove nil? [current-user-msg]))]
        (swap! session assoc "messages" fresh-msgs)
        ;; Clear heap so old tool results don't leak
        (swap! session assoc-in ["data" "heap"] nil)
        (swap! session update "data" dissoc "last-search"))
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
      :max-tokens 16384
      :nudges {:max-step-blocks 3
               :recover-tool-errors? true}
      :pre-hook pre-hook
      :persistence (let [db-path (or (System/getenv "SESSION_DB_PATH")
                                     "data/tapalakbot-sessions.db")
                         parent (.getParentFile (java.io.File. db-path))]
                     (when parent (.mkdirs parent))
                     (sess/create db-path))
      :effects? true})))

(defn ask
  "Ask TapalakBot a question. Returns response string."
  ([text] (ask "terminal" text))
  ([user-id text]
   (h/handle-message @tapalakbot user-id text)))

(defn ask-stream
  "Run the agent, rank the full candidate pool, and return the first result page.
   The full pool is retained in :pool for deterministic pagination."
  ([user-id text status-cb]
   (ask-stream user-id text status-cb {}))
  ([user-id text status-cb {:keys [stream-cb city-id search-status-cb early-photos-cb]}]
   (let [cards-atom (atom [])
         stats-atom (atom nil)
         query-atom (atom nil)
         effective-stream-cb (or stream-cb (fn [_]))
         result (binding [*captured-cards* cards-atom
                          *captured-query* query-atom
                          *current-user-id* user-id
                          *captured-stats* stats-atom
                          *user-city-id* city-id
                          *search-status-cb* search-status-cb
                          *early-photos-cb* early-photos-cb]
                  (h/handle-message-stream!
                   @tapalakbot user-id text
                   effective-stream-cb
                   :status-cb status-cb))
         agent-text (if (map? result) (:content result) (str result))
         pool (vec @cards-atom)
         cards (vec (take result-page-size pool))
         stats @stats-atom
         query @query-atom]
     ;; Cache the complete ranked pool for numeric drill-down.
     (when (seq pool)
       (cache-ads! user-id pool))
     (log/info :ask-stream-done
               :text-len (count agent-text)
               :candidates (count pool)
               :cards (count cards)
               :has-more (> (count pool) (count cards))
               :has-stats (some? stats))
     {:text (or agent-text "")
      :cards cards
      :pool pool
      :has-more (> (count pool) (count cards))
      :stats stats
      :query query})))

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
