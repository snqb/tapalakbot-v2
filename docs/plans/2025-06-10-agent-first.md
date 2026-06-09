# Agent-First Refactor Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Replace the orchestrator dispatch (policy → intent → mode handler → curator → render) with a single clj-harness agent that decides what to do via tools. The agent IS the orchestrator. Deterministic rails (search, render, tiers) stay.

**Architecture:**
```
User → bot.clj → core.clj (clj-harness agent loop):
    LLM sees conversation + tools
    → decides: call search? market_stats? just chat?
    → tools return structured data (captured)
    → agent generates conversational text
    ↓
bot.clj extracts captured cards from tool results
    → render.clj → deterministic HTML cards
    ↓
Telegram: agent text + rendered cards
```

**Tech Stack:** Clojure, clj-harness (agent loop, wrap-tools, streaming), existing tools in core.clj, render.clj.

---

## What Exists Already

- `core.clj` — Bot factory (`h/create-bot`), tools (`research`, `market_stats`, `search`), system prompt, pre-hook, session persistence. **This is the right foundation.**
- `clj-harness` — `handle-message-stream!` with `status-cb`, returns `{:content :tool-outputs}`. Full agent loop with tool calling, retries, logging.
- `render.clj` — Deterministic card renderer (unchanged)
- `search.clj` — Deterministic search pipeline (unchanged)

## What Gets Deleted

- `intent.clj` — Agent classifies intent itself
- `orchestrator.clj` — Agent is the orchestrator
- `policy.clj` — Agent decides what to do (but keep greeting/reset fast paths in bot.clj)

## What Changes

- `bot.clj` — Dispatches through `core/ask-stream` instead of `orch/orchestrate`
- `core.clj` — Search tool captures structured cards for render pipeline; system prompt updated

---

## Phase 1: Agent Bridge (connect agent to render pipeline)

### Task 1.1: Add card capture to search tool

**Objective:** When the agent calls the search tool, capture the structured card data so we can render it deterministically after the agent responds.

**Files:**
- Modify: `src/tapalakbot/core.clj`

**Implementation:**

Add a dynamic var to hold captured cards:

```clojure
(def ^:dynamic *captured-cards*
  "Captured structured cards from search tool execution.
   Used by bot.clj to render deterministic cards after agent responds."
  nil)

(def ^:dynamic *captured-stats*
  "Captured search stats (avg, min, max, count) from tool execution."
  nil)
```

Modify `search-execute` to capture cards into the dynamic var. The tool already calls `search/search` internally — we just need to capture the structured result before formatting it as text for the LLM.

Find the `search-execute` function and add card capture at the point where search results are available (after `lalafo/search` and `mashina/search-cars` calls, before formatting to text).

The key change: after the search returns structured items, store them in `*captured-cards*`:

```clojure
;; Inside search-execute, after getting results:
(when (bound? #'*captured-cards*)
  (set! *captured-cards* structured-cards))
(when (bound? #'*captured-stats*)
  (set! *captured-stats* stats))
```

### Task 1.2: Create ask-stream function

**Objective:** A function that runs the agent and returns both the agent's text and any captured card data.

**Files:**
- Modify: `src/tapalakbot/core.clj`

Add after the existing `ask` function:

```clojure
(defn ask-stream
  "Run agent with streaming. Returns {:text \"...\" :cards [...] :stats {...}}.
   status-cb is called with progress updates."
  ([user-id text status-cb]
   (ask-stream user-id text status-cb {}))
  ([user-id text status-cb opts]
   (let [cards-atom (atom nil)
         stats-atom (atom nil)
         result (binding [*captured-cards* cards-atom
                          *captured-stats* stats-atom]
                  (h/handle-message-stream!
                   @tapalakbot user-id text
                   (fn [chunk] nil)  ;; stream-cb — we don't stream to Telegram
                   :status-cb status-cb))]
     {:text  (if (map? result) (:content result) (str result))
      :cards @cards-atom
      :stats @stats-atom
      :tool-outputs (when (map? result) (:tool-outputs result))})))
```

### Task 1.3: Run tests to verify core.clj compiles

```bash
cd /Users/sn/Projects/tapalakbot-v2
clojure -M:test -d test/tapalakbot/render_test.clj test/tapalakbot/policy_test.clj
```

---

## Phase 2: Wire bot.clj to agent

### Task 2.1: Replace orchestrator dispatch with agent call

**Objective:** bot.clj's `handle-orchestrated` calls `core/ask-stream` instead of `orch/orchestrate`, then renders cards from captured data.

**Files:**
- Modify: `src/tapalakbot/bot.clj`

