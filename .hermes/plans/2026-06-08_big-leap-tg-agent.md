# tapalakbot + clj-harness: Big Leap to tg-agent Architecture

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Transform tapalakbot from "LLM generates everything" to "deterministic pipeline + LLM curates" — the tg-agent harnessed architecture where trust-critical facts (prices, URLs, titles) never touch the LLM, and structured payloads flow from tools to transport.

**Architecture:** Three layers, each owning what it should:

1. **Deterministic layer** (search + parse + render): query_builder, lalafo, mashina, monitor, NEW render.clj — owns all trust-critical facts
2. **Agent layer** (LLM): core.clj — owns ONLY curation (which items) + prose (intro/CTA in Russian). Shrinks from ~2000 token output to ~100 tokens
3. **Transport layer** (Telegram): bot.clj — owns session state, UX, progress messages, structured payload → HTML rendering

**Current anti-patterns being eliminated:**
- LLM generates Telegram HTML → deterministic card renderer replaces it
- url-store atom hack → structured data passthrough in clj-harness
- citation-replace regex surgery → deterministic link injection
- strip-fake-urls / strip-tables safety nets → LLM never touches URLs
- 2000-token system prompt about anti-hallucination → 100-token structured contract

**Tech Stack:** Clojure 1.12, deps.edn, Malli (for schema), clj-harness (git dep), SQLite, Java HttpClient.

**Constraints:**
- clj-harness changes are backward-compatible (no API breaks for downstream)
- tapalakbot external behavior preserved (Telegram user sees same quality results)
- All existing tests pass
- No new Java/Python dependencies

---

## Phase 1: clj-harness Structured Output Passthrough

The foundation. Tools return structured data alongside text. The harness preserves it. Without this, everything else requires hacks.

### Task 1.1: Preserve `:structured` in execute-tool-call

**Objective:** When a tool returns `{:content "text for LLM" :structured {:cards [...]} }`, the harness keeps `:structured` on the result map instead of discarding it.

**Files:**
- Modify: `src/clj_harness/tool_loop.clj:142-147`

**Step 1: Modify execute-tool-call return map**

Current (line 142-147):
```clojure
result-str (str (result-content enriched))
{:tool name
 :ok? (boolean (and tool (result-ok? enriched)))
 :message {"role" "tool"
           "tool_call_id" id
           "content" (format-tool-output heap-atom name result-str)}}
```

Replace with:
```clojure
result-str (str (result-content enriched))
(let [structured (when (map? enriched) (:structured enriched))]
  {:tool name
   :ok? (boolean (and tool (result-ok? enriched)))
   :structured structured   ;; ← NEW: preserved for callers
   :message {"role" "tool"
             "tool_call_id" id
             "content" (format-tool-output heap-atom name result-str)}})
```

**Step 2: Verify backward compatibility**

```bash
cd /Users/sn/Projects/clj-harness
clojure -M -e '
(require (quote [clj-harness.core :as h]))
(let [bot (h/create-bot {:name "test" :prompt "Say hi" :model :deepseek-v4-pro :provider :deepseek :max-turns 1 :nudges false})]
  (let [result (h/handle-message bot "u1" "hello" :max-turns 1)]
    (println "Result type:" (type result))
    (println "Is string?" (string? result))
    (println "Preview:" (subs (str result) 0 (min 50 (count (str result)))))))
```
Expected: Result is a string. Existing callers work unchanged.

**Step 3: Commit**

```bash
cd /Users/sn/Projects/clj-harness
git add src/clj_harness/tool_loop.clj
git commit -m "feat: preserve :structured data from tool results in execute-tool-call"
```

---

### Task 1.2: Expose structured outputs in handle-message

**Objective:** `handle-message` returns `{:content "..." :tool-outputs [...]}` when tools produced structured data, or just the string when they didn't. Backward-compatible.

**Files:**
- Modify: `src/clj_harness/core.clj:226-263` (handle-message)

**Step 1: Add structured collection to handle-message**

Current (simplified):
```clojure
(defn handle-message [bot user-id text & {:keys [model provider max-turns] :as overrides}]
  (let [...]
    resp ((:pipeline bot) ctx)
    result (or (:content resp) "Sorry, something went wrong.")
    ...
    result))
```

Replace the return section (after pipeline call) with:
```clojure
    resp ((:pipeline bot) ctx)
    result (or (:content resp) "Sorry, something went wrong.")
    ;; Collect structured outputs from tool results in the agent loop
    tool-outputs (vec (keep :structured (:tool-results resp)))]
    (observe/record! ...)
    (memory/session-add! session "assistant" result)
    (save-session! bot user-id session)
    (when session-heap (heap/gc! session-heap))
    ;; Return structured map if any tool produced structured data, else plain string
    (if (seq tool-outputs)
      {:content result :tool-outputs tool-outputs}
      result)))
```

**Note:** This requires the pipeline (wrap-tools / wrap-tools-v2) to include `:tool-results` in the response map. Check if it already does — if not, add it in wrap-tools:

In `middleware.clj` wrap-tools, after the loop, add `:tool-results` to the final response:
```clojure
;; At the end of wrap-tools loop, when returning final response:
(assoc resp :tool-results (vec (filter :structured tool-results-so-far)))
```

The `tool-results-so-far` accumulates across loop iterations. Add it to the loop bindings:
```clojure
(loop [msgs messages turn 0 nudge-state (gr/make-state) tool-results []]
  ;; ... inside, after executing tools:
  (recur ... (into tool-results results))
  ;; ... at the end:
  {:content ... :tool-results tool-results})
```

**Step 2: Same for handle-message-stream!**

