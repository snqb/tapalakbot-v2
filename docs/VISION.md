# TapalakBot VISION — Very Fucking Smart

## The goal

A marketplace intelligence agent that doesn't just search — it *understands the market*.
Users don't ask for listings. They ask for *decisions*.
The bot should give them the data, reasoning, and confidence to make one.

## What "smart" means

### 1. Research-first, not search-first

When a user asks about an unfamiliar product, the bot researches BEFORE searching.
It learns model names, variants, typical prices, and market context.
Then it searches with precision — not with generic guesses.

```
User: "нужен роутер для большого дома"
Bot:  → researches "best router large house 2026 Kyrgyzstan available models"
      → learns: TP-Link Archer AX, Xiaomi AX, Keenetic
      → checks market prices for routers
      → searches each model on Lalafo
      → "Для большого дома нужен роутер с поддержкой Mesh.
         3 модели на рынке: TP-Link Archer AX (~3500-5000),
         Xiaomi AX (~2500-4000), Keenetic (~6000-9000).
         Вот что есть сейчас: ..."
```

### 2. Market-aware, not listing-aware

The bot knows the *market*, not just the *listings*.
It uses the monitor DB to understand pricing trends, availability, and seasonality.
Every answer includes market context: "this is above market average" or "prices are rising."

```
User: "iphone 13 до 30000"
Bot:  → checks market: avg 28000, trending down, good availability
      → "iPhone 13 в среднем 28000 сом. Ваш бюджет 30000 — выше рынка, хорошие варианты.
         Рынок падает (-8% за месяц), можно торговаться. Вот 12 вариантов: ..."
```

### 3. Multi-source, cross-referenced

No single platform tells the full story.
The bot cross-references Lalafo, Mashina, Bazar, and web research.
It flags discrepancies: "Bazar is cheaper than Lalafo for this item."

```
User: "ноутбук для работы"
Bot:  → researches best work laptops under budget
      → scans Lalafo, Mashina, Bazar
      → "На Bazar.kg на 15% дешевле чем на Lalafo.
         Топ-3 модели: ThinkPad T480 (15000-25000), MacBook Air M1 (35000-50000),
         Dell Latitude (12000-20000). Рынок стабилен."
```

### 4. Decision support, not information dump

The bot doesn't just list items.
It compares, recommends, warns, and reasons.
It says "this is a good deal" or "wait, prices are dropping" or "avoid this seller."

```
User: "наушники sony до 5000"
Bot:  → "Sony WH-1000XM3 — лучший вариант в бюджете. На рынке 8 шт, средняя 4200.
         🔥 Sony WH-1000XM3, #A — 3500 сом, отличное состояние, проверенный продавец
         ⚠️ Sony WH-1000XM4, #B — 4800 сом, цена ниже рынка на 40%, ВЕРОЯТНО ПОДДЕЛКА
         📊 Sony WF-1000XM3 (вкладыши), #C — 3000 сом"
```

### 5. Stateful conversations

The bot remembers what you asked about.
It can compare across conversations.
"Этот вариант дешевле того, что ты смотрел вчера."

```
Session 1: "macbook до 40000"
Session 2: "а что лучше этот или тот за 35000?"
Bot:      → remembers Session 1 context
          → "Тот MacBook Pro 2017 за 35000 — лучшее соотношение цена/качество.
             Процессор на 30% быстрее, чем у варианта за 40000 из прошлого поиска."
```

### 6. Proactive intelligence

The bot doesn't just respond — it *advises*.
When it detects a bad deal, it warns.
When it finds a pattern, it tells you.
When it's uncertain, it says so.

```
User: "продаю iphone 13, за сколько выставить?"
Bot:  → checks market price distribution
      → "Рынок: 22000-35000 сом, медиана 28000.
         За 25000 продашь за 2-3 дня. За 30000 — за неделю.
         Сейчас 34 конкурента, рынок насыщен. Рекомендую 26000 для быстрой продажи."
```

### 7. Scam and quality awareness

The bot knows when a listing looks suspicious.
Price too low? Marked as potential scam.
No photos? Flagged.
Seller has bad history? Warned.

