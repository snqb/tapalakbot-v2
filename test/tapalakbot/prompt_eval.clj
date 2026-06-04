(ns tapalakbot.prompt-eval
  "Automated prompt evaluation harness.
   Tests all bot prompts against real Lalafo data with scoring.

   Usage: clojure -M -m tapalakbot.prompt-eval"
  (:require [tapalakbot.lalafo :as lalafo]
            [tapalakbot.monitor.tracker :as tracker]
            [clj-harness.llm :as llm]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ══════════════════════════ TEST SCENARIOS ══════════════════════════

(def scenarios
  "Test scenarios for each prompt type."
  {;; Query generation scenarios
   :query-gen
   [{:input "iPhone 13 до 30000"
     :expected-queries ["iPhone 13" "айфон 13" "Apple iPhone 13"]
     :expected-price-max 30000
     :bad-queries ["iPhone 15" "Samsung"]}

    {:input "macbook m1"
     :expected-queries ["MacBook M1" "макбук M1"]
     :expected-needs-research false
     :bad-queries ["iPhone" "Samsung"]}

    {:input "planshet so stilusom"
     :expected-queries ["планшет со стилусом" "tablet stylus"]
     :expected-needs-research true}

    {:input "router do 4000"
     :expected-queries ["роутер" "router" "маршрутизатор"]
     :expected-price-max 4000
     :bad-queries ["iPhone" "macbook"]}

    {:input "velosiped goriy"
     :expected-queries ["велосипед горный" "mountain bike"]
     :bad-queries ["iPhone" "macbook"]}]

   ;; Relevance filter scenarios (core.clj)
   :relevance-core
   [{:query "iPhone 13"
     :items [{"id" 100 "title" "iPhone 13 128GB" "price" 25000 "desc" "в хорошем состоянии"}
             {"id" 101 "title" "Чехол для iPhone 13" "price" 500 "desc" "силикон"}
             {"id" 102 "title" "Зарядка для iPhone" "price" 800 "desc" "быстрая зарядка"}
             {"id" 103 "title" "iPhone 13 Pro Max 256GB" "price" 35000 "desc" "новый"}
             {"id" 104 "title" "Стекло для iPhone 13" "price" 300 "desc" "защитное"}
             {"id" 105 "title" "Наушники AirPods" "price" 15000 "desc" "оригинал"}
             {"id" 106 "title" "iPhone 12 64GB" "price" 18000 "desc" "б/у"}
             {"id" 107 "title" "Чехол кожаный iPhone 13" "price" 1200 "desc" "натуральная кожа"}
             {"id" 108 "title" "iPhone 13 mini" "price" 22000 "desc" "отличное состояние"}
             {"id" 109 "title" "Кабель Lightning" "price" 200 "desc" "оригинальный Apple"}
             {"id" 110 "title" "iPhone 14 128GB" "price" 32000 "desc" "минимальные следы использования"}
             {"id" 111 "title" "Ремонт iPhone" "price" 3000 "desc" "замена экрана"}]
     :relevant-ids #{100 103 106 108 110}
     :irrelevant-ids #{101 102 104 105 107 109 111}}

    {:query "iPad"
     :items [{"id" 200 "title" "iPad Air M1" "price" 45000 "desc" "256GB"}
             {"id" 201 "title" "Чехол для iPad" "price" 2000 "desc" "клавиатура"}
             {"id" 202 "title" "iPad Pro 12.9" "price" 80000 "desc" "M2 чип"}
             {"id" 203 "title" "Apple Pencil" "price" 8000 "desc" "2 поколение"}
             {"id" 204 "title" "Планшет Android Samsung" "price" 15000 "desc" "Tab S8"}
             {"id" 205 "title" "iPad mini 6" "price" 35000 "desc" "64GB"}
             {"id" 206 "title" "Защитное стекло iPad" "price" 500 "desc" "10.2 дюйма"}
             {"id" 207 "title" "iPad 9 поколение" "price" 25000 "desc" "32GB"}]
     :relevant-ids #{200 202 205 207}
     :irrelevant-ids #{201 203 204 206}}

    {:query "IQOS"
     :items [{"id" 300 "title" "IQOS ILUMA" "price" 12000 "desc" "новый"}
             {"id" 301 "title" "Чехол для IQOS" "price" 1500 "desc" "кожаный"}
             {"id" 302 "title" "IQOS 3 Duo" "price" 8000 "desc" "б/у"}
             {"id" 303 "title" "Зарядка для IQOS" "price" 2000 "desc" "оригинальная"}
             {"id" 304 "title" "Табак для IQOS" "price" 400 "desc" "Marlboro"}
             {"id" 305 "title" "IQOS TEREA" "price" 500 "desc" "под заправку"}
             {"id" 306 "title" "Power bank IQOS" "price" 3000 "desc" "портативный"}]
     :relevant-ids #{300 302}
     :irrelevant-ids #{301 303 304 305 306}}]

   ;; Category matching scenarios (tracker)
   :category-match
   [{:input "помещение под кофейню"
     :expected-category-id 2067
     :expected-name "Restaurant and cafe rentals"}

    {:input "офис в аренду"
     :expected-category-id 2068
     :expected-name "Office rentals"}

    {:input "магазин аренда"
     :expected-category-id 2066
     :expected-name "Retail rentals"}

    {:input "iphone 15"
     :expected-category-id 110
     :expected-name "Apple iPhone"}

    {:input "ноутбук"
     :expected-category-id 118
     :expected-name "Laptops and Netbooks"}

    {:input "велосипед"
     :expected-category-id 262
     :expected-name "Bicycles"}

    {:input "квартира 2 комнаты"
     :expected-category-id nil}]})

;; ══════════════════════════ PROMPT VARIANTS ══════════════════════════

(def query-gen-variants
  "Different versions of the query generation prompt."
  {:v1-original
   "You are a search query generator for Lalafo.kg marketplace in Kyrgyzstan.
Given what a user wants to buy, generate optimal search queries.

Rules:
1. Generate 4-6 query variants with EXACT MODEL NAMES (not generic terms)
2. Include both English and Russian/Cyrillic variants when relevant
3. For well-known brands: use model numbers and names
   - iPhone 13 → [\"iPhone 13\", \"айфон 13\", \"Apple iPhone 13\"]
4. For niche products: research what models exist first
5. Price filters: extract min/max price if mentioned
6. Return ONLY a JSON object: {\"queries\": [...], \"price_min\": N|null, \"price_max\": N|null, \"needs_research\": bool, \"research_query\": \"...\"}
7. Set needs_research=true only for genuinely niche products where model names are unknown"

   :v2-simpler
   "Generate search queries for Lalafo.kg (Kyrgyzstan marketplace).

Input: what user wants to buy.
Output: JSON {\"queries\": [4-6 variants], \"price_min\": null, \"price_max\": number|null, \"needs_research\": false}

Rules:
- Include exact model names and brand names
- Mix English and Russian/Cyrillic: [\"iPhone 13\", \"айфон 13\"]
- Extract price if mentioned
- needs_research=true only for unknown niche products"

   :v3-russian-focused
   "Генератор поисковых запросов для Lalafo.kg (Кыргызстан).

Вход: что хочет купить пользователь.
Выход: JSON {\"queries\": [4-6 вариантов], \"price_min\": null, \"price_max\": number|null, \"needs_research\": false}

Правила:
- Используй точные названия моделей и брендов
- Миксуй английский и русский: [\"iPhone 13\", \"айфон 13\"]
- Извлекай цену если указана
- needs_research=true только для нишевых продуктов"})

(def relevance-core-variants
  "Different versions of the core relevance filter prompt."
  {:v1-detailed
   "You are a listing relevance filter for Lalafo.kg marketplace.
Given a list of listings and what the user is looking for, identify
which listings are ACTUALLY the product the user wants - not accessories,
chargers, cases, parts, services, or unrelated items.

Rules:
- Judge title AND description. Lalafo titles are often generic or wrong; the description may reveal the real product.
- Accessories (chargers, cables, cases, stylus-only) → NOT relevant
- Boxes, packaging, parts → NOT relevant
- Different product category entirely → NOT relevant
- Wrong brand/model when user asked for specific → NOT relevant
- Services/repairs → NOT relevant
- If user asked for router: chargers, antennas, modems → NOT relevant (unless they ARE routers)
- If user asked for phone: cases, screen protectors, chargers, boxes → NOT relevant
- If user asked for laptop: RAM sticks, chargers, bags, stickers → NOT relevant
- If user asked for iPad: ONLY actual Apple iPad tablets are relevant. Android tablets, graphic tablets, children tablets, keyboards, Apple Pencil/stylus-only, cases, glass, cables, hubs, monitors, phones → NOT relevant unless the description clearly says an actual Apple iPad tablet is included
- If user asked for IQOS/айкос/electronic cigarette: ONLY actual heating devices (Iluma, 3 Duo, Originals, IQOS device) are relevant. Cases, holders, chargers, lighters, power banks, cigarette cases, accessories → NOT relevant
- If user asked for generic tablet: actual tablets are relevant; accessories, graphic tablets, cases, cables, stylus-only → NOT relevant

Return ONLY a JSON array of relevant listing IDs. Nothing else.
Example: [113171780, 112908144, 111226783]"

   :v2-compact
   "Filter Lalafo listings by relevance. Return JSON array of relevant IDs only.

User wants: {query}
Listings: {listings}

Rules:
- Include: actual products matching user's intent
- Exclude: accessories, cases, chargers, cables, parts, services, repairs
- Exclude: wrong brand/model
- Exclude: different product category
- Title + description both matter
- Return: [id1, id2, ...] or []"

   :v3-category-aware
   "You are a relevance filter for Lalafo.kg marketplace listings.
Given a user's search intent and a list of items, return ONLY the indices (0-based) of items that are relevant.

Rules:
1. Include items that MATCH the user's intent - don't be overly strict
2. For 'кофейня/кафе помещение' → include any commercial space suitable for food service
3. For 'офис' → include any office space
4. For 'магазин' → include any retail space
5. For electronics/phones/cars → include matching products
6. EXCLUDE only: completely unrelated items, services, repairs, accessories
7. Return ONLY a JSON array of indices, e.g. [0, 2, 4]
8. If 3+ items look relevant, return them all"})

(def category-match-variants
  "Different versions of the category matching prompt."
  {:v1-rules
   "You are a category matcher for Lalafo.kg marketplace in Kyrgyzstan.
Given a user's search intent, find the MOST SPECIFIC category_id from the category list.

Rules:
1. Pick the DEEPEST (most specific) leaf category
2. For 'кофейня/кафе помещение' → Restaurant and cafe rentals (2067)
3. For 'офис' → Office rentals (2068)
4. For 'магазин' → Retail rentals (2066)
5. For 'склад' → Warehouse and workshop rentals (2065)
6. If unsure, return null for category_id and use the original query as text search
7. Return ONLY a JSON object: {\"category_id\": number|null, \"category_name\": \"string\", \"text_query\": \"string\"}"

   :v2-simple
   "Match user search to Lalafo.kg category.

User wants: {query}
Available categories: {categories}

Pick the MOST SPECIFIC category. Return JSON:
{\"category_id\": number|null, \"category_name\": \"string\"}

Rules:
- Deepest leaf category preferred
- If no good match, return {\"category_id\": null}"})

;; ══════════════════════════ EVALUATION FUNCTIONS ══════════════════════════

(defn- call-llm
  "Call LLM with messages, return content string."
  [model messages & {:keys [max-tokens] :or {max-tokens 500}}]
  (try
    (let [resp (llm/llm model messages [] :provider :openrouter :max-tokens max-tokens)]
      (get-in resp ["choices" 0 "message" "content"]))
    (catch Exception e
      (log/warn :llm-call-failed :error (.getMessage e))
      nil)))

(defn- parse-json-response
  "Try to extract JSON from LLM response."
  [content]
  (when content
    (let [clean (str/replace (or content "") #"```json|```" "")
          json-str (or (second (re-find #"(?s)(\{.*\})" clean))
                       (second (re-find #"(?s)(\[.*\])" clean)))]
      (when json-str
        (try
          (json/parse-string (str/trim json-str) true)
          (catch Exception _ nil))))))

(defn eval-query-gen
  "Evaluate query generation prompt variant."
  [variant-key prompt scenario]
  (let [{:keys [input expected-queries expected-price-max expected-needs-research bad-queries]} scenario
        messages [{:role "system" :content prompt}
                  {:role "user" :content (str "User wants to buy: " input)}]
        content (call-llm :kimi-k2 messages :max-tokens 300)
        parsed (parse-json-response content)
        queries (vec (:queries parsed))
        score (atom 0)
        notes (atom [])]

    ;; Check query quality
    (doseq [eq expected-queries]
      (when (some #(str/includes? (str/lower-case %) (str/lower-case eq)) queries)
        (swap! score inc)
        (swap! notes conj (str "✓ found: " eq))))

    ;; Check bad queries
    (doseq [bq bad-queries]
      (when (some #(str/includes? (str/lower-case %) (str/lower-case bq)) queries)
        (swap! score dec)
        (swap! notes conj (str "✗ bad query: " bq))))

    ;; Check price extraction
    (when (and expected-price-max (= expected-price-max (:price_max parsed)))
      (swap! score inc)
      (swap! notes conj "✓ price_max correct"))

    ;; Check research flag
    (when (and expected-needs-research (= true (:needs_research parsed)))
      (swap! score inc)
      (swap! notes conj "✓ needs_research correct"))

    {:variant variant-key
     :scenario input
     :score @score
     :max-score (+ (count expected-queries) 2)
     :queries queries
     :parsed parsed
     :notes @notes}))

(defn eval-relevance-core
  "Evaluate core relevance filter prompt variant."
  [variant-key prompt scenario]
  (let [{:keys [query items relevant-ids irrelevant-ids]} scenario
        items-text (str/join "\n" (map-indexed
                                   (fn [i item]
                                     (str (inc i) ". [#" (get item "id") "] "
                                          (get item "title") " - " (get item "desc")))
                                   items))
        messages [{:role "system" :content prompt}
                  {:role "user" :content (str "User is looking for: " query "\n\nListings:\n" items-text
                                              "\n\nReturn JSON array of relevant listing IDs.")}]
        content (call-llm :kimi-k2 messages :max-tokens 300)
        parsed (parse-json-response content)
        returned-ids (set (if (vector? parsed) parsed (vec parsed)))
        true-positives (count (clojure.set/intersection returned-ids relevant-ids))
        false-positives (count (clojure.set/intersection returned-ids irrelevant-ids))
        false-negatives (count (clojure.set/difference relevant-ids returned-ids))
        precision (if (pos? (+ true-positives false-positives))
                    (double (/ true-positives (+ true-positives false-positives)))
                    0.0)
        recall (if (pos? (+ true-positives false-negatives))
                 (double (/ true-positives (+ true-positives false-negatives)))
                 0.0)
        f1 (if (pos? (+ precision recall))
             (double (* 2 (/ (* precision recall) (+ precision recall))))
             0.0)]
    {:variant variant-key
     :scenario query
     :precision precision
     :recall recall
     :f1 (if (pos? (+ precision recall)) (* 2 (/ (* precision recall) (+ precision recall)) 0) 0)
     :true-positives true-positives
     :false-positives false-positives
     :false-negatives false-negatives
     :returned-ids returned-ids
     :relevant-ids relevant-ids}))

(defn eval-category-match
  "Evaluate category matching prompt variant."
  [variant-key prompt scenario]
  (let [{:keys [input expected-category-id expected-name]} scenario
        categories-str (try (lalafo/search-categories input) (catch Exception _ "No categories"))
        messages [{:role "user" :content (str "Match this search to a Lalafo.kg category.\n\n"
                                              "User wants: " input "\n\n"
                                              "Matching categories:\n" categories-str "\n\n"
                                              prompt)}]
        content (call-llm :kimi-k2 messages :max-tokens 100)
        parsed (parse-json-response content)
        got-id (:category_id parsed)
        correct? (if expected-category-id
                   (= got-id expected-category-id)
                   (nil? got-id))]
    {:variant variant-key
     :scenario input
     :expected expected-category-id
     :got got-id
     :correct? correct?
     :category-name (:category_name parsed)}))

;; ══════════════════════════ MAIN EVALUATION ══════════════════════════

(defn run-evaluation
  "Run full evaluation across all scenarios and prompt variants."
  []
  (println "╔══════════════════════════════════════════════════════╗")
  (println "║  🔬 TapalakBot Prompt Evaluation Harness             ║")
  (println "╚══════════════════════════════════════════════════════╝")
  (println)

  ;; 1. Query Generation
  (println "═══ QUERY GENERATION ═══")
  (doseq [[variant-key prompt] query-gen-variants]
    (println (str "\n--- " variant-key " ---"))
    (doseq [scenario (:query-gen scenarios)]
      (let [result (eval-query-gen variant-key prompt scenario)]
        (println (str "  " (:scenario result) ": " (:score result) "/" (:max-score result)
                      " | queries: " (vec (:queries result))
                      " | notes: " (vec (:notes result)))))))

  ;; 2. Relevance Filter (core.clj)
  (println "\n\n═══ RELEVANCE FILTER (core.clj) ═══")
  (doseq [[variant-key prompt] relevance-core-variants]
    (println (str "\n--- " variant-key " ---"))
    (doseq [scenario (:relevance-core scenarios)]
      (let [result (eval-relevance-core variant-key prompt scenario)]
        (println (str "  " (:scenario result) ": P=" (format "%.2f" (double (:precision result)))
                      " R=" (format "%.2f" (double (:recall result)))
                      " F1=" (format "%.2f" (double (:f1 result)))
                      " TP=" (:true-positives result)
                      " FP=" (:false-positives result)
                      " FN=" (:false-negatives result))))))

  ;; 3. Category Matching
  (println "\n\n═══ CATEGORY MATCHING ═══")
  (doseq [[variant-key prompt] category-match-variants]
    (println (str "\n--- " variant-key " ---"))
    (doseq [scenario (:category-match scenarios)]
      (let [result (eval-category-match variant-key prompt scenario)]
        (println (str "  " (:scenario result) ": "
                      (if (:correct? result) "✓" "✗")
                      " expected=" (:expected result)
                      " got=" (:got result)
                      " (" (:category-name result) ")")))))

  (println "\n\n═══ DONE ═══"))

;; ══════════════════════════ LIVE DATA TEST ══════════════════════════

(defn live-relevance-test
  "Search Lalafo with a query, get real items, run relevance filter, show results."
  [query & {:keys [category-id price-max] :or {price-max 50000}}]
  (println (str "\n═══ LIVE TEST: " query " ═══"))
  (let [result (lalafo/search {"queries" [query]
                               "category_id" category-id
                               "price_max" price-max
                               "candidate_limit" 30})
        data (json/parse-string result true)
        items (:items data)
        _ (println (str "  Found: " (count items) " items"))
        ;; Run core.clj relevance filter
        items-text (str/join "\n" (map-indexed
                                   (fn [i item]
                                     (str (inc i) ". [#" (:id item) "] "
                                          (:title item) " — "
                                          (when-let [p (:price item)]
                                            (str (format "%,.0f" (double p)) " KGS"))
                                          " — " (subs (or (:desc item) "") 0 (min 80 (count (or (:desc item) ""))))))
                                   items))
        prompt-v1 (:v1-detailed relevance-core-variants)
        prompt-v2 (:v2-compact relevance-core-variants)
        ;; Test v1
        resp-v1 (call-llm :kimi-k2 [{:role "system" :content prompt-v1}
                                    {:role "user" :content (str "User is looking for: " query "\n\nListings:\n" items-text
                                                                "\n\nReturn JSON array of relevant listing IDs.")}]
                          :max-tokens 300)
        parsed-v1 (parse-json-response resp-v1)
        ids-v1 (set (if (vector? parsed-v1) parsed-v1 []))
        ;; Test v2
        resp-v2 (call-llm :kimi-k2 [{:role "system" :content prompt-v2}
                                    {:role "user" :content (str "User is looking for: " query "\n\nListings:\n" items-text
                                                                "\n\nReturn JSON array of relevant listing IDs.")}]
                          :max-tokens 300)
        parsed-v2 (parse-json-response resp-v2)
        ids-v2 (set (if (vector? parsed-v2) parsed-v2 []))]
    (println (str "  v1-detailed: " (count ids-v1) " relevant IDs"))
    (println (str "  v2-compact:  " (count ids-v2) " relevant IDs"))
    ;; Show which items each filter picked
    (doseq [item items]
      (let [id (:id item)
            in-v1 (contains? ids-v1 id)
            in-v2 (contains? ids-v2 id)
            marker (cond (and in-v1 in-v2) "✓✓"
                         in-v1 "✓ "
                         in-v2 " ✓"
                         :else "  ")]
        (when (or in-v1 in-v2)
          (println (str "  " marker " #" id " " (:title item) " | "
                        (when-let [p (:price item)] (str (format "%,.0f" (double p)) " KGS")))))))
    {:v1-count (count ids-v1) :v2-count (count ids-v2) :items items}))

(defn run-live-tests
  "Run live tests with real Lalafo data."
  []
  (println "\n╔══════════════════════════════════════════════════════╗")
  (println "║  🔬 Live Data Tests — Real Lalafo Search             ║")
  (println "╚══════════════════════════════════════════════════════╝")
  (live-relevance-test "iPhone 13" :price-max 35000)
  (live-relevance-test "iPad" :price-max 60000)
  (live-relevance-test "IQOS" :price-max 20000)
  (live-relevance-test "MacBook" :price-max 100000)
  (live-relevance-test "помещение под кофейню" :category-id 2067)
  (println "\n═══ LIVE TESTS DONE ═══"))

(defn -main [& args]
  (if (= (first args) "live")
    (run-live-tests)
    (run-evaluation)))

(comment
  ;; Quick test single scenario
  (eval-query-gen :v1-original (:v1-original query-gen-variants) (first (:query-gen scenarios)))

  ;; Full eval
  (run-evaluation)

  ;; Live tests
  (run-live-tests)

  ;; Quick live test
  (live-relevance-test "iPhone 13" :price-max 35000))
