(ns tapalakbot.simulation
  "Full-scale simulation runner for auto-research style improvements.

   Runs a batch of realistic user queries through the real agent pipeline
   (ask-stream), capturing every event: LLM calls, tool calls, streaming
   draft states, token usage, latency, errors.

   Output: JSONL event log + summary report at
   ~/agent-artifacts/tapalakbot-v2/simulation/<timestamp>/

   Usage:
     clojure -M:simulation              ;; full batch (20 queries)
     clojure -M:simulation 5            ;; first 5 queries only
     clojure -M:simulation 0 \"айфон\"   ;; single custom query

   The JSONL log is the primary artifact. Each line is a structured event:
     {:event :query.start :trace-id ... :query ... :ts ...}
     {:event :llm.call :trace-id ... :model ... :latency-ms ... :tokens ...}
     {:event :tool.call :trace-id ... :tool ... :ok ... :elapsed ...}
     {:event :draft.chunk :trace-id ... :seq 0 :text ... :ts ...}
     {:event :query.end :trace-id ... :text-len ... :cards ... :total-ms ...}

   Auto-research workflow:
     1. Run simulation → get JSONL
     2. Analyze: latency p50/p99, token costs, tool call patterns, failure modes
     3. Make improvements (prompts, tool schemas, nudges, model config)
     4. Re-run simulation → compare metrics
     5. Iterate

   Draft states: streaming chunks are captured as :draft.chunk events,
   so you can reconstruct how the response evolved token-by-token. This
   lets you analyze WHERE the agent wastes tokens or goes off track."
  (:require
   [tapalakbot.core :as t]
   [clj-harness.observe :as observe]
   [clj-harness.stream :as stream]
   [com.brunobonacci.mulog :as u]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.java.io :as io])
  (:import
   [java.io File PrintWriter]
   [java.time Instant]
   [java.util UUID]))

;; ══════════════════════ QUERY CATALOG ══════════════════════

