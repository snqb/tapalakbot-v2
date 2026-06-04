(ns notebooks.marketplace
  "KG Marketplace Search — Live Notebook
  
  Search across Bazar.kg and Mashina.kg from one place.
  Edit this file and see results update live in the browser."
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [nextjournal.clerk :as clerk]
            [tapalakbot.bazar :as bazar]
            [tapalakbot.mashina :as mashina]
            [clojure.string :as str]))

;; ## 🔍 KG Marketplace Search
;;
;; Search Bazar.kg and Mashina.kg — Kyrgyzstan's largest marketplaces.

(defonce search-query (atom "hyundai"))

;; ---

;; ### 🚗 Bazar.kg — Car Search

(defn search-bazar [query]
  (let [result (bazar/search-cars! query :max-pages 2)]
    {:platform "Bazar.kg"
     :total (:total result)
     :listings (mapv (fn [l]
                       {:title (:title l)
                        :price (if-let [p (:price l)]
                                 (str p " " (:currency l))
                                 "Договорная")
                        :url (:url l)})
                     (:listings result))}))

(def bazar-results (search-bazar @search-query))

bazar-results

;; ---

;; ### 🚘 Mashina.kg — Connection Status

(defn check-mashina []
  (try
    (let [session (mashina/healthcheck)]
      (if (= :ok (:status session))
        {:platform "Mashina.kg"
         :status "✅ Connected"
         :cf-clearance (:cf-clearance session)}
        {:platform "Mashina.kg"
         :status "⚠️ Needs session"
         :error (:error session)}))
    (catch Exception e
      {:platform "Mashina.kg"
       :status "❌ Error"
       :error (.getMessage e)})))

(def mashina-status (check-mashina))

mashina-status

;; ---

;; ## 📊 All Categories

(def category-stats
  (mapv (fn [[k _]]
          (let [result (bazar/search :category k)]
            {:category (name k)
             :listings (count (:listings result))
             :pages (:total-pages result)}))
        (take 6 bazar/categories)))

category-stats

;; ---

;; ## 🏷️ Popular Brands

(def popular-brands
  (mapv (fn [brand]
          (let [result (bazar/search-cars! brand)]
            {:brand brand
             :count (count (:listings result))
             :first-price (when-let [l (first (:listings result))]
                            (when-let [p (:price l)]
                              (str p " " (:currency l))))}))
        ["hyundai" "toyota" "kia" "daewoo" "honda"]))

popular-brands

;; ---

;; ## ℹ️ Notes
;;
;; - **Mashina.kg**: Uses RiskBypass + Smartproxy to solve Cloudflare
;; - **Bazar.kg**: Direct HTML scraping, no auth needed
;; - Edit `search-query` and re-evaluate to search different brands
