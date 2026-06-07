;; Test: Letter token citation system
;; Verifies that #A, #B, #C tokens are correctly replaced with clickable links
(ns tapalakbot.citation-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]))

;; Simulated url-store (maps letter → entry)
(def test-url-store
  {"A" {:url "https://lalafo.kg/item/1" :title "iPad Pro 128GB"}
   "B" {:url "https://lalafo.kg/item/2" :title "iPad Air 64GB"}
   "C" {:url "https://lalafo.kg/item/3" :title "iPad Mini"}})

(defn simulate-citation-replace [text url-store]
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

(deftest letter-token-replacement
  (testing "Basic replacement"
    (let [text "• iPad Pro — #A — 78000\n• iPad Air — #B — 55000"
          result (simulate-citation-replace text test-url-store)]
      (is (str/includes? result "<a href='https://lalafo.kg/item/1'>"))
      (is (str/includes? result "<a href='https://lalafo.kg/item/2'>"))
      (is (not (str/includes? result "#A")))
      (is (not (str/includes? result "#B")))))

  (testing "Unknown letter preserved"
    (let [text "• Something — #Z — 1000"
          result (simulate-citation-replace text test-url-store)]
      (is (str/includes? result "#Z"))
      (is (not (str/includes? result "<a href=")))))

  (testing "Trailing dash stripped"
    (let [text "• iPad Pro — — #A — 78000"
          result (simulate-citation-replace text test-url-store)]
      (is (str/includes? result "iPad Pro</a>"))
      (is (not (str/includes? result "—</a>")))))

  (testing "Mixed content"
    (let [text "📱 Нашёл!\n\n💰 Хорошая цена\n• iPad Pro — #A — 78000\n• iPad Air — #B — 55000\n\n💎 Премиум\n• iPad Mini — #C — 95000"
          result (simulate-citation-replace text test-url-store)]
      (is (= 3 (count (re-seq #"<a href=" result))))
      (is (str/includes? result "78000"))
      (is (str/includes? result "95000")))))

(deftest letter-token-format
  (testing "Letter generation produces correct sequence"
    (let [letters (mapv #(str (char (+ 65 %))) (range 10))]
      (is (= ["A" "B" "C" "D" "E" "F" "G" "H" "I" "J"] letters)))))

(comment
  ;; Run tests
  (clojure.test/run-tests 'tapalakbot.citation-test))
