# tapalakbot-v2: Conversational Architecture Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Restore conversational intelligence to TapalakBot while keeping deterministic rails for trust-critical facts (prices, URLs, card rendering).

**Architecture:** Replace the regex gatekeeper with an LLM-powered intent router for non-trivial messages. Keep fast paths (greetings, thanks, reset) as instant regex. Everything else goes through a single smart LLM call that understands intent, has access to tools (search, market data), and produces structured output. Deterministic layer still owns cards, prices, links, tiers.

**Tech Stack:** Clojure, clj-harness (LLM calls + session management), SQLite monitor DB, DeepSeek/OpenRouter models.

---

## The Problem

```
User: "iphone 13"          → regex matches "iphone" → :search → works ✓
User: "which is better"    → regex misses everything → :unknown → search for "which is better" → COMEDY ✗
User: "хочу айфон"         → regex matches "хочу" → :search → dumps raw cards ✗ (wanted research/guidance)
User: "а что по ценам"     → regex misses → :unknown → search for "а что по ценам" → COMEDY ✗
```

Three root causes:
1. policy.clj regex classifier can't understand conversational intent
2. `:unknown` → do-search is the worst fallback possible
3. LLM is demoted to JSON index-picker, stripped of conversational ability

## What We Keep (Deterministic Rails)

| Component | File | Status |
|-----------|------|--------|
| Card rendering (render-reply, render-card, render-cards) | render.clj | Keep as-is |
| Price tier assignment (assign-tier) | render.clj | Keep as-is |
| Accessory filtering + dedup | search.clj | Keep as-is |
| Platform routing (lalafo/mashina) | search.clj | Keep as-is |
| Structured reply contract `{:mode ... :cards [...]}` | orchestrator.clj | Keep, expand |
| Fast paths for greetings/thanks/reset/help/tracking | policy.clj + orchestrator.clj | Keep |
| Monitor DB for market data | monitor/store.clj | Keep, use more |
| Transcript capture | bot.clj | Keep |

## What Changes

### Phase 1: Smart Intent Router (fixes the comedy)

Replace the `:unknown → do-search` footgun with an LLM-powered intent classifier that kicks in when regex can't classify a message. This is the minimum viable fix — it stops the comedy immediately.

### Phase 2: Research Mode (fixes "хочу айфон")

New `:research` mode that combines monitor DB market data + search results + LLM synthesis into an intelligent research response with price ranges, market context, and curated picks.

### Phase 3: Conversation Memory (enables follow-ups)

Store what was shown and discussed in the session. Enables the bot to answer "which was the cheapest?", "tell me about the second one", "are those good prices?"

### Phase 4: Response Mode Expansion

New modes that make responses appropriate to intent:
- `:research` — market intelligence + picks
- `:followup` — conversational answer about previous results
- `:chat` — small talk
- Keep existing: `:search`, `:compare`, `:refine`, `:shortlist`

---

## Phase 1: Smart Intent Router

### Task 1.1: Create LLM intent classifier function

**Objective:** A function that takes user text + session context and returns an intent keyword and any extracted parameters.

**Files:**
- Create: `src/tapalakbot/intent.clj`

**Implementation:**

