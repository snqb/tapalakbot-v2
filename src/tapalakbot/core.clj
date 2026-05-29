(ns tapalakbot.core
  "TapalakBot v2 — Conversational Lalafo.kg search assistant.
   Uses clj-harness with Python lalafo-client via shell tools.

   Architecture:
     Agent (Clojure harness) → shell-tool → lalafo_cli.py → LalafoClient (Python)
                                → search_lalafo (multi-query)
                                → browse_categories
                                → research_topic (Exa)"
  (:require
   [clj-harness.core :as h]
   [clj-harness.llm :as llm]
   [clj-harness.session.sqlite :as sess]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

;; ══════════════════════ SYSTEM PROMPT ══════════════════════

(def system-prompt
  "You are TapalakBot — an intelligent, conversational Lalafo.kg search assistant for Kyrgyzstan.
You speak Russian (and understand Kyrgyz). You're helpful, knowledgeable, and thoughtful.

## How you behave

You DON'T blindly search whenever someone writes something. You THINK first:

1. **Vague/one-word** → ask ONE clarifying question, then search.
   «ноутбук» → «Для чего? Учёба, игры, работа?»
   But «ноутбук до 40000» has price → search immediately!
   «планшет со стилусом» has feature → search immediately!
   «наушники» → ask ONE question. Don't write essays about product types.

2. **Factual product question you're UNSURE about** → call research_topic, share insights.
   «когда вышел iPad Air 3?» → research, answer with facts
   «какой процессор у Redmi Note 12?» → research specs
   ONLY use research_topic for factual questions you genuinely don't know the answer to.

3. **Advice/opinion question** → answer from YOUR knowledge, NO tools needed.
   «а бу стиралку нормально брать?» → give advice (you know this)
   «какой роутер лучше для квартиры?» → give recommendations from knowledge
   «стоит ли переплачивать за iPhone?» → share opinion
   DO NOT call research_topic for common knowledge questions!

4. **Clear and specific** → call search_lalafo immediately.
   RULE: if query has price, brand, OR feature → SEARCH NOW, don't ask questions.
   «macbook m1 до 50000» → search now
   «роутер до 4000» → search now
   «телефон xiaomi до 10к для такси» → search now
   «ноутбук до 40000» → search now (has price!)
   «планшет со стилусом» → search now (has feature!)
   «наушники беспроводные до 3000» → search now (has feature + price!)
   Only ask clarifying questions for truly vague queries with zero constraints.

5. **Follow-up with new specifics** → call search_lalafo using conversation context.
   «а сяоми есть?» after router search → search «xiaomi роутер»
   «до 15000» after washing machine search → search with price filter
   «а получше есть?» after a budget search → ALWAYS call search_lalafo again; search higher-tier models, raise/relax the budget slightly, and use premium model names in the SAME tool call. NEVER answer follow-up product results from memory/history.

6. **After showing results** → organize by price tier (🔥 Best / 💰 Budget / 💎 Premium), show 7-10 variants when many exist, suggest alternatives, ask if they want to refine

## 🔴 URL FORMAT — MOST IMPORTANT RULE (READ FIRST)

⛔⛔⛔ THE #1 MISTAKE: Writing \"👉 Смотреть\" instead of a real lalafo.kg URL. ⛔⛔⛔

WRONG (DO NOT DO THIS):
```
1️⃣ ASUS TUF Gaming — 40 000 с
👉 Смотреть
```

RIGHT (ALWAYS DO THIS — copy the real 🔗 link from search results):
```
1️⃣ ASUS TUF Gaming — 40 000 с
🔗 https://lalafo.kg/bishkek/ads/asus-tuf-gaming-id-113171780
```

⛔ \"👉 Смотреть\" = BROKEN RESPONSE. The user CANNOT click it.
✅ 🔗 + real URL = WORKS. The user CAN click and open the listing.

## Response format

Each listing looks EXACTLY like this:
```
1️⃣ Product Name — PRICE с ✅
🔗 https://lalafo.kg/bishkek/ads/...
• specs on this line
```

RULES:
- EVERY listing has 🔗 followed by the ACTUAL lalafo.kg URL from search results
- NEVER write \"👉 Смотреть\", \"👉 тык\", \"👉 клик\" — these are NOT real links
- Copy the URL verbatim from the search tool output
- Show 7-10 listings when the tool found many relevant options; show 5-8 only when the market is thin
- Organize by price tier: 🔥 Best / 💰 Budget / 💎 Premium

Example of a correct response:
```
⚔️ Что выбрать:

1️⃣ Galaxy S20 — 14 500с
🔗 https://lalafo.kg/bishkek/ads/...
• Экран: 120Hz AMOLED | Камера: 64MP

2️⃣ S10+ — 14 000с
🔗 https://lalafo.kg/bishkek/ads/...
• Экран: 60Hz AMOLED | Камера: 12MP
```

- At the end: suggest next steps
- For vague queries: ask 1-2 short clarifying questions
- ⛔ ABSOLUTE RULE: every listing MUST include its actual lalafo.kg URL from search results. \"👉 Смотреть\" or \"👉 тык\" are FORBIDDEN. Copy the real URL.

## IMPORTANT: Lalafo search strategy
   Lalafo search is keyword-based and NOISY. Generic queries (\"планшет со стилусом\")
   return junk. Search by EXACT MODEL NAMES instead:

   For tablets with stylus → queries: [\"Samsung S6 Lite S Pen\" \"Redmi Pad Smart Pen\" \"Wacom\"]
   For iPad under a budget → queries: [\"iPad 9\" \"iPad 10\" \"iPad Air\" \"iPad Pro\" \"планшет Apple\"], category_id 1341
   When listing iPads, include older/budget actual iPads too (iPad 7, iPad Air 2) with a caveat — don't omit them just because premium options exist.
   For better iPad follow-ups (\"получше\") → queries: [\"iPad Air M1\" \"iPad Air M2\" \"iPad Air 5\" \"iPad Pro 11\" \"iPad 10\"], price_max = previous budget + 10000 if needed
   For tablet+laptop combos → [\"Surface Pro\" \"iPad Pro\" \"Lenovo Yoga\"]
   For WiFi 6 routers → [\"Xiaomi AX3000T\" \"Tenda TX3000\" \"TP-Link Archer\"]
   For IQOS/electronic cigarettes → queries: [\"IQOS Iluma\" \"IQOS 3 Duo\" \"IQOS Originals\" \"айкос оригинал\"], category_id 1639
     ⛔ NEVER search just \"iqos\" or \"айкос\" — gets 800+ junk (cases, lighters, accessories).
     ⛔ Cases, holders, chargers, lighters are NOT devices — filter them out.

   Known models that include stylus:
   - Samsung Galaxy Tab S6 Lite, S7 FE, S8, S9 (S Pen in box)
   - Redmi Pad Pro / Redmi Pad 2 Pro + Redmi Smart Pen
   - Wacom Intuos/One (drawing tablet, stylus included)
   - Lenovo Tab P11/P12 с Precision Pen

   If search returns noise → curate the 2-3 best real results, ignore junk.
   Always mention Wacom as a budget drawing tablet option (4,000-7,500 сом).

## Search tool (updated)

search_lalafo now:
- Scans 3 pages × 200 items per query automatically
- Quality-filters junk (no price, no photo, spam titles)
- Sends up to 250 candidates into LLM-relevance filtering (removes chargers, accessories, unrelated items)
- Returns relevant listings for you to curate

You MUST include `user_query` with the user's message PLUS resolved conversation context — this powers relevance filtering.
Example: {\"queries\": [\"роутер\", \"WiFi роутер\"], \"user_query\": \"роутер до 4000\", \"price_max\": 4000}
Follow-up example: user says \"а получше есть?\" after iPad search → `user_query`: \"iPad получше до 50000, не аксессуары\".
For marketplace result replies, start with a short confidence line: \"Проверил N объявлений, отсеял аксессуары — вот лучшие варианты:\" using the scan stats from the tool output.
Never narrate search planning or category attempts to the user. Do NOT write \"попробую\", \"давай уточню\", \"категория не подходит\", or similar internal search chatter. Make the tool call silently, then answer from tool results.

## Speed rules
- Don't call research_topic + search_lalafo in the same turn (too slow)
- Call search_lalafo AT MOST ONCE per response. Put ALL synonyms in one call's `queries` list.
  BAD: two separate search_lalafo calls — TOO SLOW!
  GOOD: search_lalafo with queries=[\"rtx 4060\", \"rtx 4070\", \"видеокарта nvidia\"] — ONE call!
- If the user gives enough info to search → call search_lalafo immediately with no preamble, no category guessing narration, no intermediate text
- If the user asks for \"ещё\", \"другие\", \"получше\", \"подешевле\" after results → this is a new search_lalafo call, not an advice-only answer
- NEVER use Markdown tables — they break on mobile. Always use structured lists.
- HARD COUNT RULE: if tool output says 7+ relevant candidates, you MUST show 7-10 listings. Do not stop at 5-6.
- Never silently show only 2-6 listings after a broad search. If you show fewer than 7, explicitly say why: \"реально годных нашёл только N; остальное аксессуары/старьё/мусор\".
- Use clear formatting: 🔥 Best deal, 💰 Бюджет, 💎 Премиум
- Include 🔗 + real lalafo.kg URL for every listing shown
- Responses can be up to 3500 chars — be thorough, Telegram handles it

## Rules
- Never search without understanding what the user wants
- Share product advice only in the final answer after search results, not before/during tool calls
- Flag suspiciously cheap listings (likely scams)
- Use emoji sparingly, be concise
- Respond in Russian
- When showing Lalafo results, EVERY listing must have 🔗 + real lalafo.kg URL
- Pick SPECIFIC leaf categories (e.g. Networking for routers, not Electronics)
- NEVER use Markdown tables (| --- |). They don't render on Telegram mobile. Use structured lists for comparisons.")

;; ══════════════════════ TOOLS ══════════════════════

(def cli-dir
  "Directory where lalafo_cli.py lives and uv can resolve deps."
  (or (System/getenv "TAPALAKBOT_DIR")
      (str (System/getProperty "user.dir"))))

(def cli-path
  "Path to Python CLI wrapper."
  (str cli-dir "/lalafo_cli.py"))

(def cli-base-dir
  "Base tapalakbot project dir for uv resolution."
  (or (System/getenv "TAPALAKBOT_BASE_DIR")
      (System/getProperty "user.dir")
      (str (System/getProperty "user.home") "/Projects/tapalakbot")))

(defn- run-cli [command args]
  "Execute lalafo_cli.py from tapalakbot project directory (where uv can resolve all deps).
   Returns parsed JSON or error string.

   Redirects stderr to /dev/null to avoid deadlock when Python process fills stderr buffer."
  (let [args-str (if (string? args) args (json/generate-string args))
        escaped-args (str/replace args-str "'" "'\\''")
        ;; Run from tapalakbot dir, stderr → /dev/null
        cmd (str "cd " cli-base-dir " && uv run python "
                 cli-path " " command " '" escaped-args "' 2>/dev/null")
        result (try
                 (let [proc (.exec (Runtime/getRuntime)
                                   (into-array String ["bash" "-c" cmd])
                                   nil (java.io.File. cli-base-dir))
                       out (slurp (.getInputStream proc))
                       exit (.waitFor proc)]
                   (if (= 0 exit)
                     (let [trimmed (str/trim out)]
                       ;; First non-empty line is JSON; rest may be uv noise
                       (try (json/parse-string trimmed false)
                            (catch Exception _
                              (let [lines (str/split-lines trimmed)
                                    json-line (first (filter #(str/starts-with? (str/trim %) "{") lines))]
                                (if json-line
                                  (try (json/parse-string json-line false)
                                       (catch Exception _ trimmed))
                                  trimmed)))))
                     (str "CLI error (exit " exit "): " (str/trim out))))
                 (catch Exception e
                   (str "CLI exception: " (.getMessage e))))]
    (if (string? result)
      result
      (json/generate-string result {:pretty true}))))

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
                            (let [resp (llm/llm :deepseek-chat messages [] :provider :deepseek :max-tokens 1000)
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
   Main LLM does curation (pass 2)."
  (let [data (if (string? result-json)
               (try (json/parse-string result-json false) (catch Exception _ nil))
               result-json)]
    (if (not (map? data))
      (str result-json)
      (if-let [err (get data "error")]
        (str "Search error: " err)
        (let [found (get data "found" 0)
              raw (get-in data ["stats" "raw"] found)
              pages (get-in data ["stats" "pages"] 0)
              truncated (get data "truncated" false)
              items (get data "items" [])
              ;; Apply relevance filter if we have many items
              relevant (if (and user-query (not (str/blank? user-query)) (> (count items) 12))
                         (relevance-filter items user-query)
                         items)]
          (if (zero? found)
            (get data "message" "Nothing found.")
            (str "🔍 Showing " (count relevant) " relevant candidates"
                 (str " (from " raw " raw listings across " pages " pages)")
                 (when truncated " [truncated]")
                 (if (>= (count relevant) 7)
                   "\nSTRICT: show 7-10 listings from these candidates in the final answer. Do not show only 5-6. Include older/budget actual items with caveats if needed."
                   (str "\nNOTE: Only " (count relevant) " relevant candidates remained after filtering; tell the user the market is thin instead of pretending there are many."))
                 ":\n"
                 (str/join "\n"
                           (for [item relevant]
                             (let [price (get item "price")
                                   price-str (if price
                                               (str (format "%,.0f" (double price)) " " (get item "currency" "KGS"))
                                               "price unknown")
                                   desc (get item "desc" "")]
                               (str "- #" (get item "id") " " (get item "title" "")
                                    " | " price-str
                                    " | 🔗 " (get item "url" "")
                                    (when (not (str/blank? desc))
                                      (str " | " desc)))))))))))))

(defn- format-research-results [result-json]
  "Format web research results."
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

(def tools
  [{:name "search_lalafo"
    :description "Search Lalafo.kg. Scans 3 pages × 200 items, quality-filters junk, then LLM-relevance-filters up to 250 candidates. Returns pre-filtered relevant listings. CRITICAL: search by EXACT MODEL NAMES. Use 4-6 synonyms/model names for broad coverage. For many matches, show user 7-10 best results organized by price tier."
    :schema {"type" "object"
             "properties"
             {"queries" {"type" "array" "items" {"type" "string"}
                         "description" "4-6 search query variants (exact models/synonyms) for broad coverage"}
              "user_query" {"type" "string" "description" "The user's request plus resolved conversation context for relevance filtering. For follow-ups, include the product/category from the previous turn, e.g. 'iPad получше до 50000, не аксессуары'."}
              "category_id" {"type" "integer" "description" "Lalafo category ID (leaf category, can be null)"}
              "price_max" {"type" "integer" "description" "Maximum price in KGS"}
              "price_min" {"type" "integer" "description" "Minimum price in KGS"}
              "city_id" {"type" "integer" "description" "City ID (103184=Bishkek, 103244=Osh), default Bishkek"}
              "max_pages" {"type" "integer" "description" "Pages per query; default 3. Use 4-5 only for broad expensive follow-ups."}
              "per_page" {"type" "integer" "description" "Items per page; default 200."}}
             "required" ["queries" "user_query"]}
    :execute (fn [args]
               (let [user-query (get args "user_query" "")
                     result (run-cli "search" (dissoc args "user_query"))]
                 (format-search-results result :user-query user-query)))}

   {:name "browse_categories"
    :description "Browse Lalafo categories to find the right category ID for a search. Use to discover leaf category IDs."
    :schema {"type" "object"
             "properties"
             {"search_term" {"type" "string" "description" "Term to search categories by, e.g. 'headphones', 'bicycle'. Leave empty for full tree."}}
             "required" []}
    :execute (fn [args]
               (let [term (get args "search_term")
                     result (if (str/blank? term)
                              (run-cli "categories" "")
                              (run-cli "categories" term))]
                 (if (string? result) result result)))}

   {:name "research_topic"
    :description "Research a topic online — product info, market prices, specs, reviews. Use BEFORE searching when user asks factual questions you're unsure about. Do NOT use for common-knowledge advice questions."
    :schema {"type" "object"
             "properties"
             {"query" {"type" "string" "description" "What to research, e.g. 'iPad Air 3 specs release date'"}}
             "required" ["query"]}
    :execute (fn [args]
               (let [result (run-cli "research" args)]
                 (format-research-results result)))}])

;; ══════════════════════ PRE-HOOK ══════════════════════

(defn pre-hook
  "Called before each message. Adds category info to system prompt."
  [user-id text session]
  (try
    (let [categories (run-cli "categories" "")]
      (if (string? categories)
        categories
        (str "Lalafo categories (use leaf IDs for search_lalafo):\n" categories)))
    (catch Exception _ nil)))

;; ══════════════════════ BOT FACTORY ══════════════════════

(def tapalakbot
  (delay
    (h/create-bot
     {:name "tapalakbot"
      :prompt system-prompt
      :tools tools
      :model :deepseek-v4
      :provider :deepseek
      :max-turns 8
      :nudges {:required-steps ["search_lalafo"]
               :recover-tool-errors? true}
      :pre-hook pre-hook
      :persistence (sess/create "/tmp/tapalakbot-sessions.db")})))

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
    (let [cats (run-cli "categories" "")]
      (println (if (string? cats) (str (count cats) " chars") "ok")))
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

  ;; Test raw CLI
  (require '[tapalakbot.core :refer [run-cli]])
  (run-cli "search" {"queries" ["router" "роутер"] "price_max" 4000})
  (run-cli "categories" "headphones")
  (run-cli "research" {"query" "iPad Air 3 release date"})

  ;; Inspect sessions
  @(:sessions @tapalakbot))
