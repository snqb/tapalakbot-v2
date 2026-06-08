# Make tapalakbot World-Class tg-Agent

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Transform tapalakbot from "working prototype" to "world-class Telegram marketplace bot" — scannable cards, trustworthy tiers, progressive UX, smart refinement, and proper session memory.

**Architecture:** Same tg-agent three-layer architecture. Changes are surgical improvements to each layer:
- Deterministic layer: better filtering, deterministic tiers from stats, smarter refine
- Agent layer: improved curator prompt with KG market context
- Transport layer: progressive loading, richer session state, transcript capture

**Tech Stack:** Clojure 1.12, deps.edn, clj-harness (git dep), SQLite, Java HttpClient.

---

## Problem Analysis

### Current Output (live test "роутер до 4000"):
```
💰 Продам адаптер Xiaomi USB Type-C – USB / HDMI (ZJQ01TM), белый — <b>1 000 KGS</b> — (lalafo)
    <a href="https://lalafo.kg/...">открыть</a>
```

### World-Class Output (target):
```
🔥 <b>Лучшая цена</b>

<b>Мобильный 4G WiFi роутер Huawei</b>
💰 2 500 сом · 📍 Бишкек
<i>Отличное состояние</i>
<a href="https://lalafo.kg/...">Открыть на Lalafo →</a>

━━━━━━━━━━━━━━━
```

### 7 Critical Gaps:
1. **Card rendering** — wall of text, no visual separation
2. **Accessory filter** — "адаптер" passes as router
3. **Tier assignment** — LLM decides pricing perception (violates tg-agent)
4. **Progress UX** — static "💭 ..." instead of progressive status
5. **Refine flow** — string concat doesn't actually refine
6. **Session state** — no budget/category/last-shown memory
7. **LLM curator prompt** — generic, no KG market context

---

## Phase 1: Card Rendering Redesign

The single biggest visual impact. Cards must be instantly scannable.

### Task 1.1: Redesign render-card layout

**Objective:** Each card is a multi-line block with bold title, prominent price, location chip, condition badge, and clear link CTA.

**Files:**
- Modify: `src/tapalakbot/render.clj`

**Current render-card** (single line):
```clojure
(defn render-card [{:keys [title price currency url platform condition year mileage city tier]}]
  (let [emoji (tier-emoji tier)
        price-s (format-price price)
        parts (cond-> [] ...)]
    (str (str/join " — " parts)
         (when url ...))))
```

**New render-card** (multi-line block):
```clojure
(defn render-card
  "Render a single card to Telegram HTML. Multi-line block format."
  [{:keys [title price currency url platform condition year mileage city tier desc]
    :or {currency "сом"}}]
  (let [emoji    (tier-emoji tier)
        price-s  (when price (str "<b>" (format-price price) " " (escape-html currency) "</b>"))
        ;; Detail chips: location, year, mileage, condition
        chips    (cond-> []
                   city     (conj (str "📍 " (escape-html city)))
                   year     (conj (str year " г."))
                   mileage  (conj (str (format-price mileage) " км"))
                   condition (conj (str "📋 " (escape-html condition))))
        chip-str (when (seq chips) (str/join " · " chips))
        ;; Desc snippet (first 80 chars)
        desc-snip (when (and desc (> (count desc) 10))
                    (let [d (subs desc 0 (min 80 (count desc)))]
                      (str "<i>" (escape-html d)
                           (when (> (count desc) 80) "…") "</i>")))]
    (str emoji " <b>" (escape-html title) "</b>\n"
         (when price-s (str price-s "\n"))
         (when chip-str (str chip-str "\n"))
         (when desc-snip (str desc-snip "\n"))
         (when (and url (not (str/blank? url)))
           (str "<a href=\"" url "\">Открыть на " (name platform) " →</a>")))))
```