Replace the orchestrator call block in `handle-orchestrated` (around line 574-630):

```clojure
;; OLD:
(let [cfg (-> @t/tapalakbot :config)
      orch-future (future
                   (orch/orchestrate text session
                     :model ... :provider ... :status-cb status-cb))
      reply (deref orch-future 45000 :timeout)]
  (case (:mode reply) ...))

;; NEW:
(let [uid (str "tg-" user-id)
      status-cb (fn [status-text]
                  (when-let [msg-id @thinking-msg-id]
                    (try (tg/edit-message chat-id msg-id status-text :parse-mode nil)
                         (catch Exception _))))
      result-future (future
                      (t/ask-stream uid text status-cb))
      result (deref result-future 60000 :timeout)]
  (if (= result :timeout)
    ;; Timeout
    (when-let [msg-id @thinking-msg-id]
      (tg/edit-message chat-id msg-id "⏳ Слишком долго. Попробуйте ещё раз." :parse-mode nil))
    ;; Render agent text + cards
    (let [agent-text (:text result)
          cards (:cards result)
          stats (:stats result)
          ;; Assign tiers to cards
          final-cards (when (seq cards)
                       (mapv (fn [card]
                               (let [tier (render/assign-tier (:price card) (:avg stats))]
                                 (assoc card :tier (or tier :good))))
                             cards))
          ;; Build reply for render
          reply {:mode :shortlist
                 :intro agent-text
                 :cards (or final-cards [])
                 :cta nil
                 :assumptions []}]
      (render-orchestrated chat-id @thinking-msg-id reply user-id text)
      (log-transcript! user-id text reply))))
```

### Task 2.2: Keep fast-path shortcuts for greetings/reset

**Objective:** Greetings and reset should be instant (no LLM call). Keep these as direct responses in bot.clj.

**Files:**
- Modify: `src/tapalakbot/bot.clj`

Before the agent call, add fast-path checks:

```clojure
;; Fast paths — instant, no LLM
(let [tl (str/lower-case (str/trim (or text "")))]
  (cond
    ;; Greeting
    (re-find #"^\s*(привет|салам|хай|hello|hi|добр[оы]й|здравствуй)" tl)
    (do (when-let [msg-id @thinking-msg-id]
          (tg/edit-message chat-id msg-id greeting-resp :parse-mode nil))
        nil)
    
    ;; Reset
    (re-find #"(новый диалог|сброс|забудь|начать сначала|сначала|заново)" tl)
    (do (hc/reset-session! @t/tapalakbot uid)
        (when-let [msg-id @thinking-msg-id]
          (tg/edit-message chat-id msg-id "🗑️ Контекст очищен. Начнём заново!" :parse-mode nil))
        nil)
    
    ;; Thanks
    (re-find #"^\s*(спасибо|спс|ок|окей|понял|ладно|thanks)\s*$" tl)
    (do (when-let [msg-id @thinking-msg-id]
          (tg/edit-message chat-id msg-id "Пожалуйста! 😊 Если нужно найти что-то ещё — пишите." :parse-mode nil))
        nil)
    
    ;; Help
    (re-find #"(помощь|help|что умеешь|как пользов)" tl)
    (do (when-let [msg-id @thinking-msg-id]
          (tg/edit-message chat-id msg-id help-resp :parse-mode nil))
        nil)
    
    ;; Everything else → agent
    :else
    ;; ... agent call from Task 2.1
    ))
```

### Task 2.3: Remove orchestrator require from bot.clj

**Files:**
- Modify: `src/tapalakbot/bot.clj`

Remove:
```clojure
[tapalakbot.orchestrator :as orch]
[tapalakbot.intent :as intent]
```

The agent handles everything the orchestrator and intent classifier used to do.

---

## Phase 3: System prompt refinement

### Task 3.1: Update system prompt for conversational agent

**Objective:** The system prompt should guide the agent to be conversational, use tools intelligently, and produce responses that work with the card renderer.

**Files:**
- Modify: `src/tapalakbot/core.clj` — `system-prompt` def

Key changes to the system prompt:
- Remove the rigid "MUST call tools for EVERY query" — let the agent decide
- Add guidance for conversational responses (greetings, follow-ups, advice)
- Keep anti-hallucination rules (never invent prices/URLs)
- Add guidance on when to use which tool
- Response format: agent text is the intro/framing, cards are rendered separately