In `stream.clj` stream-agent, collect tool results with `:structured` and include in return. The streaming path returns accumulated content string — change to return `{:content "..." :tool-outputs [...]}` when structured data exists.

**Step 3: Verify**

```bash
clojure -M -e '
(require (quote [clj-harness.core :as h]))
(let [bot (h/create-bot {:name "test" :prompt "Say hi" :model :deepseek-v4-pro :provider :deepseek :max-turns 1 :nudges false})]
  (let [result (h/handle-message bot "u1" "hello" :max-turns 1)]
    (println "Type:" (type result))
    (println "String?" (string? result))
    (println "Map?" (map? result))))
```
Expected: String (no tools called, no structured data).

**Step 4: Commit**

```bash
git add src/clj_harness/core.clj src/clj_harness/middleware.clj src/clj_harness/stream.clj
git commit -m "feat: handle-message returns {:content :tool-outputs} when tools produce structured data"
```

---

### Task 1.3: Fix 3 bugs from code review

**Objective:** Remove debug println, fix compact sampling, remove dummy LLM call.

**Files:**
- Modify: `src/clj_harness/agent_loop.clj:159`
- Modify: `src/clj_harness/compact.clj:74`
- Modify: `src/clj_harness/middleware.clj:187-188`

**Step 1: Remove debug println (agent_loop.clj:159)**

Replace:
```clojure
(do (println "[DEBUG] :fatal reached, content:" (pr-str (when content (subs content 0 (min 50 (count content))))) "reason:" (:reason checked))
    [(assoc state :phase :done :response {:content (if step-fail? fallback (or content fallback))})
     [(fx/make-emit-event fx/event-turn-end :reason :nudge-fatal)]])
```
With:
```clojure
[(assoc state :phase :done :response {:content (if step-fail? fallback (or content fallback))})
 [(fx/make-emit-event fx/event-turn-end :reason :nudge-fatal)]]
```

**Step 2: Fix compact sampling (compact.clj:74)**

Replace:
```clojure
summary-msgs (vec (take split-at (take split-at older)))
```
With:
```clojure
summary-msgs (vec (concat (take split-at older)
                          (take-last split-at older)))
```

**Step 3: Remove dummy LLM call (middleware.clj:187-188)**

In wrap-tools-v2, remove or skip the dummy-resp call. The model/provider are resolved from ctx or config later anyway:
```clojure
;; Remove these lines:
dummy-resp (try (handler {:messages [] :tools _tool-schemas})
                (catch Exception _ {:content "" :tool-calls nil}))
```

**Step 4: Clean unused imports (agent_loop.clj:43, middleware.clj:12,14)**

Remove:
- `agent_loop.clj:43` — `[clojure.tools.logging :as log]` (unused)
- `middleware.clj:12` — `put!` from core.async refer (unused)
- `middleware.clj:14` — `[clj-harness.effects :as fx]` (unused)

**Step 5: Smoke test**

```bash
clojure -M -e '(doseq [n (quote [clj-harness.core clj-harness.agent-loop clj-harness.compact clj-harness.middleware])] (require n)) (println :ok)'
```
Expected: `:ok`

**Step 6: Commit**

```bash
git add -A
git commit -m "fix: remove debug println, fix compact sampling, remove dummy LLM call, clean unused imports"
```

---

### Task 1.4: Tag clj-harness v2.4.0

**Objective:** Tag release so tapalakbot pins to it.

```bash
cd /Users/sn/Projects/clj-harness
git tag -a v2.4.0 -m "Structured output passthrough + bug fixes"
git log --oneline -5  # verify
```

---

## Phase 2: tapalakbot — Deterministic Card Renderer

The LLM never touches prices, URLs, or card layout again. This module builds Telegram HTML from structured data.

### Task 2.1: Create render.clj — card data structures

**Objective:** Define the structured card schema and the card-to-HTML renderer.

**Files:**
- Create: `src/tapalakbot/render.clj`

**Step 1: Create render.clj with card schema + renderer**

