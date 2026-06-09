(ns simulate-hard-queries
  (:require [tapalakbot.policy :as p]
            [tapalakbot.render :as r]
            [clojure.string :as str]))

(def queries
  [;; Typos — should be :search or at least not :unknown
   ["велосиепед" nil]
   ["айфон12" nil]
   ["samsng galaxy" nil]
   ["макбуук про" nil]
   ["карбоновый веласипед" nil]

   ;; Vague / ambiguous
   ["хочу машину" nil]
   ["что нибудь" nil]
   ["нужна тачка" nil]
   ["хочу что нибудь купить" nil]
   ["ищу подарок" nil]

   ;; Non-Russian
   ["carbon bike in bishkek" nil]
   ["buy iphone 13" nil]
   ["find cheap laptop" nil]

   ;; Mixed language
   ["купить iphone 13 pro max 256gb черный" nil]

   ;; Very short — should search if >3 chars
   ["машина" nil]
   ["телефон" nil]
   ["квартира" nil]
   ["вело" nil]  ;; 4 chars, should search
   ["bmw" nil]   ;; 3 chars, should match bmw in regex
   ["коф" nil]   ;; 3 chars — borderline

   ;; Short with session — should be :refine
   ["карбон" {:data {:last-search "велосипед"}}]
   ["дешевле" {:data {:last-search "ноутбук"}}]
   ["а ssd" {:data {:last-search "ноутбук"}}]
   ["бюджетнее" {:data {:last-search "ноутбук"}}]  ;; not in refine-keywords but short+session

   ;; Gibberish (< 4 chars) — should be :unknown
   ["asd" nil]
   ["123" nil]
   ["..." nil]
   ["да" nil]

   ;; Refine keywords — should be :refine
   ["дешевле" nil]
   ["дороже" nil]
   ["только новые" nil]
   ["в бишкеке" nil]
   ["побольше" nil]

   ;; Comparison
   ["что лучше тойота камри или хонда аккорд" nil]
   ["сравни macbook air и pro" nil]

   ;; Greeting-like but with intent — should be :search not :greeting
   ["привет найди телефон" nil]
   ["салам ищу велосипед" nil]

   ;; Empty
   ["" nil]
   [nil nil]])

(defn classify-and-verdict [q sess]
  (let [cls (p/classify q sess)
        ok?  (or (= cls :search) (= cls :refine) (= cls :compare)
                 (= cls :greeting) (= cls :reset) (= cls :tracking)
                 (= cls :help) (= cls :thanks)
                 (and (= cls :unknown) (or (nil? q) (< (count (str/trim q)) 4))))]
    {:query q :class cls :ok? ok?}))

(defn -main []
  (println "=== POLICY CLASSIFICATION: HARD QUERIES ===\n")
  (let [results (map (fn [[q s]] (classify-and-verdict q s)) queries)
        good (filter :ok? results)
        bad  (remove :ok? results)]
    (doseq [r results]
      (let [marker (if (:ok? r) "✓" "✗")]
        (printf "%s %-6s | %s\n" marker (name (:class r))
                (pr-str (:query r)))))
    (printf "\n%d/%d passed\n" (count good) (count results))
    (when (seq bad)
      (println "\nFAILURES:")
      (doseq [r bad]
        (printf "  %s => :%s (session: %s)\n"
                (pr-str (:query r)) (name (:class r))
                (if (second (nth (filter #(= (first %) (:query r)) queries) 0)) "yes" "no"))))
    (System/exit (if (seq bad) 1 0))))
