<!-- Updated: 2026-11-03 -->
# tapalakbot-v2

- **Agent-first architecture** — one configured conversation agent decides intent and calls tools. Tools return structured data; cards render deterministically. The answer generator never receives listing price fields or action URLs; user-supplied budget bounds are allowed.
- **Streaming is the agent path** — `telegram_conversation.clj` calls `core/ask-stream`, then clj-harness `handle-message-stream!` and `stream/stream-agent`. Status phases remain live; generated prose is withheld until advisor grounding finishes. This bypasses synchronous middleware; observability hooks belong in `observe/record!` + mulog, not middleware alone.
- **Configured models** — the conversation agent and generative helpers use `:gemini-3.5-flash` through OpenRouter; semantic listing selection uses typed `typesafe/jev-1.13` requests, not a second generative relevance filter. Both are configured in `resources/config.edn`. Russian VPS egress requires a working authenticated gateway.
- **clj-harness** — keep semantic intent in the primary agent. The pre-hook only adds durable context, locations and owned subscriptions; never clear history with a keyword/regex intent classifier.
- **Observability** — mulog structured events + observe ring buffer. Every LLM call emits `:llm-call` with model/latency/tokens. Every turn emits `::agent.turn.start/end`. Trace IDs correlate full request trajectories. See Observability section.
- **Simulation** — `clojure -M:simulation` runs 20 realistic queries through the real pipeline, capturing every event (LLM calls, tool calls, draft chunks, status phases) to JSONL. See Simulation section.
- **Monitor in same JVM** — `server.clj` starts the monitor on its own thread and immediately continues to Telegram transport startup; never wait for the initial market scan. Notifications use the same `render/render-reply`.
- **Telegram rendering split** — agent analysis is a short native Rich Markdown paragraph; listings are a separate Rich HTML document with controls on the cards. Pages contain up to 20 cards, six expanded with native photos, the rest collapsed. Cards show source facts, one condition and material warnings, not stock JEV explanations. Collection/selection counters are inside “Как отбирали”. Links stay outside tables for Android. Never send duplicate media groups.
- **Conversation isolation** — Telegram updates are keyed by chat/user/thread. A bounded executor processes one update per conversation and coalesces a busy conversation to its newest pending update.
- **Lalafo only** — all categories, including vehicles, use Lalafo. The conversation agent supplies full `user_want` and up to three keyword variants; collection preserves complete descriptions and source parameters, caps the candidate pool at 200, applies deterministic KGS budgets, then calls JEV.
- **Durable buying request** — the existing persisted harness session stores `["data" "buying-request"]`: full intent, keyword variants, nullable bounds/city, explicit feedback/exclusions, the accepted pool and latest numbered view. A genuinely new request clears previous product constraints; follow-ups retain them and their tracking association.
- **Buying advice** — `purchase_research.clj` retrieves Exa evidence; durable strategy is explicitly a hypothesis, not verified engineering knowledge. The primary agent selects owned excerpt IDs (`r1e1`), never supplies quotation text or source URLs. `purchase_advice.clj` resolves exact quotes and `[[rN]]` links. Research must not add mandatory user constraints to the following search.
- **Photo evidence** — inspect only owned current-search candidates, including rejected ones. `listing_vision.clj` sends original Lalafo images to Gemini; inscriptions are not authenticity or functionality. Provider limitations use closed codes, rendered by the server; free-form limitations and evaluative observations fail closed. Failure categories, usage and finish reason survive normalization errors. Cached evidence must still match the card's images; JSON enum values normalize before reuse. Only typed JEV can admit an inspected candidate.
- **Vision reasoning budget** — Gemini 3.5 Flash has mandatory reasoning; vision requests use `reasoning.effort=minimal` within the unchanged 1200-token output limit. Reasoning shares that limit with JSON: a real diagnostic spent 1153 of 1183 completion tokens on thinking and truncated the answer. `exclude=true` does not save tokens; do not disable mandatory reasoning or repair partial JSON into accepted evidence.
- **Recommendation feedback** — `recommend_results` validates proposed explanations before returning success or changing the selection. Unsupported proposals return exact rejected claims and source-owned seller/photo context so the primary agent can revise without another photo request. Writer projections redact seller monetary/action metadata; original cards remain unchanged. Approval is a server-owned `:grounded?` marker, absent from writer views and invalidated by a new proposal or photo evidence. Never treat generated notes as evidence.
- **Advisor delivery boundary** — typed `advice_grounding.clj` checks complete explanation paragraphs and recommendation checks, not intent/relevance. Each question embeds its exact claim. Listing facts use one owned card's evidence; unbound final prose receives research only, never a mixed listing/photo context. Verification actions may investigate a risk without asserting it exists. Preserve study populations and distinguish reviewer preferences from requirements. Raw prose is withheld; only checked text is persisted/delivered. `unsupported` is atomic, with supported-ID hints but no partial mutation. Per-card notes forbid inline citation markers; server-owned evidence alone renders citations.
- **Typed claim binding** — each question embeds its exact claim and listing ID as untrusted data. Do not address claims by an unlabeled array position. Generated claims stay out of source state; numeric answer keys only correlate responses.
- **Restart semantics** — durable intent, sources, strategy and successful photo evidence survive; full source-card caches do not. Pre-hook/tool responses explicitly distinguish missing cached cards from invalid evidence IDs. A requested fresh shortlist must search again; never retry recommendations against persisted IDs alone. Search and drill-down caches have independent expiry.
- **Tracker release invariants** — notifications contain at most 20 accepted cards; only the confirmed batch becomes seen. A failed cycle must not terminate the daemon, while interruption still stops it. Retained pre-provenance seen rows migrate transactionally to unknown (`rejected=-1`); filter edits clear unknown/negative rows but retain known positives. A semantic query/title edit clears the obsolete provider category, not ownership or schedule-only state.
- **Owned tracking** — `tracking/manage!` supports create/list/update/pause/resume/stop. Only the server supplies conversation/chat/topic ownership. The agent tool and Telegram controls share this service; managing another saved subscription must not replace the active buying task.
- **Tracker cache semantics** — seed every accepted pool ID at creation. Keep rejected evaluations separate from delivered/seeded IDs. A filter edit clears negatives and migrated legacy unknowns; positive IDs retire only after a confirmed Telegram message ID. Check current enabled/filter state before and after source/JEV work.
- **Cached result ordering** — `result_order.clj` defaults to direct matches first, then ascending authoritative KGS price within each fit group. Price-desc and JEV relevance are view changes over the full cached pool, never fresh search/LLM calls. Unknown comparable prices sort last in price modes. Every new sort gets a new owned cursor, resets result indexes/page offset, invalidates old page/sort controls and preserves the original cache expiry; exhausted cursors remain sortable. Numeric drill-down follows the latest ordered view.
- **Advisor view is not the price view** — the writer receives up to 40 candidates in JEV relevance order, including fit/condition/risk/rank metadata and the full accepted count; all source cards remain cached for human price views and owned inspection. Price-blind generation must not inherit a cheap-first candidate order. Rank is semantic fit/evidence, not a quality guarantee.
- **Ordinary use versus repair** — an explicitly broken essential function is incompatible with ordinary requested use; cosmetic wear alone is not. Repair/parts requests may admit the same broken item. Never blanket-filter the `damaged` condition.
- **Configured workflow budget** — `core.clj` reads `:agent :max-turns` from config (9). The pinned harness reserves two slots before forcing a final answer, leaving seven executable tool steps for research, strategy, search, inspection and bounded proposal correction. This does not add HTTP retries or longer timeouts.
- **Unambiguous result controls** — “На этой странице: N–M” expands already-delivered cards; “Следующая страница” advances the cached pool. Budget buttons say “Найти до/от … сом” with the exact authoritative threshold and start a new search. Do not use relative “Дешевле/Дороже” labels for full-pool median budgets that can exceed the visible page's prices.

