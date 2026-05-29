<!-- Updated: 2026-05-29 -->
# tapalakbot-v2

> Clojure Telegram bot for Lalafo.kg marketplace search. DeepSeek LLM + Python lalafo-client via shell tools. Progressive streaming, HTML formatting, anti-table safety net, 60 QA cycles. **Deployed: NixOS VPS 85.239.40.192 (systemd).** See [docs/deployment.md](docs/deployment.md).

## Architecture

```
User → Telegram → bot.clj → core.clj → clj-harness v2.0.0 → DeepSeek API
                         │         │
                    clj-harness   ├─ tapalakbot.lalafo (direct HTTP)
                    (telegram,    │    └─ search → Lalafo API
                     streaming,   └─ shell-tool
                     format)           ├─ lalafo_cli.py → categories, research
                                      └─ (search REMOVED — now Clojure)
```

Most Telegram/format/streaming logic lives in `clj-harness`. Tapalakbot files are thin app-layer wires.

| File | Lines | Purpose |
|------|-------|---------|
| `core.clj` | ~540 | Agent: system prompt, 3 tools, pre-hook, REPL |
| `lalafo.clj` | ~230 | Direct Lalafo.kg HTTP client (search, categories, smoke test) |
| `bot.clj` | ~74 | Telegram bot: polling loop, handler, `/start` `/help` `/reset` |
| `server.clj` | ~43 | Entry point: bot + REPL + healthcheck |
| `tg/format.clj` | ~17 | Thin wrapper → `clj-harness.telegram.format` |
| `tg/channel.clj` | ~17 | Thin wrapper → `clj-harness.telegram` |
| `lalafo_cli.py` | ~140 | Python CLI: categories, research (search ported to Clojure) |
| `cycle_tuner.py` | ~380 | Automated QA: send queries, score responses, apply prompt fixes |

**Deleted** (v2): `tg/streaming.clj` — streaming now in clj-harness.telegram.streaming.

## Key Decisions

- **DeepSeek** (`:deepseek-v4` → API model `deepseek-chat`) — 10× cheaper than Claude, adequate Russian + tool calling. Token from `pass deepseek-api/token`. Config: `resources/config.edn` models map.
- **Direct Clojure search** — `tapalakbot.lalafo/search` calls Lalafo API directly via Java HttpClient. No Python shell-out, no subprocess deadlocks, no uv startup cost. Only categories & research remain as shell tools.
- **Python CLI, not rewrite** → **CHANGED**: Search ported to Clojure (May 2026). Python retained for categories & research.
- **clj-harness middleware stack** — core-agent → wrap-tools → wrap-retry → wrap-logging. `:nudges` is configured to require `search_lalafo` before final answers, so marketplace turns cannot answer from stale session memory.
- **HTML parse_mode** — `tg/format.clj` converts LLM markdown to HTML, sent with `parse_mode="HTML"`. Telegram handles entities natively.
- **Thinking indicator** — typing → "🧠 Думаю..." → LLM → edit-message. Falls back to delete+send for multi-chunk responses.
- **Sessions on atoms** — Per-user dialog history. Follow-up context preserved. Lost on restart (no SQLite — tracked as P0 gap).
- **Category pre-hook** — Live category tree injected into system prompt before each message.

## Prompt Tuning (v3, settled)

Key rules from 3-cycle test-fix loop + 60 auto-tune queries:

1. **Search always for marketplace turns** — Prompt says search aggressively, and `:nudges {:required-steps ["search_lalafo"]}` now enforces a current-turn `search_lalafo` call before final answers.
2. **NEVER tables** — `| --- |` breaks on Telegram mobile. 3 anti-table rules in prompt + safety net in `format.clj`.
3. **Search by MODEL NAMES** — "Samsung S6 Lite S Pen" not "планшет стилус". Lalafo keyword search is noisy.
4. **Response format** — Price tiers (🔥 💰 💎), 5-8 listings, lalafo.kg link for EVERY item, 3500 chars max.
5. **40 items from CLI** — per_page=60, top 40 sent to LLM for curation.

## Tools

1. **search_lalafo** — Multi-query parallel search. Required by `:nudges` before final agent answers; returns relevant items, LLM curates best listings.
2. **browse_categories** — Live category tree with Russian hints.
3. **research_topic** — Exa web search for factual questions.

## Lalafo Search Quality

Lalafo keyword search is noisy. Only model-specific queries return relevant results:
- Tablets with stylus: "Samsung S6 Lite S Pen", "Wacom", "Redmi Pad Smart Pen"
- Generic "планшет стилус" → junk (Samsung phones, car parts)

