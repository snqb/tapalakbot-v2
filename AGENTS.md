<!-- Updated: 2026-10-23 -->
# tapalakbot-v2

- **Agent-first architecture** — clj-harness LLM is the brain: sees conversation, decides intent, calls tools, generates text. Tools return structured data. Cards rendered deterministically. LLM never touches prices or URLs.
- **Streaming is the agent path** — non-fast-path Telegram messages use `handle-message-stream!` (core.clj), which calls `stream/stream-agent` directly and bypasses the middleware pipeline. Observability hooks must live in `observe/record!` + mulog, not middleware alone.
- **Configured models** — the conversation agent and generative helpers use `:gemini-3.5-flash` through OpenRouter; semantic listing selection uses typed `typesafe/jev-1.13` requests, not a second generative relevance filter. Both are configured in `resources/config.edn`. Russian VPS egress requires a working authenticated gateway.
- **clj-harness** — middleware stack for synchronous handler: `handle-message` goes through core-agent and clj-harness wrappers; streaming uses `stream/stream-agent` directly. `:nudges` requires tools before final answers. `:effects? true` uses effect-driven agent loop.
- **Observability** — mulog structured events + observe ring buffer. Every LLM call emits `:llm-call` with model/latency/tokens. Every turn emits `::agent.turn.start/end`. Trace IDs correlate full request trajectories. See Observability section.
- **Simulation** — `clojure -M:simulation` runs 20 realistic queries through the real pipeline, capturing every event (LLM calls, tool calls, draft chunks, status phases) to JSONL. See Simulation section.
- **Monitor in same JVM** — `server.clj` starts the monitor on its own thread and immediately continues to Telegram polling; never wait for the initial market scan on the bot startup path. Notifications use the same `render/render-reply`.
- **Telegram rendering split** — agent analysis is native Rich Markdown; deterministic listings are a separate Rich HTML document. Pages contain up to 20 cards with source-owned prices/currencies/URLs and typed decision reasons. `result_delivery.clj` owns opaque price refinements and media → text-only Rich → complete legacy fallbacks. Links stay outside tables for Android. Never send duplicate media groups.
- **Conversation isolation** — Telegram updates are keyed by chat/user/thread. A bounded executor processes one update per conversation and coalesces a busy conversation to its newest pending update.
- **Lalafo only** — all categories, including vehicles, use Lalafo. The conversation agent supplies full `user_want` and up to three keyword variants; collection preserves complete descriptions and source parameters, caps the candidate pool at 200, applies deterministic KGS budgets, then calls JEV.

## Architecture

```
Telegram update → clj-harness parser → bot.clj bounded per-conversation dispatcher
    → fast path or core.clj (handle-message-stream!)
    → stream/stream-agent: SSE streaming + tool loop
    → LLM sees conversation + tools (search, research)
    → search.clj → full Lalafo collection → source-currency budget checks → typed JEV selection
    → agent generates conversational text (streamed to user)
    ↓
bot.clj extracts accepted source cards → result_delivery.clj + render.clj
    ↓
Telegram: agent text (Rich Messages) + rendered cards (HTML)
```