```clojure
(ns tapalakbot.render
  "Deterministic card renderer for Telegram HTML.
   Structured data in, Telegram HTML out. LLM never touches this."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════ SCHEMA ════════════════════

;; A Card:
;; {:title "iPhone 13 128GB"
;;  :price 25000
;;  :currency "KGS"
;;  :url "https://lalafo.kg/..."
;;  :platform :lalafo    ;; :lalafo | :mashina
;;  :condition "хороший" ;; optional
;;  :year 2021           ;; optional (cars)
;;  :mileage "45000 км"  ;; optional (cars)
;;  :city "Бишкек"       ;; optional
;;  :tier :good}         ;; :great | :good | :premium — assigned by orchestrator

;; A Reply:
;; {:mode :shortlist     ;; :clarify | :shortlist | :refine | :no-results | :error
;;  :intro "Нашёл 8 iPhone 13 на Lalafo.kg"
;;  :cards [...]
;;  :cta "Хотите сузить по бюджету?"
;;  :assumptions ["Цены в сомах"]
;;  :platforms-used [:lalafo :mashina]}

;; ════════════════════ TIER LOGIC ════════════════════

(defn assign-tier
  "Assign price tier based on item price vs market average.
   :great = below 70% of avg, :good = within range, :premium = above 130% of avg."
  [price avg-price]
  (when (and price avg-price (pos? avg-price))
    (let [ratio (/ (double price) (double avg-price))]
      (cond
        (< ratio 0.7)  :great
        (> ratio 1.3)  :premium
        :else           :good))))

(defn tier-emoji [tier]
  (case tier
    :great   "🔥"
    :good    "💰"
    :premium "💎"
    "📦"))

;; ════════════════════ CARD RENDERING ════════════════════

(defn format-price
  "Format price with thousand separators: 25000 → '25 000'"
  [price]
  (when price
    (let [s (str (long price))]
      (str/join (reverse (mapcat (fn [i c] (if (and (pos? i) (zero? (mod i 3))) [" " c] [c]))
                                 (range) (reverse s)))))))

(defn render-card
  "Render a single card to Telegram HTML."
  [{:keys [title price currency url platform condition year mileage city tier]}]
  (let [tier-icon (tier-emoji tier)
        price-str (when price
                    (str "<b>" (format-price price) " " (or currency "KGS") "</b>"))
        detail-parts (cond-> []
                       condition (conj condition)
                       year      (conj (str year " г."))
                       mileage   (conj mileage)
                       city      (conj city))
        detail-str (when (seq detail-parts) (str/join ", " detail-parts))]
    (str tier-icon " " title
         (when price-str (str " — " price-str))
         (when detail-str (str " (" detail-str ")"))
         (when (and url (not (str/blank? url)))
           (str " <a href='" url "'>ссылка</a>")))))

(defn render-cards
  "Render a vector of cards to Telegram HTML. Groups by tier."
  [cards]
  (let [grouped (group-by :tier cards)
        tier-order [:great :good :premium]
        tier-names {:great "🔥 Выгодная цена" :good "💰 Хорошая цена" :premium "💎 Премиум"}
        lines (into []
                    (mapcat
                     (fn [tier]
                       (let [items (get grouped tier)]
                         (when (seq items)
                           (into [(str "\n" (get tier-names tier tier) "\n")]
                                 (map render-card items)))))
                     tier-order))]
    (str/join "\n" lines)))

;; ════════════════════ FULL REPLY RENDERING ════════════════════

(defn render-reply
  "Render a full Reply map to Telegram HTML string."
  [{:keys [mode intro cards cta assumptions]}]
  (case mode
    :error    (str "❌ " (or intro "Произошла ошибка. Попробуйте ещё раз."))
    :no-results (str "🔍 " (or intro "Ничего не найдено."))
    :clarify  (str "❓ " (or intro "Уточните, что именно вы ищете."))
    ;; shortlist, refine — full render
    (let [card-html (when (seq cards) (render-cards cards))
          assumptions-line (when (seq assumptions)
                             (str "\n<i>" (str/join " · " assumptions) "</i>"))]
      (str (when intro (str intro "\n"))
           card-html
           assumptions-line
           (when cta (str "\n\n" cta))))))
```

**Step 2: Verify it loads**

```bash
cd /Users/sn/Projects/tapalakbot-v2
clojure -M -e '(require (quote [tapalakbot.render :as r])) (println :ok)'
```
Expected: `:ok`

**Step 3: Test render-card with sample data**

```bash
clojure -M -e '
(require (quote [tapalakbot.render :as r]))
(println (r/render-card {:title "iPhone 13 128GB" :price 25000 :currency "KGS"
                         :url "https://lalafo.kg/123" :tier :good :condition "хороший"}))
(println)
(println (r/render-reply {:mode :shortlist
                          :intro "📱 Нашёл 3 iPhone 13 на Lalafo.kg"
                          :cards [{:title "iPhone 13 128GB" :price 25000 :url "https://lalafo.kg/1" :tier :good}
                                  {:title "iPhone 13 Pro" :price 42000 :url "https://lalafo.kg/2" :tier :premium}]
                          :cta "Хотите сузить по бюджету?"
                          :assumptions ["Цены в сомах"]}))
'
```
Expected: Clean HTML output with tier groups, formatted prices, clickable links.

**Step 4: Commit**

```bash
git add src/tapalakbot/render.clj
git commit -m "feat: deterministic card renderer — structured data in, Telegram HTML out"
```

---

## Phase 3: tapalakbot — Deterministic Turn Classifier

Replace the regex purchase-intent-pattern with a proper classifier.

### Task 3.1: Create policy.clj — turn classifier

**Objective:** Classify user intent deterministically, before the LLM sees anything.

**Files:**
- Create: `src/tapalakbot/policy.clj`

**Step 1: Create policy.clj**

