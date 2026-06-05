(ns tapalakbot.prompt-eval
  "Prompt evaluation harness v2."
  (:require [tapalakbot.lalafo :as lalafo]
            [clj-harness.llm :as llm]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn- call-llm [model messages & {:keys [max-tokens] :or {max-tokens 500}}]
  (let [try-call (fn []
                   (try
                     (let [resp (llm/llm model messages [] :provider :openrouter :max-tokens max-tokens)]
                       (get-in resp ["choices" 0 "message" "content"]))
                     (catch Exception e (log/warn :llm-failed :error (.getMessage e)) nil)))]
    (or (try-call) (try-call))))

(defn- parse-json [content]
  (when content
    (let [clean (-> content (str/replace #"```json|```" "") str/trim)
          obj (second (re-find #"(?s)(\{[^{}]*\})" clean))
          arr (second (re-find #"(?s)(\[[^\[\]]*\])" clean))]
      (when-let [s (or obj arr)]
        (try (json/parse-string (str/trim s) true) (catch Exception _ nil))))))

(defn- fuzzy-match? [needle haystack]
  (let [nl (str/lower-case (str needle)) hl (str/lower-case (str haystack))]
    (or (str/includes? hl nl)
        (and (str/includes? hl "айфон") (str/includes? nl "iphone"))
        (and (str/includes? hl "iphone") (str/includes? nl "айфон"))
        (and (str/includes? hl "макбук") (str/includes? nl "macbook"))
        (and (str/includes? hl "macbook") (str/includes? nl "макбук"))
        (and (str/includes? hl "велосипед") (str/includes? nl "bike"))
        (and (str/includes? hl "bike") (str/includes? nl "велосипед"))
        (and (str/includes? hl "роутер") (str/includes? nl "router"))
        (and (str/includes? hl "router") (str/includes? nl "роутер")))))

;; ═══════════ QUERY GENERATION ═══════════

(def query-scenarios
  [{:input "iPhone 13 до 30000" :expected ["iPhone 13" "айфон 13"] :forbidden ["Samsung Galaxy" "iPad"] :price-max 30000}
   {:input "macbook m1" :expected ["MacBook" "макбук"] :forbidden ["iPhone" "Samsung"]}
   {:input "роутер wifi до 4000" :expected ["роутер" "router"] :forbidden ["iPhone" "macbook"] :price-max 4000}
   {:input "велосипед горный" :expected ["велосипед" "mountain bike"] :forbidden ["iPhone" "macbook"]}
   {:input "PlayStation 5" :expected ["PlayStation" "PS5"] :forbidden ["Xbox" "Nintendo"]}
   {:input "айфон 14 про" :expected ["iPhone 14" "айфон 14"] :forbidden ["Samsung" "iPad"]}
   {:input "диван" :expected ["диван"] :forbidden ["iPhone" "macbook"]}
   {:input "ноутбук игровой" :expected ["ноутбук" "gaming laptop" "игровой"] :forbidden ["iPhone"]}])

(def query-prompts
  {:v1-generic
   (str "You generate search queries for Lalafo.kg (Kyrgyzstan flea marketplace).\n"
        "Given what user wants, output JSON:\n"
        "{\"queries\": [4-6 search strings], \"price_min\": null, \"price_max\": number|null}\n"
        "Rules:\n"
        "- Mix English and Russian variants: [\"iPhone 13\", \"айфон 13\"]\n"
        "- Include the exact product name user mentioned\n"
        "- Add 1-2 broader category terms as fallbacks\n"
        "- NEVER include other models/generations\n"
        "- Extract price if mentioned\n"
        "- Lalafo is a flea market — include generic terms too")

   :v3-smart
   (str "Search query generator for Lalafo.kg marketplace in Kyrgyzstan.\n"
        "Input: user's purchase intent.\n"
        "Output: JSON {\"queries\": [4-6 variants], \"price_min\": null, \"price_max\": number|null}\n"
        "Strategy:\n"
        "1. PRIMARY: exact product name in both English and Russian\n"
        "2. SECONDARY: broader category terms\n"
        "3. BUDGET: if price mentioned, include price range queries\n"
        "4. CRITICAL: NEVER include other models/generations\n"
        "5. On Lalafo (flea market), generic terms often find more")})

(defn eval-query-gen [prompt scenario]
  (let [{:keys [input expected forbidden price-max]} scenario
        messages [{:role "system" :content prompt}
                  {:role "user" :content (str "User wants to buy: " input)}]
        content (call-llm :kimi-k2 messages :max-tokens 300)
        parsed (parse-json content)
        queries (vec (:queries parsed))
        found (atom []) missed (atom []) bad (atom [])]
    (doseq [eq expected]
      (if (some #(fuzzy-match? eq %) queries) (swap! found conj eq) (swap! missed conj eq)))
    (doseq [f forbidden]
      (when (some #(fuzzy-match? f %) queries) (swap! bad conj f)))
    (let [pc (and price-max (= price-max (:price_max parsed)))]
      {:score (+ (count @found) (- (count @bad)) (if pc 1 0))
       :max-score (+ (count expected) (if price-max 1 0))
       :found @found :missed @missed :bad @bad :queries queries :input input})))

;; ═══════════ RELEVANCE FILTER ═══════════

(def relevance-scenarios
  [{:query "iPhone 13"
    :items [{"id" 100 "title" "iPhone 13 128GB" "desc" "в хорошем состоянии"}
            {"id" 101 "title" "Чехол для iPhone 13" "desc" "силикон"}
            {"id" 102 "title" "Зарядка для iPhone" "desc" "быстрая зарядка"}
            {"id" 103 "title" "iPhone 13 Pro Max 256GB" "desc" "новый"}
            {"id" 104 "title" "Стекло для iPhone 13" "desc" "защитное"}
            {"id" 105 "title" "Наушники AirPods" "desc" "оригинал"}
            {"id" 106 "title" "iPhone 12 64GB" "desc" "б/у"}
            {"id" 107 "title" "Чехол кожаный iPhone 13" "desc" "кожа"}
            {"id" 108 "title" "iPhone 13 mini" "desc" "отличное состояние"}
            {"id" 109 "title" "Кабель Lightning" "desc" "оригинальный"}
            {"id" 110 "title" "iPhone 14 128GB" "desc" "минимальные следы"}
            {"id" 111 "title" "Ремонт iPhone" "desc" "замена экрана"}]
    :relevant #{100 103 106 108 110} :exclude #{101 102 104 105 107 109 111}}
   {:query "iPad"
    :items [{"id" 200 "title" "iPad Air M1" "desc" "256GB"}
            {"id" 201 "title" "Чехол для iPad" "desc" "клавиатура"}
            {"id" 202 "title" "iPad Pro 12.9" "desc" "M2"}
            {"id" 203 "title" "Apple Pencil" "desc" "2 поколение"}
            {"id" 204 "title" "Планшет Android Samsung" "desc" "Tab S8"}
            {"id" 205 "title" "iPad mini 6" "desc" "64GB"}
            {"id" 206 "title" "Защитное стекло iPad" "desc" "10.2\""}
            {"id" 207 "title" "iPad 9 поколение" "desc" "32GB"}]
    :relevant #{200 202 205 207} :exclude #{201 203 204 206}}
   {:query "IQOS"
    :items [{"id" 300 "title" "IQOS ILUMA" "desc" "новый"}
            {"id" 301 "title" "Чехол для IQOS" "desc" "кожаный"}
            {"id" 302 "title" "IQOS 3 Duo" "desc" "б/у"}
            {"id" 303 "title" "Зарядка для IQOS" "desc" "оригинальная"}
            {"id" 304 "title" "Табак для IQOS" "desc" "Marlboro"}
            {"id" 305 "title" "IQOS TEREA" "desc" "под заправку"}
            {"id" 306 "title" "Power bank IQOS" "desc" "портативный"}]
    :relevant #{300 302} :exclude #{301 303 304 305 306}}
   {:query "PlayStation 5"
    :items [{"id" 400 "title" "PS5 Slim Digital" "desc" "новая"}
            {"id" 401 "title" "Чехол PS5" "desc" "силикон"}
            {"id" 402 "title" "PlayStation 5 Disc" "desc" "б/у"}
            {"id" 403 "title" "Джойстик PS5 DualSense" "desc" "черный"}
            {"id" 404 "title" "PS5 подставка" "desc" "оригинальная"}
            {"id" 405 "title" "Игра Spider-Man 2 PS5" "desc" "диск"}
            {"id" 406 "title" "Зарядка для PS5 контроллера" "desc" "USB-C"}
            {"id" 407 "title" "PS5 Pro" "desc" "2TB"}]
    :relevant #{400 402 407} :exclude #{401 403 404 405 406}}])

(def relevance-prompts
  {:v1-detailed
   (str "You are a relevance filter for Lalafo.kg marketplace listings.\n"
        "Given what the user wants and a list of listings, return ONLY the IDs of relevant items.\n"
        "INCLUDE: actual products matching user's intent (any model/brand variant)\n"
        "EXCLUDE: accessories, cases, chargers, cables, parts, services, repairs\n"
        "EXCLUDE: wrong product category entirely\n"
        "Title AND description both matter.\n"
        "Return JSON array of IDs: [123, 456, 789]")

   :v3-concise
   (str "Return IDs of listings that match what the user wants to buy.\n"
        "Exclude accessories, chargers, cases, parts, services, repairs.\n"
        "Exclude wrong product category.\n"
        "Return JSON array: [id1, id2, ...] or [] if nothing matches.")

   :v4-product-focused
   (str "You are a product relevance filter for Lalafo.kg marketplace.\n"
        "Given user's search intent and listings, identify which are THE ACTUAL PRODUCT.\n"
        "INCLUDE: Same product in any model/brand/condition, different generations\n"
        "EXCLUDE: Accessories, services, different product category\n"
        "TITLE + DESCRIPTION: Both matter.\n"
        "Return JSON array of relevant listing IDs.")})

(defn eval-relevance [prompt scenario]
  (let [{:keys [query items relevant exclude]} scenario
        items-text (str/join "\n" (map-indexed
                                   (fn [i item] (str (inc i) ". [#" (get item "id") "] " (get item "title") " — " (get item "desc")))
                                   items))
        messages [{:role "system" :content prompt}
                  {:role "user" :content (str "User is looking for: " query "\n\nListings:\n" items-text "\n\nReturn JSON array of relevant listing IDs.")}]
        content (call-llm :kimi-k2 messages :max-tokens 300)
        parsed (parse-json content)
        returned (cond (vector? parsed) (set parsed)
                       (sequential? parsed) (set parsed)
                       (map? parsed) (set (first (vals parsed)))
                       :else #{})
        tp (count (clojure.set/intersection returned relevant))
        fp (count (clojure.set/intersection returned exclude))
        fn (- (count relevant) tp)
        precision (if (pos? (+ tp fp)) (/ (double tp) (+ tp fp)) 0.0)
        recall (if (pos? (+ tp fn)) (/ (double tp) (+ tp fn)) 0.0)
        f1 (if (pos? (+ precision recall)) (* 2 (/ (* precision recall) (+ precision recall))) 0.0)]
    {:query query :precision precision :recall recall :f1 f1 :tp tp :fp fp :fn fn}))

;; ═══════════ CATEGORY MATCHING ═══════════

(def category-scenarios
  [{:input "помещение под кофейню" :accept-ids [2067 2064 2065 2066 2068]}
   {:input "офис в аренду" :accept-ids [2068 2064 2065 2066 2067]}
   {:input "iphone 15" :accept-ids [110 1471 10180]}
   {:input "ноутбук" :accept-ids [118 1343]}
   {:input "велосипед" :accept-ids [262 1483]}])

(defn eval-category [prompt scenario]
  (let [{:keys [input accept-ids]} scenario
        categories-str (try (lalafo/search-categories input) (catch Exception _ "No categories"))
        messages [{:role "user" :content (str "Match this search to a Lalafo.kg category.\n\nUser wants: " input "\n\nMatching categories:\n" categories-str "\n\n" prompt)}]
        content (call-llm :kimi-k2 messages :max-tokens 100)
        parsed (parse-json content)
        got-id (:category_id parsed)]
    {:input input :got got-id :correct? (contains? (set accept-ids) got-id) :category-name (:category_name parsed)}))

(def category-prompts
  {:v1-simple "Pick the MOST SPECIFIC (deepest/leaf) category. Return JSON: {\"category_id\": number|null, \"category_name\": \"string\"}"})

;; ═══════════ RUNNERS ═══════════

(defn run-query-gen []
  (println "\n=== QUERY GENERATION ===")
  (doseq [[k prompt] query-prompts]
    (println (str "\n--- " k " ---"))
    (let [results (mapv #(eval-query-gen prompt %) query-scenarios)
          total-score (reduce + (map :score results))
          total-max (reduce + (map :max-score results))
          pct (if (pos? total-max) (int (* 100 (/ total-score total-max))) 0)]
      (doseq [r results]
        (println (str "  " (:input r) ": " (:score r) "/" (:max-score r) " found=" (vec (:found r))
                      (when (seq (:missed r)) (str " missed=" (vec (:missed r))))
                      (when (seq (:bad r)) (str " BAD=" (vec (:bad r)))))))
      (println (str "  TOTAL: " total-score "/" total-max " (" pct "%)")))))

(defn run-relevance []
  (println "\n=== RELEVANCE FILTER ===")
  (doseq [[k prompt] relevance-prompts]
    (println (str "\n--- " k " ---"))
    (let [results (mapv #(eval-relevance prompt %) relevance-scenarios)
          total-tp (reduce + (map :tp results))
          total-fp (reduce + (map :fp results))
          total-fn (reduce + (map :fn results))
          macro-p (if (pos? (+ total-tp total-fp)) (/ (double total-tp) (+ total-tp total-fp)) 0.0)
          macro-r (if (pos? (+ total-tp total-fn)) (/ (double total-tp) (+ total-tp total-fn)) 0.0)
          macro-f1 (if (pos? (+ macro-p macro-r)) (* 2 (/ (* macro-p macro-r) (+ macro-p macro-r))) 0.0)]
      (doseq [r results]
        (println (str "  " (:query r) ": P=" (format "%.2f" (double (:precision r)))
                      " R=" (format "%.2f" (double (:recall r)))
                      " F1=" (format "%.2f" (double (:f1 r)))
                      " TP=" (:tp r) " FP=" (:fp r) " FN=" (:fn r))))
      (println (str "  MACRO: P=" (format "%.2f" (double macro-p))
                    " R=" (format "%.2f" (double macro-r))
                    " F1=" (format "%.2f" (double macro-f1))
                    " (" (int (* 100 macro-f1)) "%)")))))

(defn run-categories []
  (println "\n=== CATEGORY MATCHING ===")
  (doseq [[k prompt] category-prompts]
    (println (str "\n--- " k " ---"))
    (let [results (mapv #(eval-category prompt %) category-scenarios)
          correct (count (filter :correct? results))
          total (count results)]
      (doseq [r results]
        (println (str "  " (:input r) ": " (if (:correct? r) "correct" "WRONG")
                      " got=" (:got r) " (" (:category-name r) ")")))
      (println (str "  SCORE: " correct "/" total " (" (int (* 100 (/ correct total))) "%)")))))

(defn run-all []
  (println "=== TapalakBot Prompt Eval v2 ===")
  (run-query-gen)
  (run-relevance)
  (run-categories)
  (println "\n=== DONE ==="))

(defn -main [& _args] (run-all))