**New render-cards** (with separators):
```clojure
(defn render-cards
  "Group cards by tier and render with headers and separators."
  [cards]
  (let [grouped (->> cards
                     (map #(assoc % :tier (or (:tier %) :good)))
                     (group-by :tier))]
    (str/join "\n\n"
              (for [tier tier-order
                    :let [group (get grouped tier)]
                    :when (seq group)]
                (str "<b>" (tier-headers tier) "</b>\n\n"
                     (str/join "\n\n━━━━━━━━━━━━━━━\n\n"
                               (map render-card group)))))))
```

**Step 2: Verify it loads**
```bash
clojure -M -e '(require (quote [tapalakbot.render :as r])) (println :ok)'
```

**Step 3: Test with sample data**
```bash
clojure -M -e '
(require (quote [tapalakbot.render :as r]))
(println (r/render-reply
  {:mode :shortlist
   :intro "📱 Нашёл 3 роутера на Lalafo.kg"
   :cards [{:title "Мобильный 4G WiFi роутер Huawei" :price 25000 :currency "сом"
            :url "https://lalafo.kg/1" :tier :great :city "Бишкек"
            :condition "отличное" :desc "Мобильный 4G wifi роутер для Mega, отличное состояние"}
           {:title "Портативный 4G/5G WiFi роутер MiFi" :price 1200 :currency "сом"
            :url "https://lalafo.kg/2" :tier :good :city "Бишкек"}
           {:title "Netgear Nighthawk M5" :price 35000 :currency "сом"
            :url "https://lalafo.kg/3" :tier :premium :city "Ош"}]
   :cta "Хотите портативный или домашний?"
   :assumptions ["Цены в сомах"]}))
'
```
Expected: Multi-line cards with bold titles, prominent prices, location chips, separators.

**Step 4: Update render_test.clj**
Add tests for new card format.

**Step 5: Commit**
```bash
git add src/tapalakbot/render.clj test/tapalakbot/render_test.clj
git commit -m "feat: world-class card rendering — multi-line blocks, separators, chips"
```

---

## Phase 2: Deterministic Tier Assignment

Tiers MUST be deterministic from market stats. LLM should never decide pricing perception.

### Task 2.1: Add deterministic tier assignment to orchestrator

**Objective:** Assign tiers based on price vs search stats average. Remove LLM tier overrides.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`
- Modify: `src/tapalakbot/render.clj` (assign-tier is already there)

**Step 1: In orchestrator.clj, replace LLM tier overrides with deterministic assignment**

Current (line 197-203):
```clojure
;; Apply tier overrides from curator
final-cards (mapv
             (fn [i card]
               (if-let [tier (get (:tiers curated) i)]
                 (assoc card :tier tier)
                 card))
             (:selected-idx curated)
             selected)
```

Replace with:
```clojure
;; Deterministic tier assignment from stats
final-cards (mapv
             (fn [card]
               (let [tier (render/assign-tier (:price card) (:avg stats))]
                 (assoc card :tier (or tier :good))))
             selected)