```clojure
(ns tapalakbot.intent
  "LLM-powered intent classifier. Used when regex policy can't classify a message.
   Understands conversational intent: follow-up questions, research requests, chat."
  (:require [clj-harness.llm :as llm]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private intent-prompt
  "You are an intent classifier for a marketplace Telegram bot (Lalafo.kg, Mashina.kg).
  
Analyze the user message and return ONLY JSON with the intent and any parameters.

## Intent types
- search: User wants to find listings. E.g. 'iphone 13', 'найди макбук', 'toyota camry'
- research: User wants market intelligence + guidance. E.g. 'хочу айфон', 'нужен велосипед для города', 'что лучше купить для работы'
- followup: Question ABOUT previously shown results. E.g. 'which is better', 'а какой норм', 'покажи самый дешёвый', 'расскажи про второй'
- compare: Explicit comparison between two items. E.g. 'что лучше iphone или samsung'
- refine: Narrowing/filtering a previous search. E.g. 'дешевле', 'только в бишкеке', 'только новые', 'до 30000'
- chat: Small talk, thanks, general questions. E.g. 'спасибо', 'как дела', 'что ты умеешь'

## Context
Last search: {{last-search}}
Last mode: {{last-mode}}
Shown items (count): {{item-count}}
Message: {{message}}

## Output format
JSON only: {\"intent\":\"search\",\"query\":\"iphone 13\",\"confidence\":0.9}

For followup: include what the user is asking about (cheapest, best, more info, etc.)
For research: include the product category and what the user wants to know
For chat: just the intent, no extra params")

(defn classify-intent
  "Classify user intent using LLM.
   text — user message string
   session-state — map with :last-search, :last-mode, :conversation keys (can be nil)
   returns {:intent :search/:research/:followup/:compare/:refine/:chat
             :query \"...\"  ;; extracted search/refinement query
             :confidence 0.0-1.0}"
  [text session-state]
  (let [context (str "Last search: " (or (:last-search session-state) "none") "\n"
                     "Last mode: " (or (:last-mode session-state) "none") "\n"
                     "Shown items: " (or (:last-card-count session-state) "none"))
        prompt (-> intent-prompt
                   (str/replace "{{last-search}}" (or (:last-search session-state) "none"))
                   (str/replace "{{last-mode}}" (or (:last-mode session-state) "none"))
                   (str/replace "{{item-count}}" (str (or (:last-card-count session-state) 0)))
                   (str/replace "{{message}}" text))
        messages [{"role" "system" "content" prompt}
                  {"role" "user" "content" text}]
        resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 200 :timeout-ms 15000)
        content (get-in resp ["choices" 0 "message" "content"])
        json-str (or (re-find #"(?s)\{.*\}" (or content "{}")) "{}")
        parsed (try (json/parse-string json-str true) (catch Exception _ {}))]
    {:intent    (keyword (or (:intent parsed) "search"))
     :query     (or (:query parsed) text)
     :confidence (or (:confidence parsed) 0.5)}))
```

### Task 1.2: Add intent test

**Files:**
- Create: `test/tapalakbot/intent_test.clj`

```clojure
(ns tapalakbot.intent-test
  (:require [clojure.test :refer :all]
            [tapalakbot.intent :as intent]))

;; These tests verify the function signature and fallback behavior.
;; Full LLM integration tested in orchestrator integration tests.

(deftest test-classify-intent-fallback
  ;; Without LLM (mocked to fail), should fall back to :search
  (testing "falls back to :search when LLM fails"
    (with-redefs [clj-harness.llm/llm (fn [& _] (throw (Exception. "no LLM")))]
      (let [result (intent/classify-intent "какой-то запрос" nil)]
        (is (= :search (:intent result)))
        (is (= "какой-то запрос" (:query result)))))))
```

### Task 1.3: Wire intent classifier into orchestrator

**Objective:** Replace `:unknown → do-search` with `→ intent/classify-intent → appropriate handler`.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj` (lines 324-332, the `:unknown` branch)
- Modify: `src/tapalakbot/orchestrator.clj` (add require for tapalakbot.intent)

**Step 1: Add require**

Add to the ns form:
```clojure
[clj-harness.llm :as llm]
[tapalakbot.intent :as intent]  ;; ADD THIS
```

**Step 2: Replace `:unknown` handler**

Replace lines 324-332:
```clojure
      ;; ── Unknown ──
      ;; Try searching anyway — users type typos, brands, model names that
      ;; the regex won't catch. Only show help for very short/gibberish text.
      (if (and text (> (count (str/trim text)) 3))
        (do (log/info :unknown-but-trying-search :text text)
            (do-search text session {:status-cb status-cb :model model :provider provider}))
        {:mode  :no-results
         :intro "🤔 Напишите, что ищете — например, «найди iphone 13»."
         :cards [] :cta nil :assumptions []})
