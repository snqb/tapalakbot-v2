(ns tapalakbot.search
  "Full-source Lalafo collection, deterministic budget checks and JEV selection."
  (:require [tapalakbot.query-builder :as qb]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.jev :as jev]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn lalafo-item->card
  "Normalize source facts without shortening descriptions or inferring specifications."
  [item]
  (let [images (get item "images")
        image-url (fn [image]
                    (if (string? image) image
                        (or (get image "original_url") (get image "thumbnail_url"))))]
    {:id (get item "id")
     :title (get item "title")
     :desc (or (get item "desc") (get item "description") "")
     :price (get item "price")
     :currency (get item "currency")
     :price-kgs (get item "price_kgs")
     :url (get item "url")
     :platform :lalafo
     :image (or (some image-url images) (get item "image") (get item "image_url"))
     :images (vec (keep image-url images))
     :city (get item "city")
     :condition (get item "condition")
     :params (get item "params")}))

(defn dedup-cards
  "Deduplicate source IDs/URLs while retaining distinct offers for the same model."
  [cards]
  (:cards
   (reduce (fn [{:keys [seen] :as state} card]
             (let [key (or (when (:id card) [(:platform card) (str (:id card))])
                           (:url card))]
               (if (and key (contains? seen key)) state
                   (-> state (update :seen conj key) (update :cards conj card)))))
           {:seen #{} :cards []} cards)))

(defn card-price-kgs
  "Return a known KGS amount, never treating a raw foreign-currency price as KGS."
  [{:keys [price price-kgs currency]}]
  (cond
    (number? price-kgs) price-kgs
    (and (number? price)
         (contains? #{"KGS" "СОМ"} (str/upper-case (str currency)))) price
    :else nil))

(defn- within-budget? [card minimum maximum]
  (if (or minimum maximum)
    (when-let [price (card-price-kgs card)]
      (and (or (nil? minimum) (<= minimum price))
           (or (nil? maximum) (<= price maximum))))
    true))

(defn- compute-stats [cards]
  (let [prices (vec (keep card-price-kgs cards))]
    (if (seq prices)
      {:avg (long (/ (reduce + prices) (count prices)))
       :min (apply min prices) :max (apply max prices) :count (count prices)}
      {:count 0})))

(defn- search-terms [query supplied]
  (let [terms (->> supplied
                   (filter string?)
                   (map str/trim)
                   (remove str/blank?)
                   distinct
                   (take 3)
                   vec)]
    (if (seq terms) terms [query])))

(defn search
  "Collect up to 200 full listings, apply source-bound budgets, then select with JEV.
   :queries are short keyword variants supplied by the conversation agent.
   :query remains the complete user intent used by the semantic selector."
  ([user-query] (search user-query {}))
  ([user-query {:keys [queries price-min price-max city-id category-id status-cb
                      budget-override?]}]
   (let [parsed (qb/parse user-query)
         minimum (if budget-override? price-min (or (:price-min parsed) price-min))
         maximum (if budget-override? price-max (or (:price-max parsed) price-max))
         effective-query (if budget-override?
                           (str (:query parsed) "\nДействующий бюджет в сомах: "
                                (if (or minimum maximum)
                                  (str (when minimum (str "от " minimum " "))
                                       (when maximum (str "до " maximum)))
                                  "без ограничений"))
                           user-query)
         terms (search-terms (:query parsed) queries)
         base {:query effective-query :platforms [:lalafo]
               :qb-result (assoc parsed :price-min minimum :price-max maximum)}]
     (try
       (when status-cb (status-cb "Собираю объявления с Lalafo…"))
       (let [raw (lalafo/search {"queries" terms "price_min" minimum "price_max" maximum
                                 "city_id" city-id "category_id" category-id
                                 "per_page" 200 "candidate_limit" 200})
             data (if (string? raw) (json/parse-string raw) raw)
             _ (when (or (not (map? data)) (get data "error"))
                 (throw (ex-info "Marketplace collection unavailable" {:type :collection-unavailable})))
             collection (get data "stats" {})
             candidates (->> (get data "items" [])
                             (mapv lalafo-item->card)
                             dedup-cards
                             (filterv #(within-budget? % minimum maximum)))
             selected (jev/select-cards effective-query candidates
                                        {:status-cb status-cb
                                         :price-min minimum :price-max maximum})
             cards (vec (:cards selected))]
         (log/info :search-selected :query user-query :candidates (count candidates)
                   :accepted (count cards) :model (get-in selected [:selection :model]))
         (merge base {:cards cards :stats (compute-stats cards)
                      :selection (:selection selected)
                      :collection (into {} (map (fn [[k v]] [(keyword k) v])) collection)
                      :search-status :ok}))
       (catch Exception e
         (log/error e :search-unavailable :query user-query :type (:type (ex-data e)))
         (merge base {:cards [] :stats {:count 0} :search-status :unavailable
                      :error "Не удалось проверить объявления. Это не означает, что подходящих вариантов нет. Попробуйте повторить поиск позже."}))))))
