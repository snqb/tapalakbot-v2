(ns tapalakbot.locations
  "One Lalafo city vocabulary for conversation planning and Telegram controls."
  (:require [clojure.string :as str]))

(def cities
  [{:id 103184 :name "Бишкек" :icon "🏙️"}
   {:id 103185 :name "Ош" :icon "🏔️"}
   {:id 103202 :name "Каракол" :icon "🌊"}
   {:id 103199 :name "Джалал-Абад" :icon "🏭"}
   {:id 103203 :name "Нарын" :icon "🏛️"}
   {:id 103207 :name "Талас" :icon "🌿"}
   {:id 103206 :name "Баткен" :icon "🕌"}])

(defn context-text []
  (str "\nГОРОДА LALAFO: "
       (str/join "; " (map #(str (:name %) " city_id=" (:id %)) cities))
       ". Если пользователь назвал известный город, передай его city_id. "
       "null означает весь Кыргызстан, а не неизвестный код названного города. Не выдумывай ID."))

(defn keyboard []
  {"inline_keyboard"
   (mapv vector
         (cons {"text" "🇰🇬 Весь Кыргызстан" "callback_data" "city:nil"}
               (map (fn [{:keys [id name icon]}]
                      {"text" (str icon " " name) "callback_data" (str "city:" id)}) cities)))})
