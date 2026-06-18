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
User → Telegram → bot.clj → core.clj (clj-harness agent loop):
    LLM sees conversation + tools (search, market_stats, research)
    → agent decides intent, calls tools as needed
    → tools return structured data (captured in dynamic vars)
    → agent generates conversational text
    ↓
bot.clj extracts captured cards → render.clj → deterministic HTML
    ↓
Telegram: agent text + rendered cards
```

**Agent-first architecture:**
1. **Agent layer** (clj-harness): LLM is the brain — sees full conversation, decides what tools to call, generates conversational text. Uses `wrap-tools` middleware for automatic tool calling loop.
2. **Deterministic layer** (search + render): search.clj, render.clj, query_builder.clj, lalafo.clj, mashina.clj — own all trust-critical facts (prices, URLs, cards).
3. **Transport layer** (Telegram): bot.clj — fast-path shortcuts for greetings/reset, thinking indicator, card rendering.

**Key insight:** The agent decides intent and drives the conversation. Tools do the heavy lifting. Cards are rendered deterministically from captured search results. LLM never touches prices or URLs.

## File Map

| File | Lines | Purpose |
|------|-------|---------|
| `core.clj` | ~700 | Agent: system prompt, tools (search/market_stats/research), ask-stream, card capture |
| `bot.clj` | ~780 | Telegram: agent dispatch, fast paths, card rendering, tracking UI |
| `render.clj` | ~180 | Deterministic card renderer — tier groups, formatted prices, Telegram HTML |
| `search.clj` | ~200 | Structured search pipeline — cards out, no LLM in search path |
| `query_builder.clj` | ~340 | NL→structured params: price, platform, category extraction |

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

# Telegram bot locally (polling mode, auto-starts monitor)
BOT_TOKEN='...' clojure -M:bot

# Telegram bot with webhooks (faster, needs public HTTPS URL)
BOT_TOKEN='...' WEBHOOK_URL='https://your-domain.com/webhook' WEBHOOK_PORT=8080 clojure -M:bot

# Monitor only (standalone, no Telegram)
clojure -M:monitor

# Run tests
clojure -M:test -n tapalakbot.render-test -n tapalakbot.policy-test
```

### Webhook mode

Set `WEBHOOK_URL` env var to enable webhooks (e.g. `https://your-domain.com/webhook`).
Optionally set `WEBHOOK_PORT` (default 8080). Without `WEBHOOK_URL`, falls back to polling.

On startup: calls Telegram `deleteWebhook` (clears stale), then `setWebhook`. If setWebhook fails, auto-falls back to polling.

Jetty serves POST `/webhook` (Telegram updates) and GET `/health` (healthcheck). Everything else 404s.

## Testing

```bash
# All core tests (24 tests, 86 assertions)
clojure -M:test -n tapalakbot.render-test -n tapalakbot.policy-test

# Individual test namespaces
clojure -M:test -n tapalakbot.render-test
clojure -M:test -n tapalakbot.policy-test
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
