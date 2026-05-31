<!-- Updated: 2026-06-01 -->
# tapalakbot-v2

> Clojure Telegram bot for Lalafo.kg marketplace search. DeepSeek LLM + native Clojure HTTP client. Progressive streaming, HTML formatting, anti-table safety net, 53 test scenarios. **Price monitor** tracks 10 categories, serves market intelligence via HTTP API. **Deployed: NixOS VPS 85.239.40.192 (systemd).** See [docs/deployment.md](docs/deployment.md).

## Architecture

```
User → Telegram → bot.clj → core.clj → clj-harness → DeepSeek API
                         │         │
                    clj-harness   └─ lalafo.clj (direct HTTP)
                    (telegram,         ├─ search → Lalafo API
                     streaming,        ├─ categories → Lalafo API
                     format)           └─ exa-research → Exa API

                         ┌─────────────────────────────────────┐
                         │  Price Monitor (same JVM process)   │
                         │                                     │
                         │  scanner.clj → lalafo.clj (search)  │
                         │       ↓                             │
                         │  store.clj → SQLite (/tmp/*.db)     │
                         │       ↓                             │
                         │  api.clj → Ring/Jetty :8787         │
                         │       ↓                             │
                         │  client.clj ← bot.clj (/start, /prices) │
                         └─────────────────────────────────────┘
```

Most Telegram/format/streaming logic lives in `clj-harness`. Tapalakbot files are thin app-layer wires. The monitor is a background service embedded in the same JVM — started by `server.clj` via `ensure-monitor!`.

## File Map

| File | Lines | Purpose |
|------|-------|---------|
| `core.clj` | ~450 | Agent: system prompt, smart_search tool, pre-hook, REPL |
| `lalafo.clj` | ~380 | Direct Lalafo.kg HTTP client + Exa research + healthcheck |
| `bot.clj` | ~140 | Telegram bot: handler, `/start` `/help` `/prices`, streaming agent |
| `server.clj` | ~65 | Entry point: bot + monitor auto-start + healthcheck |
| `tg/format.clj` | ~17 | Thin wrapper → `clj-harness.telegram.format` |
| `tg/channel.clj` | ~17 | Thin wrapper → `clj-harness.telegram` |
| **monitor/store.clj** | ~260 | SQLite: categories, items, price snapshots + queries |
| **monitor/scanner.clj** | ~150 | Background Lalafo scanner (every 4h), accessory filter |
| **monitor/api.clj** | ~265 | Ring/Jetty HTTP API (:8787): trending, deals, search, history |
| **monitor/client.clj** | ~130 | HTTP client for monitor API (used by bot) |
| **monitor/main.clj** | ~60 | Monitor standalone entry point |
| **test/tapalakbot/test_scenarios.clj** | ~450 | 53 test scenarios for agent testing |

## Key Decisions

- **DeepSeek** (`:deepseek-v4-pro`) — adequate Russian + tool calling. Token from `pass deepseek-api/token`. Config: `resources/config.edn` models map.
- **Direct Clojure search + categories + research** — All tools use Java HttpClient directly. Zero Python shell-outs.
- **clj-harness middleware stack** — core-agent → wrap-tools → wrap-retry → wrap-logging. `:nudges` requires `smart_search` before final answers.
- **HTML parse_mode** — `tg/format.clj` converts LLM markdown to HTML, sent with `parse_mode="HTML"`.
- **Progressive streaming** — `handle-message-stream!` with phase tracking (`:initial` → `:streaming` → `:tool` → `:done`). Debounced edits (max once per 1500ms). Status callback for phase changes (🧠 thinking → 🔧 tool).
- **Monitor in same JVM** — Avoids separate deployment. `server.clj` auto-starts monitor thread if not already running.
- **Accessory filter** — `scanner.clj` excludes чехол, зарядка, ремонт, установка, etc. from monitor results. Price cap: 500K KGS.

## Tools

### smart_search (single tool for all purchase queries)

Handles the full pipeline internally:
1. **LLM query generation** — Generates 4-6 optimal search queries from user intent
2. **Optional research** — For niche products, researches model names first
3. **Lalafo search** — Searches with generated queries
4. **Returns curated results** — Formatted for Telegram

**Retry logic:** DeepSeek sometimes returns empty content for query generation. 3-attempt retry with fallback to raw user query.

**Nudge:** `:required-steps ["smart_search"]` — agent must call smart_search before final answer for purchase queries.

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

# Run test suite
clojure -M -e '(require (quote [tapalakbot.test-scenarios :as ts])) (ts/run-all-tests)'
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

- **DeepSeek empty responses** — DeepSeek sometimes returns `""` for query generation. 3-attempt retry with fallback to raw user query.
- **Healthcheck at startup** — Bot runs `lalafo/smoke-test` on boot. Logs `:healthcheck-pass` or `:healthcheck-fail`.
- **Local dev deps.edn** — Uses `:local/root` for clj-harness. Don't commit this — git SHA in committed tree.
- **Only ONE process per bot token** — Two pollers = 409 Conflict.
- **Table stripping safety net** — `bot.clj` regex-strips `| --- |` even if LLM ignores rules.
- **clj-harness pinned** — git dep SHA in deps.edn. `:local/root` for dev, git SHA for deploy.
- **Lalafo search noise** — Generic queries return junk. Use exact model names.
