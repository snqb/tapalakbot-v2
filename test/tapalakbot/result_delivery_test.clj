(ns tapalakbot.result-delivery-test
  (:require [clj-harness.telegram :as tg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tapalakbot.result-delivery :as delivery]))

(defn- cards [n]
  (mapv (fn [i]
          {:id i :title (str "Товар " i) :price 10000 :currency "KGS"
           :platform :lalafo :url (str "https://lalafo.kg/bishkek/ads/item-id-" i)
           :image (str "https://img.example/" i ".jpg")})
        (range 1 (inc n))))

(deftest long-refinements-remain-owned-and-keep-complete-intent
  (let [owner "tg-42:-100:17"
        query (str (apply str (repeat 12 "Компактная дорожка для ходьбы под столом. "))
                   "Только сам тренажёр, не коврик.")
        keyboard (delivery/results-keyboard query nil false owner {:cards (cards 1)})
        callback (get-in keyboard ["inline_keyboard" 0 0 "callback_data"])
        token (second (str/split callback #":" 2))]
    (is (<= (alength (.getBytes callback "UTF-8")) 64))
    (is (nil? (delivery/refinement-message "tg-42:-100:18" :cheaper token)))
    (let [refined (delivery/refinement-message owner :cheaper token)]
      (is (str/starts-with? (:text refined) query))
      (is (= 9000 (:price-max refined)))
      (is (contains? refined :price-min))
      (is (nil? (:price-min refined))))
    (is (nil? (delivery/refinement-message owner :cheaper token)))))

(deftest currency-unknown-or-foreign-prices-cannot-set-a-kgs-refinement
  (is (nil? (delivery/results-keyboard
             "товар" nil false "tg-42"
             {:cards [{:price 100 :currency "USD"} {:price nil :currency "KGS"}]}))))

(deftest failed-rich-media-retries-with-all-links-and-without-media
  (let [sent (atom [])
        source (cards 20)]
    (with-redefs [tg/send-md (fn [& _] {"message_id" 1})
                  tg/send-rich-message
                  (fn [_ & options]
                    (let [opts (apply hash-map options)]
                      (swap! sent conj opts)
                      (when-not (str/includes? (:html opts) "<img")
                        {"message_id" 2})))]
      (delivery/send-reply! 42 {:intro "Подборка" :cards source}
                            {:thread-id 17 :keyboard {"inline_keyboard" []}}))
    (is (= 2 (count @sent)))
    (is (every? #(str/includes? (:html (second @sent)) (:url %)) source))
    (is (not (str/includes? (:html (second @sent)) "<img")))
    (is (= 17 (:thread-id (second @sent))))))

(deftest legacy-fallback-never-loses-overflow-cards-or-controls
  (let [sent (atom [])
        source (cards 20)
        keyboard {"inline_keyboard" [[{"text" "Ещё" "callback_data" "page:abc"}]]}]
    (with-redefs [tg/send-md (fn [& _] {"message_id" 1})
                  tg/send-rich-message (fn [& _] (throw (ex-info "Rich unavailable" {})))
                  tg/send-message
                  (fn [_ text & options]
                    (swap! sent conj {:text text :options (apply hash-map options)})
                    {"message_id" 2})]
      (delivery/send-reply! 42 {:intro "Подборка" :cards source}
                            {:keyboard keyboard :thread-id 17}))
    (let [text (str/join "\n" (map :text @sent))]
      (is (every? #(str/includes? text (:url %)) source)))
    (is (every? #(= keyboard (get-in % [:options :reply_markup])) @sent))
    (is (every? #(= 17 (get-in % [:options :thread-id])) @sent))))