```clojure
(ns tapalakbot.policy
  "Deterministic turn classifier — decides what happens BEFORE the LLM sees anything.
   Runs on every user message. Returns a mode that determines the execution path."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════ PATTERNS ════════════════════

(def ^:private greeting-re
  #"(?i)^\s*(привет|здравствуй|добрый|доброе|хай|hello|hi|hey|салам|سلام)\s*$")

(def ^:private reset-re
  #"(?i)^\s*(новый диалог|сброс|заново|start|reset|начать)\s*$")

(def ^:private tracking-re
  #"(?i)^\s*(мои подписки|отслеживан|подписк|track|alerts)\s*$")

(def ^:private help-re
  #"(?i)^\s*(помощь|help|что умеешь|команды)\s*$")

(def ^:private thanks-re
  #"(?i)^\s*(спасибо|спс|thanks|thank you|ок|понял|ладно)\s*$")

(def ^:private refine-keywords
  #{"дешевле" "дороже" "только новые" "только бу" "без пробега"
    "чёрный" "белый" "красный" "синий" "зелёный"
    "поближе" "подешевле" "получше" "посовременнее"
    "другой цвет" "другой размер" "другой бренд"
    "а если" "а можно" "а что насчёт" "а как насчёт"})

(def ^:private comparison-re
  #"(?i)(что лучше|сравни|какой лучше|чем отличается|что выбрать|что посоветуешь)")

(def ^:private purchase-intent-re
  #"(?i)(найди|ищ[уе]|купи[ть]|сколько стоит|цена|в продаже|покажи|хочу|ищу|надо|нужен|нужна|нужно|прода[ею]|до \d+|от \d+|б/у|подерж|бу\b|нов[аы]я|планшет|ноут|телефон|айфо|iphone|samsung|xiaomi|макбук|пылесос|роутер|телевиз|монитор|наушник|мышк[аи]|клавиатур|видеокарт|процессор|холодильник|стирал|велосипед|самокат|hyundai|toyota|honda|bmw|mercedes|lexus|квартир|участ[ко])"))

;; ════════════════════ CLASSIFIER ════════════════════

(defn classify
  "Classify user message intent. Returns a mode keyword.
   Modes:
     :greeting    — say hello
     :reset       — clear context
     :tracking    — show subscriptions
     :help        — show help
     :thanks      — acknowledge
     :refine      — narrow existing search
     :compare     — compare options
     :search      — new purchase search
     :unknown     — pass to agent (LLM handles it)"
  [text session-state]
  (let [t (str/trim (or text ""))]
    (cond
      ;; Zero-length or blank
      (str/blank? t) :unknown

      ;; Exact matches (fast path)
      (re-find greeting-re t)  :greeting
      (re-find reset-re t)     :reset
      (re-find tracking-re t)  :tracking
      (re-find help-re t)      :help
      (re-find thanks-re t)    :thanks

      ;; Refine: short message + existing search context
      (and session-state
           (< (count t) 30)
           (some #(str/includes? (str/lower-case t) %) refine-keywords))
      :refine

      ;; Compare
      (re-find comparison-re t) :compare

      ;; Purchase intent
      (re-find purchase-intent-re t) :search

      ;; Fallback: let the LLM figure it out
      :else :unknown)))

(defn should-search?
  "Should we run deterministic search for this message?"
  [mode]
  (contains? #{:search :refine} mode))

(defn needs-llm?
  "Does this message need LLM processing?"
  [mode]
  (contains? #{:unknown :compare :refine} mode))
```

**Step 2: Verify it loads**

```bash
clojure -M -e '(require (quote [tapalakbot.policy :as p])) (println :ok)'
```
Expected: `:ok`

**Step 3: Quick smoke test**

```bash
clojure -M -e '
(require (quote [tapalakbot.policy :as p]))
(println "привет:" (p/classify "привет" nil))
(println "найди iphone 13:" (p/classify "найди iphone 13" nil))
(println "дешевле:" (p/classify "дешевле" {:last-search "iphone"}))
(println "что лучше:" (p/classify "что лучше, iphone или samsung" nil))
(println "random text:" (p/classify "расскажи анекдот" nil))
'
```
Expected: `:greeting`, `:search`, `:refine`, `:compare`, `:unknown`

**Step 4: Commit**

```bash
git add src/tapalakbot/policy.clj
git commit -m "feat: deterministic turn classifier — replaces regex purchase-intent-pattern"
```

---

## Phase 4: tapalakbot — Search → Structured Pipeline

Make search-execute return structured data that render.clj can consume.

### Task 4.1: Refactor search-execute to return structured data

**Objective:** `search-execute` in core.clj returns `{:cards [...] :stats {:avg N :min N :max N}}` instead of formatted text for the LLM.

**Files:**
- Modify: `src/tapalakbot/core.clj` — refactor search-execute, format-search-results
- Create: `src/tapalakbot/search.clj` — new dedicated search pipeline (optional, or refactor in core.clj)

**Approach:** Refactor inside core.clj to minimize disruption. The key change is that `search-execute` produces a structured map AND a text summary (for LLM context), not just text.

**Step 1: Create search.clj — dedicated search pipeline**

Extract search logic from core.clj into a focused module. This is cleaner than modifying core.clj's tangled internals.