```

With:
```clojure
      ;; ── Unknown ──
      ;; Regex couldn't classify. Use LLM to understand intent.
      ;; This handles conversational follow-ups, research queries, 
      ;; and any natural language the regex can't match.
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
            (do-research text query session
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
            
            ;; Fallback — should not happen
            (do-search text session
                       {:status-cb status-cb :model model :provider provider})))
        ;; Very short/gibberish — show help
        {:mode  :shortlist
         :intro "🤔 Напишите, что ищете — например, «найди iphone 13»."
         :cards [] :cta nil :assumptions []})
```

### Task 1.4: Add stub handlers for new modes

**Objective:** Add temporary implementations for `do-research`, `do-followup`, `do-chat` that work but don't need the full Phase 2+3 implementation. Research degrades to search, followup gives a reasonable default, chat responds conversationally.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`

Add after `do-search` (before line 281, before `orchestrate`):

```clojure
;; ── Stub handlers (full implementations in Phase 2-4) ──

(defn- do-research
  "Research mode stub: falls back to search with market context enrichment.
   Full implementation in Phase 2."
  [text query session {:keys [status-cb model provider]}]
  (let [result (do-search (or query text) session
                          {:status-cb status-cb :model model :provider provider})
        market-ctx (get-market-context query)]
    (if market-ctx
      (-> result
          (assoc :mode :research)
          (update :intro #(str "📊 " (or (:category market-ctx) "Рынок") 
                               ": средняя цена " (render/format-price (long (:avg market-ctx))) " сом"
                               " (от " (render/format-price (:min market-ctx))
                               " до " (render/format-price (:max market-ctx)) ")\n\n" %)))
      (assoc result :mode :research))))

(defn- do-followup
  "Followup mode stub: gives a generic response about previous results.
   Full implementation in Phase 3."
  [text state {:keys [status-cb model provider]}]
  (let [last-search (or (:last-search state) "предыдущий запрос")
        item-count (or (:last-card-count state) "несколько")]
    {:mode :shortlist
     :intro (str "По «" last-search "» я показал " item-count " вариантов. "
                 "Уточните, что именно вас интересует — цена, состояние, конкретная модель?")
     :cards [] :cta nil :assumptions []}))

(defn- do-chat
  "Chat mode: simple conversational response using LLM."
  [text {:keys [model provider]}]
  (try
    (let [messages [{"role" "system" 
                     "content" "You are TapalakBot, a marketplace assistant for Kyrgyzstan (Lalafo.kg, Mashina.kg). Be friendly, concise, helpful. Speak Russian. Keep responses under 300 chars."}
                    {"role" "user" "content" text}]
          resp (llm/llm (or model :kimi-k2) messages [] 
                        :provider (or provider :openrouter) 
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
```

### Task 1.5: Update orchestrator tests

**Files:**
- Modify: `test/tapalakbot/orchestrator_test.clj`

Add tests for `:unknown` routing to intent classifier:

```clojure
(deftest test-unknown-routes-to-intent-classifier
  (testing "unknown routes to intent classifier when session exists"
    (with-redefs [intent/classify-intent (fn [_ _] {:intent :followup :query "which is better"})
                  search/search (fn [& _] (throw (Exception. "search should not be called")))]
      (let [session (make-session {:last-search "iphone 13" :last-card-count 5})
            result (orch/orchestrate "which is better" session)]
        (is (contains? #{:shortlist :followup} (:mode result)))
        (is (string? (:intro result))))))
  
  (testing "unknown routes chat to do-chat"
    (with-redefs [intent/classify-intent (fn [_ _] {:intent :chat :query "как дела"})
                  llm/llm (fn [& _] {"choices" [{"message" {"content" "Привет! Чем могу помочь?"}}]})]
      (let [session (make-session {})
            result (orch/orchestrate "как дела" session)]
        (is (= :shortlist (:mode result)))
        (is (string? (:intro result)))))))
```

### Task 1.6: Remove dead `should-search?` and `needs-llm?` references

**Objective:** With LLM-powered intent routing, `:unknown` no longer means "search the literal text". The `should-search?` and `needs-llm?` helpers in policy.clj are misleading since `:unknown` now goes through intent classification. But keep them — they're used in tests and could still be useful. No change needed.

