(ns tapalakbot.policy-test
  (:require [clojure.test :refer :all]
            [tapalakbot.policy :as p]))

;; ════════════════════ GREETINGS ════════════════════

(deftest test-greetings
  (is (= :greeting (p/classify "привет" nil)))
  (is (= :greeting (p/classify "Привет!" nil)))
  (is (= :greeting (p/classify "салам" nil)))
  (is (= :greeting (p/classify "hello" nil)))
  (is (= :greeting (p/classify "добрый день" nil)))
  (is (= :greeting (p/classify "  привет  " nil))))

;; ════════════════════ SEARCH ════════════════════

(deftest test-search
  (is (= :search (p/classify "найди iphone 13" nil)))
  (is (= :search (p/classify "купить ноутбук до 50000" nil)))
  (is (= :search (p/classify "hyundai solaris 2020" nil)))
  (is (= :search (p/classify "ищу роутер до 4000" nil)))
  (is (= :search (p/classify "сколько стоит samsung galaxy" nil)))
  (is (= :search (p/classify "iphone 13 pro max 256gb" nil)))
  (is (= :search (p/classify "купить квартиру в бишкеке" nil))))

;; ════════════════════ FAST PATHS ════════════════════

(deftest test-reset
  (is (= :reset (p/classify "новый диалог" nil)))
  (is (= :reset (p/classify "сброс" nil)))
  (is (= :reset (p/classify "заново" nil))))

(deftest test-tracking
  (is (= :tracking (p/classify "мои подписки" nil)))
  (is (= :tracking (p/classify "отслеживание" nil))))

(deftest test-help
  (is (= :help (p/classify "помощь" nil)))
  (is (= :help (p/classify "что умеешь" nil))))

(deftest test-thanks
  (is (= :thanks (p/classify "спасибо" nil)))
  (is (= :thanks (p/classify "спс" nil)))
  (is (= :thanks (p/classify "ок" nil)))
  (is (= :thanks (p/classify "понял" nil))))

;; ════════════════════ COMPARE ════════════════════

(deftest test-greeting-vs-search
  ;; Search intent beats greeting prefix
  (is (= :search (p/classify "привет найди телефон" nil)))
  (is (= :search (p/classify "салам ищу велосипед" nil)))
  ;; Pure greeting still works
  (is (= :greeting (p/classify "привет" nil)))
  (is (= :greeting (p/classify "салам" nil))))

(deftest test-typo-tolerance
  (is (= :search (p/classify "велосиепед" nil)))
  (is (= :search (p/classify "вело" nil)))
  (is (= :search (p/classify "машина" nil)))
  (is (= :search (p/classify "samsng galaxy" nil)))
  (is (= :search (p/classify "макбуук про" nil)))
  (is (= :search (p/classify "айфон12" nil))))

;; ════════════════════ REFINE ════════════════════

(deftest test-refine-keyword
  (is (= :refine (p/classify "дешевле" nil)))
  (is (= :refine (p/classify "дороже" nil)))
  (is (= :refine (p/classify "только новые" nil))))

(deftest test-refine-with-session
  ;; Short context phrase + active session → :refine
  (is (= :refine (p/classify "а карбон" {:data {:last-search "велосипед"}})))
  (is (= :refine (p/classify "с ssd" {:data {:last-search "ноутбук"}})))
  ;; Without session → :unknown
  (is (= :unknown (p/classify "а карбон" nil)))
  ;; Long message with session → :search (not refine)
  (is (= :search (p/classify "найди карбоновый велосипед в бишкеке"
                              {:data {:last-search "велосипед"}}))))

;; ════════════════════ UNKNOWN ════════════════════

(deftest test-unknown
  (is (= :unknown (p/classify "расскажи анекдот" nil)))
  (is (= :unknown (p/classify "" nil)))
  (is (= :unknown (p/classify nil nil))))

;; ════════════════════ DECISION HELPERS ════════════════════

(deftest test-should-search
  (is (p/should-search? :search))
  (is (p/should-search? :refine))
  (is (not (p/should-search? :greeting)))
  (is (not (p/should-search? :unknown))))

(deftest test-needs-llm
  (is (p/needs-llm? :unknown))
  (is (p/needs-llm? :compare))
  (is (not (p/needs-llm? :search)))
  (is (not (p/needs-llm? :greeting))))