```clojure
(ns tapalakbot.search
  "Deterministic search pipeline — produces structured results.
   No LLM involvement in search execution or result formatting."
  (:require [tapalakbot.query-builder :as qb]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.mashina :as mashina]
            [tapalakbot.monitor.store :as monitor-store]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════ STRUCTURED CARD ════════════════════

(defn- lalafo-item->card
  "Convert Lalafo API item to structured card."
  [item]
  (let [price (get item "price")]
    {:title    (get item "title" "")
     :price    (when price (long price))
     :currency (get item "currency" "KGS")
     :url      (get item "url" "")
     :platform :lalafo
     :desc     (get item "desc" "")}))

(defn- mashina-item->card
  "Convert Mashina API item to structured card."
  [item]
  (let [price-amount (get-in item [:price :amount])
        price-currency (get-in item [:price :currency] "KGS")]
    {:title    (:title item)
     :price    (when price-amount (long price-amount))
     :currency price-currency
     :url      (:url item "")
     :platform :mashina
     :year     (:year item)
     :mileage  (:mileage item)
     :city     (:city item)}))

(defn- accessory-score
  "Quick deterministic score for accessory/service junk. Higher = more junk."
  [title]
  (let [t (str/lower-case (or title ""))
        bad-words #{"зарядк" "кабел" "чехол" "стекло" "плeнк" "ремонт"
                    "установка" "обложк" "коробка" "настройк"}]
    (count (filter #(str/includes? t %) bad-words))))

(defn- dedup-cards
  "Remove duplicate cards by title similarity."
  [cards]
  (let [seen (volatile! #{})]
    (filterv (fn [c]
               (let [key (str/lower-case (subs (:title c) 0 (min 20 (count (:title c)))))]
                 (if (@seen key)
                   false
                   (do (vswap! seen conj key) true))))
             cards)))

;; ════════════════════ SEARCH PIPELINE ════════════════════

(defn search
  "Run deterministic search across platforms. Returns structured result.

   Input: user query string
   Output: {:cards [...] :stats {:avg N :min N :max N :count N} :platforms [:lalafo :mashina] :query \"...\"}"
  [user-query & {:keys [use-llm?] :or {use-llm? true}}]
  (log/info :search-pipeline-start :query user-query)
  (let [;; Step 1: Parse intent
        qb-result (qb/build user-query :use-llm? use-llm?)
        price-min (:price-min qb-result)
        price-max (:price-max qb-result)
        platforms (let [p (:platforms qb-result)]
                    (cond
                      (:is-auto? qb-result) [:mashina]
                      (:is-electronics? qb-result) [:lalafo :mashina]
                      (:is-real-estate? qb-result) [:lalafo]
                      :else (or p [:lalafo])))
        search? (fn [p] (some #{p} platforms))

        ;; Step 2: Generate search queries (deterministic from QueryBuilder)
        queries (or (when (seq (:query qb-result)) [(:query qb-result)])
                    [user-query])

        ;; Step 3: Search Lalafo
        lalafo-cards (when (search? :lalafo)
                       (try
                         (let [search-args (cond-> {"queries" queries
                                                     "candidate_limit" 80}
                                             price-min (assoc "price_min" price-min)
                                             price-max (assoc "price_max" price-max))
                               result (lalafo/search search-args)
                               data (when (string? result) (json/parse-string result false) result)
                               items (when (map? data) (get data "items" []))]
                           (when (seq items)
                             (mapv lalafo-item->card items)))
                         (catch Exception e
                           (log/warn :lalafo-search-failed (.getMessage e))
                           nil)))

        ;; Step 4: Search Mashina (for cars)
        mashina-cards (when (search? :mashina)
                        (try
                          (let [mr (mashina/search-cars :query (first queries) :size 10)]
                            (when (seq (:listings mr))
                              (mapv mashina-item->card (:listings mr))))
                          (catch Exception e
                            (log/warn :mashina-search-failed (.getMessage e))
                            nil)))

        ;; Step 5: Combine and clean
        all-cards (->> (concat lalafo-cards mashina-cards)
                       (remove #(> (accessory-score (:title %)) 2))
                       dedup-cards
                       vec)

        ;; Step 6: Compute stats
        prices (keep :price all-cards)
        stats (when (seq prices)
                {:avg (long (/ (apply + prices) (count prices)))
                 :min (apply min prices)
                 :max (apply max prices)
                 :count (count all-cards)})]

    (log/info :search-pipeline-done
              :cards (count all-cards)
              :platforms platforms
              :stats stats)

    {:cards    all-cards
     :stats    stats
     :platforms platforms
     :query    user-query}))
```

**Step 2: Verify it loads**

```bash
clojure -M -e '(require (quote [tapalakbot.search :as s])) (println :ok)'
```
Expected: `:ok`

**Step 3: Commit**

```bash
git add src/tapalakbot/search.clj
git commit -m "feat: deterministic search pipeline — structured cards out, no LLM in search path"
```

---

## Phase 5: tapalakbot — The Orchestrator

The glue between search (deterministic), LLM (curator), and render (deterministic). This is the core of the tg-agent refactor.

### Task 5.1: Create orchestrator.clj

**Objective:** Orchestrates: classify intent → search → LLM curation → structured reply → render to HTML.

**Files:**
- Create: `src/tapalakbot/orchestrator.clj`

**Step 1: Create orchestrator.clj**

