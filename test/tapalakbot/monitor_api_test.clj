(ns tapalakbot.monitor-api-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty :as jetty]
            [tapalakbot.monitor.api :as api]
            [tapalakbot.monitor.scanner :as scanner]))

(defn- request
  ([method uri] (request method uri nil))
  ([method uri token]
   {:request-method method
    :uri uri
    :headers (cond-> {}
               token (assoc "authorization" (str "Bearer " token)))}))

(deftest static-assets-cannot-escape-docs
  (testing "path traversal is rejected"
    (is (= 404 (:status (api/app (request :get "/designs/../../deps.edn")))))))

(deftest manual-scan-requires-an-admin-token
  (System/setProperty "tapalakbot.monitor-admin-token" "expected-secret")
  (try
    (is (= 403 (:status (api/app (request :post "/scan")))))
    (is (= 403 (:status (api/app (request :post "/scan" "wrong-secret")))))
    (finally
      (System/clearProperty "tapalakbot.monitor-admin-token"))))

(deftest manual-scan-is-single-flight
  (let [started (promise)
        release (promise)]
    (System/setProperty "tapalakbot.monitor-admin-token" "expected-secret")
    (try
      (with-redefs [scanner/scan-all! (fn []
                                        (deliver started true)
                                        @release
                                        {:categories 0 :items 0 :snapshots 0})]
        (is (= 202 (:status (api/app (request :post "/scan" "expected-secret")))))
        (is (true? (deref started 1000 false)))
        (is (= 409 (:status (api/app (request :post "/scan" "expected-secret")))))
        (deliver release true))
      (finally
        (deliver release true)
        (System/clearProperty "tapalakbot.monitor-admin-token")))))

(deftest monitor-listens-on-loopback-by-default
  (let [options (atom nil)
        server-var (ns-resolve 'tapalakbot.monitor.api 'server)]
    (with-redefs [jetty/run-jetty (fn [_app opts]
                                   (reset! options opts)
                                   ::server)]
      (api/start-server! :port 8787)
      (is (= "127.0.0.1" (:host @options)))
      (reset! @server-var nil))))