### Task 1.7: Run all tests

```bash
cd /Users/sn/Projects/tapalakbot-v2
clojure -M:test -d test/tapalakbot/policy_test.clj test/tapalakbot/orchestrator_test.clj test/tapalakbot/intent_test.clj test/tapalakbot/render_test.clj
```

Expected: all tests pass. The orchestrator test for `:unknown` now verifies intent classifier routing.

---

## Phase 2: Research Mode

### Task 2.1: Enhance market context enrichment

**Objective:** `get-market-context` in orchestrator.clj currently does substring matching on category names. Make it smarter — also include DB items, price trends, and item count.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj` (lines 33-49)

Replace `get-market-context`:

```clojure
(defn- get-market-context
  "Get rich market intelligence for a product category from monitor DB.
   Returns {:avg N :min N :max N :count N :category \"name\" 
            :sample-items [...] :price-range-str \"...\"}"
  [product-type]
  (try
    (let [categories (monitor-store/get-category-summary)
          match (some #(when (str/includes?
                              (str/lower-case (or (:name %) ""))
                              (str/lower-case (or product-type "")))
                         %)
                      categories)]
      (when match
        (let [items (try (monitor-store/get-items-by-category (:id match))
                         (catch Exception _ []))]
          {:avg              (:avg_price match)
           :min              (:min_price match)
           :max              (:max_price match)
           :count            (:item_count match)
           :category         (:name match)
           :sample-items     (take 3 items)
           :price-range-str  (str (render/format-price (long (:min_price match)))
                                  " – " (render/format-price (long (:max_price match)))
                                  " сом")})))
    (catch Exception _ nil)))
```

### Task 2.2: Build research LLM prompt and handler

**Objective:** Replace the stub `do-research` with a real implementation that combines market data + search results + LLM synthesis into an informative research response.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`

Replace the stub `do-research`:

```clojure
(def ^:private research-prompt
  "You are a marketplace research assistant for Kyrgyzstan (Lalafo.kg, Mashina.kg).
The user is exploring a product category — they want guidance, not just listings.

## Input
- User query: what they're looking for
- Market data: price ranges, typical prices from our monitoring database
- Search results: current live listings

## Your job
1. Give a SHORT market overview (1-2 sentences about typical prices, what's available)
2. Pick 5-6 best items from the search results (by index)
3. Write a helpful intro and CTA

## Output format
Return ONLY valid JSON:
{\"selected\":[0,2,4],\"intro\":\"📱 iPhone 13 на рынке КР: цены от 25 000 до 65 000 сом. Самые популярные — 128GB версии. Вот лучшие варианты:\",\"cta\":\"Какой бюджет рассматриваете? Могу отфильтровать.\",\"market_note\":\"Средняя цена 42 000 сом\",\"assumptions\":[]}

Rules:
- intro: 1-2 sentences, mention price range from market data, be informative
- selected: 5-6 best indices (0-based)
- cta: helpful follow-up question
- market_note: brief stat from market data
- NO markdown, ONLY JSON")

(defn- do-research
  "Research mode: market intelligence + curated picks + LLM synthesis.
   Uses monitor DB for historical data, search for live listings,
   and LLM to synthesize an intelligent research response."
  [text query session {:keys [status-cb model provider]}]
  (when status-cb (status-cb "📊 Анализирую рынок..."))
  (let [market-ctx (get-market-context query)
        search-result (search/search (or query text) {:use-llm? true})
        cards (:cards search-result)
        stats (:stats search-result)]
    (if (empty? cards)
      {:mode :no-results
       :intro (str "По «" query "» пока нет данных. "
                   "Попробуйте более общий запрос или другую категорию.")
       :cards [] :cta nil :assumptions []}
      (let [_ (when status-cb (status-cb (str "📊 " (count cards) " вариантов, анализирую...")))
            context (str "User query: " query "\n"
                         (when market-ctx
                           (str "MARKET DATA: " (:category market-ctx) "\n"
                                "  Price range: " (:price-range-str market-ctx) "\n"
                                "  Average: " (long (:avg market-ctx)) " KGS\n"
                                "  Items tracked: " (:count market-ctx) "\n\n"))
                         "LIVE RESULTS (" (count cards) " items):\n"
                         (str/join "\n"
                           (map-indexed
                            (fn [i c]
                              (str i ". " (:title c) " — " (:price c) " " (or (:currency c) "KGS")
                                   (when (:year c) (str " | " (:year c) " yr"))
                                   (when (:city c) (str " | " (:city c)))))
                            (take 20 cards))))
            messages [{"role" "system" "content" research-prompt}
                      {"role" "user" "content" context}]
            resp (llm/llm (or model :kimi-k2) messages []
                          :provider (or provider :openrouter)
                          :max-tokens 500 :timeout-ms 30000)
            content (get-in resp ["choices" 0 "message" "content"])
            parsed (try
                     (let [json-str (or (re-find #"(?s)\{.*\}" (or content "{}")) "{}")
                           p (cheshire.core/parse-string json-str true)]
                       p)
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
                                 :last-market-ctx market-ctx})
        (when status-cb (status-cb "✨ Готовлю обзор..."))
        {:mode :research
         :intro (or (sanitize-intro (:intro parsed))
                    (str "📊 " (or (:category market-ctx) query)
                         ": цены " (:price-range-str market-ctx)
                         ". Вот лучшие варианты:"))
         :cards final-cards
         :cta (:cta parsed "Уточните бюджет или характеристики?")
         :assumptions (or (:assumptions parsed) [])
         :platforms-used (:platforms search-result)
         :query query
         :market-note (:market_note parsed)}))))
```