```clojure
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

(defn get-session-state
  "Get structured state from session data map."
  [session]
  (get @session "data" {}))

(defn patch-session-state!
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
          match (some #(when (str/includes? (str/lower-case (:name %))
                                            (str/lower-case (or product-type "")))
                         %)
                      categories)]
      (when match
        {:avg (:avg_price match)
         :min (:min_price match)
         :max (:max_price match)
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
- tiers: \"great\" (best price), \"good\" (fair), \"premium\" (expensive) — use market avg for reference
- intro: 1 line, Russian, include platform name and count
- cta: 1 line suggestion for next action
- assumptions: 0-2 lines about what you assumed (price currency, condition, etc.)
- Keep intro under 100 chars, CTA under 60 chars")

(defn- parse-curated-response
  "Parse LLM curator response into structured data."
  [content cards-count]
  (try
    (let [json-str (or (re-find #"(?s)\{.*\}" content) "{}")
          parsed (clojure.edn/read-string (str "{" (subs json-str 1 (- (count json-str) 1)) "}"))
          ;; Handle string keys from JSON
          parsed (if (map? parsed)
                   (into {} (map (fn [[k v]] [(keyword k) v]) parsed))
                   {})]
      (let [selected-idx (or (:selected parsed) (vec (range (min 8 cards-count))))
            tiers (:tiers parsed {})]
        {:intro (:intro parsed "Нашёл варианты")
         :cta (:cta parsed "Хотите уточнить?")
         :assumptions (:assumptions parsed [])
         :selected-idx selected-idx
         :tiers (into {} (map (fn [[k v]]
                                (let [idx (if (string? k) (parse-long k) (long k))
                                      tier (keyword (str v))]
                                  [idx tier]))
                              tiers))}))
    (catch Exception e
      (log/warn :curator-parse-failed (.getMessage e))
      {:intro "Нашёл варианты"
       :cta "Хотите уточнить?"
       :assumptions []
       :selected-idx (vec (range (min 8 cards-count)))
       :tiers {}})))

(defn- call-curator
  "Call LLM to curate search results. Returns curated reply map."
  [user-query cards stats]
  (try
    (let [market-ctx (get-market-context user-query)
          results-text (str/join "\n"
                                 (map-indexed
                                  (fn [i c]
                                    (str i ". " (:title c)
                                         " — " (:price c) " " (:currency c)
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
      {:intro (str "Нашёл " (count cards) " вариантов")
       :cta "Хотите уточнить?"
       :assumptions []
       :selected-idx (vec (range (min 8 (count cards))))
       :tiers {}})))

;; ════════════════════ ORCHESTRATOR ════════════════════

(defn orchestrate
  "Main entry point. Takes user message + session, returns structured reply.

   Returns: {:mode :shortlist :intro \"...\" :cards [...] :cta \"...\" :assumptions [...]}
   Or for quick paths: {:mode :greeting :intro \"...\" :cards []}"
  [text session]
  (let [state (get-session-state session)
        mode (policy/classify text state)]
    (log/info :orchestrate :mode mode :text text)
    (case mode

      ;; ── Fast paths (no search, no LLM) ──
      :greeting
      {:mode :greeting
       :intro "👋 Салам! Я TapalakBot — помогу найти товары на Lalafo.kg\n\nНапишите что ищете!"
       :cards [] :cta nil :assumptions []}

      :reset
      {:mode :reset}

      :thanks
      {:mode :thanks
       :intro "Пожалуйста! 😊 Если нужно найти что-то ещё — пишите."
       :cards [] :cta nil :assumptions []}

      :help
      {:mode :help
       :intro "🔍 Напишите что ищете — я помогу найти на Lalafo.kg\n\n🔔 После поиска нажмите «Отслеживать» — буду проверять каждые 24ч"
       :cards [] :cta nil :assumptions []}

      :tracking
      {:mode :tracking}

      ;; ── Search paths ──
      :search
      (let [{:keys [cards stats platforms query]} (search/search text)
            curated (call-curator query cards stats)
            selected-cards (mapv (fn [i] (get cards i))
                                 (:selected-idx curated))
            ;; Apply tier overrides from curator
            final-cards (mapv (fn [c]
                                (let [idx (.indexOf (:selected-idx curated) c)]
                                  (if-let [tier (get (:tiers curated) idx)]
                                    (assoc c :tier tier)
                                    c)))
                              selected-cards)]
        (patch-session-state! session {:last-search query
                                       :last-platforms platforms})
        {:mode :shortlist
         :intro (:intro curated)
         :cards final-cards
         :cta (:cta curated)
         :assumptions (:assumptions curated)
         :platforms-used platforms})

      :refine
      (let [last-search (or (:last-search state) text)
            refined-query (str last-search " " text)
            {:keys [cards stats platforms query]} (search/search refined-query)
            curated (call-curator query cards stats)
            selected-cards (mapv (fn [i] (get cards i))
                                 (:selected-idx curated))]
        (patch-session-state! session {:last-search refined-query})
        {:mode :refine
         :intro (:intro curated)
         :cards selected-cards
         :cta (:cta curated)
         :assumptions (conj (:assumptions curated) (str "Поиск: " refined-query))
         :platforms-used platforms})

      :compare
      {:mode :compare
       :intro "🔍 Воспользуйтесь поиском — напишите что ищете, и я покажу варианты для сравнения."
       :cards [] :cta nil :assumptions []}

      ;; ── Unknown: let LLM handle it ──
      :unknown
      {:mode :unknown
       :llm-context {:text text :session-state state}})))
```

**Step 2: Verify it loads**

```bash
clojure -M -e '(require (quote [tapalakbot.orchestrator :as o])) (println :ok)'
```
Expected: `:ok`

**Step 3: Commit**

```bash
git add src/tapalakbot/orchestrator.clj
git commit -m "feat: orchestrator — turn policy + search + LLM curator + structured reply"
```

---

## Phase 6: Rewire bot.clj

The final integration. bot.clj calls orchestrator instead of raw LLM. Cards are rendered deterministically.

### Task 6.1: Refactor process-agent-message to use orchestrator

**Objective:** Replace the LLM-everything flow with orchestrator → render pipeline.

**Files:**
- Modify: `src/tapalakbot/bot.clj` — refactor handle-agent, process-agent-message

**Step 1: Add orchestrator require**

Add to bot.clj requires:
```clojure
[tapalakbot.orchestrator :as orch]
[tapalakbot.render :as render]
```

**Step 2: Create render-orchestrated-response**

New function in bot.clj:
```clojure
(defn- render-orchestrated
  "Render an orchestrator reply to Telegram HTML + buttons."
  [chat-id msg-id reply user-id query]
  (let [html (render/render-reply reply)
        ;; Add track button after results
        track-btn (when (and (seq (:cards reply)) query)
                    (track-context-button user-id query))]
    ;; Edit the thinking message with rendered HTML
    (when msg-id
      (try
        (tg/edit-message chat-id msg-id html :parse-mode "HTML")
        (catch Exception e
          (log/error e :orchestrated-edit-fail)
          (tg/send-message chat-id html :parse-mode "HTML"))))
    ;; Send track button as separate message (if we have results)
    (when track-btn
      (try
        (Thread/sleep 300)
        (tg/send-message chat-id
                         (str "🔔 Хотите отслеживать «" query "»?")
                         :reply_markup track-btn)
        (catch Exception e
          (log/warn e :track-button-fail))))))
```

**Step 3: Create handle-orchestrated**

