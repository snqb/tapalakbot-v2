(ns tapalakbot.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [tapalakbot.bot :as bot]
            [tapalakbot.server :as server])
  (:import [java.io ByteArrayInputStream]))

(def app @#'server/app)

(defn request [secret]
  {:uri "/webhook"
   :request-method :post
   :headers (cond-> {} secret (assoc "x-telegram-bot-api-secret-token" secret))
   :body (ByteArrayInputStream. (.getBytes "{}" "UTF-8"))})

(deftest webhook-rejects-unauthenticated-updates
  (testing "a public caller cannot forge Telegram updates"
    (System/setProperty "tapalakbot.webhook-secret" "expected-secret")
    (try
      (is (= 403 (:status (app (request nil)))))
      (is (= 403 (:status (app (request "wrong-secret")))))
      (finally
        (System/clearProperty "tapalakbot.webhook-secret")))))

(deftest webhook-preserves-topic-identity
  (let [received (atom nil)
        body "{\"message\":{\"message_id\":9,\"message_thread_id\":17,\"text\":\"iphone\",\"from\":{\"id\":42},\"chat\":{\"id\":-100123}}}"]
    (System/setProperty "tapalakbot.webhook-secret" "expected-secret")
    (try
      (with-redefs [bot/extended-handler #(reset! received %)]
        (let [response (app {:uri "/webhook"
                             :request-method :post
                             :headers {"x-telegram-bot-api-secret-token" "expected-secret"}
                             :body (ByteArrayInputStream. (.getBytes body "UTF-8"))})]
          (is (= 200 (:status response)))
          (is (= 17 (:thread-id @received)))
          (is (= -100123 (:chat-id @received)))))
      (finally
        (System/clearProperty "tapalakbot.webhook-secret")))))
