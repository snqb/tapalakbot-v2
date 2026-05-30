(ns tapalakbot.test-scenarios
  "50 test scenarios for TapalakBot agent.
   Tests cover: basic search, edge cases, follow-ups, context resets, error handling."
  (:require [tapalakbot.core :as t]
            [tapalakbot.lalafo :as lalafo]
            [clj-harness.core :as h]
            [clj-harness.effects :as fx]
            [clojure.core.async :refer [chan sliding-buffer close! <!!]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

;; ════════════════════════════ TEST INFRA ════════════════════════════

(def ^:private test-bot (atom nil))

(defn- get-bot []
  (or @test-bot
      (let [bot (h/create-bot {:name "test"
                               :prompt t/system-prompt
                               :tools t/tools
                               :model :deepseek-v4-pro
                               :provider :deepseek
                               :max-turns 5
                               :nudges {:required-steps ["smart_search"]
                                        :recover-tool-errors? true}})]
        (reset! test-bot bot)
        bot)))

(defn- query-bot
  "Send a message to the bot and return the response.
   Options:
     :user-id — user identifier (default: \"test-user\")
     :timeout-ms — max wait time (default: 60000)"
([text] (query-bot text {}))
   ([text {:keys [user-id timeout-ms] :or {user-id "test-user" timeout-ms 120000}}]
   (let [bot (get-bot)
         events> (chan (sliding-buffer 64))
         result-promise (promise)]
     ;; Run in a future with timeout
     (future
       (try
         (let [result (h/handle-message bot user-id text :events> events>)]
           (deliver result-promise result))
         (catch Exception e
           (deliver result-promise {:error (.getMessage e)}))))
     ;; Wait for result with timeout
     (let [result (deref result-promise timeout-ms {:error "timeout"})]
       (close! events>)
       result))))

(defn- query-bot-with-context
  "Send multiple messages in sequence to test context."
  [messages]
  (let [bot (get-bot)
        uid (str "ctx-" (System/currentTimeMillis))]
    (mapv (fn [msg]
            (let [events> (chan (sliding-buffer 64))
                  result (try
                           (h/handle-message bot uid msg :events> events>)
                           (catch Exception e {:error (.getMessage e)}))]
              (close! events>)
              result))
          messages)))

(defn- assert-contains
  "Assert that text contains expected substring."
  [text expected]
  (when-not (str/includes? (str text) expected)
    (throw (ex-info (str "Expected '" expected "' in response")
                    {:text text :expected expected}))))

(defn- assert-has-url
  "Assert that response contains a lalafo.kg URL."
  [text]
  (when-not (re-find #"lalafo\.kg" (str text))
    (throw (ex-info "Expected lalafo.kg URL in response"
                    {:text text}))))

(defn- assert-no-table
  "Assert that response doesn't contain markdown table syntax."
  [text]
  (when (re-find #"\|---" (str text))
    (throw (ex-info "Found markdown table in response"
                    {:text text}))))

(defn- assert-min-length
  "Assert that text has minimum length."
  [text min-len]
  (when (< (count (str text)) min-len)
    (throw (ex-info (str "Response too short: " (count (str text)) " < " min-len)
                    {:text text}))))

(defn- run-test
  "Run a single test scenario. Returns {:name ... :pass? ... :error ... :ms ...}"
  [name f]
  (let [t0 (System/currentTimeMillis)
        result (try
                 (f)
                 {:pass? true}
                 (catch Exception e
                   {:pass? false :error (.getMessage e)}))
        elapsed (- (System/currentTimeMillis) t0)]
    (merge {:name name :ms elapsed} result)))

;; ════════════════════════════ SCENARIOS ════════════════════════════

(def scenarios
  [
   ;; ─── BASIC SEARCH (1-10) ────────────────────────────────────────
   {:name "1. iPhone 13 search"
    :fn (fn []
          (let [r (query-bot "найди iphone 13")]
            (assert-contains r "iPhone")
            (assert-has-url r)))}

   {:name "2. Samsung Galaxy search"
    :fn (fn []
          (let [r (query-bot "ищу samsung galaxy s23")]
            (assert-contains r "Samsung")))}

   {:name "3. MacBook search"
    :fn (fn []
          (let [r (query-bot "macbook air m1 до 50000")]
            (assert-contains r "MacBook")))}

   {:name "4. PlayStation search"
    :fn (fn []
          (let [r (query-bot "playstation 5")]
            (assert-contains r "PlayStation")))}

   {:name "5. iPad search"
    :fn (fn []
          (let [r (query-bot "ipad до 30000")]
            (assert-contains r "iPad")))}

   {:name "6. Xiaomi phone search"
    :fn (fn []
          (let [r (query-bot "xiaomi redmi note 12")]
            (assert-contains r "Redmi")))}

   {:name "7. Laptop search with budget"
    :fn (fn []
          (let [r (query-bot "ноутбук до 40000 сом")]
            (assert-contains r "ноутбук")))}

   {:name "8. Headphones search"
    :fn (fn []
          (let [r (query-bot "airpods pro")]
            (assert-contains r "AirPods")))}

   {:name "9. Washing machine search"
    :fn (fn []
          (let [r (query-bot "стиральная машина до 20000")]
            (assert-contains r "стирал")))}

   {:name "10. TV search"
    :fn (fn []
          (let [r (query-bot "телевизор 55 дюймов")]
            (assert-contains r "телевизор")))}

   ;; ─── PRICE FILTERS (11-15) ──────────────────────────────────────
   {:name "11. Max price filter"
    :fn (fn []
          (let [r (query-bot "iphone до 20000")]
            (assert-contains r "iPhone")))}

   {:name "12. Min-max price range"
    :fn (fn []
          (let [r (query-bot "samsung от 10000 до 25000")]
            (assert-contains r "Samsung")))}

   {:name "13. High budget search"
    :fn (fn []
          (let [r (query-bot "macbook pro до 100000")]
            (assert-contains r "MacBook")))}

   {:name "14. Low budget search"
    :fn (fn []
          (let [r (query-bot "телефон до 5000")]
            (assert-contains r "телефон")))}

   {:name "15. Very specific price"
    :fn (fn []
          (let [r (query-bot "iphone 14 ровно 35000")]
            (assert-contains r "iPhone")))}

   ;; ─── ADVICE QUESTIONS (16-20) ───────────────────────────────────
   {:name "16. Advice: should I buy used?"
    :fn (fn []
          (let [r (query-bot "стоит ли брать б/у телефон?")]
            (assert-min-length r 50)))}

   {:name "17. Advice: which is better?"
    :fn (fn []
          (let [r (query-bot "что лучше samsung или xiaomi?")]
            (assert-min-length r 50)))}

   {:name "18. Advice: router for apartment"
    :fn (fn []
          (let [r (query-bot "какой роутер лучше для квартиры?")]
            (assert-min-length r 50)))}

   {:name "19. Advice: laptop for study"
    :fn (fn []
          (let [r (query-bot "какой ноутбук взять для учёбы?")]
            (assert-min-length r 50)))}

   {:name "20. Advice: is it worth overpaying?"
    :fn (fn []
          (let [r (query-bot "стоит ли переплачивать за iphone?")]
            (assert-min-length r 50)))}

   ;; ─── VAGUE QUERIES (21-25) ──────────────────────────────────────
   {:name "21. One word: ноутбук"
    :fn (fn []
          (let [r (query-bot "ноутбук")]
            ;; Should ask clarifying question
            (assert-min-length r 20)))}

   {:name "22. One word: телефон"
    :fn (fn []
          (let [r (query-bot "телефон")]
            (assert-min-length r 20)))}

   {:name "23. One word: планшет"
    :fn (fn []
          (let [r (query-bot "планшет")]
            (assert-min-length r 20)))}

   {:name "24. One word: наушники"
    :fn (fn []
          (let [r (query-bot "наушники")]
            (assert-min-length r 20)))}

   {:name "25. One word: телевизор"
    :fn (fn []
          (let [r (query-bot "телевизор")]
            (assert-min-length r 20)))}

   ;; ─── FOLLOW-UPS WITH CONTEXT (26-35) ────────────────────────────
   {:name "26. Follow-up: cheaper"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["iphone 13 до 30000" "а подешевле есть?"])]
            (assert-contains (second results) "iPhone")))}

   {:name "27. Follow-up: better"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["samsung до 20000" "а получше есть?"])]
            (assert-contains (second results) "Samsung")))}

   {:name "28. Follow-up: different brand"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["iphone 13" "а samsung есть?"])]
            (assert-contains (second results) "Samsung")))}

   {:name "29. Follow-up: with price"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["телефон" "до 15000"])]
            (assert-min-length (second results) 50)))}

   {:name "30. Follow-up: more details"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["macbook air" "расскажи подробнее"])]
            (assert-min-length (second results) 100)))}

   {:name "31. Follow-up: accessories"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["iphone 13" "а чехлы есть?"])]
            (assert-min-length (second results) 50)))}

   {:name "32. Follow-up: compare"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["iphone 13" "сравни с samsung s23"])]
            (assert-min-length (second results) 100)))}

   {:name "33. Follow-up: different category"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["iphone 13" "а наушники какие взять?"])]
            (assert-min-length (second results) 50)))}

   {:name "34. Follow-up: budget change"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["iphone до 20000" "а до 40000?"])]
            (assert-contains (second results) "iPhone")))}

   {:name "35. Follow-up: location change"
    :fn (fn []
          (let [results (query-bot-with-context
                         ["iphone в бишкеке" "а в оше?"])]
            (assert-min-length (second results) 50)))}

   ;; ─── EDGE CASES (36-45) ─────────────────────────────────────────
   {:name "36. Empty message"
    :fn (fn []
          (let [r (query-bot "")]
            (assert-min-length r 10)))}

   {:name "37. Very long message"
    :fn (fn []
          (let [r (query-bot (str "ищу " (str/join " " (repeat 50 "телефон"))))]
            (assert-min-length r 50)))}

   {:name "38. Special characters"
    :fn (fn []
          (let [r (query-bot "iphone 13 @#$%^&*()")]
            (assert-contains r "iPhone")))}

   {:name "39. Emoji in message"
    :fn (fn []
          (let [r (query-bot "📱 iphone 13 📱")]
            (assert-contains r "iPhone")))}

   {:name "40. Mixed language"
    :fn (fn []
          (let [r (query-bot "ищу iphone 13 белый цвет")]
            (assert-contains r "iPhone")))}

   {:name "41. Typo in brand"
    :fn (fn []
          (let [r (query-bot "айфон 13")]
            (assert-contains r "iPhone")))}

   {:name "42. Cyrillic brand name"
    :fn (fn []
          (let [r (query-bot "самсунг галактика")]
            (assert-contains r "Samsung")))}

   {:name "43. Multiple items"
    :fn (fn []
          (let [r (query-bot "ищу iphone и samsung")]
            (assert-min-length r 100)))}

   {:name "44. Negative price"
    :fn (fn []
          (let [r (query-bot "iphone до -1000")]
            (assert-min-length r 50)))}

   {:name "45. Very high price"
    :fn (fn []
          (let [r (query-bot "iphone до 1000000")]
            (assert-contains r "iPhone")))}

   ;; ─── CONTEXT RESET (46-50) ──────────────────────────────────────
   {:name "46. New user same query"
    :fn (fn []
          (let [r1 (query-bot "iphone 13" {:user-id "user-A"})
                r2 (query-bot "iphone 13" {:user-id "user-B"})]
            ;; Both should work independently
            (assert-contains r1 "iPhone")
            (assert-contains r2 "iPhone")))}

   {:name "47. User reset then query"
    :fn (fn []
          (let [uid (str "reset-" (System/currentTimeMillis))
                r1 (query-bot "iphone 13" {:user-id uid})
                ;; Simulate reset by using new user-id
                r2 (query-bot "samsung" {:user-id (str uid "-new")})]
            (assert-contains r2 "Samsung")))}

   {:name "48. Multiple users interleaved"
    :fn (fn []
          (let [r1 (query-bot "iphone" {:user-id "u1"})
                r2 (query-bot "samsung" {:user-id "u2"})
                r3 (query-bot "xiaomi" {:user-id "u3"})]
            (assert-contains r1 "iPhone")
            (assert-contains r2 "Samsung")
            (assert-contains r3 "Xiaomi")))}

   {:name "49. Long conversation then reset"
    :fn (fn []
          (let [uid "long-conv"
                _ (query-bot "iphone 13" {:user-id uid})
                _ (query-bot "а samsung есть?" {:user-id uid})
                _ (query-bot "а подешевле?" {:user-id uid})
                ;; New user
                r (query-bot "macbook" {:user-id "new-user"})]
            (assert-contains r "MacBook")))}

   {:name "50. Rapid fire queries"
    :fn (fn []
          (let [futures (mapv (fn [i]
                                (future (query-bot (str "товар " i)
                                                   {:user-id (str "rapid-" i)})))
                              (range 5))]
            (doseq [f futures]
              (let [r @f]
                (assert-min-length r 20)))))}])