```

Do the same for the `:refine` path (line 221-222).

**Step 2: Simplify curator prompt — remove tier responsibility**

Update `curator-prompt` to remove tier assignment:
```clojure
(def ^:private curator-prompt
  "You are a marketplace assistant curator for a KG marketplace bot. Given search results, pick the best 5-8 items and write a brief Russian intro + CTA.

Return ONLY a JSON object:
{
  \"selected\": [0, 2, 4, 5, 7],
  \"intro\": \"Нашёл iPhone 13 на Lalafo.kg — 8 вариантов!\",
  \"cta\": \"Хотите сузить по бюджету или состоянию?\",
  \"assumptions\": [\"Цены в сомах\"]
}

Rules:
- selected: indices of best items (0-based) from the results list
- intro: 1 line, Russian, include platform name and count. Be specific about what you found.
- cta: 1 line suggestion for next action (filter by price, condition, location)
- assumptions: 0-2 lines about what you assumed
- Keep intro under 100 chars, CTA under 60 chars
- Items with lowest price are best value, highest price are premium
- Skip items that are clearly accessories or not the main product")
```

**Step 3: Remove `:tiers` from parse-curated-response**

Simplify `parse-curated-response` to not parse tiers.

**Step 4: Update tests**

**Step 5: Commit**
```bash
git add src/tapalakbot/orchestrator.clj
git commit -m "feat: deterministic tier assignment from stats — LLM no longer decides pricing"
```

---

## Phase 3: Accessory Filter Improvement

The current filter misses adapters, cables, cases. Need more coverage.

### Task 3.1: Expand accessory-bad-words and lower threshold

**Objective:** Catch more junk in search results.

**Files:**
- Modify: `src/tapalakbot/search.clj`

**Step 1: Expand the bad-words list**

Current (8 words):
```clojure
(def ^:private accessory-bad-words
  ["зарядк" "кабел" "чехол" "стекло" "ремонт" "установка"
   "обложк" "коробка" "настройк"])
```

New (expanded):
```clojure
(def ^:private accessory-bad-words
  "Words indicating accessory, service, or non-product listing."
  ["зарядк" "кабел" "чехол" "стекло" "ремонт" "установка"
   "обложк" "коробка" "настройк" "адаптер" "переходник"
   "плeнк" "защитн" "аксессуар" "запчаст" "комплект"
   "подароч" "упаков" "держател" "кронштейн" "стенд"
   "подставк" "сидень" "чехол" "накладк" "наклейк"
   "обтяжк" "перчатк" "шнур" "провод" "розетк"
   "переходник" " удлинител" "фотоаппарат" "видеокамер"
   "объектив" "фотовспышк" "штатив" "монопод"])
```

**Step 2: Lower threshold from 2 to 1**

Current: `(filterv #(<= (accessory-score (:title %)) 2) all-cards)`
New: `(filterv #(<= (accessory-score (:title %)) 1) all-cards)`

**Step 3: Add relevance scoring**

Add a simple relevance score based on title containing the search query:
```clojure
(defn relevance-score
  "Score how relevant a card is to the search query. Higher = more relevant."
  [title query]
  (let [t (str/lower-case (or title ""))
        q (str/lower-case (or query ""))]
    (if (str/includes? t q) 10 0)))
```

In the search pipeline, after dedup, sort by relevance:
```clojure
;; Sort by relevance (query match first)
sorted (sort-by #(- (relevance-score (:title %) query)) deduped)
```

**Step 4: Commit**
```bash
git add src/tapalakbot/search.clj
git commit -m "feat: expanded accessory filter + relevance scoring"
```

---

## Phase 4: Progressive Loading UX

World-class bots show what's happening, not just "💭 ...".

### Task 4.1: Add status callback to orchestrator

**Objective:** Orchestrator accepts a status-cb function and calls it at each stage.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`
- Modify: `src/tapalakbot/bot.clj` (handle-orchestrated)

**Step 1: Add status-cb to orchestrate**

```clojure
(defn orchestrate
  "Main entry point. Takes user message + session + optional status-cb.
   status-cb: (fn [status-text]) called for progressive loading updates."
  ([text session] (orchestrate text session nil))
  ([text session status-cb]
   (let [status (or status-cb (constantly nil))]
     (status "🔍 Ищу на Lalafo.kg...")
     (let [state (get-session-data session)
           mode  (policy/classify text state)]
       (case mode
         :search
         (let [_ (status "📊 Обрабатываю результаты...")
               {:keys [cards stats platforms query]}
               (search/search text {:use-llm? true})]
           (if (empty? cards)
             no-results-reply
             (do (status (str "✨ Подбираю лучшие из " (count cards) "..."))
                 (let [curated    (call-curator query cards stats)
                       selected   (mapv #(get cards %) (:selected-idx curated))
                       final-cards (mapv
                                    (fn [card]
                                      (let [tier (render/assign-tier (:price card) (:avg stats))]
                                        (assoc card :tier (or tier :good))))
                                    selected)]
                   (patch-session! session {:last-search  query
                                           :last-platforms platforms})
                   {:mode           :shortlist
                    :intro          (:intro curated)
                    :cards          final-cards
                    :cta            (:cta curated)
                    :assumptions    (:assumptions curated)
                    :platforms-used platforms
                    :query          query}))))
         ;; ... other modes
         )))))
```

**Step 2: Wire status-cb in bot.clj**

In `handle-orchestrated`, pass a status callback that edits the thinking message:

```clojure
(defn- handle-orchestrated
  [{:keys [chat-id user-id text] :as msg}]
  (let [uid (str "tg-" user-id)
        bot @t/tapalakbot
        session (clj-harness.core/get-or-create-session bot uid)
        thinking-msg-id (atom nil)
        ;; Status callback: edit the thinking message
        status-cb (fn [status-text]
                    (when-let [msg-id @thinking-msg-id]
                      (try
                        (tg/edit-message chat-id msg-id status-text :parse-mode nil)
                        (catch Exception _))))]
    ;; Show initial thinking
    (when-let [m (tg/send-message chat-id "💭 ..." :parse-mode nil)]
      (reset! thinking-msg-id (some-> m (get "result") (get "message_id"))))
    ;; Run orchestrator with status updates
    (let [reply (orch/orchestrate text session status-cb)]
      ;; ... render reply
      )))
```

**Step 3: Commit**
```bash
git add src/tapalakbot/orchestrator.clj src/tapalakbot/bot.clj
git commit -m "feat: progressive loading UX — status updates during search"
```

---

## Phase 5: Smart Refine Flow

"дешевле" should actually lower the price, not just append text.

### Task 5.1: Implement smart query refinement

**Objective:** Refine keywords modify search parameters, not just concatenate strings.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`

**Step 1: Add refine logic**

```clojure
(defn- apply-refine
  "Apply refine keyword to existing search state. Returns refined query + price adjustments."
  [last-search refine-text state]
  (let [t (str/lower-case refine-text)]
    (cond
      ;; Price adjustments
      (some #(str/includes? t %) ["дешевле" "подешевле" "поменьше"])
      (let [old-max (or (:last-price-max state) 999999)
            new-max (long (* old-max 0.7))]  ;; Lower by 30%
        {:query last-search
         :price-max new-max
         :assumptions [(str "Снизил бюджет до " (render/format-price new-max) " сом")]})

      (some #(str/includes? t %) ["дороже" "подороже" "получше"])
      (let [old-max (or (:last-price-max state) 999999)
            new-max (long (* old-max 1.5))]  ;; Raise by 50%
        {:query last-search
         :price-max new-max
         :assumptions [(str "Поднял бюджет до " (render/format-price new-max) " сом")]})

      ;; Location
      (some #(str/includes? t %) ["в бишкеке" "bishkek"])
      {:query (str last-search " Бишкек")
       :assumptions ["Фильтр: Бишкек"]}

      (some #(str/includes? t %) ["в оше" "osh"])
      {:query (str last-search " Ош")
       :assumptions ["Фильтр: Ош"]}

      ;; Condition
      (some #(str/includes? t %) ["только новые" "новые" "новый"])
      {:query (str last-search " новый")
       :assumptions ["Фильтр: новые"]}

      (some #(str/includes? t %) ["только б/у" "только бу" "б/у" "бу"])
      {:query (str last-search " б/у")
       :assumptions ["Фильтр: б/у"]}

      ;; Default: concatenate
      {:query (str last-search " " refine-text)
       :assumptions []})))
```

**Step 2: Use in orchestrate refine path**

```clojure
:refine
(let [last-search (or (:last-search state) text)
      refined (apply-refine last-search text state)
      {:keys [cards stats platforms query]}
      (search/search (:query refined) {:use-llm? true})]
  ;; ... rest of refine flow, using refined query and price adjustments
  )
```

**Step 3: Store price-max in session**

In the `:search` path, after search:
```clojure
(patch-session! session {:last-search  query
                        :last-platforms platforms
                        :last-price-max (:price-max qb-result)})
```

**Step 4: Commit**
```bash
git add src/tapalakbot/orchestrator.clj
git commit -m "feat: smart refine — price/location/condition adjustments"
```

---

## Phase 6: Session State Enrichment

Remember budget, category, last-shown cards for better context.

### Task 6.1: Expand session state schema

**Objective:** Track more context across turns.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`

**Step 1: Expand session state patching**

In `:search` path:
```clojure
(patch-session! session {:last-search     query
                        :last-platforms   platforms
                        :last-price-max   (:price-max qb-result)
                        :last-price-min   (:price-min qb-result)
                        :last-category    (cond
                                            (:is-auto? qb-result) :auto
                                            (:is-electronics? qb-result) :electronics
                                            (:is-real-estate? qb-result) :real-estate
                                            :else :general)
                        :last-card-count  (count final-cards)
                        :last-shown-ids   (mapv :url final-cards)})
```

**Step 2: Use category context in greeting/help**

In greeting-reply, if user has previous searches:
```clojure
(defn- greeting-reply-for-state
  "Personalized greeting based on session state."
  [state]
  (if (:last-search state)
    {:mode :shortlist
     :intro (str "👋 Салам! Возвращаемся к «" (:last-search state) "»?\n\n"
                 "Или напишите новый запрос!")
     :cards [] :cta nil :assumptions []}
    greeting-reply))
```

**Step 3: Commit**
```bash
git add src/tapalakbot/orchestrator.clj
git commit -m "feat: enriched session state — budget, category, last-shown tracking"
```

---

## Phase 7: LLM Curator Prompt Improvement

Better curation with KG market context and examples.

### Task 7.1: Improve curator prompt with market context

**Objective:** Curator knows about KG market specifics and produces better Russian copy.

**Files:**
- Modify: `src/tapalakbot/orchestrator.clj`

**Step 1: Update curator-prompt**

```clojure
(def ^:private curator-prompt
  "Ты куратор маркетплейса для бота по поиску товаров на Lalafo.kg (Кыргызстан).

Даны результаты поиска. Выбери лучшие 5-8 товаров и напиши краткое вступление + CTA на русском.

Верни ТОЛЬКО JSON:
{
  \"selected\": [0, 2, 4, 5, 7],
  \"intro\": \"Нашёл iPhone 13 на Lalafo.kg — 8 вариантов!\",
  \"cta\": \"Хотите сузить по бюджету или состоянию?\",
  \"assumptions\": [\"Цены в сомах\"]
}

Правила:
- selected: индексы лучших товаров (0-based)
- intro: 1 строка, русский, упомяни платформу и количество. Будь конкретен — «Нашёл роутеры Huawei» лучше «Нашёл варианты»
- cta: 1 строка — предложение следующего действия (фильтр по цене, состоянию, локации)
- assumptions: 0-2 строки о предположениях (валюта, состояние, регион)
- Товары с низкой ценой — лучшая цена, с высокой — премиум
- Пропускай товары, которые явно являются аксессуарами, а не основным товаром
- Если везультатах есть разнообразие (разные модели, состояния), упомяни это
- Вступление до 100 символов, CTA до 60 символов")
```

**Step 2: Commit**
```bash
git add src/tapalakbot/orchestrator.clj
git commit -m "feat: improved curator prompt — KG market context, Russian copy"
```

---

## Phase 8: Transcript Capture

The tg-agent skill requires transcript capture for the replay loop.

### Task 8.1: Add transcript logging to bot.clj

**Objective:** Log every exchange for later review.

**Files:**
- Modify: `src/tapalakbot/bot.clj`

**Step 1: Add transcript logging function**

```clojure
(defn- log-transcript!
  "Log a transcript entry for later review."
  [user-id user-text reply mode]
  (try
    (let [entry {:user-id    user-id
                 :user-text  user-text
                 :mode       mode
                 :intro      (:intro reply)
                 :card-count (count (:cards reply))
                 :cta        (:cta reply)
                 :timestamp  (System/currentTimeMillis)}]
      (log/info :transcript entry))
    (catch Exception _)))
```

**Step 2: Call after each orchestrated response**

In `handle-orchestrated`, after rendering:
```clojure
(log-transcript! user-id text reply (:mode reply))
```

**Step 3: Commit**
```bash
git add src/tapalakbot/bot.clj
git commit -m "feat: transcript capture — log every exchange for replay"
```

---

## Phase 9: Tests

### Task 9.1: Update render_test.clj for new card format

**Files:**
- Modify: `test/tapalakbot/render_test.clj`

Add tests for:
- Multi-line card rendering
- Separator between cards
- Desc snippet truncation
- Location chip rendering

### Task 9.2: Add orchestrator_test.clj for refine flow

**Files:**
- Modify: `test/tapalakbot/orchestrator_test.clj`

Add tests for:
- `apply-refine` with price adjustments
- `apply-refine` with location
- `apply-refine` with condition

### Task 9.3: Run all tests

```bash
clojure -M:test -d test/tapalakbot/render_test.clj test/tapalakbot/policy_test.clj test/tapalakbot/orchestrator_test.clj
```
Expected: All tests pass.

---

## Phase 10: End-to-End Verification

### Task 10.1: Live smoke test

```bash
cd /Users/sn/Projects/tapalakbot-v2
clojure -M -e '
(require (quote [tapalakbot.orchestrator :as orch]))
(require (quote [tapalakbot.render :as r]))
(def session (atom {"data" {}}))
(let [reply (orch/orchestrate "роутер до 4000" session)]
  (println (r/render-reply reply)))
'
```

Expected:
- Multi-line cards with bold titles
- Deterministic tiers from stats
- No accessories in results
- Progressive status (if status-cb wired)
- Clean, scannable output

### Task 10.2: Test refine flow

```bash
clojure -M -e '
(require (quote [tapalakbot.orchestrator :as orch]))
(require (quote [tapalakbot.render :as r]))
(def session (atom {"data" {:last-search "роутер" :last-price-max 4000}}))
(let [reply (orch/orchestrate "дешевле" session)]
  (println (r/render-reply reply))
  (println "SESSION:" (get @session "data")))
'
```

Expected: Results with lower prices, session shows adjusted price-max.

---

## Summary: What Changed

| Area | Before | After |
|------|--------|-------|
| **Card format** | Single dense line | Multi-line blocks with bold title, price, chips, separators |
| **Tier assignment** | LLM decides | Deterministic from market stats |
| **Accessory filter** | 8 words, threshold 2 | 40+ words, threshold 1, relevance scoring |
| **Progress UX** | Static "💭 ..." | Progressive: "🔍 Ищу..." → "📊 Обрабатываю..." → "✨ Подбираю..." |
| **Refine flow** | String concat | Smart price/location/condition adjustments |
| **Session state** | 2 fields | 7+ fields (budget, category, last-shown, etc.) |
| **LLM curator prompt** | Generic English | KG market context, Russian, examples |
| **Transcript** | None | Full exchange logging |

### Expected Impact
- **Visual:** Cards go from wall-of-text to scannable blocks
- **Trust:** Tiers based on real market data, not LLM opinion
- **Correctness:** Accessory filter catches 5x more junk
- **Speed:** Progressive loading feels 2x faster
- **Continuity:** Refine actually works, session remembers context