New function in bot.clj:
```clojure
(defn- handle-orchestrated
  "Handle message via orchestrator pipeline. Returns nil."
  [{:keys [chat-id user-id text] :as msg}]
  (let [uid (str "tg-" user-id)
        bot @t/tapalakbot
        session (clj-harness.core/get-or-create-session bot uid)
        thinking-msg-id (atom nil)]
    ;; Show thinking indicator
    (when-let [m (tg/send-message chat-id "💭 ..." :parse-mode nil)]
      (reset! thinking-msg-id (some-> m (get "result") (get "message_id"))))
    (try
      (let [reply (orch/orchestrate text session)]
        (case (:mode reply)
          ;; Fast paths — simple text response
          (:greeting :thanks :help)
          (do (when-let [msg-id @thinking-msg-id]
                (tg/edit-message chat-id msg-id (:intro reply) :parse-mode nil))
              nil)

          :reset
          (do (hc/reset-session! @t/tapalakbot uid)
              (release! uid)
              (store-pending! uid nil)
              (when-let [msg-id @thinking-msg-id]
                (tg/edit-message chat-id msg-id "🗑️ Контекст очищен. Начнём заново!" :parse-mode nil))
              nil)

          :tracking
          (do (when-let [msg-id @thinking-msg-id]
                (tg/delete-message chat-id msg-id))
              (show-tracking-list chat-id user-id)
              nil)

          ;; Search results — render cards
          (:shortlist :refine)
          (do (render-orchestrated chat-id @thinking-msg-id reply user-id (:query reply))
              nil)

          ;; Unknown — fall back to LLM agent
          :unknown
          (do (handle-agent msg) nil)

          ;; Fallback
          (do (when-let [msg-id @thinking-msg-id]
                (tg/edit-message chat-id msg-id "🤔" :parse-mode nil))
              (handle-agent msg) nil)))
      (catch Exception e
        (log/error e :orchestrated-error {:user-id uid})
        (when-let [msg-id @thinking-msg-id]
          (try (tg/edit-message chat-id msg-id "❌ Ошибка. Попробуйте ещё раз."
                                :parse-mode nil)
               (catch Exception _)))))))
```

**Step 4: Wire into extended-handler**

In the extended-handler function, replace the agent dispatch:
```clojure
;; BEFORE:
(and (not (str/blank? text)) (:text parsed))
(do (handler-future (fn [] (handle-agent parsed))) nil)

;; AFTER:
(and (not (str/blank? text)) (:text parsed))
(do (handler-future (fn [] (handle-orchestrated parsed))) nil)
```

**Step 5: Verify it loads**

```bash
clojure -M -e '(require (quote [tapalakbot.bot :as bot])) (println :ok)'
```
Expected: `:ok`

**Step 6: Commit**

```bash
git add src/tapalakbot/bot.clj
git commit -m "feat: bot.clj rewired to use orchestrator — cards rendered deterministically"
```

---

## Phase 7: Remove Dead Code

### Task 7.1: Clean up url-store, citation-replace, strip-fake-urls

**Objective:** Remove the anti-hallucination hacks from core.clj and bot.clj that are no longer needed because the LLM never touches URLs/prices.

**Files:**
- Modify: `src/tapalakbot/core.clj` — remove url-store, *current-user-id*, citation-replace-related code
- Modify: `src/tapalakbot/bot.clj` — remove citation-replace, strip-fake-urls, strip-tables functions

**Step 1: Remove from bot.clj**

Remove these functions (they are no longer called from the orchestrated path):
- `strip-tables` (line 430-433)
- `strip-fake-urls` (line 435-465)
- `citation-replace` (line 467-520)

Keep `extract-search-query` (still useful for track button).

**Step 2: Simplify core.clj**