## Running (local/dev)

⚠️ Production runs on VPS — stop VPS service first (only ONE process per token).

```bash
cd /Users/sn/Projects/tapalakbot-v2

# Terminal test (one-shot, no Telegram)
TAPALAKBOT_BASE_DIR=$HOME/Projects/tapalakbot clojure -M:run "роутер до 4000"

# Telegram bot locally
BOT_TOKEN='...' TAPALAKBOT_BASE_DIR=$HOME/Projects/tapalakbot clojure -M:bot
```

## QA

- 20/20 terminal QA passed
- 20/20 Telegram QA passed
- 10/10 cycle-3 tests: all real URLs, price tiers, 4-9 links
- 60 auto-tune queries: 62% ≥ 30pts, 20% startup lag (warmup fix applied)
- **101/101 link validation (2026-05-16)**: 0% dead URLs across 20 isolated queries. See Link Quality section.

Reports: `.git/reports/qa-report-20260513.md`, `.git/reports/telegram-qa-report-20260513.md`, `.git/reports/cycle-tuning-20260514.md`, `.git/reports/link-check-v2-20260516-231725.jsonl`

Full architecture: `.git/reports/system-architecture-20260515.md`

## Link Quality (v2, 2026-05-16)

20-cycle isolated link validation (clean session per query):
- **101/101 URLs LIVE (200) — 0% dead rate**
- Each query ran against freshly restarted bot with empty session context
- 14/20 queries produced search results with URLs; 6/20 correctly didn't search (advice/greeting/vague)
- Every URL loads correctly. All page titles match bot claims (verified via Lalafo og:title meta tags, not HTML `<h1>` which shows breadcrumbs)

**Why earlier tests showed 63% dead:** Session context contamination. After 3+ queries, bot had 60+ items accumulated from previous searches. LLM reused stale session items instead of calling `search_lalafo` for new topics. Some items expired in the 10-15 minutes between fetch and check.

**Fix applied:** Restart bot between queries (clean session). The bot itself is correct — every URL it sends is a real, live listing. The issue is design: search results should be ephemeral (per-query), not mixed with persistent user context.

Report: `.git/reports/link-check-v2-20260516-231725.jsonl`

## Memory Architecture (decision)

Two-tier, not monolithic:

| Tier | What | Lifetime | Backend |
|------|------|----------|---------|
| **User context** | Preferences, dialog thread, price ranges | Persistent across restarts | SQLite (clj-harness) |
| **Search results** | Items from `search_lalafo` | **Per-query only** | Ephemeral atom, cleared on next user message |

Currently both are mixed in `session-state` atom → cross-contamination. Split them: persist user context, scope search results to active query.

## Gotchas

### Deployment (NixOS VPS)

- **Telegram blocked in Russia** — Most `api.telegram.org` IPs blocked. Only `149.154.167.220` works. NixOS: `networking.extraHosts = "149.154.167.220 api.telegram.org";`
- **proxychains useless with Java** — Java NIO bypasses LD_PRELOAD. Use /etc/hosts or ProxySelector.
- **uv sync --all-packages** — Plain `uv sync` skips workspace members. Must `--all-packages`.
- **packages symlink** — `lalafo_cli.py` resolves from `_basedir/packages/`. VPS: `/opt/tapalakbot-v2/packages → /opt/tapalakbot/packages`

### General

- **Only ONE process per bot token** — Two pollers = 409 Conflict. VPS ↔ local conflict.
- **TAPALAKBOT_BASE_DIR** must point to Python tapalakbot project for `uv run`.
- **Lalafo `totalCount` in `_meta`** — Not in response root.
- **String quoting** — `"` inside Clojure strings must be escaped as `\"`. Check with `clojure -M -e`.
- **Table stripping safety net** — `format.clj` regex-strips `| --- |` even if LLM ignores rules.
- **Session lost on restart** — SQLite persistence in clj-harness, not wired in tapalakbot yet.
- **clj-harness pinned** — git dep SHA in deps.edn. `:local/root` for dev, git tag for deploy.
- **Reset command** — `/reset` via `h/reset-session!`. Keyboard button "🔄 Новый диалог".
- **Session context contamination** — Old search results leak across queries. `:nudges` requires fresh `search_lalafo`. Fix: per-query scope.
- **Warmup needed** — First 1-2 queries after restart get 0 links (JVM + categories).
- **Lalafo page titles** — HTML `<h1>` shows breadcrumbs, use `og:title` for real title.
