(ns tapalakbot.sim-v2
  "Simulation harness for v2 bot. Runs diverse queries, captures outputs,
   scores: hallucination, links, streaming, relevance.

   Usage: bb test/tapalakbot/sim_v2.clj"
  (:require
   [tapalakbot.core :as t]
   [clojure.string :as str]
   [clojure.java.io :as io]))

;; ══════════════════════ DIVERSE QUERIES (25) ══════════════════════

(def queries
  "25 diverse queries across categories and intents."
  [{:id "q01" :text "iphone 13"                              :cat :electronics :expect :listings}
   {:id "q02" :text "найди iphone 13 до 30000"               :cat :electronics :expect :listings}
   {:id "q03" :text "роутер до 4000 сом"                     :cat :electronics :expect :listings}
   {:id "q04" :text "samsung galaxy s23"                     :cat :electronics :expect :listings}
   {:id "q05" :text "macbook air m1"                         :cat :electronics :expect :listings}
   {:id "q06" :text "toyota camry"                           :cat :auto       :expect :listings}
   {:id "q07" :text "hyundai tucson до 30000 $"               :cat :auto       :expect :listings}
   {:id "q08" :text "самый дешевый lexus"                    :cat :auto       :expect :listings}
   {:id "q09" :text "мерседес б/у"                           :cat :auto       :expect :listings}
   {:id "q10" :text "велосипед горный"                       :cat :sport      :expect :listings}
   {:id "q11" :text "наушники для бега"                      :cat :electronics :expect :listings}
   {:id "q12" :text "планшет для учёбы до 15000"             :cat :electronics :expect :listings}
   {:id "q13" :text "ноутбук для работы до 25000"            :cat :electronics :expect :listings}
   {:id "q14" :text "холодильник бюджет"                     :cat :appliances :expect :listings}
   {:id "q15" :text "телевизор samsung до 20000"             :cat :electronics :expect :listings}
   {:id "q16" :text "xiaomi redmi note"                      :cat :electronics :expect :listings}
   {:id "q17" :text "пылесос робот до 10000"                 :cat :appliances :expect :listings}
   {:id "q18" :text "bmw x5 2020"                            :cat :auto       :expect :listings}
   {:id "q19" :text "honda cr-v"                             :cat :auto       :expect :listings}
   {:id "q20" :text "стоит ли брать macbook air m1 за 35000" :cat :electronics :expect :analysis}
   {:id "q21" :text "продаю iphone 13 128gb, за сколько выставить" :cat :electronics :expect :advice}
   {:id "q22" :text "как выбрать подержанный ноутбук"        :cat :electronics :expect :advice}
   {:id "q23" :text "привет"                                 :cat :greeting  :expect :fast}
   {:id "q24" :text "самокат электро до 20000"               :cat :sport      :expect :listings}
   {:id "q25" :text "квартира бишкек посуточно"              :cat :realestate :expect :listings}])

;; ══════════════════════ SCORING ══════════════════════

