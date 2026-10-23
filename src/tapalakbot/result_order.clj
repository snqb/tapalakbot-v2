(ns tapalakbot.result-order
  "Stable, source-authoritative ordering for complete result pools."
  (:require [tapalakbot.search :as search]))

(def default-mode :price-asc)
(def supported-modes #{:price-asc :price-desc :relevance})

(defn mode-label
  "Short Russian label used by result sort controls."
  [mode]
  (case mode
    :price-desc "Цена ↓"
    :relevance "По соответствию"
    "Цена ↑"))

 (defn- fit-rank [card]
   (let [fit (get-in card [:decision :fit])]
     (cond
       (or (= fit :direct) (= fit "direct")) 0
       (or (= fit :possible) (= fit "possible")) 1
       :else 2)))

(defn order-cards
  "Return a stable full-pool view without changing source-owned card fields.
   Direct matches precede possible matches in every mode. Price modes place
   amounts without an authoritative KGS comparison last; relevance uses the
   existing JEV score. Equal keys retain the input order."
  [cards mode]
  (let [mode (if (contains? supported-modes mode) mode default-mode)]
    (->> (vec cards)
         (map-indexed vector)
         (sort-by (fn [[index card]]
                    (let [p (search/card-price-kgs card)
                          price-key (if (number? p) p 0)
                          known? (if (number? p) 0 1)
                          rank (or (:rank-score card) 0)
                          mode-key (case mode
                                     :relevance [(- rank) known?]
                                     :price-desc [known? (- price-key)]
                                     [known? price-key])]
                      (into [(fit-rank card)] (conj mode-key index)))))
         (mapv second))))