### Task 2.3: Update render.clj for research mode

**Objective:** Add `:research` mode rendering that includes market context before cards.

**Files:**
- Modify: `src/tapalakbot/render.clj` (line 128, the render-reply function)

Add a `:research` case in `render-reply`:

```clojure
(defn render-reply
  [{:keys [mode intro cards cta assumptions market-note]}]
  (case mode
    :error      ...
    :no-results ...
    :clarify    ...
    :research   ;; Research mode — intro + market note + cards
    (str (when (and intro (not (str/blank? intro)))
           (str intro "\n\n"))
         (when (and market-note (not (str/blank? market-note)))
           (str "<i>" (escape-html market-note) "</i>\n\n"))
         (when (seq cards)
           (render-cards cards))
         (when (seq assumptions)
           (let [a (if (vector? assumptions) (str/join " · " assumptions) (str assumptions))]
             (str "\n\n<i>" a "</i>")))
         (when (and cta (not (str/blank? cta)))
           (str "\n\n💬 " cta)))
    ;; Default: ...
    (str ...)))
```

### Task 2.4: Update bot.clj for research mode rendering

**Files:**
- Modify: `src/tapalakbot/bot.clj` (line 601, handle-orchestrated)

The existing code:
```clojure
(:shortlist :refine)
(do (render-orchestrated chat-id @thinking-msg-id reply user-id (:query reply))
    ...)
```

Change to:
```clojure
(:shortlist :refine :research)
(do (render-orchestrated chat-id @thinking-msg-id reply user-id (:query reply))
    ...)
```

### Task 2.5: Run tests

```bash
clojure -M:test -d test/tapalakbot/render_test.clj test/tapalakbot/orchestrator_test.clj test/tapalakbot/intent_test.clj
```

---

## Phase 3: Conversation Memory

### Task 3.1: Extend session state schema

**Objective:** Store richer conversation context in the session so follow-up questions can reference what was shown.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj` — update `patch-session!` calls to store more state

Update `do-search` session patching (around line 262-271) to also store:

```clojure
(patch-session! session {:last-search     query
                         :last-mode        :search
                         :last-platforms   platforms
                         :last-price-max   (:price-max result)
                         :last-price-min   (:price-min result)
                         :last-category    ...
                         :last-card-count  (count final-cards)
                         :last-items       (mapv #(select-keys % [:title :price :currency :platform])
                                                final-cards)})