```
Bot: "⚠️ iPhone 13 за 15000 — цена на 50% ниже рынка. Вероятно мошенничество.
     У продавца новый аккаунт, нет отзывов. Не рекомендую."
```

---

## Architecture to achieve this

### Tools (replacing the monolithic smart_search)

```
┌─────────────────────────────────────────────────────┐
│  Agent (LLM)                                        │
│                                                     │
│  Tools available at every turn:                      │
│                                                     │
│  🔬 research(topic)                                  │
│     Exa/Serper web search → structured knowledge     │
│     "what models of headphones exist under 5000?"    │
│                                                     │
│  📊 market_stats(category, metric)                    │
│     Monitor DB → price distribution, trends, volume  │
│     "what's the average price of планшеты?"          │
│                                                     │
│  🔍 search(query, platform, filters)                  │
│     Lalafo + Mashina + Bazar → curated results       │
│     "search iphone 13 price 20000-35000"             │
│                                                     │
│  📈 price_history(product_id)                         │
│     Monitor DB → price over time for specific items   │
│     "has this listing dropped in price?"             │
│                                                     │
│  🛡️ check_seller(seller_id)                          │
│     Seller reputation, listing history, scam risk    │
│     "is this seller trustworthy?"                    │
│                                                     │
│  💡 suggest(alternatives to X, budget Y)              │
│     LLM-driven with market data → recommendations     │
│     "what's like iPad but cheaper?"                  │
└─────────────────────────────────────────────────────┘
```

### Data plane

```
Monitor DB (price history, categories, trends)
     ↓
Agent has real-time market intelligence for:
  - "средняя цена" → actual average from DB
  - "цена падает" → trend detection from time series
  - "хорошая сделка" → percentile analysis
  - "много конкурентов" → inventory counts
```

### Streaming UX

```
🧠 Исследую "роутер для большого дома"...
   → research tool running
📊 Проверяю рыночные цены на роутеры...
   → market_stats tool running (concurrent!)
🔍 Ищу TP-Link Archer AX на Lalafo...
   → search tool running
💬 Формирую рекомендации...
   → LLM synthesizing results
```

### Guardrails

```
Required: research → market_stats → search (any order, all required before answer)
Terminal: answer (only after all three complete)
Budget: max 8 turns, 3 retries per tool
Steering: if LLM tries to answer without research → inject correction
```

---

## Migration path (3 phases)

### Phase 1 — Split the monolith (1 session)
- Replace `smart_search` with 3 tools: `research`, `market_stats`, `search`
- LLM calls them in sequence, guardrails enforce order
- Same user experience, richer underlying data
- **Risk:** LLM might call wrong tool. Guardrails catch it.

### Phase 2 — Add intelligence (1 session)
- Price percentile analysis ("это выше рынка на 20%")
- Trend detection ("цены падают")
- Cross-platform comparison
- Scam heuristics (price < 40% of avg → flag)
- **Risk:** Monitor DB must have enough data. Current scanner runs every 4h.

### Phase 3 — Proactive + Stateful (2 sessions)
- Session memory across conversations
- Proactive warnings (scam, bad deal, market shift)
- Custom recommendations ("что-то типа X но дешевле")
- Seller reputation
- **Risk:** Requires session persistence (done) + monitor DB growth.

---

## What doesn't change

- Lalafo/Mashina/Bazar HTTP clients — unchanged
- QueryBuilder — used internally by search tool
- Monitor — scanner, store, API — unchanged (just queried by new tools)
- Telegram integration — format, streaming, tracking — unchanged
- clj-harness — event bus, guardrails, snapshot persistence — unchanged

---

## Success metrics

| Metric | Current | Target |
|--------|---------|--------|
| Answer quality | "here are 12 listings" | "this is a good deal because X" |
| Research depth | 0 (no web research displayed to user) | LLM cites research findings |
| Market context | 0% of answers include market data | 80% include price context |
| Scam detection | None | Basic price-based heuristics |
| Cross-platform | Manual (all in one search) | Explicit comparison with reasoning |
| Speed (3 tools) | N/A | <8s (tools run concurrently) |