## Architecture

```
Telegram update → clj-harness parser → bot.clj bounded per-conversation dispatcher
    → telegram_conversation.clj → core/ask-stream → clj-harness streaming loop
    → primary agent sees conversation, durable goal and owned subscriptions
    → tools: research, purchase_strategy, search, inspect_listings, recommend_results, reject_results, tracking
    → search.clj → Lalafo collection → source-safe budgets/exclusions → typed JEV
    → photo evidence → typed re-selection → primary-agent proposal → typed grounding
    ↓
telegram_conversation.clj → result_delivery.clj + render.clj → source-owned cards
    ↓
Telegram: agent text (Rich Messages) + rendered cards (HTML)
```

**Three layers:**
1. **Agent** (clj-harness): LLM decides intent, calls tools, generates text. `wrap-tools` middleware for tool calling loop.
2. **Search + selection**: `lalafo_collection.clj` normalizes and round-robins source candidates; `search.clj` owns numeric constraints and currency-safe statistics; `jev.clj` evaluates semantic fit/condition/evidence/risk, validates complete responses and annotates exact owned cards. No lexical prefilter or statistical price-outlier removal.
3. **Transport + state**: `bot.clj` owns dispatch and commands; `telegram_conversation.clj` owns streamed turns and `tracking_ui.clj` owns subscription controls. `result_delivery.clj` owns result delivery/refinements; `bot_state.clj` and `result_store.clj` own expiring conversation-scoped state and atomic pagination.