```

### Task 3.2: Build followup handler

**Objective:** Replace the stub `do-followup` with a real implementation that uses conversation history.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`

Replace stub `do-followup`:

```clojure
(def ^:private followup-prompt
  "You are TapalakBot, a marketplace assistant. The user is asking a follow-up question
about items you previously showed them.

## Context
Previous search: {{last-search}}
Items shown: {{item-count}} items
Item details:
{{item-details}}

## Your job
Answer the user's question about the shown items. Be helpful and specific.
If they ask 'which is better' or 'what do you recommend', compare the items briefly.
If they ask about the cheapest, tell them.
Keep it under 200 chars.

Return ONLY valid JSON:
{\"answer\":\"Your answer text\",\"cta\":\"Optional follow-up question\"}")

(defn- do-followup
  "Followup mode: answer questions about previously shown items
   using conversation context."
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
            context (-> followup-prompt
                        (str/replace "{{last-search}}" last-search)
                        (str/replace "{{item-count}}" (str item-count))
                        (str/replace "{{item-details}}" item-details))
            messages [{"role" "system" "content" context}
                      {"role" "user" "content" text}]
            resp (try (llm/llm (or model :kimi-k2) messages []
                               :provider (or provider :openrouter)
                               :max-tokens 300 :timeout-ms 20000)
                      (catch Exception _ nil))
            content (get-in resp ["choices" 0 "message" "content"])
            json-str (or (re-find #"(?s)\{.*\}" (or content "{}")) "{}")
            parsed (try (cheshire.core/parse-string json-str true) (catch Exception _ {}))]
        {:mode :followup
         :intro (or (:answer parsed)
                    (str "Вот что я нашёл по «" last-search "». Уточните запрос?"))
         :cards []
         :cta (:cta parsed)
         :assumptions []}))))
```

### Task 3.3: Update bot.clj for followup mode

**Files:**
- Modify: `src/tapalakbot/bot.clj`

Add `:followup` to the render case:
```clojure
(:shortlist :refine :research :followup)
(do (render-orchestrated chat-id @thinking-msg-id reply user-id (:query reply))
    ...)
```

### Task 3.4: Update render.clj for followup mode

**Files:**
- Modify: `src/tapalakbot/render.clj`

Add `:followup` case — just shows intro, no cards:
```clojure
:followup
(str (when (and intro (not (str/blank? intro)))
       intro)
     (when (and cta (not (str/blank? cta)))
       (str "\n\n💬 " cta)))
```

### Task 3.5: Run tests

```bash
clojure -M:test -d test/tapalakbot/orchestrator_test.clj test/tapalakbot/render_test.clj
```

---

## Phase 4: Polish & Edge Cases

### Task 4.1: Fix compare mode rendering

**Objective:** `compare-products` currently only shows the intro string — the comparison_points and verdict are parsed but never rendered. Fix the compare handler to show structured comparison.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj` — `compare-products` function
- Modify: `src/tapalakbot/render.clj` — add `:compare` case

**compare-products** (around line 143-181): Add comparison_points and verdict to the return map:

```clojure
{:mode          :compare
 :intro         (or (sanitize-intro (:intro parsed)) ...)
 :comparison    (:comparison_points parsed)
 :verdict       (:verdict parsed)
 :cards         []
 :cta           (:cta parsed ...)
 :assumptions   (or (:assumptions parsed) [])}