```clojure
(def system-prompt
  "You are TapalakBot — a smart marketplace assistant for Kyrgyzstan.
You help people find products on Lalafo.kg and Mashina.kg.
Speak Russian. Be conversational, helpful, and concise.

## Your tools
- search: Find actual listings. Returns real prices and URLs.
- market_stats: Get price ranges and market data for a category.
- research: Look up product info, specs, alternatives online.

## When to use tools
- User wants to find/buy something → search (and market_stats first if research query)
- User asks about prices → market_stats, then search
- User asks a follow-up about previous results → just answer from conversation history
- User greets or chats → just respond naturally, no tools needed
- User asks 'which is better' → compare items you already showed, no new search needed

## Response style
- Your text response is conversational framing — intro, recommendations, advice
- Cards with prices/links are rendered automatically from search results
- Don't repeat prices in your text that are already in the cards
- Be concise: 1-3 sentences of framing, not paragraphs
- If user asks 'хочу айфон' (research), call market_stats THEN search, then give brief advice

## Anti-hallucination
- NEVER invent prices, URLs, or listing details
- If you don't have data from tools, say so
- Never make up market statistics")
```

### Task 3.2: Run tests

```bash
clojure -M:test -d test/tapalakbot/render_test.clj test/tapalakbot/policy_test.clj
```

---

## Phase 4: Status updates and UX

### Task 4.1: Wire status-cb to thinking message

**Objective:** The agent's status callbacks (from tool execution) update the Telegram thinking message.

**Files:**
- Modify: `src/tapalakbot/bot.clj`

The status-cb from `handle-message-stream!` is already wired in Task 2.1. Just need to make sure it updates the thinking message:

```clojure
status-cb (fn [status-text]
            (when-let [msg-id @thinking-msg-id]
              (try (tg/edit-message chat-id msg-id status-text :parse-mode nil)
                   (catch Exception _))))
```

clj-harness will call this with status updates like "Calling search..." etc.

### Task 4.2: Add tracking button after search results

**Objective:** After the agent returns search results, show the tracking button.

**Files:**
- Modify: `src/tapalakbot/bot.clj`

After rendering cards, check if cards were returned and add tracking button:

```clojure
(when (seq final-cards)
  (let [track-btn (track-context-button user-id text)]
    (when track-btn
      (try (tg/send-message chat-id "Хотите отслеживать?" 
                            :parse-mode nil :reply_markup track-btn)
           (catch Exception _)))))
```

---

## Phase 5: Cleanup

### Task 5.1: Remove dead code

**Files to clean up:**
- `src/tapalakbot/intent.clj` — DELETE (agent handles intent)
- `test/tapalakbot/intent_test.clj` — DELETE
- `src/tapalakbot/orchestrator.clj` — Keep only `get-market-context` if used elsewhere, delete rest
- `test/tapalakbot/orchestrator_test.clj` — DELETE (agent handles what orchestrator did)
- `src/tapalakbot/policy.clj` — Keep (still used for fast-path regex in bot.clj)
- `src/tapalakbot/query_builder.clj` — Keep (used by search tool in core.clj)

### Task 5.2: Update AGENTS.md

Update architecture diagram, file map, and key design decisions to reflect agent-first.

### Task 5.3: Run full test suite

```bash
cd /Users/sn/Projects/tapalakbot-v2
clojure -M:test -d test/tapalakbot/render_test.clj test/tapalakbot/policy_test.clj
```

### Task 5.4: Commit and deploy

```bash
git add -A && git commit -m "refactor: agent-first architecture — clj-harness agent replaces orchestrator"
git push origin main
# Deploy to VPS
sshpass -p '...' ssh root@85.239.40.192 "cd /opt/tapalakbot-v2 && git pull && rm -rf .cpcache && systemctl restart tapalakbot"
```

---

## Files Summary

| File | Action | Phase |
|------|--------|-------|
| `src/tapalakbot/core.clj` | MODIFY (card capture, ask-stream, system prompt) | 1, 3 |
| `src/tapalakbot/bot.clj` | MODIFY (agent dispatch, fast paths, remove orchestrator) | 2, 4 |
| `src/tapalakbot/intent.clj` | DELETE | 5 |
| `test/tapalakbot/intent_test.clj` | DELETE | 5 |
| `src/tapalakbot/orchestrator.clj` | DELETE (mostly) | 5 |
| `test/tapalakbot/orchestrator_test.clj` | DELETE | 5 |
| `AGENTS.md` | UPDATE | 5 |

## What Doesn't Change

- `render.clj` — Deterministic card rendering stays
- `search.clj` — Deterministic search pipeline stays
- `query_builder.clj` — NL→structured params stays
- `lalafo.clj`, `mashina.clj` — API clients stay
- `monitor/` — Price monitoring stays
- `policy.clj` — Kept for fast-path regex (greetings, reset)
