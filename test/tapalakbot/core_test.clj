(ns tapalakbot.core-test
  (:require [clj-harness.core :as harness]
            [clj-harness.llm :as llm]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tapalakbot.core :as core]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.monitor.tracker :as tracker]
            [tapalakbot.query-builder :as qb]))

(deftest production-agent-uses-configured-openrouter-model
  (let [config (:config @core/tapalakbot)]
    (is (= :gemini-3.5-flash (:model config)))
    (is (= :openrouter (:provider config)))))

(deftest mashina-results-format-with-real-links
  (let [format-results @#'core/format-mashina-results
        output (binding [core/*current-user-id* "tg-42"]
                 (format-results
                  {:total 1
                   :listings [{:id 1
                               :title "Toyota Camry 70"
                               :url "https://mashina.kg/details/1"
                               :year 2020
                               :mileage 50000
                               :price {:amount 1500000}}]}))]
    (is (str/includes? output "Toyota Camry 70"))
    (is (str/includes? output "https://mashina.kg/details/1"))))

(deftest helper-models-use-the-same-openrouter-contract
  (let [calls (atom [])
        fake-llm (fn [model _messages _tools & options]
                   (swap! calls conj {:model model :options (apply hash-map options)})
                   {"choices" [{"message" {"content" "{\"category_id\":null,\"category_name\":null}"}}]})]
    (with-redefs [llm/llm fake-llm
                  lalafo/search-categories (constantly "No categories")]
      (qb/enrich-with-llm "iphone")
      (tracker/match-category "iphone"))
    (is (= 2 (count @calls)))
    (is (every? #(= :gemini-3.5-flash (:model %)) @calls))
    (is (every? #(= :openrouter (get-in % [:options :provider])) @calls))))

(deftest marketplace-image-prefers-original-resolution
  (let [preferred-image (ns-resolve 'tapalakbot.core 'preferred-lalafo-image)]
    (is (some? preferred-image))
    (when preferred-image
      (is (= "https://cdn/original.jpg"
             (preferred-image
              {"thumbnail_url" "https://cdn/thumb.jpg"
               "images" [{"original_url" "https://cdn/original.jpg"
                          "thumbnail_url" "https://cdn/nested-thumb.jpg"}]}))))))

(deftest ask-stream-returns-the-query-used-by-search
  (with-redefs [harness/handle-message-stream!
                (fn [_bot _user-id _text _stream-cb & _options]
                  (reset! core/*captured-query* "ноутбук i5 дешевле")
                  {:content "Готово"})]
    (is (= "ноутбук i5 дешевле"
           (:query (core/ask-stream "tg-42" "дешевле" nil))))))

(deftest relevance-filter-honors-a-valid-empty-decision
  (let [filter-items @#'core/relevance-filter
        items [{"id" "1" "title" "Игрушка"}
               {"id" "2" "title" "Музыкальный плакат"}]]
    (with-redefs [llm/llm
                  (fn [& _]
                    {"choices" [{"message" {"content" "[]"}}]})]
      (is (empty? (filter-items items "флюгегехаймен"))))))

(deftest relevance-filter-falls-back-only-on-malformed-output
  (let [filter-items @#'core/relevance-filter
        items [{"id" "1" "title" "iPhone 13"}]]
    (with-redefs [llm/llm
                  (fn [& _]
                    {"choices" [{"message" {"content" "not json"}}]})]
      (is (= items (vec (filter-items items "iphone 13")))))))

(deftest mashina-capture-keeps-the-full-candidate-pool-with-images
  (let [capture (ns-resolve 'tapalakbot.core 'capture-mashina-cards!)
        listings (mapv (fn [n]
                         {:id n
                          :title (str "Toyota Camry " n)
                          :url (str "https://mashina.kg/details/" n)
                          :price {:amount (+ 1000000 n) :currency "KGS"}
                          :year 2020
                          :mileage 50000
                          :engine 2.5
                          :gearbox "автомат"
                          :city "Бишкек"
                          :images [(str "https://storage.mashina.kg/" n ".webp")]})
                       (range 25))]
    (is (some? capture))
    (when capture
      (binding [core/*captured-cards* (atom [])]
        (capture listings)
        (is (= 25 (count @core/*captured-cards*)))
        (is (= "https://storage.mashina.kg/0.webp"
               (:image (first @core/*captured-cards*))))
        (is (= {:engine 2.5 :gearbox "автомат"}
               (select-keys (first @core/*captured-cards*) [:engine :gearbox])))))))

(deftest result-cursor-pages-through-ranked-pool-without-duplicates
  (let [cache-pool (ns-resolve 'tapalakbot.core 'cache-result-pool!)
        next-page (ns-resolve 'tapalakbot.core 'next-result-page!)
        cards (mapv (fn [n] {:id n :title (str "Card " n)}) (range 45))]
    (is (some? cache-pool))
    (is (some? next-page))
    (when (and cache-pool next-page)
      (let [cursor-id (cache-pool "tg-42" "camry" cards 20)
            second-page (next-page "tg-42" cursor-id 20)
            third-page (next-page "tg-42" cursor-id 20)]
        (is (= (vec (range 20 40)) (mapv :id (:cards second-page))))
        (is (true? (:has-more second-page)))
        (is (= (vec (range 40 45)) (mapv :id (:cards third-page))))
        (is (false? (:has-more third-page)))
        (is (nil? (next-page "tg-42" cursor-id 20)))
        (is (= 45 (count (set (concat (range 20)
                                      (map :id (:cards second-page))
                                      (map :id (:cards third-page)))))))))))

(deftest result-cursor-rejects-another-user
  (let [cache-pool (ns-resolve 'tapalakbot.core 'cache-result-pool!)
        next-page (ns-resolve 'tapalakbot.core 'next-result-page!)]
    (is (some? cache-pool))
    (is (some? next-page))
    (when (and cache-pool next-page)
      (let [cursor-id (cache-pool "tg-owner" "camry" [{:id 1} {:id 2}] 1)]
        (is (nil? (next-page "tg-other" cursor-id 1)))))))

(deftest deterministic-auto-search-fast-path-is-specific
  (let [fast? (ns-resolve 'tapalakbot.core 'specific-auto-query?)]
    (is (true? (fast? "Toyota Camry 70 до 2000000"
                      {:is-auto? true :query "Toyota Camry 70"})))
    (is (true? (fast? "BMW X5 2018" {:is-auto? true :query "BMW X5 2018"})))
    (is (false? (fast? "машина до 2 млн" {:is-auto? true :query "машина"})))
    (is (false? (fast? "семейный кроссовер"
                       {:is-auto? true :query "семейный кроссовер"})))
    (is (false? (fast? "iphone 13" {:is-auto? false :query "iphone 13"})))))