**Advisor release verification:** minimal-effort vision, natural iPad clarification/budget refinement, explained iPad/treadmill selection and native Rich delivery have real receipts. Sewing's safe captured proposal passed an exact typed replay after fixing an overbroad unknown-condition rejection; do not mislabel that replay as a new autonomous turn. The user authorized publication on 2026-11-03; verify the actual deployed conversation and controls, not merely health or these local receipts. See the execution journal for current release state and known grounding limits.


## Marketplace

### Lalafo.kg
- General classifieds (cars, real estate, electronics, services)
- Direct Java `HttpClient`; no subprocess, browser cookie, proxy credential, or RiskBypass dependency
- Main search passes `:city-id nil` to include all regions; never replace an explicit nil with the old Bishkek default.
- Provider numeric price filters are deliberately omitted: mixed-currency semantics are unverified. Local budget checks require an explicitly known KGS amount or authoritative source KGS equivalent. Missing currency is not KGS.
- JEV batches contain at most 20 cards and a conservative 30k-character request estimate, with three workers and a 30s request timeout. Descriptions are not truncated; an oversized individual listing fails explicitly.
- The selector returns source-bound cards plus `:selection` counts; partial/malformed responses fail the whole selection. A valid empty decision remains empty. Tracker failures defer the check without marking candidates seen.
- Reconcile typed decisions before admission: missing product identity, explicit incompatibility and current-offer price/currency conflict are excluded; concrete suspicious-source evidence can only produce an uncertain alternative. Missing photos/descriptions alone are not a filter. Historical purchase amounts are not current sale-price conflicts.


## Observability

Three-layer architecture — each covers what the others miss:

1. **Middleware** (sync path only): `wrap-trace-id` stamps UUID, `wrap-observability` emits mulog events + observe ring buffer. Only sees `handle-message` (sync), NOT `handle-message-stream!`.
2. **observe/record! hooks** (all paths): `:llm-call` events at `core-agent` and `stream-agent` cover generative calls. `jev.clj` records each typed HTTP batch directly, including failures, model, latency, correlation ID and provider-reported usage/cost.
3. **mulog events** (structured logs): `::agent.msg-in/out`, `::agent.turn.start/end/error` with trace-id, dialogue-id, latency-ms, tokens. Console publisher now, Prometheus/file later.

**Trace ID flow**: handle-message-stream! generates UUID → passed to stream-agent → every observe event + mulog event carries it → grep one user's full trajectory.

**Token tracking**: DeepSeek/OpenRouter returns `usage: {prompt_tokens, completion_tokens, total_tokens}` in raw response. `core-agent` passes it through as `:usage`. `consume-stream` captures it from SSE final chunk. Both emit `:llm-call` observe events. `observe/compute-stats` aggregates.

