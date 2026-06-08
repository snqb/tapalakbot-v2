(ns tapalakbot.search
  "Deterministic search pipeline — produces structured results."
  (:require [tapalakbot.query-builder :as qb]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.mashina :as mashina]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════════════ CONVERSION ════════════════════════════

(defn lalafo-item->card
  "Convert a Lalafo API item (JSON map with string keys) to a card map."
  [item]
  (let [p (get item "price")]
    {:title    (get item "title")
     :price    (when p (long p))
     :currency (get item "currency" "KGS")
     :url      (get item "url")
     :platform :lalafo
     :desc     (get item "desc")}))

(defn mashina-item->card
  "Convert a Mashina listing to a card map.
   Normalizes price to a flat number (not nested map)."
  [listing]
  (let [p (:price listing)]
    {:title    (:title listing)
     :price    (when (and p (:amount p)) (long (:amount p)))
     :currency (or (:currency p) "KGS")
     :url      (:url listing)
     :year     (:year listing)
     :mileage  (when-let [m (:mileage listing)]
                 (when (number? m) (long m)))
     :city     (:city listing)
     :platform :mashina}))

;; ════════════════════════════ FILTERING ════════════════════════════

(def ^:private accessory-bad-words
  "Words in title that indicate accessory / junk."
  ["зарядк" "кабел" "чехол" "стекло" "ремонт" "установка"
   "обложк" "коробка" "настройк"])

(defn accessory-score
  "Quick deterministic score for junk.
   Count of bad-words found in the lowercased title."
  [title]
  (let [t (str/lower-case (or title ""))]
    (count (filter #(str/includes? t %) accessory-bad-words))))

(defn dedup-cards
  "Remove duplicates by first-20-chars of lowercased title."
  [cards]
  (let [seen (volatile! #{})]
    (filterv
     (fn [card]
       (let [key (subs (str/lower-case (or (:title card) "")) 0
                       (min 20 (count (or (:title card) ""))))]
         (if (@seen key)
           false
           (do (vswap! seen conj key) true))))
     cards)))

;; ════════════════════════════ LALAFO SEARCH ════════════════════════════

(defn- search-lalafo
  "Search Lalafo with a vector of queries.
   Returns a vector of card maps."
  [queries price-min price-max]
  (log/info :search-lalafo :queries queries :price [price-min price-max])
  (let [raw (lalafo/search {"queries"       queries
                            "price_min"     price-min
                            "price_max"     price-max
                            "candidate_limit" 80})
        parsed (if (string? raw)
                 (try (json/parse-string raw) (catch Exception _ {}))
                 raw)
        items (get parsed "items" [])]
    (mapv lalafo-item->card items)))

;; ════════════════════════════ MASHINA SEARCH ════════════════════════════

(defn- search-mashina
  "Search Mashina for cars.
   Returns a vector of card maps."
  [query]
  (log/info :search-mashina :query query)
  (let [result (mashina/search-cars :query query :size 10)]
    (mapv mashina-item->card (:listings result))))

;; ════════════════════════════ STATS ════════════════════════════

(defn- card-price
  "Extract a numeric price from a card. Handles both raw and nested formats."
  [card]
  (let [p (:price card)]
    (cond
      (nil? p) nil
      (number? p) (long p)
      (map? p) (long (:amount p))
      :else nil)))

(defn- compute-stats
  "Compute avg/min/max/count from a collection of cards."
  [cards]
  (let [prices (keep card-price cards)
        ps (vec prices)]
    (if (empty? ps)
      {:avg 0 :min 0 :max 0 :count 0}
      (let [sum (reduce + ps)]
        {:avg   (long (/ sum (count ps)))
         :min   (apply min ps)
         :max   (apply max ps)
         :count (count ps)}))))

;; ════════════════════════════ MAIN ════════════════════════════

(defn search
  "Main search entry point. Deterministic — no LLM involvement.

   Takes a user-query string and optional opts map:
     :use-llm?  — pass to qb/build (default true)

   Steps:
     1. qb/build to get platform routing + price constraints
     2. Route to appropriate platforms
     3. Search Lalafo / Mashina
     4. Filter accessories, dedup
     5. Compute stats

   Returns {:cards [...] :stats {:avg N :min N :max N :count N}
            :platforms [...] :query \"...\"}"
  ([user-query] (search user-query {}))
  ([user-query {:keys [use-llm?] :or {use-llm? true}}]
   (log/info :search-start :query user-query :use-llm? use-llm?)
   (let [qb-result (qb/build user-query :use-llm? use-llm?)
         {:keys [query price-min price-max
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
                           (search-mashina query)
                           (catch Exception e
                             (log/warn :mashina-search-failed :error (.getMessage e))
                             [])))

         ;; 5. Combine, filter accessories (score > 2), dedup
         all-cards (concat lalafo-cards mashina-cards)
         filtered  (filterv #(<= (accessory-score (:title %)) 2) all-cards)
         deduped   (dedup-cards filtered)

         ;; 6. Stats
         stats (compute-stats deduped)]

     (log/info :search-done
               :platforms platforms
               :lalafo-count (count lalafo-cards)
               :mashina-count (count mashina-cards)
               :final-count (count deduped))

     {:cards    deduped
      :stats    stats
      :platforms platforms
      :query    query})))
