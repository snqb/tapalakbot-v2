;; ═══════════════════════════════════════════════════════════
;; TapalakBot citation simulation pipeline
;; Usage: clojure -M test/tapalakbot/citation_simulation.clj
;; ═══════════════════════════════════════════════════════════
(ns tapalakbot.citation-simulation
  "Simulates the full bot pipeline: search → format → LLM → citation.
   Used for testing citation-replace without running the actual bot."
  (:require [tapalakbot.lalafo :as lalafo]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- build-url-store
  "Build url-store from Lalafo search results: {letter → {:url :title :item-id}}"
  [items]
  (let [letters "ABCDEFGHIJKLMNOPQRSTUVWXYZ"]
    (into {}
          (map-indexed
            (fn [i item]
              (let [letter (str (nth letters i))
                    id (str (get item "id"))
                    url (get item "url" "")]
                [letter {:url url
                         :title (get item "title" "")
                         :item-id id}]))
            items))))

(defn- format-for-llm
  "Simulate format-search-results output: '- #A Title | Price KGS'"
  [items]
  (let [letters "ABCDEFGHIJKLMNOPQRSTUVWXYZ"]
    (str/join "\n"
              (map-indexed
                (fn [i item]
                  (str "#" (nth letters i) " "
                       (get item "title" "")
                       " | " (get item "price") " KGS"))
                items))))

(defn citation-replace
  "Replace #A, #B, #C tokens with clickable links."
  [text url-store]
  (let [strip-bold (fn [s] (str/replace s #"\*\*([^*]+)\*\*" "$1"))
        clean-suffix (fn [s] (str/replace s #"[—–,\s-]+$" ""))]
    (str/replace text #"(?:[-•]\s+)([^\n]*?)\s*#([A-Z]+)"
                 (fn [[_ prefix letter]]
                   (let [entry (get url-store letter)
                         url (:url entry)
                         cp (-> prefix str/trimr strip-bold clean-suffix)]
                     (if url
                       (str "• <a href='" url "'>" cp "</a>")
                       (str "• " prefix " #" letter)))))))

(defn simulate
  "Run full simulation with given search query and LLM response."
  [query llm-response]
  (println "═══ CITATION SIMULATION ═══")
  (printf "Query: %s\n" query)

  ;; Step 1: Real search
  (println "\n── Search ──")
  (let [raw (lalafo/search {"queries" [query] "per-page" 8 "candidate_limit" 8})
        items (get (json/parse-string raw false) "items" [])
        url-store (build-url-store items)]
    (printf "Found %d items\n" (count items))
    (printf "Tool output:\n%s\n" (format-for-llm items))

    ;; Step 2: LLM response
    (println "\n── LLM Response ──")
    (println llm-response)

    ;; Step 3: Citation replace
    (println "\n── After Citation Replace ──")
    (let [result (citation-replace llm-response url-store)]
      (println result)
      (let [links (count (re-seq #"<a href=" result))
            tokens (count (re-seq #"#[A-Z]" result))]
        (printf "\nLinks: %d | Remaining tokens: %d\n" links tokens))
      result)))

;; ═══ Example usage ═══
(comment
  (simulate
    "iPhone 13"
    "📱 Нашёл iPhone!\n\n• iPhone 13 128GB — #A — 25000 сом\n• iPhone 13 64GB — #B — 28000 сом")

  ;; Test with fabricated tokens (LLM might use wrong letters)
  (simulate
    "iPad M2"
    "📱 iPad!\n\n• iPad Pro M2 — #X — 78000 сом\n• iPad Air M2 — #Y — 55000 сом"))

;; Run if called directly
(when (= *ns* (the-ns 'tapalakbot.citation-simulation))
  (simulate "iPhone 13"
            "📱 Нашёл iPhone!\n\n• iPhone 13 128GB — #A — 25000 сом\n• iPhone 13 64GB — #B — 28000 сом"))
