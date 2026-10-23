(ns tapalakbot.lalafo-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.lalafo-collection :as collection]))

(defn- item
  [id & {:as fields}]
  (merge {"id" id
          "title" (str "TITLE " id)
          "description" (str "Full description for " id " with more than eighty characters. "
                              "This must survive unchanged.")
          "url" (str "/item/" id)
          "price" 100
          "currency" "KGS"
          "images" [{"original_url" (str "https://img.example/" id ".jpg")
                     "thumbnail_url" (str "https://img.example/" id "-thumb.jpg")}]
          "city" "Бишкек"
          "condition" "used"
          "params" {"memory" "128 GB"}}
         (into {} (map (fn [[k v]] [(name k) v])) fields)))

(defn- page-result
  [items & {:keys [has-more? total-count] :or {has-more? false}}]
  {:ok? true :failed? false :items items :has-more? has-more?
   :total-count (or total-count (count items))})

(defn- parsed-search
  [responses args]
  (with-redefs [lalafo/build-client (constantly :fake-client)
                lalafo/search-page
                (fn [_ query & {:keys [page] :or {page 1}}]
                  (or (get responses [query page])
                      (page-result [])))]
    (json/parse-string (lalafo/search args))))

(deftest full-source-fields-survive-and-no-quality-heuristics
  (let [raw (item "one" :title "ALL CAPS VALID LISTING" :price nil :images [])
        result (parsed-search {["q" 1] (page-result [raw])}
                              {"queries" ["q"]})
        got (first (get result "items"))]
    (is (= "ALL CAPS VALID LISTING" (get got "title")))
    (is (= (get raw "description") (get got "desc")))
    (is (nil? (get got "price")))
    (is (= [] (get got "images")))
    (is (= "Бишкек" (get got "city")))
    (is (= {"memory" "128 GB"} (get got "params")))))

(deftest deduplicates-and-round-robins-before-cap
  (let [result (parsed-search {["a" 1] (page-result [(item "a") (item "b") (item "c")])
                              ["b" 1] (page-result [(item "d") (item "a") (item "e")])}
                             {"queries" ["a" "b"] "candidate_limit" 3})]
    (is (= ["a" "d" "b"] (mapv #(get % "id") (get result "items"))))
    (is (= 5 (get-in result ["stats" "raw"])))
    (is (true? (get result "truncated")))))

(deftest budget-never-compares-usd-with-kgs
  (let [result (parsed-search {["q" 1]
                               (page-result [(item "kgs-ok" :price 100 :currency "KGS")
                                             (item "kgs-high" :price 300 :currency "KGS")
                                             (item "usd" :price 10 :currency "USD")
                                             (item "unknown" :price 999 :currency nil)])}
                              {"queries" ["q"] "price_max" 200})
        ids (set (map #(get % "id") (get result "items")))]
    (is (contains? ids "kgs-ok"))
    (is (not (contains? ids "kgs-high")))
    (is (contains? ids "usd"))
    (is (contains? ids "unknown"))))

(deftest page-fetch-stops-after-no-novel-ids
  (let [calls (atom [])
        responses {["q" 1] (page-result [(item "same")] :has-more? true)
                   ["q" 2] (page-result [(item "same")] :has-more? true)}
        result (with-redefs [lalafo/build-client (constantly :fake-client)
                             lalafo/search-page
                             (fn [_ query & {:keys [page] :or {page 1}}]
                               (swap! calls conj [query page])
                               (get responses [query page] (page-result [])))]
                 (json/parse-string (lalafo/search {"queries" ["q"]
                                                     "max_pages" 3})))]
    (is (= [["q" 1] ["q" 2]] @calls))
    (is (= 2 (get-in result ["stats" "pages"])))
    (is (= ["same"] (mapv #(get % "id") (get result "items"))))))

(deftest partial-failure-is-reported
  (let [result (parsed-search {["ok" 1] (page-result [(item "ok")])
                              ["bad" 1] {:ok? false :failed? true :items []}}
                             {"queries" ["ok" "bad"]})]
    (is (= 1 (get-in result ["stats" "failed-queries"])))
    (is (= ["ok"] (mapv #(get % "id") (get result "items"))))))

(deftest all-failures-are-not-an-empty-success
  (with-redefs [lalafo/build-client (constantly :fake-client)
                lalafo/search-page
                (fn [& _] {:ok? false :failed? true :items []})]
    (try
      (lalafo/search {"queries" ["q"]})
      (is false "expected search failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= :lalafo-fetch-failed (:type (ex-data e))))))))

(deftest explicit-nil-city-means-all-cities
  (let [seen (atom nil)]
    (with-redefs [lalafo/get-json
                  (fn [_ _ params]
                    (reset! seen params)
                    {"items" [] "_meta" {}})]
      (#'lalafo/search-page :client "q" :city-id nil))
    (is (not (contains? @seen :city_id)))))

(deftest malformed-source-url-is-rejected
  (is (nil? (collection/normalize-item (assoc (item "bad") "url" "javascript:alert(1)"))))
  (is (= "https://lalafo.kg/item/ok"
         (get (collection/normalize-item (item "ok")) "url"))))

(deftest source-id-normalization-and-url-host-boundary
  (let [result (parsed-search
                {["q" 1] (page-result [(item 123) (item "123")
                                        (item "bad" :url "https://evil.example/ads/id-123")])}
                {"queries" ["q"]})]
    (is (= [123] (mapv #(get % "id") (get result "items"))))
    (is (= 2 (get-in result ["stats" "raw"]))))
  (is (nil? (collection/canonical-url "https://lalafo.kg:8443/ads/id-1")))
  (is (nil? (collection/canonical-url "https://attacker@lalafo.kg/ads/id-1"))))

(deftest page-limit-cannot-be-presented-as-a-complete-market
  (let [result (parsed-search {["q" 1] (page-result [(item "one")] :has-more? true)}
                              {"queries" ["q"] "max_pages" 1})]
    (is (true? (get result "truncated")))))
