(ns tapalakbot.orchestrator-test
  "Unit tests for the orchestrator module.
   All external dependencies (search, LLM, monitor) are mocked with with-redefs.
   No real API calls are made."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [tapalakbot.orchestrator :as orch]
            [tapalakbot.policy :as policy]
            [tapalakbot.search :as search]
            [clj-harness.llm :as llm]
            [tapalakbot.monitor.store :as monitor-store]))

;; ════════════════════ HELPERS ════════════════════

(defn- make-session
  "Create a mock session atom with optional initial data."
  ([] (atom {"data" {}}))
  ([data-map] (atom {"data" data-map})))

(defn- mock-search-result
  "Create a standard mock search result."
  [cards]
  {:cards    cards
   :stats    {:avg 50000 :min 30000 :max 80000 :count (count cards)}
   :platforms [:lalafo]
   :query    "iphone 13"})

(defn- mock-llm-curator-response
  "Create a mock LLM response with curator JSON."
  [selected tiers intro cta]
  {"choices" [{"message" {"content"
                           (str "{\"selected\":" selected
                                ",\"tiers\":" tiers
                                ",\"intro\":\"" intro "\""
                                ",\"cta\":\"" cta "\""
                                ",\"assumptions\":[]}")
                           }}]})

(defn- sample-cards
  "Return a vector of sample card maps."
  []
  [{:title "iPhone 13 128GB" :price 35000 :currency "KGS" :url "https://lalafo.kg/1" :platform :lalafo}
   {:title "iPhone 13 256GB" :price 45000 :currency "KGS" :url "https://lalafo.kg/2" :platform :lalafo}
   {:title "iPhone 13 Pro"   :price 55000 :currency "KGS" :url "https://lalafo.kg/3" :platform :lalafo}
   {:title "iPhone 13 Mini"  :price 30000 :currency "KGS" :url "https://lalafo.kg/4" :platform :lalafo}
   {:title "iPhone 13 Pro Max" :price 65000 :currency "KGS" :url "https://lalafo.kg/5" :platform :lalafo}])

;; ════════════════════ TEST 1: FAST PATHS ════════════════════
;; Greetings, thanks, help, reset, tracking should return correct modes
;; without calling search or LLM.

(deftest test-fast-paths
  (testing "greeting returns shortlist with welcome intro"
    (with-redefs [search/search (fn [& _] (throw (Exception. "search should not be called")))
                  llm/llm       (fn [& _] (throw (Exception. "LLM should not be called")))]
      (let [result (orch/orchestrate "привет" nil)]
        (is (= :shortlist (:mode result)))
        (is (string? (:intro result)))
        (is (empty? (:cards result)))
        (is (str/includes? (:intro result) "TapalakBot")))))

  (testing "thanks returns shortlist with thanks message"
    (with-redefs [search/search (fn [& _] (throw (Exception. "search should not be called")))
                  llm/llm       (fn [& _] (throw (Exception. "LLM should not be called")))]
      (let [result (orch/orchestrate "спасибо" nil)]
        (is (= :shortlist (:mode result)))
        (is (str/includes? (:intro result) "Пожалуйста"))
        (is (empty? (:cards result))))))

  (testing "help returns shortlist with help info"
    (with-redefs [search/search (fn [& _] (throw (Exception. "search should not be called")))
                  llm/llm       (fn [& _] (throw (Exception. "LLM should not be called")))]
      (let [result (orch/orchestrate "помощь" nil)]
        (is (= :shortlist (:mode result)))
        (is (str/includes? (:intro result) "TapalakBot"))
        (is (str/includes? (:intro result) "Lalafo.kg"))
        (is (empty? (:cards result))))))

  (testing "reset returns :reset mode"
    (with-redefs [search/search (fn [& _] (throw (Exception. "search should not be called")))
                  llm/llm       (fn [& _] (throw (Exception. "LLM should not be called")))]
      (let [result (orch/orchestrate "новый диалог" nil)]
        (is (= :reset (:mode result))))))

  (testing "tracking returns :tracking mode"
    (with-redefs [search/search (fn [& _] (throw (Exception. "search should not be called")))
                  llm/llm       (fn [& _] (throw (Exception. "LLM should not be called")))]
      (let [result (orch/orchestrate "отслеживание" nil)]
        (is (= :tracking (:mode result)))))))

;; ════════════════════ TEST 2: EMPTY SEARCH RESULTS ════════════════════

(deftest test-search-with-empty-results
  (testing "empty search results return :no-results mode"
    (let [empty-result {:cards [] :stats {:avg 0 :min 0 :max 0 :count 0}
                        :platforms [:lalafo] :query "nonexistent thing"}]
      (with-redefs [search/search (fn [q & opts] empty-result)
                    llm/llm       (fn [& _] (throw (Exception. "LLM should not be called")))]
        (let [result (orch/orchestrate "найди несуществующую вещь xyz123" nil)]
          (is (= :no-results (:mode result)))
          (is (empty? (:cards result)))
          (is (string? (:intro result))))))))

;; ════════════════════ TEST 3: SEARCH WITH RESULTS + MOCKED CURATOR ════════════════════

