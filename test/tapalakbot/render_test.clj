(ns tapalakbot.render-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [tapalakbot.render :as r]))

;; ════════════════════ PRICE FORMATTING ════════════════════

(deftest test-format-price
  (is (= "25 000" (r/format-price 25000)))
  (is (= "1 234 567" (r/format-price 1234567)))
  (is (= "500" (r/format-price 500)))
  (is (nil? (r/format-price nil))))

;; ════════════════════ TIER ASSIGNMENT ════════════════════

(deftest test-assign-tier
  (is (= :great (r/assign-tier 20000 35000)))    ;; 57% of avg
  (is (= :good (r/assign-tier 30000 35000)))     ;; 86% of avg
  (is (= :premium (r/assign-tier 50000 35000)))  ;; 143% of avg
  (is (nil? (r/assign-tier nil 35000)))
  (is (nil? (r/assign-tier 25000 nil))))

(deftest test-tier-emoji
  (is (= "🔥" (r/tier-emoji :great)))
  (is (= "💰" (r/tier-emoji :good)))
  (is (= "💎" (r/tier-emoji :premium)))
  (is (= "•" (r/tier-emoji nil))))

;; ════════════════════ CARD RENDERING ════════════════════

(deftest test-render-card
  (let [card {:title "iPhone 13 128GB" :price 25000 :currency "KGS"
              :url "https://lalafo.kg/123" :tier :good :condition "хороший"}
        html (r/render-card card)]
    (is (str/includes? html "iPhone 13 128GB"))
    (is (str/includes? html "25 000"))
    (is (str/includes? html "KGS"))
    (is (str/includes? html "lalafo.kg"))
    (is (str/includes? html "href="))
    (is (str/includes? html "хороший"))))

(deftest test-render-card-no-url
  (let [html (r/render-card {:title "Test" :price 100 :tier :great})]
    (is (str/includes? html "Test"))
    (is (str/includes? html "100"))
    (is (not (str/includes? html "href=")))))

(deftest test-render-card-with-year-mileage
  (let [html (r/render-card {:title "Hyundai Solaris" :price 800000 :year 2020
                             :mileage 45000 :city "Бишкек" :tier :good})]
    (is (str/includes? html "2020"))
    (is (str/includes? html "45 000"))
    (is (str/includes? html "Бишкек"))))

;; ════════════════════ REPLY RENDERING ════════════════════

(deftest test-render-reply-shortlist
  (let [reply {:mode :shortlist
               :intro "Нашёл 3 варианта"
               :cards [{:title "A" :price 100 :url "http://a.com" :tier :great}
                       {:title "B" :price 200 :url "http://b.com" :tier :good}]
               :cta "Хотите ещё?"
               :assumptions ["Цены в сомах"]}
        html (r/render-reply reply)]
    (is (str/includes? html "Нашёл 3 варианта"))
    (is (str/includes? html "🔥"))        ;; great tier
    (is (str/includes? html "💰"))        ;; good tier
    (is (str/includes? html "Хотите ещё?"))
    (is (str/includes? html "Цены в сомах"))))

(deftest test-render-reply-error
  (is (str/includes? (r/render-reply {:mode :error}) "❌")))

(deftest test-render-reply-no-results
  (is (str/includes? (r/render-reply {:mode :no-results}) "🔍")))

(deftest test-render-reply-clarify
  (is (str/includes? (r/render-reply {:mode :clarify}) "❗")))

(deftest test-render-reply-vector-assumptions
  (let [html (r/render-reply {:mode :shortlist :intro "Hi"
                              :assumptions ["a" "b" "c"]})]
    (is (str/includes? html "a · b · c"))))

(deftest agent-markdown-is-normalized-for-telegram-html
  (let [markdown (str "| Показатель | Значение |\n"
                      "| --- | --- |\n"
                      "| Диапазон цен | 100 500 — 118 000 сом |\n\n"
                      "🏆 [MacBook Pro](https://lalafo.kg/ad/1) — ==100 500 сом==")
        html (r/strip-markdown markdown)]
    (is (str/includes? html "<pre>"))
    (is (str/includes? html "<a href='https://lalafo.kg/ad/1'>MacBook Pro</a>"))
    (is (str/includes? html "<b>100 500 сом</b>"))
    (is (not (str/includes? html "| ---")))
    (is (not (str/includes? html "==")))))

(deftest native-results-keep-links-outside-tables-for-android
  (let [cards (mapv (fn [n]
                      {:title (str "MacBook " n)
                       :price (+ 30000 (* n 1000))
                       :currency "KGS"
                       :url (str "https://lalafo.kg/ad/" n)
                       :city "Бишкек"
                       :tier :good})
                    (range 1 10))
        html (r/render-results-rich cards)]
    (is (str/includes? html "<h2>Варианты (9)</h2>"))
    (is (not (str/includes? html "<table")))
    (is (= 6 (count (re-seq #"<h3>" html))))
    (is (str/includes? html "<details><summary>Ещё 3 варианта</summary>"))
    (is (str/includes? html
                       "<a href=\"https://lalafo.kg/ad/9\">Открыть объявление →</a>"))
    (is (not (str/includes? html "━━━━━━━━━━━━━━━")))))

(deftest native-results-embed-six-top-images-in-rich-slideshow
  (let [cards (mapv (fn [n]
                      {:title (str "MacBook " n)
                       :url (str "https://lalafo.kg/ad/" n)
                       :image (str "https://img.example/" n ".jpg")})
                    (range 1 9))
        html (r/render-results-rich cards)]
    (is (str/includes? html "<tg-slideshow>"))
    (is (= 6 (count (re-seq #"<img src=" html))))
    (is (str/includes? html "<img src=\"https://img.example/1.jpg\"/>"))
    (is (not (str/includes? html "https://img.example/7.jpg")))))

(deftest native-auto-results-show-decision-details-and-source
  (let [html (r/render-results-rich
              [{:title "Toyota Camry 70"
                :price 1850000
                :currency "KGS"
                :url "https://mashina.kg/details/camry"
                :platform :mashina
                :year 2019
                :mileage 75000
                :engine 2.5
                :gearbox "автомат"
                :city "Бишкек"}])]
    (is (str/includes? html "2019 г. · 75 000 км · 2.5 л · автомат · 📍 Бишкек"))
    (is (str/includes? html
                       "<a href=\"https://mashina.kg/details/camry\">Открыть на Mashina.kg →</a>"))
    (is (str/includes? html "<footer>Источник: Mashina.kg</footer>"))))

(deftest twenty-rich-results-fit-the-telegram-message-budget
  (let [cards (mapv (fn [n]
                      {:title (str "Toyota Camry 70 — надёжный автомобиль " n)
                       :price (+ 1500000 (* n 1000))
                       :currency "KGS"
                       :url (str "https://mashina.kg/details/camry-" n)
                       :image (str "https://storage.mashina.kg/camry-" n ".webp")
                       :platform :mashina
                       :year 2020
                       :mileage (+ 50000 n)
                       :engine 2.5
                       :gearbox "автомат"
                       :city "Бишкек"})
                    (range 20))
        html (r/render-results-rich cards)]
    (is (< (count html) 32768))
    (is (= 20 (count (re-seq #"<a href=" html))))
    (is (= 6 (count (re-seq #"<h3>" html))))
    (is (= 6 (count (re-seq #"<img src=" html))))
    (is (str/includes? html "<details><summary>Ещё 14 вариантов</summary>"))))
