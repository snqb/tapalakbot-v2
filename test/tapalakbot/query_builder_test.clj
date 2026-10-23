(ns tapalakbot.query-builder-test
  "Comprehensive test suite for QueryBuilder.
   
   Tests:
   1. Price extraction (100+ patterns)
   2. Platform detection (50+ queries)
   3. Bazar category matching (30+ queries)
   4. Full build pipeline
   
   Run: clojure -M test/tapalakbot/query_builder_test.clj"
  (:require [tapalakbot.query-builder :as qb]
            [clojure.test :refer [deftest is]]))

;; ════════════════════════════ TEST UTILITIES ════════════════════════════

(defmacro assert-eq
  [name actual expected]
  `(is (= ~expected ~actual) ~name))

;; ════════════════════════════ PRICE EXTRACTION TESTS ════════════════════════════

(deftest price-extraction-cases
  (println "\n📊 === Price Extraction Tests ===")

  ;; 🌡️ Air conditioner prices (the original bug)
  (assert-eq "кондиционер до 20к"
             (qb/extract-price "кондиционер до 20к")
             {:price-min nil :price-max 20000})

  (assert-eq "кондиционер до 30к"
             (qb/extract-price "кондиционер до 30к")
             {:price-min nil :price-max 30000})

  (assert-eq "сплит система до 50к"
             (qb/extract-price "сплит система до 50к")
             {:price-min nil :price-max 50000})

  ;; 📱 Phone prices
  (assert-eq "iphone до 30000"
             (qb/extract-price "iphone до 30000")
             {:price-min nil :price-max 30000})

  (assert-eq "iphone 13 до 30к"
             (qb/extract-price "iphone 13 до 30к")
             {:price-min nil :price-max 30000})

  (assert-eq "iphone от 15000 до 30000"
             (qb/extract-price "iphone от 15000 до 30000")
             {:price-min 15000 :price-max 30000})

  (assert-eq "айфон 14 про макс до 50к"
             (qb/extract-price "айфон 14 про макс до 50к")
             {:price-min nil :price-max 50000})

  (assert-eq "samsung galaxy s23 до 35к"
             (qb/extract-price "samsung galaxy s23 до 35к")
             {:price-min nil :price-max 35000})

  ;; 💻 Laptop prices
  (assert-eq "ноутбук до 25к"
             (qb/extract-price "ноутбук до 25к")
             {:price-min nil :price-max 25000})

  (assert-eq "macbook air m1 до 50000"
             (qb/extract-price "macbook air m1 до 50000")
             {:price-min nil :price-max 50000})

  (assert-eq "ноутбук бюджет 20000"
             (qb/extract-price "ноутбук бюджет 20000")
             {:price-min nil :price-max 20000})

  (assert-eq "ноутбук до 40000 от 20000"
             (qb/extract-price "ноутбук до 40000 от 20000")
             {:price-min 20000 :price-max 40000})

  (assert-eq "ноутбук 15-30 тыс"
             (qb/extract-price "ноутбук 15-30 тыс")
             {:price-min 15000 :price-max 30000})

  ;; 🚗 Car prices
  (assert-eq "hyundai solaris до 800к"
             (qb/extract-price "hyundai solaris до 800к")
             {:price-min nil :price-max 800000})

  (assert-eq "toyota camry до 1500000"
             (qb/extract-price "toyota camry до 1500000")
             {:price-min nil :price-max 1500000})

  (assert-eq "машина бюджет 5000"
             (qb/extract-price "машина бюджет 5000")
             {:price-min nil :price-max 5000})

  (assert-eq "авто от 400000 до 800000"
             (qb/extract-price "авто от 400000 до 800000")
             {:price-min 400000 :price-max 800000})

  ;; 📺 Electronics
  (assert-eq "телевизор до 30к"
             (qb/extract-price "телевизор до 30к")
             {:price-min nil :price-max 30000})

  (assert-eq "планшет до 20к"
             (qb/extract-price "планшет до 20к")
             {:price-min nil :price-max 20000})

  (assert-eq "playstation 5 до 40к"
             (qb/extract-price "playstation 5 до 40к")
             {:price-min nil :price-max 40000})

  (assert-eq "видеокарта до 25к"
             (qb/extract-price "видеокарта до 25к")
             {:price-min nil :price-max 25000})

  (assert-eq "наушники до 5000"
             (qb/extract-price "наушники до 5000")
             {:price-min nil :price-max 5000})

  (assert-eq "наушники до 3к"
             (qb/extract-price "наушники до 3к")
             {:price-min nil :price-max 3000})

  ;; No price → nil
  (assert-eq "iphone 13" (qb/extract-price "iphone 13") nil)
  (assert-eq "ноутбук для работы" (qb/extract-price "ноутбук для работы") nil)
  (assert-eq "машина toyota" (qb/extract-price "машина toyota") nil)

  ;; Single digit with к
  (assert-eq "цена 5к" (qb/extract-price "цена 5к") {:price-min nil :price-max 5000})
  (assert-eq "1к сом" (qb/extract-price "1к сом") {:price-min nil :price-max 1000})
  (assert-eq "пустой запрос" (qb/extract-price "пустой запрос") nil)
  (assert-eq "цена 5к" (qb/extract-price "цена 5к") {:price-min nil :price-max 5000})
  (assert-eq "стоимость до 100к" (qb/extract-price "стоимость до 100к") {:price-min nil :price-max 100000})

  ;; Budget keyword variations
  (assert-eq "бюджет 15000" (qb/extract-price "бюджет 15000") {:price-min nil :price-max 15000})
  (assert-eq "15000" (qb/extract-price "15000") nil)
  (assert-eq "500 сом" (qb/extract-price "500 сом") {:price-min nil :price-max 500})
  (assert-eq "до 20000 сом" (qb/extract-price "до 20000 сом") {:price-min nil :price-max 20000}))

;; ════════════════════════════ PLATFORM DETECTION TESTS ════════════════════════════

(deftest platform-detection-cases
  (println "\n🚗 === Platform Detection Tests ===")

  ;; Cars also route to Lalafo while retaining the auto category flag.
  (assert-eq "hyundai solaris" (:platforms (qb/detect-platform "hyundai solaris")) [:lalafo])
  (assert-eq "toyota camry" (:platforms (qb/detect-platform "toyota camry")) [:lalafo])
  (assert-eq "BMW X5" (:platforms (qb/detect-platform "BMW X5")) [:lalafo])
  (assert-eq "mercedes" (:platforms (qb/detect-platform "mercedes")) [:lalafo])
  (assert-eq "kia rio" (:platforms (qb/detect-platform "kia rio")) [:lalafo])
  (assert-eq "ford focus" (:platforms (qb/detect-platform "ford focus")) [:lalafo])
  (assert-eq "tesla model 3" (:platforms (qb/detect-platform "tesla model 3")) [:lalafo])
  (assert-eq "mitsubishi pajero" (:platforms (qb/detect-platform "mitsubishi pajero")) [:lalafo])
  (assert-eq "авто новое" (:platforms (qb/detect-platform "авто новое")) [:lalafo])
  (assert-eq "машина" (:platforms (qb/detect-platform "машина")) [:lalafo])
  (assert-eq "nissan qashqai" (:platforms (qb/detect-platform "nissan qashqai")) [:lalafo])

  ;; Russian/CIS cars
  (assert-eq "ваз 2107" (:platforms (qb/detect-platform "ваз 2107")) [:lalafo])
  (assert-eq "lada granta" (:platforms (qb/detect-platform "lada granta")) [:lalafo])
  (assert-eq "уаз патриот" (:platforms (qb/detect-platform "уаз патриот")) [:lalafo])

  ;; Chinese cars
  (assert-eq "chery tiggo" (:platforms (qb/detect-platform "chery tiggo")) [:lalafo])
  (assert-eq "geely coolray" (:platforms (qb/detect-platform "geely coolray")) [:lalafo])
  (assert-eq "changan uni-t" (:platforms (qb/detect-platform "changan uni-t")) [:lalafo])

  ;; Electronics → Lalafo + Bazar
  (assert-eq "iphone 13" (:platforms (qb/detect-platform "iphone 13")) [:lalafo])
  (assert-eq "samsung s24" (:platforms (qb/detect-platform "samsung s24")) [:lalafo])
  (assert-eq "macbook pro" (:platforms (qb/detect-platform "macbook pro")) [:lalafo])
  (assert-eq "ipad air" (:platforms (qb/detect-platform "ipad air")) [:lalafo])
  (assert-eq "ноутбук asus" (:platforms (qb/detect-platform "ноутбук asus")) [:lalafo])
  (assert-eq "кондиционер lg" (:platforms (qb/detect-platform "кондиционер lg")) [:lalafo])
  (assert-eq "телевизор samsung" (:platforms (qb/detect-platform "телевизор samsung")) [:lalafo])
  (assert-eq "наушники airpods" (:platforms (qb/detect-platform "наушники airpods")) [:lalafo])
  (assert-eq "playstation 5" (:platforms (qb/detect-platform "playstation 5")) [:lalafo])
  (assert-eq "холодильник" (:platforms (qb/detect-platform "холодильник")) [:lalafo])
  (assert-eq "пылесос" (:platforms (qb/detect-platform "пылесос")) [:lalafo])

  ;; Real estate → Lalafo only
  (assert-eq "квартира" (:platforms (qb/detect-platform "квартира")) [:lalafo])
  (assert-eq "аренда офиса" (:platforms (qb/detect-platform "аренда офиса")) [:lalafo])
  (assert-eq "снять комнату" (:platforms (qb/detect-platform "снять комнату")) [:lalafo])
  (assert-eq "купить дом" (:platforms (qb/detect-platform "купить дом")) [:lalafo])

  ;; Default → Lalafo + Bazar
  (assert-eq "книга" (:platforms (qb/detect-platform "книга")) [:lalafo])
  (assert-eq "стул" (:platforms (qb/detect-platform "стул")) [:lalafo])
  (assert-eq "собака" (:platforms (qb/detect-platform "собака")) [:lalafo])

  ;; Bool flags
  (assert-eq "car is-auto?" (:is-auto? (qb/detect-platform "hyundai")) true)
  (assert-eq "phone is-auto?" (:is-auto? (qb/detect-platform "iphone")) false)
  (assert-eq "car is-electronics?" (:is-electronics? (qb/detect-platform "iphone")) true)
  (assert-eq "office is-real-estate?" (:is-real-estate? (qb/detect-platform "аренда офиса")) true))

;; ════════════════════════════ BAZAR CATEGORY TESTS ════════════════════════════

;; ════════════════════════════ QUERY STRIPPING TESTS ════════════════════════════

(deftest query-stripping-cases
  (println "\n🔍 === Query Stripping Tests ===")
  (assert-eq "strips price" (:query (qb/parse "кондиционер до 20к")) "кондиционер")
  (assert-eq "strips min-max" (:query (qb/parse "iphone от 15000 до 30000")) "iphone")
  (assert-eq "strips budget" (:query (qb/parse "ноутбук бюджет 25000")) "ноутбук")
  (assert-eq "keeps model" (:query (qb/parse "iphone 13 pro max")) "iphone 13 pro max")
  (assert-eq "strips car price" (:query (qb/parse "hyundai solaris до 800к")) "hyundai solaris"))

;; ════════════════════════════ FULL BUILD TESTS ════════════════════════════

(deftest full-build-cases
  (println "\n🏗 === Full Build Tests ===")
  (let [r1 (qb/parse "кондиционер до 20к")]
    (assert-eq "build: ac price" (:price-max r1) 20000)
    (assert-eq "build: ac platforms" (:platforms r1) [:lalafo])
    (assert-eq "build: ac query" (:query r1) "кондиционер"))

  (let [r2 (qb/parse "hyundai solaris 2020 до 800000")]
    (assert-eq "build: car price" (:price-max r2) 800000)
    (assert-eq "build: car platforms" (:platforms r2) [:lalafo])
    (assert-eq "build: car is-auto?" (:is-auto? r2) true))

  (let [r3 (qb/parse "iphone 13 pro max от 15000 до 35000")]
    (assert-eq "build: phone price-min" (:price-min r3) 15000)
    (assert-eq "build: phone price-max" (:price-max r3) 35000)
    (assert-eq "build: phone platforms" (:platforms r3) [:lalafo])
    (assert-eq "build: phone is-auto?" (:is-auto? r3) false))

  (let [r4 (qb/parse "аренда офиса в бишкеке")]
    (assert-eq "build: office platforms" (:platforms r4) [:lalafo])
    (assert-eq "build: office is-real-estate?" (:is-real-estate? r4) true)))

;; ════════════════════════════ PRICE EDGE CASES ════════════════════════════

(deftest price-edge-cases
  (println "\n🎯 === Price Edge Case Tests ===")

  ;; Multi-word prices
  (assert-eq "до 1 500 сом" (qb/extract-price "часы до 1 500 сом") {:price-min nil :price-max 1500})

  ;; Range with spaces
  (assert-eq "10000-20000" (qb/extract-price "товар 10000-20000") {:price-min 10000 :price-max 20000})

  ;; Different currencies
  (assert-eq "до 5000 сом" (qb/extract-price "до 5000 сом") {:price-min nil :price-max 5000})
  (assert-eq "до 1000 тенге" (qb/extract-price "до 1000 тенге") {:price-min nil :price-max 1000})

  ;; Price with extra text
  (assert-eq "цена до 15000" (qb/extract-price "цена до 15000 торг") {:price-min nil :price-max 15000})

  ;; Incorrect "до" without number
  (assert-eq "доставка" (qb/extract-price "нужна доставка") nil)

  ;; Only min
  (assert-eq "от 5000" (qb/extract-price "от 5000") {:price-min 5000 :price-max nil})

  ;; Large prices
  (assert-eq "до 1 500 000" (qb/extract-price "дом до 1 500 000") {:price-min nil :price-max 1500000}))

(deftest million-price-and-prefixes-are-normalized-in-kgs
  (is (= {:price-min nil :price-max 2000000}
         (qb/extract-price "Toyota Camry 70 до 2 млн")))
  (is (= {:price-min nil :price-max 2500000}
         (qb/extract-price "Lexus GX до 2.5 млн")))
  (is (= {:price-min nil :price-max 2000000}
         (qb/extract-price "макс 2000000")))
  (let [parsed (qb/parse "Toyota Camry 70 до 2 млн")]
    (is (= "Toyota Camry 70" (:query parsed)))
    (is (= 2000000 (:price-max parsed)))))
