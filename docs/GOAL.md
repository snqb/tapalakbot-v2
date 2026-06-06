# TapalakBot — GOAL.md

> Vision: [VISION.md](VISION.md) — marketplace intelligence agent that doesn't just search, it *understands the market*.

---

## Testing methodology: simulation-first

Inspired by `test/tapalakbot/citation_simulation.clj` — a pattern that runs the full pipeline (search → format → LLM → citation) without the bot, capturing intermediate outputs for inspection.

For each milestone below, we build a `sim_{milestone}.clj` simulator that:
1. Runs real search/market tools against Lalafo API + Monitor DB
2. Injects a pre-written LLM response (ideal answer for the query)
3. Compares actual bot output against the ideal
4. Scores: search quality, market intelligence, reasoning, scam detection

10 canonical queries tested every milestone:
```
Q1  "найди iphone 13"                           — basic search
Q2  "роутер до 4000 сом"                         — price-filtered search
Q3  "стоит ли брать macbook air m1 за 35000?"    — value judgment
Q4  "что-то типа айпада но дешевле"              — alternatives
Q5  "самый дешевый тойота камри на рынке"        — market-wide comparison
Q6  "как выбрать подержанный ноутбук"            — advice + search
Q7  "наушники для бега до 3000"                  — niche category
Q8  "продаю iphone 13, за сколько выставить?"    — seller advice
Q9  "сравни цены на lalafo и bazar на телевизоры" — cross-platform
Q10 "какие цены на квартиры в бишкеке"           — real estate (no data, graceful)
```

---

## Milestone 1: Monolith baseline → MEASURE

**Goal:** Run all 10 queries through current bot. Capture outputs. Score.

| Metric | How measured |
|--------|-------------|
| Search accuracy | Do results match query intent? (manual) |
| Price awareness | Does it mention market price? |
| Reasoning | Does it explain *why* something is a good deal? |
| Completeness | Does it find all platforms? |
| Hallucination rate | Fake URLs, fake prices, fake listings? |
| Latency | Seconds from query to final answer |

**Deliverable:** `sim_baseline.clj` — runs all 10 queries, logs full output to `.git/reports/baseline-YYYYMMDD.md`

**Time:** 1 session

---

## Milestone 2: Split the monolith → 3 tools

**Goal:** Replace `smart_search` with `research`, `market_stats`, `search`.
LLM chooses which to call. Guardrails enforce: all three must complete before answer.

### New tools

```
🔬 research(topic)
   → Exa/Serper → structured knowledge
   "what models of headphones exist under 5000 som in Kyrgyzstan?"

📊 market_stats(category, metric)
   → Monitor DB → price distribution, trends
   "avg/min/max цена на ноутбуки, trend last 30 days"

🔍 search(query, platform, filters)
   → Lalafo/Mashina/Bazar → curated listings with URLs
   "поиск iphone 13 на lalafo цена 20000-35000"
```

### How it works

```
User: "стоит ли брать macbook air m1 за 35000?"
     ↓
LLM: calls research("macbook air m1 typical price Kyrgyzstan 2026")
     → learns: M1 released 2020, typical used price $400-600
     ↓
LLM: calls market_stats("ноутбуки", :price)
     → avg 28000, min 12000, max 80000
     ↓
LLM: calls search("macbook air m1", :lalafo, {:price_max 45000})
     → 8 listings with #A-#H tokens
     ↓
LLM: synthesizes: "MacBook Air M1 на рынке 25000-45000 сом.
     Ваш вариант за 35000 — выше среднего (28000). Но если в хорошем
     состоянии с зарядкой — нормально. Вот что есть: ..."
```

### Guardrails

```clojure
{:required-steps ["research" "market_stats" "search"]
 :terminal-tools #{"answer"}
 :steering-mode :all}  ;; steering queue active
```

### Testing

`sim_milestone2.clj`:
- Run all 10 queries with mock LLM
- Verify: all 3 tools called for purchase queries, research-only for advice
- Score: improvement in price awareness vs baseline

**Time:** 1-2 sessions

---

## Milestone 2.5: Concurrent tool execution

**Goal:** `research` and `market_stats` run in parallel (no data dependency).

This is where llx idea #7 (concurrent tool execution) becomes valuable.
Before: 3 tools × 2s each = 6s sequential
After: research || market_stats (2s) + search (2s) = 4s total