;; ════════════════════════════ RUNNER ════════════════════════════

(defn run-all-tests
  "Run all test scenarios and return results."
  []
  (println "\n🧪 Running" (count scenarios) "test scenarios...\n")
  (let [results (mapv (fn [{:keys [name fn]}]
                        (let [result (run-test name fn)]
                          (if (:pass? result)
                            (println "  ✅" name "(" (:ms result) "ms)")
                            (println "  ❌" name "-" (:error result) "(" (:ms result) "ms)"))
                          result))
                      scenarios)
        passed (count (filter :pass? results))
        failed (count (filter #(not (:pass? %)) results))
        total (count results)]
    (println "\n════════════════════════════════════════")
    (println "  Results:" passed "/" total "passed")
    (when (pos? failed)
      (println "  Failed:" failed)
      (println "\n  Failed tests:")
      (doseq [r (filter #(not (:pass? %)) results)]
        (println "    •" (:name r) "-" (:error r))))
    (println "════════════════════════════════════════\n")
    {:passed passed :failed failed :total total :results results}))

(defn run-test-by-name
  "Run a single test by name."
  [name]
  (if-let [scenario (first (filter #(= name (:name %)) scenarios))]
    (run-test name (:fn scenario))
    (println "Test not found:" name)))

(comment
  ;; Run all tests
  (run-all-tests)

  ;; Run single test
  (run-test-by-name "1. iPhone 13 search")

  ;; Quick smoke test
  (query-bot "iphone 13")
  )
