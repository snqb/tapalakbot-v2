(ns tapalakbot.server
  "Entry point — starts TapalakBot with HTTP webhook or Telegram polling.
   Set WEBHOOK_URL env var (e.g. https://your-domain.com/webhook) to use webhooks.
   Without it, falls back to long-polling."
  (:require [tapalakbot.core :as t]
            [tapalakbot.bot :as bot]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.monitor.client :as monitor]
            [clj-harness.telegram :as tg]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ══════════════════════ MONITOR ══════════════════════

(defn- ensure-monitor!
  "Start the monitor service if not already running."
  []
  (if (monitor/health-check)
    (log/info :monitor-already-running)
    (do
      (log/info :monitor-not-running :starting)
      (try
        (require 'tapalakbot.monitor.main)
        (let [main-fn (resolve 'tapalakbot.monitor.main/-main)]
          (.start (Thread. ^Runnable (fn [] (main-fn)) "monitor-service"))
          ;; Poll with backoff — initial scan can take 15-30s
          (loop [attempt 0]
            (Thread/sleep (min 3000 (* 1000 (inc attempt))))
            (if (monitor/health-check)
              (log/info :monitor-started :attempt (inc attempt))
              (if (< attempt 10)
                (recur (inc attempt))
                (log/warn :monitor-start-failed)))))
        (catch Exception e
          (log/warn :monitor-start-error (.getMessage e)))))))

;; ══════════════════════ TELEGRAM UPDATE PARSER ══════════════════════

(defn- parse-update
  "Parse raw Telegram update JSON into the map format extended-handler expects.
   Handles both messages and callback queries."
  [update]
  (if-let [cb (get update "callback_query")]
    (let [from (get cb "from")
          msg  (get cb "message")]
      {:callback-id (get cb "id")
       :data        (get cb "data")
       :user-id     (get from "id")
       :chat-id     (get-in msg ["chat" "id"])
       :msg-id      (get msg "message_id")})
    (when-let [msg (or (get update "message") (get update "edited_message"))]
      (let [chat (get msg "chat")
            user (get msg "from")
            loc  (get msg "location")]
        (cond->
         {:chat-id    (get chat "id")
          :user-id    (get user "id")
          :first-name (get user "first_name" "друг")
          :text       (get msg "text")
          :message-id (get msg "message_id")}
         loc
         (assoc :location {:lat (get loc "latitude")
                           :lon (get loc "longitude")}))))))
;; ══════════════════════ WEBHOOK ══════════════════════

(defn- webhook-handler
  "Ring handler for Telegram webhook POST /webhook.
   Parses the update and dispatches to bot/extended-handler in a future
   so we return 200 immediately (Telegram expects fast ack)."
  [request]
  (try
    (let [body   (slurp (:body request))
          parsed (parse-update (json/parse-string body false))]
      (when parsed
        ;; Process async — return 200 immediately
        (future (bot/extended-handler parsed))))
    (catch Exception e
      (log/error e :webhook-parse-error)))
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"ok\":true}"})

(defn- health-handler
  "Simple health check endpoint."
  [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:status "ok" :bot "tapalakbot-v2"})})

(defn- app
  "Ring app — routes webhook and health endpoints."
  [request]
  (case (:uri request)
    "/webhook" (webhook-handler request)
    "/health"  (health-handler request)
    ;; 404 for everything else
    {:status 404
     :headers {"Content-Type" "application/json"}
     :body "{\"error\":\"not found\"}"}))

(defn- set-webhook!
  "Register webhook URL with Telegram. Returns true on success."
  [webhook-url token]
  (try
    (let [url  (str "https://api.telegram.org/bot" token "/setWebhook")
          resp (http/post url
                 {:body    (json/generate-string {"url" webhook-url
                                                  "allowed_updates" ["message" "callback_query"]})
                  :headers {"Content-Type" "application/json"}
                  :as      :json})
          ok?  (get-in resp [:body :ok])]
      (if ok?
        (log/info :webhook-set :url webhook-url)
        (log/warn :webhook-set-failed :response (:body resp)))
      ok?)
    (catch Exception e
      (log/warn :webhook-set-error (.getMessage e))
      false)))

(defn- delete-webhook!
  "Remove webhook from Telegram (so polling can work)."
  [token]
  (try
    (let [url  (str "https://api.telegram.org/bot" token "/deleteWebhook")
          resp (http/post url {:as :json})]
      (log/info :webhook-deleted :ok (get-in resp [:body :ok]))
      (get-in resp [:body :ok]))
    (catch Exception e
      (log/warn :webhook-delete-error (.getMessage e))
      false)))

;; ══════════════════════ ENTRY POINT ══════════════════════

(defn -main [& args]
  (let [token       (or (System/getenv "BOT_TOKEN") "")
        webhook-url (System/getenv "WEBHOOK_URL")
        webhook-port (or (some-> (System/getenv "WEBHOOK_PORT") parse-long) 8080)]

    ;; Init bot (pre-loads categories, starts SQLite sessions)
    (log/info :tapalakbot-start)
    @t/tapalakbot
    (log/info :bot-ready)

    ;; Auto-start monitor if not running
    (ensure-monitor!)

    ;; Start tracker (user tracking notifications)
    (try
      (require 'tapalakbot.monitor.tracker)
      (let [start-fn (resolve 'tapalakbot.monitor.tracker/start-tracker!)]
        (start-fn)
        (log/info :tracker-started))
      (catch Exception e
        (log/warn :tracker-start-error (.getMessage e))))

    ;; Healthcheck — smoke test Lalafo API
    (let [hc (lalafo/smoke-test)]
      (if (:ok? hc)
        (log/info :healthcheck-pass :found (:found hc))
        (log/warn :healthcheck-fail (or (:error hc) "unknown"))))

    (if (str/blank? token)
      (log/warn :no-bot-token "Set BOT_TOKEN env var to start Telegram bot.")
      (do
        (tg/set-token! token)
        (log/info :bot-starting :token-prefix (subs token 0 6))

        (cond
          ;; Webhook mode — WEBHOOK_URL is set
          (and webhook-url (not (str/blank? webhook-url)))
          (do
            ;; Clear any existing webhook first, then set new one
            (delete-webhook! token)
            (if (set-webhook! webhook-url token)
              (do
                (log/info :webhook-mode :url webhook-url :port webhook-port)
                ;; Start Jetty in background thread
                (run-jetty app {:port webhook-port :join? false})
                (log/info :webhook-server-started :port webhook-port))
              (do
                (log/warn :webhook-failed-falling-back-to-polling)
                (.start (Thread. ^Runnable (fn [] (bot/start-polling)) "tapalakbot-poller"))
                (log/info :polling-started))))

          ;; Polling mode — no WEBHOOK_URL
          :else
          (do
            ;; Make sure no stale webhook is blocking polling
            (delete-webhook! token)
            (.start (Thread. ^Runnable (fn [] (bot/start-polling)) "tapalakbot-poller"))
            (log/info :polling-mode)))))

    ;; Interactive mode fallback
    (println "TapalakBot v2 running. Press Ctrl+C to stop.")
    (println "   Bot token:" (if (str/blank? token) "NOT SET" (str (subs token 0 6) "...")))
    (when webhook-url
      (println "   Webhook:" webhook-url "port" webhook-port))

    ;; Keep main thread alive
    (while true
      (try (Thread/sleep 60000)
           (catch InterruptedException _ (throw (InterruptedException. "shutdown")))))))