(deftest test-search-with-results-mock-curator
  (testing "search results are curated by mocked LLM into shortlist with tiers"
    (let [cards (sample-cards)
          search-result (mock-search-result cards)
          ;; LLM curator picks 3 items: indices 0, 1, 4 with tiers
          curator-json {"selected" [0 1 4]
                        "tiers"    {"0" "great" "1" "good" "4" "premium"}
                        "intro"    "Test intro: 3 iPhones found!"
                        "cta"      "Next step?"
                        "assumptions" []}
          llm-response {"choices" [{"message" {"content" (cheshire.core/generate-string curator-json)}}]}]
      (with-redefs [search/search       (fn [q & opts] search-result)
                    llm/llm             (fn [model msgs tools & opts] llm-response)
                    monitor-store/get-category-summary (fn [] [])]
        (let [result (orch/orchestrate "найди iphone 13" nil)]
          (is (= :shortlist (:mode result)))
          (is (= "Test intro: 3 iPhones found!" (:intro result)))
          (is (= "Next step?" (:cta result)))
          ;; Should have exactly 3 cards (indices 0, 1, 4)
          (is (= 3 (count (:cards result))))
          ;; Tier assignment is deterministic based on price vs avg
          ;; 35000/50000=0.7 → :good, 45000/50000=0.9 → :good, 65000/50000=1.3 → :good
          (is (every? #{:good :great :premium} (map :tier (:cards result))))
          ;; Verify cards match original data
          (is (= "iPhone 13 128GB" (:title (nth (:cards result) 0))))
          (is (= "iPhone 13 256GB" (:title (nth (:cards result) 1))))
          (is (= "iPhone 13 Pro Max" (:title (nth (:cards result) 2)))))))))

;; ════════════════════ TEST 4: CURATOR FALLBACK ON LLM ERROR ════════════════════

(deftest test-curator-fallback-on-error
  (testing "when LLM throws, orchestrator still returns results with fallback intro"
    (let [cards (sample-cards)
          search-result (mock-search-result cards)]
      (with-redefs [search/search       (fn [q & opts] search-result)
                    llm/llm             (fn [& _] (throw (Exception. "LLM API timeout")))
                    monitor-store/get-category-summary (fn [] [])]
        (let [result (orch/orchestrate "найди iphone 13" nil)]
          ;; Should still return a shortlist, not crash
          (is (= :shortlist (:mode result)))
          ;; Fallback intro should mention item count
          (is (string? (:intro result)))
          (is (str/includes? (:intro result) "5"))
          ;; Should include some cards (fallback picks first 8)
          (is (pos? (count (:cards result))))
          ;; CTA should have default text
          (is (= "Хотите уточнить?" (:cta result))))))))

;; ════════════════════ TEST 5: REFINE USES LAST SEARCH ════════════════════

(deftest test-refine-uses-last-search
  (testing "smart refine applies price filter for дешевле"
    (let [session      (make-session {:last-search "iphone" :last-price-max 999999})
          search-result (mock-search-result (sample-cards))
          curator-json {"selected" [0 1 2]
                        "tiers"    {"0" "good" "1" "good" "2" "good"}
                        "intro"    "Refined: found 3 items"
                        "cta"      "Need more?"
                        "assumptions" []}
          llm-response {"choices" [{"message" {"content" (cheshire.core/generate-string curator-json)}}]}]
      (with-redefs [search/search       (fn [q & opts]
                                           ;; Smart refine keeps original query, adds price filter
                                           (assert (str/includes? q "iphone")
                                                   (str "Expected refined query to contain 'iphone', got: " q))
                                           search-result)
                    llm/llm             (fn [model msgs tools & opts] llm-response)
                    monitor-store/get-category-summary (fn [] [])]
        (let [result (orch/orchestrate "дешевле" session)]
          (is (= :refine (:mode result)))
          (is (= "Refined: found 3 items" (:intro result)))
          (is (= 3 (count (:cards result))))
          ;; Smart refine adds price adjustment to assumptions
          (is (some #(str/includes? % "бюджет") (:assumptions result))))))))

;; ════════════════════ TEST 6: COMPARE MODE ════════════════════

(deftest test-compare-returns-fallback-on-error
  (testing "compare intent catches errors and returns helpful fallback"
    (with-redefs [search/search (fn [& _] (throw (Exception. "search unavailable")))
                  llm/llm       (fn [& _] (throw (Exception. "LLM unavailable")))]
      (let [result (orch/orchestrate "что лучше, iphone или samsung" nil)]
        (is (= :shortlist (:mode result)))
        (is (str/includes? (:intro result) "поиском"))
        (is (empty? (:cards result)))))))

;; ════════════════════ TEST 7: SESSION STATE IS UPDATED ════════════════════

(deftest test-session-state-updated-after-search
  (testing "orchestrate patches session with last-search after successful search"
    (let [session      (make-session {})
          cards        (sample-cards)
          search-result (mock-search-result cards)
          curator-json {"selected" [0 1 2]
                        "tiers"    {"0" "good" "1" "good" "2" "good"}
                        "intro"    "Found items"
                        "cta"      "Next?"
                        "assumptions" []}
          llm-response {"choices" [{"message" {"content" (cheshire.core/generate-string curator-json)}}]}]
      (with-redefs [search/search       (fn [q & opts] search-result)
                    llm/llm             (fn [model msgs tools & opts] llm-response)
                    monitor-store/get-category-summary (fn [] [])]
        (orch/orchestrate "найди iphone 13" session)
        ;; Session should now contain last-search
        (let [state (get @session "data")]
          (is (= "iphone 13" (:last-search state)))
          (is (= [:lalafo] (:last-platforms state))))))))
