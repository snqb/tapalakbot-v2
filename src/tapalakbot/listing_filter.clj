(ns tapalakbot.listing-filter
  "Shared deterministic filtering for marketplace listing titles."
  (:require [clojure.string :as str]))

(def ^:private accessory-keywords
  {:strong #{"зарядк" "зарядное" "зарядный" "charger" "кабел" "кабель"
             "адаптер" "adapter" "стекло" "плeнк" "пленк"
             "чехол" "чехоль" "case" "обложк" "ремонт" "починк"
             "установка" "установк" "монтаж" "настройк" "прошивк"
             "гравировк" "замена" "восстановлен" "коробка" "упаковка"
             "box" "packaging" "гарантия" "страховка" "insurance"
             "warranty" "подарочный сертификат" "gift card" "схема"
             "инструкция" "manual"}
   :medium #{"держател" "холдер" "holder" "подставк" "stand"
             "кронштейн" "mount" "креплен" "bracket" "наклейк"
             "стикер" "sticker" "скин" "skin" "заглушк" "plug"
             "колпачек" "cap" "аксессуар" "accessory" "дополнительн"
             "запчаст" "spare part" "комплектующ" "батарейк" "battery"
             "аккумулятор" "переходник" "коннектор" "connector" "кнопк"
             "button" "клавиш"}
   :weak #{"услуг" "сервис" "service" "аренда" "прокат" "rent"
           "обмен" "trade" "бартер" "совместим" "compatible" "аналог"
           "analog" "набор" "комплект" "set" "kit" "bluetooth"
           "блютуз" "ручк" "стилус" "stylus" "pencil" "перо"}})

(def ^:private accessory-query-keywords
  ["чехол" "зарядк" "кабел" "наушник" "кейс" "стекло" "пленк"
   "адаптер" "батарейк" "аккумулятор"])

(defn accessory-score
  "Return a weighted accessory/service score for a listing title."
  [title]
  (let [text (str/lower-case (or title ""))
        hits (fn [kind]
               (count (filter #(str/includes? text %)
                              (get accessory-keywords kind))))]
    (min 15 (+ (* 5 (hits :strong))
               (* 3 (hits :medium))
               (* 2 (hits :weak))))))

(defn filter-accessories
  "Remove items scoring at or above threshold unless the query explicitly asks
   for an accessory. Supports string-keyed source items and keyword card maps."
  ([items user-query]
   (filter-accessories items user-query 5))
  ([items user-query threshold]
   (let [query (some-> user-query str/lower-case)
         wants-accessory? (and query
                               (some #(str/includes? query %)
                                     accessory-query-keywords))]
     (if wants-accessory?
       (vec items)
       (filterv (fn [item]
                  (< (accessory-score (or (get item "title") (:title item)))
                     threshold))
                items)))))
