<!-- Updated: 2026-06-08 -->
# tapalakbot-v2

- **LLM-powered intent routing** — Regex handles fast paths (greetings, direct searches). Everything else goes through `intent.clj` which asks the LLM to classify intent. "which is better" → followup, "хочу айфон" → research, not search for literal text.
- **Multiple response modes** — :search (direct results), :research (market intelligence + picks), :followup (conversational about shown items), :compare (structured comparison + verdict), :refine (filtered re-search), :chat (small talk).
- **Structured reply contract** — `{:mode :research :cards [...] :intro "..." :market-note "..." :cta "..."}`. Transport renders from structured data.
- **Session stores conversation context** — `:last-items` (what was shown), `:last-mode`, `:last-active` (timestamp, 30min expiry). Enables follow-ups.
- **Deterministic cards** — `render.clj` builds Telegram HTML from structured data. LLM never touches prices, URLs, or card layout.
- **DeepSeek** (`:deepseek-v4-pro`) — adequate Russian + tool calling. Token from `pass deepseek-api/token`. Config: `resources/config.edn` models map.
- **clj-harness** — middleware stack: core-agent → wrap-tools → wrap-retry → wrap-logging. `:nudges` requires tools before final answers.
- **HTML parse_mode** — `render.clj` builds Telegram HTML deterministically, sent with `parse_mode="HTML"`.
- **Monitor in same JVM** — Avoids separate deployment. `server.clj` auto-starts monitor thread if not already running.
- **Monitor notifications** — Use same `render/render-reply` for consistent card formatting.

## Architecture

```
User → Telegram → bot.clj → orchestrator.clj → policy.clj (classify)
                              │                    │
                              │              search.clj → lalafo/mashina
                              │                    │
                              │              LLM curator (pick 5 items + prose)
                              │                    │
                              │              render.clj → Telegram HTML
                              │
                         :unknown fallback → core.clj (full LLM agent)
                              │
                         clj-harness (streaming, sessions, tools)
```

**Three-layer architecture (tg-agent):**
1. **Intent layer** (LLM): `intent.clj` — classifies conversational intent when regex can't. Routes to search, research, followup, compare, refine, or chat.
2. **Deterministic layer** (search + parse + render): query_builder, lalafo, mashina, search.clj, render.clj — owns all trust-critical facts (prices, URLs, cards).
3. **Agent layer** (LLM): curator picks 5-6 best items + writes intro/CTA. Research mode synthesizes market data from monitor DB. Followup answers questions about shown items.
4. **Transport layer** (Telegram): bot.clj — session state, UX, progress messages, structured payload → HTML.

**Key insight:** Regex handles fast paths (greetings, direct searches). Everything else goes through LLM intent classification — so "which is better" after an iPhone search becomes a followup, not a search for "which is better". LLM never touches prices, URLs, or card layout. Zero hallucinated facts.

## File Map

| File | Lines | Purpose |
|------|-------|---------|
| **intent.clj** | ~75 | LLM-powered intent classifier (search/research/followup/compare/refine/chat) |
| **orchestrator.clj** | ~540 | The glue: policy → intent → mode handler → structured reply. Contains do-search, do-research, do-followup, do-chat, compare-products |
| **policy.clj** | ~110 | Deterministic fast paths (greeting/reset/help/tracking/thanks) + regex for search/compare/refine |
| **search.clj** | ~200 | Structured search pipeline — cards out, no LLM in search path |
| **render.clj** | ~180 | Deterministic card renderer — multi-mode: :shortlist, :research, :followup, :compare |
| `query_builder.clj` | ~340 | NL→structured params: price, platform, category extraction |
| `core.clj` | ~460 | Agent: system prompt, smart_search tool, pre-hook, REPL (fallback path) |
| `lalafo.clj` | ~380 | Direct Lalafo.kg HTTP client + Exa research + healthcheck |
| `mashina.clj` | ~200 | Mashina.kg API client (public API, no auth needed) |
| `bot.clj` | ~840 | Telegram bot: orchestrator dispatch, tracking UI, streaming fallback |
| `server.clj` | ~65 | Entry point: bot + monitor auto-start + healthcheck |
| `tg/format.clj` | ~17 | Thin wrapper → `clj-harness.telegram.format` |
| `tg/channel.clj` | ~17 | Thin wrapper → `clj-harness.telegram` |
| **monitor/store.clj** | ~260 | SQLite: categories, items, price snapshots + queries |
| **monitor/scanner.clj** | ~150 | Background Lalafo scanner (every 4h), accessory filter |
| **monitor/api.clj** | ~265 | Ring/Jetty HTTP API (:8787): trending, deals, search, history |
| **monitor/client.clj** | ~130 | HTTP client for monitor API (used by bot) |
| **monitor/tracker.clj** | ~290 | User tracking notifications (uses render module) |
| **monitor/main.clj** | ~60 | Monitor standalone entry point |
| **test/tapalakbot/render_test.clj** | ~100 | Card renderer tests (11 tests, 34 assertions) |
| **test/tapalakbot/policy_test.clj** | ~90 | Turn classifier tests (10 tests, 37 assertions) |
| **test/tapalakbot/orchestrator_test.clj** | ~210 | Orchestrator tests with mocked search/LLM (7 tests, 40 assertions) |

