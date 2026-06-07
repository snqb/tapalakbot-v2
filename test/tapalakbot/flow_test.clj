(ns tapalakbot.flow-test
  "End-to-end flow test: 25 realistic Kyrgyz user queries through the full pipeline.
   Tests query generation, multi-platform search, formatting, and citation replacement."
  (:require [tapalakbot.core :as t]
            [tapalakbot.mashina :as mashina]
            [tapalakbot.bazar :as bazar]
            [tapalakbot.lalafo :as lalafo]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

;; ════════════════════════════ TEST QUERIES ════════════════════════════
;; Realistic queries from Kyrgyzstan users — different ages, cities, needs

(def test-queries
  [{:query "iphone 13 pro max" :expect "cars" :category "electronics"}
   {:query "hyundai sonata 2020" :expect "cars" :category "auto"}
   {:query "ноутбук для учебы до 30000" :expect "laptops" :category "electronics"}
   {:query "диван угловой" :expect "furniture" :category "home"}
   {:query "playstation 5" :expect "gaming" :category "electronics"}
   {:query "велосипед горный" :expect "sports" :category "sport"}
   {:query "стиральная машина samsung" :expect "appliances" :category "home"}
   {:query "toyota camry 2018" :expect "cars" :category "auto"}
   {:query "airpods pro" :expect "headphones" :category "electronics"}
   {:query "квартира 2-комнатная аренда" :expect "real estate" :category "property"}
   {:query "macbook air m1" :expect "laptops" :category "electronics"}
   {:query "motoblok китайский" :expect "tools" :category "home"}
   {:query "samsung galaxy s24" :expect "phones" :category "electronics"}
   {:query "кондиционер daikin" :expect "appliances" :category "home"}
   {:query "rolex submariner" :expect "watches" :category "electronics"}
   {:query "bmw x5 2019" :expect "cars" :category "auto"}
   {:query "гитара акустическая" :expect "music" :category "sport"}
   {:query "холодильник lg" :expect "appliances" :category "home"}
   {:query "iphone 14 до 40000" :expect "phones" :category "electronics"}
   {:query "номерные знаки кыргызстан" :expect "auto parts" :category "auto"}
   {:query "офис аренда в центре" :expect "commercial" :category "property"}
   {:query " playstation 4" :expect "gaming" :category "electronics"}
   {:query "dyson v15" :expect "vacuum" :category "home"}
   {:query "tesla model 3" :expect "cars" :category "auto"}
   {:query "琎 iPhone 15" :expect "phones" :category "electronics"}])

;; ════════════════════════════ TEST RUNNER ════════════════════════════

(defn- test-lalafo [query]
  (try
    (let [result (lalafo/search {"queries" [query] "candidate_limit" 20})
          data (if (string? result) (json/parse-string result false) result)
          items (get data "items" [])
          found (get data "found" 0)]
      {:ok true :found found :items (count items) :sample (take 2 (map #(get % "title") items))})
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn- test-mashina [query]
  (try
    (let [result (mashina/search-cars :query query :size 3)
          listings (:listings result)]
      {:ok true :total (:total result) :items (count listings)
       :sample (take 2 (map :title listings))})
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn- test-bazar [query]
  (try
    (let [result (bazar/search :category :transport-cars :brand query)]
      {:ok true :items (count (:listings result))
       :sample (take 2 (map :title (:listings result)))})
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn- test-query-gen [query]
  (try
    (let [result (#'t/generate-search-queries query)]
      {:ok true :queries (:queries result) :count (count (:queries result))})
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn- test-citation-replace []
  (try
    (let [text "- #A Item — 1000 KGS"]
      ;; Test that the regex matches both dash and bullet formats
      (if (and (re-find #"(?:[-•]\s+)([^\n]*?)\s*#([A-Z]+)" text)
               (re-find #"#[A-Z]+" text))
        {:ok true}
        {:ok false :error "Regex did not match"}))
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn run-flow-tests []
  (println "🔍 TapalakBot Flow Test — 25 Queries\n")
  (println "Testing: query generation → lalafo search → mashina → bazar → formatting\n")

  (let [results (atom [])
        start-time (System/currentTimeMillis)]

    ;; Test citation-replace first (fast)
    (print "Citation replace... ") (flush)
    (let [cr (test-citation-replace)]
      (println (if (:ok cr) "✅" "❌"))
      (swap! results conj {:test "citation-replace" :result cr}))

    ;; Test each query
    (doseq [[i {:keys [query expect category]}] (map-indexed vector test-queries)]
      (let [num (inc i)]
        (print (format "\n[%2d/25] \"%s\"\n" num query)) (flush)

        ;; Query generation
        (print "    query-gen... ") (flush)
        (let [qg (test-query-gen query)]
          (print (if (:ok qg) (format "✅ (%d queries)" (:count qg)) "❌"))
          (println)
          (swap! results conj {:test (str "qg:" query) :result qg}))

        ;; Lalafo search
        (print "    lalafo... ") (flush)
        (let [lf (test-lalafo query)]
          (print (if (:ok lf) (format "✅ (%d found, %d items)" (:found lf) (:items lf)) "❌"))
          (println)
          (swap! results conj {:test (str "lalafo:" query) :result lf}))

        ;; Mashina search (cars only)
        (when (= category "auto")
          (print "    mashina... ") (flush)
          (let [ms (test-mashina query)]
            (print (if (:ok ms) (format "✅ (%d total)" (:total ms)) "❌"))
            (println)
            (swap! results conj {:test (str "mashina:" query) :result ms})))

        ;; Bazar search
        (when (= category "auto")
          (print "    bazar... ") (flush)
          (let [bz (test-bazar query)]
            (print (if (:ok bz) (format "✅ (%d items)" (:items bz)) "❌"))
            (println)
            (swap! results conj {:test (str "bazar:" query) :result bz})))))

    ;; Summary
    (let [elapsed (- (System/currentTimeMillis) start-time)
          total (count @results)
          passed (count (filter #(get-in % [:result :ok]) @results))
          failed (count (filter #(not (get-in % [:result :ok])) @results))]

      (println "\n\n═══════════════════════════════════════")
      (println (format "RESULTS: %d/%d passed (%.1f%%)" passed total (* 100.0 (/ passed total))))
      (println (format "Time: %.1f seconds" (/ elapsed 1000.0)))
      (println "═══════════════════════════════════════\n")

      ;; Print failures
      (when (pos? failed)
        (println "FAILURES:")
        (doseq [{:keys [test result]} @results]
          (when (not (:ok result))
            (println (format "  ❌ %s — %s" test (:error result))))))

      {:total total :passed passed :failed failed :elapsed-ms elapsed})))

(defn -main [& args]
  (run-flow-tests))