```

**render.clj** — add `:compare` case:

```clojure
:compare
(str (when (and intro (not (str/blank? intro)))
       (str intro "\n\n"))
     (when (seq comparison)
       (str (str/join "\n" (map #(str "• " (escape-html %)) comparison)) "\n\n"))
     (when (and verdict (not (str/blank? verdict)))
       (str "<b>Итог:</b> " (escape-html verdict) "\n"))
     (when (and cta (not (str/blank? cta)))
       (str "\n💬 " cta)))
```

### Task 4.2: Session auto-expiry for conversation state

**Objective:** After 30 minutes of inactivity, expire the conversation state so old context doesn't confuse the intent classifier.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`

Already handled by `bot.clj` `cleanup-stale-users!` which removes user-state atoms after 30 min. The session itself is from clj-harness and may persist longer. Add a timestamp check:

```clojure
(defn- get-session-data
  [session]
  (when session
    (let [data (get @session "data" {})
          last-active (:last-active data 0)
          now (System/currentTimeMillis)]
      (if (> (- now last-active) (* 30 60 1000))  ;; 30 min expiry
        {}   ;; expired — return empty state
        data))))
```

### Task 4.3: Add intent classifier cache for regex-matched messages

**Objective:** If regex already matched `:search`, skip the LLM intent call. Only use LLM intent classification for `:unknown` messages.

This is already the design — `:search` from regex goes straight to `do-search`, only `:unknown` hits `intent/classify-intent`. No change needed.

### Task 4.4: Final integration test

**Files:**
- Modify: `test/tapalakbot/flow_test.clj`

Add end-to-end test scenarios:

```clojure
(deftest test-conversational-flow
  ;; User searches → gets results
  ;; User asks "which is better" → gets followup response (not search for "which is better")
  (testing "followup doesn't trigger search"
    (with-redefs [intent/classify-intent (fn [_ _] {:intent :followup :query "which is better"})
                  search/search (fn [& _] (throw (Exception. "should not search")))]
      (let [session (make-session {:last-search "iphone 13" 
                                   :last-items [{:title "iPhone 13 128GB" :price 35000 :currency "KGS"}
                                                {:title "iPhone 13 256GB" :price 45000 :currency "KGS"}]
                                   :last-card-count 2})
            result (orch/orchestrate "which is better" session)]
        (is (contains? #{:shortlist :followup} (:mode result)))
        (is (string? (:intro result)))))))
```

### Task 4.5: Run full test suite

```bash
cd /Users/sn/Projects/tapalakbot-v2
clojure -M:test -d test/tapalakbot/policy_test.clj test/tapalakbot/orchestrator_test.clj test/tapalakbot/intent_test.clj test/tapalakbot/render_test.clj test/tapalakbot/flow_test.clj
```

Expected: all tests pass (28+ tests, 111+ assertions).

---

## Files Summary

| File | Action | Phase |
|------|--------|-------|
| `src/tapalakbot/intent.clj` | CREATE | 1.1 |
| `test/tapalakbot/intent_test.clj` | CREATE | 1.2 |
| `src/tapalakbot/orchestrator.clj` | MODIFY (require + :unknown + stubs) | 1.3, 1.4 |
| `test/tapalakbot/orchestrator_test.clj` | MODIFY (add tests) | 1.5 |
| `src/tapalakbot/orchestrator.clj` | MODIFY (market-context + do-research) | 2.1, 2.2 |
| `src/tapalakbot/render.clj` | MODIFY (:research case) | 2.3 |
| `src/tapalakbot/bot.clj` | MODIFY (mode dispatch) | 2.4, 3.3 |
| `src/tapalakbot/orchestrator.clj` | MODIFY (session + do-followup) | 3.1, 3.2 |
| `src/tapalakbot/render.clj` | MODIFY (:followup case) | 3.4 |
| `src/tapalakbot/orchestrator.clj` | MODIFY (compare fix) | 4.1 |
| `src/tapalakbot/render.clj` | MODIFY (:compare case) | 4.1 |
| `src/tapalakbot/orchestrator.clj` | MODIFY (session expiry) | 4.2 |
| `test/tapalakbot/flow_test.clj` | MODIFY (integration test) | 4.4 |

## What Doesn't Change

- `policy.clj` — regex fast paths remain untouched. They work well for greetings/thanks/reset/help/tracking. The regex patterns for search/compare still catch clear cases.
- `render.clj` — card rendering, tier assignment, price formatting untouched. All new modes use the same deterministic renderer.
- `search.clj` — platform routing, accessory filtering, dedup untouched.
- `bot.clj` — transport layer only gets minor mode dispatch additions.
- `monitor/store.clj` — no changes needed, already has the data we need.
