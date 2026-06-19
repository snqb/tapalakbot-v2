<!-- Updated: 2026-06-20 -->
# tapalakbot-v2

- **Agent-first architecture** — clj-harness LLM is the brain: sees conversation, decides intent, calls tools, generates text. Tools return structured data. Cards rendered deterministically. LLM never touches prices or URLs.
- **Streaming is the production path** — `handle-message-stream!` (core.clj) is used for every Telegram message. It calls `stream/stream-agent` directly, BYPASSING the middleware pipeline. Observability hooks are in `observe/record!` + mulog events, NOT in middleware alone.
- **Gemini 3.5 Flash** (`:gemini-3.5-flash`) via OpenRouter — main model. DeepSeek V4 Pro available as fallback. Relevance filter uses `:kimi-k2` via OpenRouter. Config: `resources/config.edn`.
- **clj-harness** — middleware stack: core-agent → wrap-tools → wrap-retry → wrap-trace-id → wrap-observability → wrap-logging. `:nudges` requires tools before final answers. `:effects? true` uses effect-driven agent loop.
- **Observability** — mulog structured events + observe ring buffer. Every LLM call emits `:llm-call` with model/latency/tokens. Every turn emits `::agent.turn.start/end`. Trace IDs correlate full request trajectories. See Observability section.
- **Simulation** — `clojure -M:simulation` runs 20 realistic queries through the real pipeline, capturing every event (LLM calls, tool calls, draft chunks, status phases) to JSONL. See Simulation section.
- **Monitor in same JVM** — `server.clj` auto-starts monitor thread. Notifications use same `render/render-reply`.

## Architecture

```
User → Telegram → bot.clj → core.clj (handle-message-stream!)
    → stream/stream-agent: SSE streaming + tool loop
    → LLM sees conversation + tools (search, research)
    → agent calls tools → structured data captured in dynamic vars
    → agent generates conversational text (streamed to user)
    ↓
bot.clj extracts captured cards → render.clj → deterministic HTML
    ↓
Telegram: agent text (Rich Messages) + rendered cards (HTML)
```

**Three layers:**
1. **Agent** (clj-harness): LLM decides intent, calls tools, generates text. `wrap-tools` middleware for tool calling loop.
2. **Deterministic** (search + render): search.clj, render.clj, query_builder.clj, lalafo.clj, mashina.clj — own all trust-critical facts.
3. **Transport** (Telegram): bot.clj — fast paths, streaming drafts, card rendering, tracking UI.

## File Map

| File | Lines | Purpose |
|------|-------|---------|
| `core.clj` | ~900 | Agent: system prompt, tools (search/research), ask-stream, card capture, relevance filter |
| `bot.clj` | ~880 | Telegram: agent dispatch, fast paths, streaming drafts, tracking UI, city selection |
| `simulation.clj` | ~370 | Full-scale simulation runner with JSONL event logging for auto-research |
| `query_builder.clj` | ~450 | NL→structured params: price, platform, category extraction. Uses :kimi-k2 for query generation |
| `lalafo.clj` | ~470 | Lalafo.kg HTTP client, Cloudflare bypass, category fetch, search |
| `riskbypass.clj` | ~220 | RiskBypass API client for Cloudflare cf_clearance solving |
| `render.clj` | ~195 | Deterministic card renderer — tier groups, formatted prices, Telegram HTML |
| `search.clj` | ~210 | Structured search pipeline — combines Lalafo + Mashina, cards out |
| `policy.clj` | ~110 | Fast-path intent classification (greetings, reset, search trigger) |
| `mashina.clj` | ~155 | Mashina.kg public REST API client |
| `server.clj` | ~270 | Entry point: webhook/polling, monitor auto-start, mulog config |

## Marketplace Platforms

### Lalafo.kg (Primary)
- General classifieds (cars, real estate, electronics, services)
- Direct HTTP, requires cf_clearance cookie via RiskBypass + Smartproxy
- All KG regions, ~50K+ active listings

### Mashina.kg (Auto-focused)
- Auto marketplace (cars, motorcycles, parts)
- Public REST API at `www.mashina.kg/api` — no auth needed
- Endpoints: `/api/ads/listings`, `/api/categories`, `/api/ads/slug/{slug}`
- 136K+ auto listings, rich attributes (year, engine, gearbox, mileage)

## Observability

Three-layer architecture — each covers what the others miss:

