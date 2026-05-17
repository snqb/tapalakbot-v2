<!-- Updated: 2026-05-18 -->
# tapalakbot-v2

> Clojure Telegram bot for Lalafo.kg marketplace search. DeepSeek LLM + Python lalafo-client via shell tools. Progressive streaming, HTML formatting, anti-table safety net, 60 QA cycles.

## Architecture

```
User → Telegram → bot.clj → core.clj → clj-harness v2.0.0 → DeepSeek API
                         │         │
                    clj-harness   └─ shell-tool
                    (telegram,         │
                     streaming,   lalafo_cli.py → LalafoClient
                     format)
```

Most Telegram/format/streaming logic lives in `clj-harness`. Tapalakbot files are thin app-layer wires.

| File | Lines | Purpose |
|------|-------|---------|
| `core.clj` | ~386 | Agent: system prompt, 3 tools, shell-tool, pre-hook, REPL |
| `bot.clj` | ~74 | Telegram bot: polling loop, handler, `/start` `/help` `/reset` |
| `server.clj` | ~32 | Entry point: bot + REPL |
| `tg/format.clj` | ~17 | Thin wrapper → `clj-harness.telegram.format` |
| `tg/channel.clj` | ~17 | Thin wrapper → `clj-harness.telegram` |
| `lalafo_cli.py` | ~140 | Python CLI: search, categories, research |
| `cycle_tuner.py` | ~380 | Automated QA: send queries, score responses, apply prompt fixes |

**Deleted** (v2): `tg/streaming.clj` — streaming now in clj-harness.telegram.streaming.

## Key Decisions

- **DeepSeek** (`:deepseek-v4` → API model `deepseek-chat`) — 10× cheaper than Claude, adequate Russian + tool calling. Token from `pass deepseek-api/token`. Config: `resources/config.edn` models map.
- **Python CLI, not rewrite** — Lalafo client stays in Python. Called via `uv run python` from tapalakbot dir.
- **clj-harness middleware stack** — core-agent → wrap-tools → wrap-retry → wrap-logging
- **HTML parse_mode** — `tg/format.clj` converts LLM markdown to HTML, sent with `parse_mode="HTML"`. Telegram handles entities natively.
- **Thinking indicator** — typing → "🧠 Думаю..." → LLM → edit-message. Falls back to delete+send for multi-chunk responses.
- **Sessions on atoms** — Per-user dialog history. Follow-up context preserved. Lost on restart (no SQLite — tracked as P0 gap).
- **Category pre-hook** — Live category tree injected into system prompt before each message.

## Prompt Tuning (v3, settled)

Key rules from 3-cycle test-fix loop + 60 auto-tune queries:

1. **Search aggressively** — If query has price, brand, OR feature → search NOW.
2. **NEVER tables** — `| --- |` breaks on Telegram mobile. 3 anti-table rules in prompt + safety net in `format.clj`.
3. **Search by MODEL NAMES** — "Samsung S6 Lite S Pen" not "планшет стилус". Lalafo keyword search is noisy.
4. **Response format** — Price tiers (🔥 💰 💎), 5-8 listings, lalafo.kg link for EVERY item, 3500 chars max.
5. **40 items from CLI** — per_page=60, top 40 sent to LLM for curation.

## Tools

1. **search_lalafo** — Multi-query parallel search. Returns 30-40 items, LLM curates 5-8.
2. **browse_categories** — Live category tree with Russian hints.
3. **research_topic** — Exa web search for factual questions.

## Lalafo Search Quality

Lalafo keyword search is noisy. Only model-specific queries return relevant results:
- Tablets with stylus: "Samsung S6 Lite S Pen", "Wacom", "Redmi Pad Smart Pen"
- Generic "планшет стилус" → junk (Samsung phones, car parts)

## Running

```bash
cd /Users/sn/Projects/tapalakbot-v2

# Telegram bot (tmux) — uses absolute path (no $HOME expansion issues)
tmux new-session -d -s tapalakbot-v2 -c ~/Projects/tapalakbot-v2
tmux send-keys -t tapalakbot-v2 \
  "BOT_TOKEN='...' TAPALAKBOT_BASE_DIR=\"\\\$HOME/Projects/tapalakbot\" clojure -M:bot 2>&1 | tee /tmp/tapalakbot-v2.log" Enter

# Terminal test
TAPALAKBOT_BASE_DIR=$HOME/Projects/tapalakbot clojure -M:run "роутер до 4000"
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

- **`$HOME` in tmux requires double quotes** — `TAPALAKBOT_BASE_DIR='$HOME/...'` blocks shell expansion; Java's `System/getenv` receives literal `$HOME`. Must use escaped double quotes in tmux send-keys: `TAPALAKBOT_BASE_DIR=\"\\$HOME/Projects/tapalakbot\"`.
- **Only ONE process per bot token** — If another process polls the same token, both break.
- **TAPALAKBOT_BASE_DIR** must point to Python tapalakbot project for `uv run`.
- **Lalafo `totalCount` in `_meta`** — Not in response root.
- **String quoting** — `"` inside Clojure strings must be escaped as `\"`. Check with `clojure -M -e` after every prompt edit.
- **Table stripping safety net** — `format.clj` regex-strips `| --- |` even if LLM ignores anti-table rules.
- **Session lost on restart** — SQLite persistence exists in clj-harness, not yet wired in tapalakbot.
- **clj-harness pinned to v2.0.0** — git dep with SHA in deps.edn. Use `:local/root` for dev edits, switch back to git tag for deploy.
- **Reset command** — `/reset` clears session via `h/reset-session!`. Keyboard button "🔄 Новый диалог" on responses.
- **Session context contamination** — Search results from previous queries accumulate and LLM reuses them for new topics. Fix: scope search results per-query, clear on next user message.
- **Warmup needed after startup** — First 1-2 queries after restart get 0 links (JVM + category tree loading). `cycle_tuner.py` has warmup fix.
- **Lalafo page titles** — HTML `<h1>` shows breadcrumbs ("Ультрабук, Б/у, Intel Core i5"), not the actual listing title. Use `og:title` meta tag for the real title.
