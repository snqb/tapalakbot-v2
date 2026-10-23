(ns notebooks.marketplace
  "Lalafo.kg marketplace search notebook.

  Edit `search-query` and re-evaluate to search current Lalafo listings."
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [nextjournal.clerk :as clerk]
            [tapalakbot.lalafo :as lalafo]
            [cheshire.core :as json]))

;; ## 🔍 Lalafo.kg Marketplace Search

(defonce search-query (atom "hyundai"))

(defn search-lalafo [query]
  (let [result (-> (lalafo/search {"queries" [query]
                                   "max_pages" 2
                                   "candidate_limit" 100})
                   (json/parse-string true))]
    {:platform "Lalafo.kg"
     :total (:found result)
     :listings (mapv (fn [{:keys [title price currency url]}]
                       {:title title
                        :price (if price
                                 (str price " " currency)
                                 "Договорная")
                        :url url})
                     (:items result))}))

(def lalafo-results (search-lalafo @search-query))

lalafo-results