## Simulation

`clojure -M:simulation [N|0 "custom query"]` — runs queries through the REAL agent pipeline.

20-query catalog covers: direct searches, research-first, budget/market, vague intent, follow-ups, edge cases (English, typos, greetings), product categories, Russian product names.

Output: `~/agent-artifacts/tapalakbot-v2/simulation/<timestamp>/`
- `events.jsonl` — every event with trace-id: query.start/end, draft.chunk (streaming deltas), status, llm-call, tool, msg-in/out
- `summary.edn` — p50/p99 latency, total tokens, card counts, success rates

Analysis helpers in `simulation.clj`: `load-events`, `events-by-trace`, `llm-call-stats`, `tool-call-stats`.

**Draft states**: every streaming text delta is a `:draft.chunk` event with seq number. Reconstructs full response evolution — see WHERE the agent rambles, wastes tokens, or goes off track.

## Running (local/dev)

Production runs in Dokploy behind a TLS-terminating reverse proxy. Never run a second poller for the same bot token.

```bash
cd /Users/sn/Projects/tapalakbot-v2

# Terminal test (one-shot, no Telegram)
clojure -M:run "роутер до 4000"

# Telegram bot locally (polling mode, auto-starts monitor)
BOT_TOKEN='...' clojure -M:bot

# Telegram bot with a public reverse-proxy webhook
BOT_TOKEN='...' WEBHOOK_URL='https://your-domain.com/webhook' TELEGRAM_WEBHOOK_SECRET='...' PORT=8080 clojure -M:bot

# Monitor only (standalone, no Telegram)
clojure -M:monitor

# Simulation (20 queries, real pipeline, JSONL event log)
clojure -M:simulation
clojure -M:simulation 5           # first 5 queries
clojure -M:simulation 0 "айфон"   # single custom query

# Run tests
clojure -M:test -n tapalakbot.core-test -n tapalakbot.tracking-test
```

### Dokploy / webhook mode

The container exposes plain HTTP on `PORT` (default 8080); Dokploy terminates TLS. `WEBHOOK_URL` enables webhook mode and requires `TELEGRAM_WEBHOOK_SECRET`; without both, startup falls back to long polling. Persist `/data` for session and monitor SQLite databases. `/health` is process liveness; `/ready` additionally requires a live polling worker (or configured webhook) and a successful marketplace probe.

## Testing

```bash
# Full deterministic suite
clojure -M:test
```

## Gotchas

### Deployment (Dokploy)

- **Telegram/gateway networking** — production uses `https://tapalak-gateway-edge.vercel.app`: exact model rewrites forward to the authenticated Railway gateway; `TELEGRAM_PROXY_URL` ends in `/telegram` and authenticates this bot in a bearer header, never the relay URL. Direct Railway and Telegram cold connections intermittently time out; Railway packet headers show unacknowledged ClientHello segments. HTTP/2 and TLS 1.2 did not fix full turns. Keep normal verified TLS and `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`. Preserve the Telegram IPv4 host entry for direct diagnostics; Dokploy removes manual Swarm host entries.
- **Persistent data** — mount `/data`; `SESSION_DB_PATH` defaults to `/data/tapalakbot-sessions.db` and `MONITOR_DB_PATH` to `/data/tapalakbot-monitor.db`.
- **Webhook ingress** — production sets `WEBHOOK_URL=https://tapalak-gateway-edge.vercel.app/webhook`; the edge accepts only the existing `pass telegram/tapalakbot/webhook-secret` value, then forwards to the fixed VPS `/webhook`, which validates it independently. Set the same `TELEGRAM_WEBHOOK_SECRET` in VPS and edge production environments. Register `max_connections=1` to preserve arrival-order coalescing. Expect one bot JVM and no polling thread. Direct Telegram → VPS delivery timed out despite the correct IP; do not restore continuous serverless `getUpdates` polling.
- **Container build must fail closed** — the Dockerfile compilation step must not use `|| true`; build failures must stop deployment.
- **Full SHA required** — tools.deps git dependencies require a full SHA.
- **clj-harness pinned** — update its full git SHA in `deps.edn` after parent fixes; do not assume local parent changes are deployed.
- **Polling startup** — the pinned harness starts at unacknowledged updates inside its existing error handler; never restore the unguarded `offset=-1` startup probe that discarded pending messages and killed the worker on connect timeout. `server/start-poller!` revokes readiness when its thread terminates.
- **OpenRouter egress** — use the dedicated `pass openrouter/lalabot-token` credential and the edge origin at `/api/v1/chat/completions`; typed JEV uses `/api/v1/systemone`. Railway still owns model authentication and its two-path allowlist. The edge Telegram function separately requires the existing `BOT_TOKEN` production secret. Verify full turns from the VPS, not only health or tiny SSE. Do not overwrite the old global credential. Save Dokploy runtime env before pushing auto-deployed `main`; keep `createEnvFile=false`. See `docs/deployment.md`.
- **Monitor trust boundary** — the monitor API binds to `127.0.0.1` unless `MONITOR_HOST` overrides it. Set `MONITOR_ADMIN_TOKEN` before exposing it; `POST /scan` requires `Authorization: Bearer …` and permits only one active scan.