1. **Middleware** (sync path only): `wrap-trace-id` stamps UUID, `wrap-observability` emits mulog events + observe ring buffer. Only sees `handle-message` (sync), NOT `handle-message-stream!`.
2. **observe/record! hooks** (all paths): `:llm-call` events at `core-agent` (llm.clj) and `stream-agent` (stream.clj) — the two chokepoints covering sync + streaming. Token usage from raw LLM response (was discarded, now passed through as `:usage`).
3. **mulog events** (structured logs): `::agent.msg-in/out`, `::agent.turn.start/end/error` with trace-id, dialogue-id, latency-ms, tokens. Console publisher now, Prometheus/file later.

**Trace ID flow**: handle-message-stream! generates UUID → passed to stream-agent → every observe event + mulog event carries it → grep one user's full trajectory.

**Token tracking**: DeepSeek/OpenRouter returns `usage: {prompt_tokens, completion_tokens, total_tokens}` in raw response. `core-agent` passes it through as `:usage`. `consume-stream` captures it from SSE final chunk. Both emit `:llm-call` observe events. `observe/compute-stats` aggregates.

## Simulation

`clojure -M:simulation [N|0 "custom query"]` — runs queries through the REAL agent pipeline.

20-query catalog covers: direct searches, research-first, budget/market, vague intent, follow-ups, edge cases (English, typos, greetings), multi-platform, Russian product names.

Output: `~/agent-artifacts/tapalakbot-v2/simulation/<timestamp>/`
- `events.jsonl` — every event with trace-id: query.start/end, draft.chunk (streaming deltas), status, llm-call, tool, msg-in/out
- `summary.edn` — p50/p99 latency, total tokens, card counts, success rates

Analysis helpers in `simulation.clj`: `load-events`, `events-by-trace`, `llm-call-stats`, `tool-call-stats`.

**Draft states**: every streaming text delta is a `:draft.chunk` event with seq number. Reconstructs full response evolution — see WHERE the agent rambles, wastes tokens, or goes off track.

## Running (local/dev)

Production runs on VPS — stop VPS service first (only ONE process per token).

```bash
cd /Users/sn/Projects/tapalakbot-v2

# Terminal test (one-shot, no Telegram)
clojure -M:run "роутер до 4000"

# Telegram bot locally (polling mode, auto-starts monitor)
BOT_TOKEN='...' clojure -M:bot

# Telegram bot with webhooks (faster, needs public HTTPS URL)
BOT_TOKEN='...' WEBHOOK_URL='https://your-domain.com/webhook' WEBHOOK_PORT=8080 clojure -M:bot

# Monitor only (standalone, no Telegram)
clojure -M:monitor

# Simulation (20 queries, real pipeline, JSONL event log)
clojure -M:simulation
clojure -M:simulation 5           # first 5 queries
clojure -M:simulation 0 "айфон"   # single custom query

# Run tests
clojure -M:test -n tapalakbot.render-test -n tapalakbot.policy-test
```

### Webhook mode

Set `WEBHOOK_URL` env var. Works with bare public IP (no domain). Self-signed cert auto-generated in `certs/`. Falls back to polling if `setWebhook` fails. Jetty serves HTTPS on `WEBHOOK_PORT` (default 8443). `/health` endpoint for monitoring.

## Testing

```bash
# 24 tests, 86 assertions
clojure -M:test -n tapalakbot.render-test -n tapalakbot.policy-test
```

## Gotchas

### Deployment (NixOS VPS)

- **Telegram blocked in Russia** — Only `149.154.167.220` works. NixOS: `networking.extraHosts = "149.154.167.220 api.telegram.org";`
- **NixOS systemd PATH lacks git** — tools.deps needs git. Add `pkgs.git` to service `environment.PATH`.
- **`.cpcache` stale after deps change** — `rm -rf /opt/tapalakbot-v2/.cpcache` on VPS before restart.
- **Full SHA required** — tools.deps requires full SHA for git deps, not prefix.
- **clj-harness pinned** — git dep SHA in deps.edn. `:local/root` for dev, git SHA for deploy.

### General

- **Java (?i) doesn't work for Cyrillic** — `policy.clj` lowercases input before regex matching. Never use `(?i)` with Russian text.
- **Only ONE process per bot token** — Two pollers = 409 Conflict.
- **Lalafo search noise** — Generic queries return junk. Use exact model names.
- **deps.edn brace matching** — mulog dep addition caused `}}}` instead of `}}` via fuzzy patch. Always verify `clojure -Spath` after deps.edn edits.