(defn hallucination-check
  "Check if response contains invented listings (no real URLs).
   Returns {:pass? bool :score 0-1 :issues [...]}"
  [text url-store user-id]
  (let [refs (re-seq #"#([A-Za-z])" text)
        ref-letters (set (map second refs))
        stored-letters (set (keys (get url-store user-id {})))
        ;; Any #X token not in url-store = hallucination
        invented (remove stored-letters ref-letters)
        ;; Pattern: bullet listings without citations
        bullet-items (count (re-seq #"(?m)^[•\\-] " text))
        has-urls? (pos? (count stored-letters))
        ;; Score
        score (cond
                (zero? (count refs)) 1.0              ;; No citations needed = no hallucination
                (seq invented) (max 0 (- 1.0 (/ (count invented) (max 1 (count refs)))))  ;; % invented
                has-urls? 1.0
                (pos? bullet-items) 0.3              ;; Lists but no urls = suspicious
                :else 1.0)]
    {:pass? (>= score 0.8)
     :score score
     :invented-letters invented
     :total-refs (count refs)
     :stored-urls (count stored-letters)
     :bullet-items bullet-items}))

(defn url-check
  "Check if response contains real marketplace URLs or citation tokens."
  [text url-store user-id]
  (let [stored (get url-store user-id {})
        url-count (count stored)
        marketplace-urls (re-seq #"https?://(www\.)?(lalafo\.kg|mashina\.kg)[^\s)]*" text)
        has-direct-urls? (seq marketplace-urls)
        ;; Citation tokens #A-#Z indicate real data will be linked
        has-citations? (boolean (re-find #"#[A-Za-z]" text))]
    {:pass? (or (pos? url-count) has-direct-urls? has-citations?)
     :url-count url-count
     :direct-urls (count marketplace-urls)
     :has-citations? has-citations?}))

(defn streaming-check
  "Estimate streaming quality from output."
  [text]
  ;; Good streaming: no visible format artifacts, natural flow
  (let [has-table-fragments? (re-find #"\|\s+---\s+\|" text)
        has-markdown-crud? (re-find #"```" text)
        has-natural-flow? (re-find #"[А-Яа-я][.?!]\s+[А-Яа-я]" text)]
    {:pass? (and (not has-table-fragments?) (not has-markdown-crud?))
     :natural-flow? (boolean has-natural-flow?)}))

(defn relevance-check
  "Basic relevance: output is not empty, not error."
  [text]
  (let [empty-or-error? (or (str/blank? text)
                            (re-find #"(?i)(error|ошибка|⚠️)" text)
                            (< (count text) 20))]
    {:pass? (not empty-or-error?)
     :char-count (count text)}))

(defn score-result
  "Score a single query result."
  [query-id text url-store user-id]
  (let [halluc (hallucination-check text url-store user-id)
        urls   (url-check text url-store user-id)
        stream (streaming-check text)
        relev  (relevance-check text)
        overall (/ (+ (:score halluc) (if (:pass? urls) 1 0) (if (:pass? stream) 1 0) (if (:pass? relev) 1 0)) 4.0)]
    {:query-id query-id
     :hallucination halluc
     :urls urls
     :streaming stream
     :relevance relev
     :overall-score (double (/ (Math/round (* overall 100)) 100.0))
     :output-first-100 (subs (str/replace text #"\n" " ") 0 (min 100 (count text)))}))

;; ══════════════════════ MAIN ══════════════════════

(defn -main [& args]
  (println "=== TapalakBot v2 Simulation Pipeline ===")
  (println "Queries:" (count queries))
  (println)
  (println "Bot loaded.")

  (let [results (atom [])
        output-dir ".git/reports"
        ts (-> (java.time.LocalDateTime/now) (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))
        out-file (str output-dir "/sim-v2-" ts ".md")]

    (io/make-parents out-file)

    (println "\nRunning" (count queries) "queries...")
    (println "Output:" out-file)
    (println)

    (doseq [{:keys [id text cat expect]} queries]
      (print (str "  [" id "] " (subs text 0 (min 40 (count text))) "... "))
      (flush)

      (try
        (let [start (System/currentTimeMillis)
              user-id (str "sim-" id)
              ;; Clear url-store BEFORE each query (uses "anonymous" key internally)
              _ (swap! t/url-store dissoc "anonymous")
              result-text (t/ask user-id text)
              elapsed (- (System/currentTimeMillis) start)
              ;; url-store is keyed by *current-user-id* which defaults to "anonymous"
              scored (score-result id result-text @t/url-store "anonymous")
              pass? (>= (:overall-score scored) 0.7)]
          (swap! results conj (assoc scored :elapsed-ms elapsed :text text :expect expect))
          (if pass?
            (println (str "✅ " (format "%.2f" (:overall-score scored)) " (" elapsed "ms)"))
            (println (str "❌ " (format "%.2f" (:overall-score scored)) " (" elapsed "ms)")
                     "  hall:" (format "%.1f" (:score (:hallucination scored)))
                     " urls:" (:url-count (:urls scored))
                     " chars:" (:char-count (:relevance scored)))))
        (catch Exception e
          (println "❌ ERROR:" (.getMessage e))
          (swap! results conj {:query-id id :text text :error (.getMessage e)}))))

    ;; Summary
    (let [all @results
          passed (filter #(>= (:overall-score % 0) 0.7) all)
          failed (remove #(>= (:overall-score % 0) 0.7) all)
          avg-score (when (seq all)
                      (/ (reduce + (keep :overall-score all)) (count all)))]

      (println "\n=== RESULTS ===")
      (println (str "Total: " (count all) " | Passed: " (count passed) " | Failed: " (count failed)))
      (when avg-score
        (println (str "Average score: " (format "%.2f" avg-score))))
      (println)

      ;; Write report
      (with-open [w (io/writer out-file)]
        (.write w (str "# TapalakBot v2 Simulation Report\n\n"))
        (.write w (str "**Date:** " ts "\n\n"))
        (.write w (str "**Results:** " (count passed) "/" (count all) " passed"
                       " | Avg score: " (format "%.2f" (or avg-score 0)) "\n\n"))
        (.write w "| ID | Text | Score | Hall | URLs | chars | ms |\n")
        (.write w "|-----|------|-------|------|------|-------|----|\n")
        (doseq [r (sort-by :query-id all)]
          (.write w (str "| " (:query-id r)
                        " | " (:text r "?")
                        " | " (format "%.2f" (get r :overall-score 0))
                        " | " (format "%.1f" (get-in r [:hallucination :score] 0))
                        " | " (get-in r [:urls :url-count] 0)
                        " | " (get-in r [:relevance :char-count] 0)
                        " | " (:elapsed-ms r 0) " |\n")))

        ;; Failed details
        (when (seq failed)
          (.write w "\n## Failed Queries\n\n")
          (doseq [r failed]
            (.write w (str "### " (:query-id r) " — " (:text r) "\n"))
            (when-let [inv (get-in r [:hallucination :invented-letters])]
              (when (seq inv)
                (.write w (str "- Invented tokens: " inv "\n"))))
            (when (get-in r [:hallucination :bullet-items])
              (when (and (zero? (get-in r [:urls :url-count])) (pos? (get-in r [:hallucination :bullet-items])))
                (.write w (str "- Has " (get-in r [:hallucination :bullet-items]) " bullet items but 0 real URLs\n"))))
            (.write w (str "- First 200 chars: " (:output-first-100 r) "\n\n"))))

        ;; Category breakdown
        (.write w "\n## Category Breakdown\n\n")
        (.write w "| Category | Queries | Avg Score |\n")
        (.write w "|----------|---------|------------|\n")
        (let [by-cat (group-by :cat queries)]
          (doseq [[cat qs] (sort-by key by-cat)]
            (let [ids (set (map :id qs))
                  cat-results (filter #(ids (:query-id %)) all)
                  avg (when (seq cat-results)
                        (/ (reduce + (keep :overall-score cat-results)) (count cat-results)))]
              (.write w (str "| " (name cat)
                            " | " (count qs)
                            " | " (format "%.2f" (or avg 0))
                            " |\n"))))))

      (println "Report written to file://" out-file)

      ;; Exit code
      (System/exit (if (>= (count passed) (int (* 0.7 (count all)))) 0 1)))))
