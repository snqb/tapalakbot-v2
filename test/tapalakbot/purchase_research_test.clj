(ns tapalakbot.purchase-research-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [tapalakbot.purchase-research :as research])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(deftest research-sends-one-json-content-type-and-keeps-readable-provenance
  (let [observed (atom nil)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/search"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (reset! observed {:content-types (vec (.get (.getRequestHeaders exchange) "Content-Type"))
                                          :body (json/parse-string (slurp (.getRequestBody exchange)) true)})
                        (let [body (.getBytes
                                    (json/generate-string
                                     {:results [{:title "Repair guide" :url "https://example.org/guide"
                                                 :text "Parts and maintenance evidence"}
                                                {:title "Insecure" :url "http://example.org/insecure" :text "No"}
                                                {:title "Listing" :url "https://lalafo.kg/item/123" :text "No"}
                                                {:title "Unreadable" :url "https://example.org/no-text"}]}) "UTF-8")]
                          (.sendResponseHeaders exchange 200 (alength body))
                          (with-open [out (.getResponseBody exchange)] (.write out body))))))
    (.start server)
    (try
      (with-redefs-fn {#'research/api-key (constantly "test-key")
                      #'research/exa-endpoint (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/search")}
        #(let [result (research/search "treadmill repair")]
           (is (= :ok (:status result)))
           (is (= ["https://example.org/guide"] (mapv :url (:sources result))))
           (is (= ["application/json"] (:content-types @observed)))
           (is (= "treadmill repair" (get-in @observed [:body :query])))))
      (finally (.stop server 0)))))

(deftest unavailable-research-is-not-a-successful-empty-answer
  (with-redefs [research/api-key (constantly nil)]
    (is (= :unavailable (:status (research/search "treadmill")))))
  (is (= :unavailable (:status (research/search "")))))
