(ns tapalakbot.monitor.tracker
  "Background tracker: checks user tracking filters against Lalafo,
   sends Telegram notifications for new items.
   
   Architecture (lalafo-client-first):
   - At creation: LLM matches user query → category_id via search-categories
   - At check time: use category_id + user's original query (no LLM for search)
   - LLM relevance filter on results only"
  (:require [tapalakbot.monitor.store :as store]
            [tapalakbot.lalafo :as lalafo]
            [clj-harness.telegram :as tg]
            [clj-harness.llm :as llm]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ══════════════════════ CONFIG ══════════════════════

(def ^:private check-interval-ms
  "Check every 2 hours."
  (* 2 60 60 1000))

(def ^:private max-notifications-per-check
  "Max new items to notify per filter per check cycle."
  5)

(def ^:private max-pages-per-query 1)

(def ^:private per-page 40)

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
          resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 100)
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
Given a user's search intent and a list of items, return ONLY the indices (0-based) of items that are genuinely relevant.

Rules:
1. Be STRICT — only include items that clearly match the user's intent
2. Exclude: services, repairs, accessories, unrelated categories
3. For real estate: must be actual premises (not apartments, not services)
4. Return ONLY a JSON array of indices, e.g. [0, 2, 4]
5. If nothing is relevant, return empty array []")

(defn filter-relevant
  "Use LLM to filter items by relevance. Returns only relevant items."
  [track-title items]
  (if (empty? items)
    []
    (let [item-lines (mapv (fn [i item]
                             (str i ". " (get item "title" "") " | " (get item "price" "нет цены")))
                           (range) items)
          prompt (str "User wants: " track-title "\n\n"
                      "Items found:\n" (str/join "\n" item-lines) "\n\n"
                      "Which items are relevant? Return JSON array of indices.")
          messages [{:role "system" :content relevance-prompt}
                    {:role "user" :content prompt}]]
      (try
        (let [resp (llm/llm :kimi-k2 messages [] :provider :openrouter :max-tokens 200)
              content (get-in resp ["choices" 0 "message" "content"])
              json-str (or (re-find #"(?s)\[.*\]" content) "[]")
              indices (try (json/parse-string json-str)
                           (catch Exception _ []))
              valid-indices (filterv #(and (integer? %) (>= % 0) (< % (count items))) indices)
              relevant (mapv items valid-indices)]
          (log/info :track-relevance :track track-title :total (count items) :relevant (count relevant))
          relevant)
        (catch Exception e
          (log/warn :track-relevance-failed :error (.getMessage e))
          [])))))

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
  "Format a notification message for relevant items."
  [track-title items]
  (let [lines (mapv (fn [item]
                      (let [title (get item "title" "")
                            short-title (if (> (count title) 60)
                                          (subs title 0 57) "...")
                            price (get item "price")
                            price-str (format-price price)
                            raw-url (or (get item "url") "")
                            url (if (str/starts-with? raw-url "http")
                                  raw-url
                                  (str "https://lalafo.kg" raw-url))]
                        (str "• " short-title
                             (when (and price (> price 0))
                               (str "\n  💰 " price-str))
                             "\n  🔗 " url)))
                    items)]
    (str "🔔 *«" track-title "»* — " (count items) " новых\n\n"
         (str/join "\n\n" lines))))

(defn- extract-user-id-from-track
  "Extract numeric Telegram user ID from track user-id (format: 'tg-123456')."
  [user-id]
  (when (str/starts-with? user-id "tg-")
    (subs user-id 3)))

;; ══════════════════════ CHECK LOGIC ══════════════════════

(defn check-track
  "Check one track: search → filter seen → LLM relevance → notify.
   Uses category_id + text_query (lalafo-client-first).
   Returns {:new-items N :notified? boolean}."
  [{:keys [id user_id title category_id] :as track}]
  (try
    ;; Step 1: Search with category_id + title (no LLM)
    (let [items (search-track track title)
          ;; Step 2: Filter out already-seen items
          new-items (filterv #(not (store/seen-item? id (get % "id"))) items)]
      (log/info :track-check-detail :track-id id :total-items (count items) :new-items (count new-items))
      ;; Step 3: Mark all found items as seen
      (doseq [item new-items]
        (store/mark-item-seen! id (get item "id")))
      ;; Step 4: Update check timestamp
      (store/mark-track-checked! id)
      ;; Step 5: LLM relevance filter + send notification
      (when (pos? (count new-items))
        (let [candidates (take (* 3 max-notifications-per-check) new-items)
              relevant (filter-relevant title candidates)
              to-send (take max-notifications-per-check relevant)]
          (log/info :track-relevance-result :track-id id :candidates (count candidates) :relevant (count relevant) :sending (count to-send))
          (when (pos? (count to-send))
            (if-let [tg-user-id (extract-user-id-from-track user_id)]
              (let [msg (format-notification title to-send)]
                (log/info :track-notify-preview :msg msg)
                (try
                  (tg/send-message tg-user-id msg :parse-mode nil)
                  (store/increment-notify-count! id)
                  (log/info :track-notified :track-id id :user user_id :items (count to-send))
                  (catch Exception e
                    (log/warn :track-notify-fail :track-id id :error (.getMessage e)))))
              (log/warn :track-invalid-user-id :track-id id :user-id user_id)))))
      {:new-items (count new-items) :notified? false})
    (catch Exception e
      (log/error :track-check-error :track-id (:id track) :error (.getMessage e))
      {:new-items 0 :notified? false})))

;; ══════════════════════ BACKGROUND LOOP ══════════════════════

(defonce ^:private tracker-thread (atom nil))

(defn run-check-cycle!
  "Check all active tracks. Returns summary."
  []
  (let [all-tracks (store/get-all-active-tracks)]
    (if (empty? all-tracks)
      (do (log/info :track-check :no-active-tracks)
          {:tracks 0 :new-items 0 :notified 0})
      (let [results (mapv check-track all-tracks)
            total-new (reduce + (map :new-items results))
            total-notified (count (filter :notified? results))]
        (log/info :track-check-complete :tracks (count all-tracks) :new-items total-new :notified total-notified)
        {:tracks (count all-tracks) :new-items total-new :notified total-notified}))))

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