**Three layers:**
1. **Agent** (clj-harness): LLM decides intent, calls tools, generates text. `wrap-tools` middleware for tool calling loop.
2. **Search + selection**: `lalafo_collection.clj` normalizes and round-robins source candidates; `search.clj` owns numeric constraints and currency-safe statistics; `jev.clj` evaluates semantic fit/condition/evidence/risk, validates complete responses and annotates exact owned cards. No lexical prefilter or statistical price-outlier removal.
3. **Transport**: `bot.clj` owns dispatch, fast paths, streaming and tracking callbacks; `result_delivery.clj` owns result delivery and refinements; `bot_state.clj` and `result_store.clj` own expiring conversation-scoped state and atomic pagination.


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
clojure -M:test -n tapalakbot.render-test -n tapalakbot.policy-test
```

### Dokploy / webhook mode

The container exposes plain HTTP on `PORT` (default 8080); Dokploy terminates TLS. `WEBHOOK_URL` enables webhook mode and requires `TELEGRAM_WEBHOOK_SECRET`; without both, startup falls back to long polling. Persist `/data` for session and monitor SQLite databases. `/health` is process liveness; `/ready` returns 200 only after transport startup and a successful marketplace probe.

## Testing

```bash
# Full deterministic suite: 103 tests / 426 assertions on 2026-10-23
clojure -M:test
```

## Gotchas

### Deployment (Dokploy)

- **Telegram route is mandatory** — the Swarm service must include `--host-add api.telegram.org:149.154.167.220`; the VPS otherwise resolves Telegram to an unreachable IPv6 endpoint and polling fails with `HttpConnectTimeoutException`. Verify with `docker service inspect tapalakbot-production-densux --format '{{json .Spec.TaskTemplate.ContainerSpec.Hosts}}'`.
- **Persistent data** — mount `/data`; `SESSION_DB_PATH` defaults to `/data/tapalakbot-sessions.db` and `MONITOR_DB_PATH` to `/data/tapalakbot-monitor.db`.
- **Webhook trust boundary** — set `TELEGRAM_WEBHOOK_SECRET`; unauthenticated POSTs to `/webhook` are rejected.
- **Container build must fail closed** — the Dockerfile compilation step must not use `|| true`; build failures must stop deployment.
- **Full SHA required** — tools.deps git dependencies require a full SHA.
- **clj-harness pinned** — update its full git SHA in `deps.edn` after parent fixes; do not assume local parent changes are deployed.
- **OpenRouter egress** — use the dedicated `pass openrouter/lalabot-token` credential and `https://openrouter-proxy-production-1ad8.up.railway.app/api/v1/chat/completions`; typed JEV uses the same origin at `/api/v1/systemone`. Verify both authenticated routes from the VPS. Do not overwrite the old global credential. Save Dokploy runtime env before pushing auto-deployed `main`; keep `createEnvFile=false`. See `docs/deployment.md`.
- **Monitor trust boundary** — the monitor API binds to `127.0.0.1` unless `MONITOR_HOST` overrides it. Set `MONITOR_ADMIN_TOKEN` before exposing it; `POST /scan` requires `Authorization: Bearer …` and permits only one active scan.

### General

- **Java (?i) doesn't work for Cyrillic** — `policy.clj` lowercases input before regex matching. Never use `(?i)` with Russian text.
- **Only ONE process per bot token** — Two pollers = 409 Conflict.
- **Stop-first rollback too** — both Swarm update and rollback order must be `stop-first`; verify after Dokploy release because service-spec recreation can reset rollout settings. Never deploy via the obsolete standalone-container recipe.
- **Forum topics are distinct conversations** — `message_thread_id` from the current update is immutable request context: preserve it through every send/edit and in the session ID. Never store a mutable "active thread" per user; one user can run multiple topics concurrently.
- **Busy conversations coalesce** — only the newest pending update is retained while a conversation is running; never add raw `future` dispatch around this path.
- **Lalafo search noise** — Generic queries return junk. Use exact model names.
- **Full source context** — JEV sees complete descriptions, condition and source parameters, not 80-character snippets. It only selects/annotates; trusted prices, currency, images and action URLs stay on the original cards.
- **Unavailable is not no-match** — collection/selector failure or an empty provider answer before search returns `:search-status :unavailable`. Never retry it as an automatic fallback search or show a successful empty-market claim.
- **Authoritative refinements** — `ask-stream` accepts `:price-min`/`:price-max` options. Presence, including nil, replaces old bounds; deterministic search removes the old textual budget before JEV evaluation. Always use owned opaque callbacks, not truncated queries.
- **Inflected budget continuations** — “до 1 сома”, “до 5 сомов” and similar budget-only turns refine the current product; classifying them as new searches clears conversation context.
- **deps.edn brace matching** — mulog dep addition caused `}}}` instead of `}}` via fuzzy patch. Always verify `clojure -Spath` after deps.edn edits.
