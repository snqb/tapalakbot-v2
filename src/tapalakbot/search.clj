(ns tapalakbot.search
  "Deterministic search pipeline — produces structured results."
  (:require [tapalakbot.query-builder :as qb]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.mashina :as mashina]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════ CONVERSION ════════════════════

(defn lalafo-item->card
  "Convert a Lalafo API item (JSON map with string keys) to a card map."
  [item]
  (let [p (get item "price")]
    {:id       (get item "id")
     :title    (get item "title")
     :price    (when p (long p))
     :currency (get item "currency" "KGS")
     :url      (get item "url")
     :platform :lalafo
     :image    (or (get item "image") (get item "image_url"))
     :desc     (get item "desc")}))

(defn mashina-item->card
  "Convert a Mashina listing to a rich result card."
  [listing]
  (let [p (:price listing)]
    {:id       (:id listing)
     :title    (:title listing)
     :price    (when (and p (:amount p)) (long (:amount p)))
     :price-kgs (:price-kgs listing)
     :price-usd (:price-usd listing)
     :currency (or (:currency p) "KGS")
     :url      (:url listing)
     :image    (first (:images listing))
     :year     (:year listing)
     :mileage  (when-let [m (:mileage listing)]
                 (when (number? m) (long m)))
     :engine   (:engine listing)
     :gearbox  (:gearbox listing)
     :city     (:city listing)
     :platform :mashina}))

;; ════════════════════ FILTERING ════════════════════

(def ^:private accessory-bad-words
  "Words indicating accessory, service, or non-product listing."
  ["зарядк" "кабел" "чехол" "стекло" "ремонт" "установка"
   "обложк" "коробка" "настройк" "адаптер" "переходник"
   "плeнк" "защитн" "аксессуар" "запчаст" "комплект"
   "подароч" "упаков" "держател" "кронштейн" "стенд"
   "подставк" "сидень" "накладк" "наклейк"
   "обтяжк" "шнур" "провод" "розетк"
   "удлинител" "объектив" "штатив" "монопод"])

(defn accessory-score
  "Quick deterministic score for junk.
   Count of bad-words found in the lowercased title."
  [title]
  (let [t (str/lower-case (or title ""))]
    (count (filter #(str/includes? t %) accessory-bad-words))))

(defn dedup-cards
  "Remove duplicate source listings without collapsing distinct ads of one model."
  [cards]
  (let [seen (volatile! #{})]
    (filterv
     (fn [card]
       (let [key (or (:url card)
                     (when (:id card) [(:platform card) (:id card)])
                     [(:platform card) (:title card) (:price card)])]
         (if (@seen key)
           false
           (do (vswap! seen conj key) true))))
     cards)))

(def ^:private token-aliases
  {"тойота" "toyota" "камри" "camry" "лексус" "lexus"
   "хонда" "honda" "хендай" "hyundai" "хундай" "hyundai"
   "мерседес" "mercedes" "бмв" "bmw"})

(def ^:private query-stopwords
  #{"до" "от" "макс" "мин" "бюджет" "цена" "сом" "кгс" "kgs" "тыс"})

(defn- normalize-search-text
  [value]
  (reduce-kv str/replace
             (-> (str (or value "")) str/lower-case (str/replace "ё" "е"))
             token-aliases))

(defn- query-tokens
  [query]
  (->> (re-seq #"[\p{L}\p{N}.]+" (normalize-search-text query))
       (remove query-stopwords)
       (remove (fn [token]
                 (when-let [n (try (Long/parseLong token) (catch Exception _ nil))]
                   (> n 9999))))
       distinct
       vec))

(defn- token-match-count
  [title tokens]
  (let [title (normalize-search-text title)]
    (count (filter #(str/includes? title %) tokens))))

(defn- kgs-currency?
  [currency]
  (#{"KGS" "СОМ"} (some-> (or currency "KGS") str str/upper-case)))

(defn card-price-kgs
  "Return a card's comparable KGS price, or nil when only another currency is known."
  [card]
  (let [p (:price card)]
    (cond
      (number? (:price-kgs card)) (long (:price-kgs card))
      (and (number? p) (kgs-currency? (:currency card))) (long p)
      (and (map? p)
           (number? (:amount p))
           (kgs-currency? (:currency p))) (long (:amount p))
      :else nil)))

(defn- within-price-range?
  [card price-min price-max]
  (if (or price-min price-max)
    (when-let [price (card-price-kgs card)]
      (and (or (nil? price-min) (<= price-min price))
           (or (nil? price-max) (<= price price-max))))
    true))

(defn- card-completeness
  [card]
  (count (keep card [:price :url :image :year :mileage :engine :gearbox :city])))

(defn rank-marketplace-cards
  "Filter a fuzzy marketplace pool locally, then rank exact, in-budget matches.

   Mashina's public endpoint performs broad fuzzy search and has no usable
   structured filters, so title and price constraints are enforced here."
  [cards {:keys [query price-min price-max]}]
  (let [tokens (query-tokens query)
        deduped (dedup-cards cards)
        in-budget (filterv #(within-price-range? % price-min price-max) deduped)
        matched (mapv #(assoc % ::matches (token-match-count (:title %) tokens))
                      in-budget)
        required (count tokens)
        exact (if (pos? required)
                (filterv #(= required (::matches %)) matched)
                matched)
        relevant (cond
                   (seq exact) exact
                   ;; A multi-token model query is a hard contract. Returning a
                   ;; nearby generation/model is worse than returning no cards.
                   (> required 1) []
                   :else
                   (let [best (apply max 0 (map ::matches matched))]
                     (if (pos? best)
                       (filterv #(= best (::matches %)) matched)
                       [])))
        budget-target price-max
        ranked (sort-by
                (fn [card]
                  [(- (::matches card))
                   (if-let [price (and budget-target (card-price-kgs card))]
                     (Math/abs (long (- budget-target price)))
                     Long/MAX_VALUE)
                   (- (long (or (:year card) 0)))
                   (long (or (:mileage card) Long/MAX_VALUE))
                   (- (card-completeness card))])
                relevant)]
    (mapv (fn [index card]
            (-> card
                (dissoc ::matches)
                (assoc :tier (cond
                               (zero? index) :great
                               (< index 5) :good
                               :else nil))))
          (range)
          ranked)))

;; ════════════════════ LALAFO SEARCH ════════════════════

(defn- search-lalafo
  "Search Lalafo with a vector of queries.
   Returns a vector of card maps."
  [queries price-min price-max]
  (log/info :search-lalafo :queries queries :price [price-min price-max])
  (let [raw (try
              (lalafo/search {"queries"       queries
                              "price_min"     price-min
                              "price_max"     price-max
                              "candidate_limit" 80})
              (catch Exception e
                (log/warn :lalafo-search-error (.getMessage e))
                nil))]
    (if (or (nil? raw) (and (string? raw) (str/blank? raw)))
      []
      (let [parsed (if (string? raw)
                     (try (json/parse-string raw) (catch Exception _ {}))
                     raw)
            items (get parsed "items" [])]
        (mapv lalafo-item->card items)))))

;; ════════════════════ MASHINA SEARCH ════════════════════

(defn- search-mashina
  "Search Mashina for cars.
   Returns a vector of card maps."
  [query]
  (log/info :search-mashina :query query)
  (let [result (try
                 (mashina/search-cars :query query :size 100)
                 (catch Exception e
                   (log/warn :mashina-search-error (.getMessage e))
                   nil))]
    (if (or (nil? result) (not (map? result)))
      []
      (mapv mashina-item->card (:listings result)))))

;; ════════════════════ RELEVANCE ════════════════════

(defn relevance-score
  "Score how relevant a card is to the search query. Higher = more relevant.
   10 if query words appear in title, 0 otherwise."
  [title query]
  (let [t (str/lower-case (or title ""))
        q-words (str/split (str/lower-case (or query "")) #"\s+")]
    (if (some #(str/includes? t %) q-words) 10 0)))

;; ════════════════════ STATS ════════════════════

(defn- compute-stats
  "Compute KGS-only avg/min/max/count from marketplace cards."
  [cards]
  (let [ps (vec (keep card-price-kgs cards))]
    (if (empty? ps)
      {:avg 0 :min 0 :max 0 :count 0}
      (let [sum (reduce + ps)]
        {:avg   (long (/ sum (count ps)))
         :min   (apply min ps)
         :max   (apply max ps)
         :count (count ps)}))))


;; ════════════════════ MAIN ════════════════════

(defn search
  "Main search entry point. Deterministic — no LLM involvement.

   Takes a user-query string and optional opts map:
     :use-llm?  — pass to qb/build (default true)

   Steps:
     1. qb/build to get platform routing + price constraints
     2. Route to appropriate platforms
     3. Search Lalafo / Mashina
     4. Filter accessories, dedup, sort by relevance
     5. Compute stats

   Returns {:cards [...] :stats {:avg N :min N :max N :count N}
            :platforms [...] :query \"...\"}"
  ([user-query] (search user-query {}))
  ([user-query {:keys [use-llm?] :or {use-llm? true}}]
   (log/info :search-start :query user-query :use-llm? use-llm?)
   (let [qb-result (qb/build user-query :use-llm? use-llm?)
         {:keys [query price-min price-max mashina-query
                 is-auto? is-electronics? is-real-estate?]} qb-result

         ;; 2. Determine platforms
         platforms (cond
                     is-auto?        [:mashina]
                     is-electronics? [:lalafo :mashina]
                     is-real-estate? [:lalafo]
                     :else           [:lalafo])

         ;; 3. Search Lalafo
         lalafo-cards (when (some #{:lalafo} platforms)
                        (try
                          (search-lalafo [query] price-min price-max)
                          (catch Exception e
                            (log/warn :lalafo-search-failed :error (.getMessage e))
                            [])))

         ;; 4. Search Mashina (cars)
         mashina-cards (when (some #{:mashina} platforms)
                         (try
                           (search-mashina (or mashina-query query))
                           (catch Exception e
                             (log/warn :mashina-search-failed :error (.getMessage e))
                             [])))

         ;; 5. Filter obvious junk, enforce exact/local constraints, then rank.
         all-cards (concat lalafo-cards mashina-cards)
         filtered (filterv #(<= (accessory-score (:title %)) 1) all-cards)
         ranked (rank-marketplace-cards
                 filtered
                 {:query (or mashina-query query)
                  :price-min price-min
                  :price-max price-max})
         stats (compute-stats ranked)]

     (log/info :search-done
               :platforms platforms
               :candidates (count filtered)
               :final-count (count ranked))

     {:cards ranked
      :stats stats
      :platforms platforms
      :query query
      :qb-result qb-result})))