## Marketplace Platforms

### Lalafo.kg (Primary)
- **Type**: General classifieds (cars, real estate, electronics, services)
- **API**: Direct HTTP, requires session management
- **Auth**: cf_clearance cookie via RiskBypass + Smartproxy
- **Coverage**: All KG regions, ~50K+ active listings

### Mashina.kg (Auto-focused)
- **Type**: Auto marketplace (cars, motorcycles, parts)
- **API**: Public REST API at `www.mashina.kg/api` — **no auth needed!**
- **Endpoints**: `/api/ads/listings`, `/api/categories`, `/api/ads/slug/{slug}`
- **Coverage**: 136K+ auto listings, rich attributes (year, engine, gearbox, mileage)
- **Query params**: `?q=hyundai&page=1&size=20`

### Platform Comparison

| Platform | Auth | API Type | Best For |
|----------|------|----------|----------|
| Lalafo.kg | RiskBypass + proxy | REST | General search, real estate |
| Mashina.kg | None | Public REST | Auto search, price comparison |

## Key Design Decisions

- **tg-agent architecture** — Deterministic search + LLM curation + deterministic rendering. LLM never touches trust-critical facts.
- **Structured reply contract** — `{:mode :shortlist :cards [...] :intro "..." :cta "..." :assumptions [...]}`. Transport layer renders from structured data.
- **Turn classifier** — `policy.clj` classifies intent deterministically before LLM. Search goes through orchestrator, unknown falls back to full LLM agent.
- **Monitor notifications** — Use same `render/render-reply` for consistent card formatting.
- **DeepSeek** (`:deepseek-v4-pro`) — adequate Russian + tool calling. Token from `pass deepseek-api/token`. Config: `resources/config.edn` models map.
- **clj-harness** — middleware stack: core-agent → wrap-tools → wrap-retry → wrap-logging. `:nudges` requires tools before final answers.
- **HTML parse_mode** — `render.clj` builds Telegram HTML deterministically, sent with `parse_mode="HTML"`.
- **Monitor in same JVM** — Avoids separate deployment. `server.clj` auto-starts monitor thread if not already running.

## Running (local/dev)

⚠️ Production runs on VPS — stop VPS service first (only ONE process per token).

```bash
cd /Users/sn/Projects/tapalakbot-v2

# Terminal test (one-shot, no Telegram)
clojure -M:run "роутер до 4000"

# Telegram bot locally (auto-starts monitor)
BOT_TOKEN='...' clojure -M:bot

# Monitor only (standalone, no Telegram)
clojure -M:monitor

# Run tests
clojure -M:test -d test/tapalakbot/render_test.clj test/tapalakbot/policy_test.clj test/tapalakbot/orchestrator_test.clj
```

## Testing

```bash
# All new tests (28 tests, 111 assertions)
clojure -M:test -d test/tapalakbot/render_test.clj test/tapalakbot/policy_test.clj test/tapalakbot/orchestrator_test.clj

# Individual test files
clojure -M:test -d test/tapalakbot/render_test.clj
clojure -M:test -d test/tapalakbot/policy_test.clj
clojure -M:test -d test/tapalakbot/orchestrator_test.clj
```

## Gotchas

### Deployment (NixOS VPS)

- **Telegram blocked in Russia** — Most `api.telegram.org` IPs blocked. Only `149.154.167.220` works. NixOS: `networking.extraHosts = "149.154.167.220 api.telegram.org";`
- **NixOS systemd PATH lacks git** — Clojure tools.deps needs `git` to resolve git deps. NixOS config must add `pkgs.git` to service `environment.PATH`. Without it: `Cannot run program "git": No such file or directory`.
- **`.cpcache` stale after deps change** — When deps.edn changes (new deps, SHA update), clear `.cpcache/` on VPS before restart: `rm -rf /opt/tapalakbot-v2/.cpcache`
- **Full SHA required** — tools.deps requires full SHA for git deps, not prefix. Use `git rev-parse HEAD` to get full SHA.

### Monitor

- **Monitor DB in /tmp** — SQLite DB at `/tmp/tapalakbot-monitor.db`. Lost on reboot (intentional — fresh scan on restart).
- **Initial scan takes ~30s** — First startup blocks while scanning 10 categories.
- **Deals need 2+ scans** — `/prices/deals` shows items 20%+ below average. Empty until at least 2 scan cycles complete.

### General

- **Java (?i) doesn't work for Cyrillic** — `policy.clj` lowercases input before regex matching. Never use `(?i)` with Russian text.
- **clj-harness pinned** — git dep SHA in deps.edn. `:local/root` for dev, git SHA for deploy.
- **Only ONE process per bot token** — Two pollers = 409 Conflict.
- **Lalafo search noise** — Generic queries return junk. Use exact model names.