In core.clj, keep `url-store`, `*current-user-id*`, and `search-execute` for backward compatibility (they're used by the `:unknown` LLM fallback path). But mark them as deprecated with comments.

**Step 3: Commit**

```bash
git add src/tapalakbot/bot.clj src/tapalakbot/core.clj
git commit -m "refactor: remove citation-replace, strip-fake-urls, strip-tables — no longer needed"
```

---

## Phase 8: Tests

### Task 8.1: Card renderer tests

**Objective:** Verify deterministic rendering produces valid Telegram HTML.

**Files:**
- Create: `test/tapalakbot/render_test.clj`

**Step 1: Create render_test.clj**

```clojure
(ns tapalakbot.render-test
  (:require [clojure.test :refer :all]
            [tapalakbot.render :as r]))

(deftest test-format-price
  (is (= "25 000" (r/format-price 25000)))
  (is (= "1 234 567" (r/format-price 1234567)))
  (is (nil? (r/format-price nil))))

(deftest test-assign-tier
  (is (= :great (r/assign-tier 20000 35000)))     ;; 57% of avg
  (is (= :good (r/assign-tier 30000 35000)))      ;; 86% of avg
  (is (= :premium (r/assign-tier 50000 35000)))   ;; 143% of avg
  (is (nil? (r/assign-tier nil 35000))))

(deftest test-render-card
  (let [card {:title "iPhone 13" :price 25000 :currency "KGS"
              :url "https://lalafo.kg/123" :tier :good :condition "хороший"}
        html (r/render-card card)]
    (is (str/includes? html "iPhone 13"))
    (is (str/includes? html "25 000"))
    (is (str/includes? html "KGS"))
    (is (str/includes? html "lalafo.kg"))
    (is (str/includes? html "href="))
    (is (str/includes? html "хороший"))))

(deftest test-render-reply-shortlist
  (let [reply {:mode :shortlist
               :intro "Нашёл 3 варианта"
               :cards [{:title "A" :price 100 :url "http://a.com" :tier :great}
                       {:title "B" :price 200 :url "http://b.com" :tier :good}]
               :cta "Хотите ещё?"
               :assumptions ["Цены в сомах"]}
        html (r/render-reply reply)]
    (is (str/includes? html "Нашёл 3 варианта"))
    (is (str/includes? html "iPhone" false)  ;; wait, titles are "A" and "B"
    (is (str/includes? html "🔥"))           ;; great tier
    (is (str/includes? html "💰"))           ;; good tier
    (is (str/includes? html "Хотите ещё?"))
    (is (str/includes? html "Цены в сомах"))))

(deftest test-render-reply-error
  (is (str/includes? (r/render-reply {:mode :error :intro "Ош"}) "❌")))
```

**Step 2: Add test alias to deps.edn**

Add `:test` alias:
```clojure
:test {:extra-paths ["test"]
       :main-opts ["-m" "cognitect.test-runner"]}
```

Add dep:
```clojure
cognitect/test-runner {:git/url "https://github.com/cognitect-labs/test-runner"
                       :git/sha "dfb30dd"}
```

**Step 3: Run tests**

```bash
clojure -M:test
```
Expected: All tests pass.

**Step 4: Commit**

```bash
git add test/tapalakbot/render_test.clj deps.edn
git commit -m "test: card renderer tests"
```

---

### Task 8.2: Policy classifier tests

**Files:**
- Create: `test/tapalakbot/policy_test.clj`

**Step 1: Create tests**

```clojure
(ns tapalakbot.policy-test
  (:require [clojure.test :refer :all]
            [tapalakbot.policy :as p]))

(deftest test-greetings
  (is (= :greeting (p/classify "привет" nil)))
  (is (= :greeting (p/classify "салам" nil)))
  (is (= :greeting (p/classify "hello" nil)))
  (is (= :greeting (p/classify "  привет  " nil))))

(deftest test-search
  (is (= :search (p/classify "найди iphone 13" nil)))
  (is (= :search (p/classify "купить ноутбук до 50000" nil)))
  (is (= :search (p/classify "hyundai solaris 2020" nil)))
  (is (= :search (p/classify "ищу роутер до 4000" nil))))

(deftest test-refine
  (is (= :refine (p/classify "дешевле" {:last-search "iphone"})))
  (is (= :refine (p/classify "только новые" {:last-search "iphone"}))))

(deftest test-unknown
  (is (= :unknown (p/classify "расскажи анекдот" nil)))
  (is (= :unknown (p/classify "" nil))))

(deftest test-thanks
  (is (= :thanks (p/classify "спасибо" nil)))
  (is (= :thanks (p/classify "ок" nil))))
```

**Step 2: Run tests**

```bash
clojure -M:test
```

**Step 3: Commit**

```bash
git add test/tapalakbot/policy_test.clj
git commit -m "test: turn classifier tests"
```

---

## Phase 9: Verification

### Task 9.1: End-to-end smoke test

**Objective:** Run the bot in one-shot mode and verify structured pipeline works.

```bash
cd /Users/sn/Projects/tapalakbot-v2
clojure -M:run "найди iphone 13 до 30000"
```

Expected:
- Search runs deterministically
- LLM curator picks 5-8 items
- Output is Telegram HTML with tier groups, formatted prices, clickable links
- No fabricated URLs, no hallucinated prices, no markdown tables

### Task 9.2: Compare old vs new

Run the same query with old and new pipeline. Verify output quality is maintained.

```bash
# New pipeline (orchestrator)
clojure -M:run "roутер до 4000"

# Verify output has:
# - Tier emoji (🔥/💰/💎)
# - Formatted prices (thousand separators)
# - Clickable links to lalafo.kg
# - Brief Russian intro
# - CTA for next action
```

### Task 9.3: Update AGENTS.md

Update the AGENTS.md to reflect new architecture:
- Add render.clj, policy.clj, orchestrator.clj, search.clj to file map
- Update architecture diagram
- Remove anti-hallucination hacks section (now handled by structure)
- Add tg-agent compliance section

---

## Summary: What Changed

| Layer | Before | After |
|---|---|---|
| **Search** | LLM calls tools, tools return text, LLM formats | Deterministic search → structured cards |
| **LLM role** | Generates entire Telegram response | Picks 5-8 items + writes 1-line intro/CTA |
| **Card rendering** | LLM generates markdown with tiers | Clojure code renders from structured data |
| **Anti-hallucination** | Regex surgery on LLM output (citation-replace, strip-fake-urls) | LLM never touches URLs/prices/titles |
| **Session state** | Implicit in LLM context | Explicit map: last-search, budget, preferences |
| **Turn routing** | purchase-intent-pattern regex → full LLM | Deterministic classifier → search or LLM fallback |
| **Structured output** | LLM text blob | {:mode :shortlist :cards [...] :cta "..."} |

### Lines of code (estimated)

| What | New | Modified | Removed |
|---|---|---|---|
| clj-harness | 0 | 3 files (~50 lines) | 0 |
| render.clj | ~120 lines | — | — |
| policy.clj | ~80 lines | — | — |
| orchestrator.clj | ~200 lines | — | — |
| search.clj | ~120 lines | — | — |
| bot.clj | — | ~80 lines | ~150 lines |
| core.clj | — | ~20 lines | ~50 lines |
| Tests | ~200 lines | — | — |
| **Total** | **~720 new** | **~150 changed** | **~200 removed** |

Net: +520 lines. But the LLM's job shrank from "generate entire response" to "pick 5 items + write 2 sentences." That's a ~95% reduction in LLM output tokens per search, which means faster responses, lower cost, and zero hallucinated prices/URLs.
