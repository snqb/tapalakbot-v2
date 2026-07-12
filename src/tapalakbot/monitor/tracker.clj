(ns tapalakbot.monitor.tracker
  "Background tracker: checks user tracking filters against Lalafo,
   sends Telegram notifications for new items.
   
   Architecture (lalafo-client-first):
   - At creation: LLM matches user query → category_id via search-categories
   - At check time: use category_id + user's original query (no LLM for search)
   - LLM relevance filter on results only"
  (:require [tapalakbot.monitor.store :as store]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.query-builder :as qb]
            [tapalakbot.render :as render]
            [clj-harness.telegram :as tg]
            [clj-harness.llm :as llm]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; ══════════════════════ CONFIG ══════════════════════

(def ^:private check-interval-ms
  "Check every 2 hours."
  (* 2 60 60 1000))

(def ^:private max-notifications-per-check
  "Max new items to notify per filter per check cycle."
  5)

(def ^:private max-pages-per-query 1)

(def ^:private per-page 40)

(def ^:private sqlite-time
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn track-due?
  "True when a subscription has never run or its own notification interval
   has elapsed."
  ([track] (track-due? track (LocalDateTime/now)))
  ([{:keys [last_checked_at notify_interval]} now]
   (if-not last_checked_at
     true
     (try
       (let [last-checked (LocalDateTime/parse last_checked_at sqlite-time)
             next-check (.plusHours last-checked (long (or notify_interval 24)))]
         (not (.isAfter next-check now)))
       (catch Exception e
         (log/warn :track-invalid-last-checked :value last_checked_at)
         true)))))

;; ══════════════════════ CATEGORY MATCHING (called once at track creation) ══════════════════════

(def ^:private category-match-prompt
  "You are a category matcher for Lalafo.kg marketplace in Kyrgyzstan.
Given a user's search intent, find the MOST SPECIFIC category_id from the category list.

Rules:
1. Pick the DEEPEST (most specific) leaf category
2. For 'кофейня/кафе помещение' → Restaurant and cafe rentals (2067)
3. For 'офис' → Office rentals (2068)
4. For 'магазин' → Retail rentals (2066)
5. For 'склад' → Warehouse and workshop rentals (2065)
6. If unsure, return null for category_id and use the original query as text search
7. Return ONLY a JSON object: {\"category_id\": number|null, \"category_name\": \"string\", \"text_query\": \"string\"}")

(defn match-category
  "Match user query to Lalafo category_id. Called ONCE at track creation.
   Uses search-categories to find candidates, then LLM picks the best one."
  [track-title]
  (try
    ;; Step 1: Get matching categories from Lalafo
    (let [categories-str (lalafo/search-categories track-title)
          prompt (str "Match this search to a Lalafo.kg category.\n\n"
                      "User wants: " track-title "\n\n"
                      "Matching categories:\n" categories-str "\n\n"
                      "Rules:\n"
                      "1. Pick the MOST SPECIFIC (deepest) category\n"
                      "2. For phones/electronics → pick the brand-specific category\n"
                      "3. For real estate → pick the specific property type\n"
                      "4. If no good match, return {\"category_id\": null}\n\n"
                      "Return JSON: {\"category_id\": number|null, \"category_name\": \"string\"}")
          messages [{:role "user" :content prompt}]
          resp (llm/llm :gemini-3.5-flash messages [] :provider :openrouter :max-tokens 100)
          content (get-in resp ["choices" 0 "message" "content"])
          json-str (or (re-find #"(?s)\{.*\}" content) "{}")
          parsed (try (json/parse-string json-str true)
                      (catch Exception _ {}))]
      {:category-id (:category_id parsed)
       :category-name (:category_name parsed)
       :text-query track-title})
    (catch Exception e
      (log/warn :category-match-failed :track-title track-title :error (.getMessage e))
      {:category-id nil :category-name nil :text-query track-title})))

;; ══════════════════════ RELEVANCE FILTER (LLM-based) ══════════════════════

(def ^:private relevance-prompt
  "You are a relevance filter for Lalafo.kg marketplace listings.
Given a user's search intent and a list of items, return ONLY the indices (0-based) of items that are relevant.

Rules:
1. Include items that MATCH the user's intent — don't be overly strict
2. For 'кофейня/кафе помещение' → include any commercial space suitable for food service
3. For 'офис' → include any office space
4. For 'магазин' → include any retail space
5. For electronics/phones/cars → include matching products
6. EXCLUDE only: completely unrelated items, services, repairs, accessories
7. Return ONLY a JSON array of indices, e.g. [0, 2, 4]
8. If 3+ items look relevant, return them all")

(defn filter-relevant
  "Use LLM to filter items by relevance. Returns only relevant items.
   Category-aware prompt that knows the search context."
  [track-title items]
  (if (empty? items)
    []
    (let [item-lines (mapv (fn [i item]
                             (str i ". " (get item "title" "") " | " (get item "price" "нет цены")))
                           (range) items)
          prompt (str "User monitors Lalafo.kg for: " track-title "\n"
                      "\nItems found:\n" (str/join "\n" item-lines) "\n\n"
                      "Rules:\n"
                      "- INCLUDE: items that match the user's search intent\n"
                      "- EXCLUDE: services, repairs, job postings, vehicle rentals, furniture sales\n"
                      "- EXCLUDE: residential (квартиры, комнаты) unless user specifically wants them\n"
                      "- Return JSON array of indices of relevant items")
          messages [{:role "user" :content prompt}]]
      (try
        (let [resp (llm/llm :gemini-3.5-flash messages [] :provider :openrouter :max-tokens 200)
              content (get-in resp ["choices" 0 "message" "content"])
              json-str (or (re-find #"(?s)\[.*\]" content) "[]")
              indices (try (json/parse-string json-str)
                           (catch Exception _ []))
              valid-indices (filterv #(and (integer? %) (>= % 0) (< % (count items))) indices)
              relevant (mapv #(nth items %) valid-indices)]
          (log/info :track-relevance :track track-title :total (count items) :relevant (count relevant))
          relevant)
        (catch Exception e
          (log/warn :track-relevance-failed :error (.getMessage e))
          nil)))))

;; ══════════════════════ SEARCH (lalafo-client-first) ══════════════════════

(defn search-track
  "Search Lalafo using category_id + text query. No LLM calls.
   This is the lalafo-client-first approach."
  [{:keys [queries price_min price_max city_id category_id]} text-query]
  (let [client (#'lalafo/build-client)
        all-items (atom {})]
    (log/info :track-search-start :category-id category_id :text-query text-query)
    ;; Search with category_id + text query
    (try
      (let [[found items pages] (#'lalafo/search-all-pages
                                 client text-query
                                 :category-id category_id
                                 :city-id (or city_id 103184)
                                 :price-min price_min
                                 :price-max price_max
                                 :max-pages max-pages-per-query
                                 :per-page per-page)]
        (log/info :track-search :query text-query :category-id category_id :found found :items (count items))
        (doseq [item items]
          (let [item-id (get item "id")
                price (get item "price")]
            (when (or (nil? price) (> price 50))
              (swap! all-items assoc item-id item)))))
      (catch Exception e
        (log/warn :track-search-error :query text-query :error (.getMessage e))))
    (log/info :track-search-result :items (count @all-items))
    (vals @all-items)))

;; ══════════════════════ NOTIFICATION ══════════════════════

(defn- format-price [p]
  (if (and p (> p 0))
    (str (format "%,.0f" (double p)) " сом")
    "цена неизвестна"))

(defn- format-notification
  "Format a notification message for relevant items using render module."
  [track-title items]
  (let [cards (mapv (fn [item]
                      (let [raw-url (or (get item "url") "")
                            url (if (str/starts-with? raw-url "http")
                                  raw-url
                                  (str "https://lalafo.kg" raw-url))]
                        {:title    (get item "title" "")
                         :price    (get item "price")
                         :currency "KGS"
                         :url      url
                         :platform :lalafo}))
                    items)
        reply {:mode :shortlist
               :intro (str "🔔 <b>«" track-title "»</b> — " (count items) " новых")
               :cards cards
               :cta nil
               :assumptions []}]
    (render/render-reply reply)))

(defn- extract-user-id-from-track
  "Extract numeric Telegram user ID from track user-id (format: 'tg-123456')."
  [user-id]
  (when (str/starts-with? user-id "tg-")
    (subs user-id 3)))

;; ══════════════════════ CHECK LOGIC ══════════════════════

(defn check-track
  "Check one subscription. Relevant items become seen only after Telegram
   confirms delivery; failed notifications remain retryable."
  [{:keys [id user_id title price_min price_max] :as track}]
  (try
    (let [items (search-track track title)
          new-items (filterv #(not (store/seen-item? id (get % "id"))) items)
          in-budget (if (or price_min price_max)
                      (filterv (fn [item]
                                 (let [p (get item "price")]
                                   (and p
                                        (or (nil? price_min) (>= p price_min))
                                        (or (nil? price_max) (<= p price_max)))))
                               new-items)
                      new-items)
          clean-items (qb/filter-accessories in-budget title)
          candidates (vec (take (* 3 max-notifications-per-check) clean-items))]
      (log/info :track-check-detail :track-id id :total-items (count items)
                :new-items (count new-items) :in-budget (count in-budget))
      (if (empty? candidates)
        (do
          (store/mark-track-checked! id)
          {:new-items 0 :notified? false})
        (let [relevant (filter-relevant title candidates)]
          (if (nil? relevant)
            (do
              (log/warn :track-classification-deferred :track-id id)
              {:new-items (count clean-items) :notified? false})
            (let [relevant-ids (set (map #(get % "id") relevant))
                  irrelevant (remove #(contains? relevant-ids (get % "id")) candidates)
                  to-send (vec (take max-notifications-per-check relevant))]
              ;; A successful relevance decision lets us retire known noise.
              (doseq [item irrelevant]
                (store/mark-item-seen! id (get item "id")))
              (if (empty? to-send)
                (do
                  (store/mark-track-checked! id)
                  {:new-items (count clean-items) :notified? false})
                (if-let [tg-user-id (extract-user-id-from-track user_id)]
                  (let [msg (format-notification title to-send)
                        sent (tg/send-message tg-user-id msg :parse-mode "HTML")]
                    (if sent
                      (do
                        (doseq [item to-send]
                          (store/mark-item-seen! id (get item "id")))
                        (store/increment-notify-count! id)
                        (store/mark-track-checked! id)
                        (log/info :track-notified :track-id id :user user_id
                                  :items (count to-send))
                        {:new-items (count clean-items) :notified? true})
                      (do
                        (log/warn :track-notify-deferred :track-id id :user user_id)
                        {:new-items (count clean-items) :notified? false})))
                  (do
                    (log/warn :track-invalid-user-id :track-id id :user-id user_id)
                    (store/mark-track-checked! id)
                    {:new-items (count clean-items) :notified? false}))))))))
    (catch Exception e
      (log/error e :track-check-error :track-id (:id track))
      {:new-items 0 :notified? false})))

;; ══════════════════════ BACKGROUND LOOP ══════════════════════

(defonce ^:private tracker-thread (atom nil))

(defn run-check-cycle!
  "Check all active tracks. Returns summary."
  []
  (let [all-tracks (store/get-all-active-tracks)
        due-tracks (filterv track-due? all-tracks)]
    (if (empty? due-tracks)
      (do (log/info :track-check :no-due-tracks)
          {:tracks 0 :new-items 0 :notified 0})
      (let [results (mapv check-track due-tracks)
            total-new (reduce + (map :new-items results))
            total-notified (count (filter :notified? results))]
        (log/info :track-check-complete :tracks (count due-tracks) :new-items total-new :notified total-notified)
        {:tracks (count due-tracks) :new-items total-new :notified total-notified}))))

(defn start-tracker!
  "Start background tracking thread."
  []
  (when @tracker-thread
    (log/warn :tracker-already-running))
  (let [t (Thread.
           (fn []
             ;; Initial delay — let scanner finish first scan
             (Thread/sleep (* 5 60 1000))
             (loop []
               (try
                 (run-check-cycle!)
                 (catch Exception e
                   (log/error :tracker-loop-error (.getMessage e))))
               (Thread/sleep check-interval-ms)
               (recur)))
           "monitor-tracker")]
    (.setDaemon t true)
    (.start t)
    (reset! tracker-thread t)
    (log/info :tracker-started :interval-ms check-interval-ms)))

(defn stop-tracker!
  "Stop background tracking thread."
  []
  (when-let [t @tracker-thread]
    (.interrupt t)
    (reset! tracker-thread nil)
    (log/info :tracker-stopped)))

(defn tracker-running?
  "Check if tracker thread is alive."
  []
  (boolean (and @tracker-thread (.isAlive ^Thread @tracker-thread))))