### General

- **Rich keyboard option** — `tg/send-rich-message` accepts `:reply-markup`; legacy `send-message`/`send-md` use `:reply_markup`. A wrong keyword silently drops all card controls. Result-flow tests exercise the actual Rich helper through the Bot API boundary rather than mocking its keyword arguments.
- **Only ONE process per bot token** — Two pollers = 409 Conflict.
- **Persist stop-first ordering** — save `updateConfigSwarm` and `rollbackConfigSwarm` objects through Dokploy `application.update`, both with `Parallelism:1` and `Order:"stop-first"`. A manual service update alone is overwritten on redeploy. Verify the resulting Swarm spec and keep exactly one bot process.
- **Forum topics are distinct conversations** — `message_thread_id` from the current update is immutable request context: preserve it through every send/edit and in the session ID. Never store a mutable "active thread" per user; one user can run multiple topics concurrently.
- **Rejection provenance** — `reject_results` requires a verbatim quote from the current user message and owned listing references. Never replay an old rejection against newly displayed numbers; pagination/sort must persist the latest numbered view.
- **Tracking association** — pause/resume/stop only change metadata on the linked buying task. Update canonical filters only for that linked task; preserve the link through search follow-ups, clear it for a new product, and never attach an old cached button to a different current task.
- **UTC scheduling** — SQLite timestamps are UTC. Compute tracker due times in UTC, not the JVM's local timezone; check due subscriptions every minute without changing their configured notification interval.
- **Busy conversations coalesce** — only the newest pending update is retained while a conversation is running; never add raw `future` dispatch around this path.
- **Lalafo search noise** — Generic queries return junk. Use exact model names.
- **Full source context** — JEV sees complete descriptions, condition and source parameters, not 80-character snippets. It only selects/annotates; trusted prices, currency, images and action URLs stay on the original cards.
- **Unavailable is not no-match** — collection/selector failure or an empty provider answer before search returns `:search-status :unavailable`. Never retry it as an automatic fallback search or show a successful empty-market claim.
- **Authoritative refinements** — `ask-stream` accepts `:price-min`/`:price-max` options. Presence, including nil, replaces old bounds; deterministic search removes the old textual budget before JEV evaluation. Always use owned opaque callbacks, not truncated queries.
- **Stale is not exhausted** — `next-result-page!` returns `:status :page`, `:exhausted` with an owned total, or `:stale` without data. Missing/expired/foreign cursors never report an empty market or expose totals; Telegram shows a recovery alert directing the user to the latest list or a new search.
- **Short continuations** — budget-only turns such as “до 5 сомов” and answers to a clarification retain the product. Use conversation history and structured state, not a second intent router.
- **deps.edn brace matching** — mulog dep addition caused `}}}` instead of `}}` via fuzzy patch. Always verify `clojure -Spath` after deps.edn edits.