Implementation: clj-harness tool-loop supports concurrent execution when
tool defs have `:concurrent? true` and no mutual dependencies.

**Time:** 1 session

---

## Milestone 3: Market intelligence

**Goal:** Bot reasons about value, not just shows listings.

### New capabilities

| Capability | Data source | Example output |
|-----------|-------------|----------------|
| Price percentile | Monitor DB | "цена на 20% выше рынка" |
| Trend detection | Monitor DB time series | "цены падают, подожди неделю" |
| Deal scoring | Price vs market + condition | "🔥 отличная сделка (ниже рынка на 30%)" |
| Scam detection | Price < 40% of avg | "⚠️ вероятно мошенничество" |
| Cross-platform | Search all 3 | "Bazar дешевле Lalafo на 15%" |
| Seller check | Lalafo user profile | "продавец с 2019, 47 объявлений" |

### Testing

`sim_milestone3.clj`:
- Inject mock monitor DB with known prices
- Verify: scam detection triggers at 40% threshold
- Verify: deal scoring matches manual judgment
- Score: % of answers with market context (>80% target)

**Time:** 2 sessions

---

## Milestone 4: Proactive + Stateful

**Goal:** Bot remembers past conversations. Warns proactively.

### New capabilities

| Capability | How |
|-----------|-----|
| Cross-session memory | Session persistence already done. Add `session.context` for user preferences, past searches |
| Proactive warnings | Post-processing check: if any listing is scam-risk, append warning |
| Custom recommendations | `suggest(category, budget, exclude)` tool with LLM reasoning |
| Follow-up detection | "а что лучше этот или тот" → resolve "этот"/"тот" from session |

### Testing

`sim_milestone4.clj`:
- Simulate multi-turn: Q1 "найди iphone", Q2 "а что лучше этот или за 30000?"
- Verify bot resolves "этот" to listing from Q1
- Verify scam warning appears for suspicious listings

**Time:** 2 sessions

---

## Milestone 5: Evaluation harness

**Goal:** Automated regression testing for all 10 queries.

### What it does

```clojure
;; sim_regression.clj
(def queries [{:id :q1 :text "найди iphone 13" :expect {:tools-called #{"search"} ...}}
              {:id :q2 :text "роутер до 4000"   :expect {:tools-called #{"search"} ...}}
              ...])

(doseq [q queries]
  (let [result (simulate q)]
    (assert-tools-called (:tools-called result) (:expect q))
    (assert-no-hallucination (:output result))
    (assert-has-urls (:output result))
    (log-result q result)))
```

Run with `clojure -M test/tapalakbot/sim_regression.clj`

**Time:** 1 session

---

## Summary timeline

| Week | Milestone | Deliverable |
|------|-----------|-------------|
| 1 | M1: Baseline | 10-query output captured, scored |
| 1-2 | M2: 3-tool split | `research`, `market_stats`, `search` live |
| 2 | M2.5: Concurrent | Tools run in parallel, 40% latency reduction |
| 2-3 | M3: Intelligence | Price percentile, deals, scam detection |
| 3-4 | M4: Stateful | Cross-session memory, proactive warnings |
| 4 | M5: Regression | Automated 10-query test harness |

---

## What we already have (solid foundation)

- ✅ Event bus — typed events for streaming UX
- ✅ State snapshot — full session persistence
- ✅ Malli schemas — clean tool definitions
- ✅ Steering queue — guardrail separation
- ✅ Effects loop — pure state machine default
- ✅ Lalafo/Mashina/Bazar clients — production HTTP
- ✅ Monitor DB — price history, categories, trends
- ✅ QueryBuilder — NL → structured params
- ✅ Citation system — #A-#Z tokens → clickable links
- ✅ clj-harness — mature agent harness

---

## Risk register

| Risk | Impact | Mitigation |
|------|--------|-----------|
| LLM ignores guardrails, answers without tools | Bad answers | Steering queue + max retries already enforced |
| Monitor DB has insufficient data for trends | Weak market intelligence | Seed DB with historical data, lower thresholds |
| 3-tool split increases latency | Slow answers | M2.5 concurrent execution |
| LLM hallucinates market prices | Wrong advice | market_stats tool is deterministic (DB query, not LLM) |
| Cross-platform scraping breaks | Missing Bazar/Mashina | Fall back to Lalafo-only with warning |