(def query-catalog
  "Realistic user queries covering different intents, categories, and complexities.
   Each query tests different aspects of the agent pipeline."
  [;; Direct product searches (should go straight to search tool)
   {:id :direct-electronics :query "айфон 13 до 30000" :expect-tools #{:search}}
   {:id :direct-cars :query "найди хундай акцент до 500000" :expect-tools #{:search}}
   {:id :direct-router :query "роутер tp link до 2000" :expect-tools #{:search}}

   ;; Research-first (unfamiliar products → research then search)
   {:id :research-laptop :query "какой ноутбук купить до 25000 для учёбы?" :expect-tools #{:research :search}}
   {:id :research-vacuum :query "посоветуй хороший пылесос до 5000" :expect-tools #{:research :search}}
   {:id :research-tablet :query "планшет для рисования до 15000" :expect-tools #{:research :search}}

   ;; Budget/market intelligence (broader, needs stats)
   {:id :budget-phone :query "сколько стоит айфон 12 сейчас?" :expect-tools #{:search}}
   {:id :market-cars :query "цены на тойота камри в Бишкеке" :expect-tools #{:search}}

   ;; Vague/intent-heavy (tests query builder + LLM understanding)
   {:id :vague-gift :query "хочу подарок жене до 10000" :expect-tools #{:research :search}}
   {:id :vague-kids :query "нужен планшет для ребёнка" :expect-tools #{:research :search}}

   ;; Follow-up style (needs session context)
   {:id :followup-cheaper :query "подешевле есть?" :expect-tools #{:search}}
   {:id :followup-compare :query "какой из них лучше?" :expect-tools #{:research}}

   ;; Edge cases
   {:id :edge-english :query "find iPhone 14 under 40000 som" :expect-tools #{:search}}
   {:id :edge-typo :query "айфн 14 про" :expect-tools #{:search}}
   {:id :edge-empty :query "что умеешь?" :expect-tools #{}}
   {:id :edge-greeting :query "привет" :expect-tools #{}}

   ;; High-value searches (multi-platform)
   {:id :multi-macbook :query "макбук pro m1 до 40000" :expect-tools #{:search}}
   {:id :multi-bike :query "велосипед горный до 8000" :expect-tools #{:search}}
   {:id :multi-tv :query "телевизор 55 дюймов до 20000" :expect-tools #{:search}}

   ;; Russian-language product names
   {:id :ru-washing :query "стиральная машина автомат до 12000" :expect-tools #{:search}}
   {:id :ru-fridge :query "холодильник двухкамерный до 15000" :expect-tools #{:search}}])

;; ══════════════════════ OUTPUT DIRECTORY ══════════════════════

(defn- output-dir
  "Create timestamped output directory. Returns File."
  []
  (let [ts (-> (Instant/now) str (str/replace ":" "-") (subs 0 19))
        dir (File. (str (System/getProperty "user.home")
                        "/agent-artifacts/tapalakbot-v2/simulation/" ts))]
    (.mkdirs dir)
    dir))

;; ══════════════════════ JSONL WRITER ══════════════════════

(defn- make-jsonl-writer
  "Returns {:write fn :close fn} for appending JSON lines to file.
   Thread-safe via synchronized PrintWriter."
  [^File file]
  (let [pw (PrintWriter. (io/writer file :append true))
        write-fn (fn [event]
                   (locking pw
                     (.println pw (json/generate-string
                                    (assoc event :ts (System/currentTimeMillis))))
                     (.flush pw)))
        close-fn (fn [] (.close pw))]
    {:write write-fn :close close-fn}))

;; ══════════════════════ EVENT CAPTURE ══════════════════════

(defn- capture-observe-events!
  "Snapshot the observe ring buffer, capture events that arrived since observe-before.
   We can't filter by trace-id because handle-message-stream! generates its own trace-id
   internally (different from the simulation's). Instead, capture all NEW events since baseline."
  [write-fn observe-before]
  (let [events (observe/snapshot)
        matching (drop observe-before events)]
    (doseq [ev matching]
      (write-fn (assoc ev :event (keyword "observe" (name (:type ev))))))
    (count matching)))

(defn- run-single-query
  "Run one query through the agent. Captures all events + draft states.
   Returns a result map with metrics."
  [write-fn {:keys [id query expect-tools]}]
  (let [trace-id (str (UUID/randomUUID))
        user-id (str "sim-" (name id))
        draft-chunks (atom [])
        status-log (atom [])
        chunk-seq (atom 0)
        t0 (System/nanoTime)]

    (write-fn {:event :query.start
               :trace-id trace-id
               :query-id (name id)
               :query query
               :expect-tools (mapv name expect-tools)})

    ;; Capture observe events BEFORE running (baseline)
    (let [observe-before (count (observe/snapshot))
          ;; stream-cb captures every text delta (draft states)
          stream-cb (fn [delta]
                      (let [seq-n @chunk-seq]
                        (swap! chunk-seq inc)
                        (swap! draft-chunks conj delta)
                        (write-fn {:event :draft.chunk
                                   :trace-id trace-id
                                   :seq seq-n
                                   :text-len (count delta)
                                   :text (subs delta 0 (min 200 (count delta)))})))
          ;; status-cb captures phase transitions
          status-cb (fn [status]
                      (swap! status-log conj status)
                      (write-fn {:event :status
                                 :trace-id trace-id
                                 :status status}))
          ;; Run the actual query through the real pipeline
          result (try
                   (t/ask-stream user-id query status-cb {:stream-cb stream-cb})
                   (catch Exception e
                     (write-fn {:event :query.error
                                :trace-id trace-id
                                :error-class (.getSimpleName (class e))
                                :error-msg (ex-message e)})
                     nil))]

      ;; Capture observe events AFTER running (delta = new events since baseline)
      (let [observe-count (capture-observe-events! write-fn observe-before)
            total-ms (int (/ (- (System/nanoTime) t0) 1e6))
            text-len (count (or (:text result) ""))
            card-count (count (:cards result))
            stats (:stats result)
            draft-count (count @draft-chunks)
            status-count (count @status-log)]

        (write-fn {:event :query.end
                   :trace-id trace-id
                   :query-id (name id)
                   :text-len text-len
                   :card-count card-count
                   :draft-chunks draft-count
                   :status-updates status-count
                   :observe-events observe-count
                   :total-ms total-ms
                   :stats stats
                   :ok (some? result)})

        {:query-id (name id)
         :query query
         :trace-id trace-id
         :text-len text-len
         :card-count card-count
         :total-ms total-ms
         :draft-chunks draft-count
         :observe-events observe-count
         :ok (some? result)
         :stats stats}))))

;; ══════════════════════ BATCH RUNNER ══════════════════════

(defn run-simulation
  "Run a batch of queries through the agent pipeline.
   n: number of queries (nil = all)
   custom-query: if provided, run only this query
   Returns summary map."
  ([] (run-simulation nil nil))
  ([n custom-query]
   (let [dir (output-dir)
         jsonl-file (File. dir "events.jsonl")
         {:keys [write close]} (make-jsonl-writer jsonl-file)
         queries (if custom-query
                   [{:id :custom :query custom-query :expect-tools #{}}]
                   (if n
                     (take n query-catalog)
                     query-catalog))]

     (println "═══════════════════════════════════════════════════")
     (println "  TapalakBot v2 — Simulation Runner")
     (println "  Queries:" (count queries))
     (println "  Output:" (.getAbsolutePath dir))
     (println "═══════════════════════════════════════════════════")
     (println)

     ;; Init mulog to capture events
     (u/set-global-context! {:service "tapalakbot-simulation" :env "sim"})
     (u/start-publisher! {:type :console :pretty-print false})

     ;; Init bot
     (println "Initializing bot...")
     @t/tapalakbot
     (println "Bot ready.")
     (println)

     ;; Run queries sequentially (concurrent would create session conflicts)
     (let [results (doall
                    (for [{:keys [id query] :as q} queries]
                      (do
                        (println (str "  [" (name id) "] " query))
                        (let [r (run-single-query write q)]
                          (println (str "    → " (:text-len r) " chars, "
                                        (:card-count r) " cards, "
                                        (:total-ms r) "ms, "
                                        (:draft-chunks r) " chunks, "
                                        (if (:ok r) "OK" "FAILED")))
                          (flush)
                          r))))
           _ (close)]

       ;; Write summary report
       (let [summary-file (File. dir "summary.edn")
             ok-count (count (filter :ok results))
             fail-count (- (count results) ok-count)
             total-ms (reduce + (map :total-ms results))
             avg-ms (if (seq results) (int (/ total-ms (count results))) 0)
             total-cards (reduce + (map :card-count results))
             total-chunks (reduce + (map :draft-chunks results))
             total-observe (reduce + (map :observe-events results))
             latencies (sort (map :total-ms results))
             p50 (when (seq latencies) (nth latencies (int (/ (count latencies) 2))))
             p99 (when (seq latencies) (nth latencies (dec (count latencies))))
             summary {:timestamp (str (Instant/now))
                      :queries (count results)
                      :ok ok-count
                      :failed fail-count
                      :total-ms total-ms
                      :avg-ms avg-ms
                      :p50-ms p50
                      :p99-ms p99
                      :total-cards total-cards
                      :total-draft-chunks total-chunks
                      :total-observe-events total-observe
                      :results results}]

         (spit summary-file (with-out-str (clojure.pprint/pprint summary)))

         ;; Print summary
         (println)
         (println "═══════════════════════════════════════════════════")
         (println "  SIMULATION COMPLETE")
         (println "═══════════════════════════════════════════════════")
         (println "  Queries:" (count results) (str "(" ok-count " ok, " fail-count " failed)"))
         (println "  Total time:" total-ms "ms")
         (println "  Avg time:" avg-ms "ms")
         (println "  p50:" p50 "ms  p99:" p99 "ms")
         (println "  Total cards:" total-cards)
         (println "  Total draft chunks:" total-chunks)
         (println "  Total observe events:" total-observe)
         (println)
         (println "  Events JSONL:" (.getAbsolutePath jsonl-file))
         (println "  Summary EDN:" (.getAbsolutePath summary-file))
         (println "═══════════════════════════════════════════════════")

         summary)))))

;; ══════════════════════ ANALYSIS HELPERS ══════════════════════

(defn load-events
  "Load JSONL events from a simulation run.
   Returns vector of event maps.
   (load-events \"~/agent-artifacts/tapalakbot-v2/simulation/2026-06-20T.../events.jsonl\")"
  [path]
  (with-open [rdr (io/reader path)]
    (->> (line-seq rdr)
         (mapv #(json/parse-string % true)))))

(defn events-by-trace
  "Group events by :trace-id.
   Returns map of trace-id → [events]."
  [events]
  (group-by :trace-id events))

(defn llm-call-stats
  "Extract LLM call stats from events.
   Returns {:count :avg-latency :total-tokens :stream-count :sync-count}."
  [events]
  (let [llm-events (filter #(= :llm-call (:type %)) events)
        latencies (keep :latency-ms llm-events)
        tokens (keep :total-tokens llm-events)
        stream-count (count (filter :stream? llm-events))]
    {:count (count llm-events)
     :avg-latency-ms (when (seq latencies) (int (/ (reduce + latencies) (count latencies))))
     :max-latency-ms (when (seq latencies) (reduce max latencies))
     :total-tokens (reduce + 0 tokens)
     :stream-count stream-count
     :sync-count (- (count llm-events) stream-count)}))

(defn tool-call-stats
  "Extract tool call stats from events."
  [events]
  (let [tool-events (filter #(= :tool (:type %)) events)
        by-name (group-by :name tool-events)]
    (into {} (map (fn [[name events]]
                    [name {:count (count events)
                           :ok (count (filter :ok? events))
                           :fail (count (remove :ok? events))
                           :avg-elapsed (when (seq events)
                                          (int (/ (reduce + (keep :elapsed events)) (count events))))}])
                  by-name))))

;; ══════════════════════ MAIN ══════════════════════

(defn -main
  "Run simulation. Args:
   (none)  — full batch (20 queries)
   N       — first N queries
   0 QUERY — single custom query"
  [& args]
  (cond
    ;; Custom query: 0 \"query text\"
    (and (seq args) (= "0" (first args)) (second args))
    (run-simulation nil (second args))

    ;; N queries
    (and (seq args) (try (pos? (Integer/parseInt (first args)))
                         (catch Exception _ false)))
    (run-simulation (Integer/parseInt (first args)) nil)

    ;; Full batch
    :else
    (run-simulation nil nil))

  (System/exit 0))
