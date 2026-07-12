(ns tapalakbot.server
  "Production entry point for Dokploy-style reverse-proxy deployment.

   PORT exposes plain HTTP health + webhook endpoints inside the container.
   Dokploy terminates TLS. Without WEBHOOK_URL, Telegram uses long polling."
  (:require [tapalakbot.core :as t]
            [tapalakbot.bot :as bot]
            [tapalakbot.lalafo :as lalafo]
            [tapalakbot.monitor.client :as monitor]
            [clj-harness.telegram :as tg]
            [com.brunobonacci.mulog :as u]
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
;; ══════════════════════ WEBHOOK AUTHENTICATION ══════════════════════

(defn- webhook-secret []
  (or (System/getenv "TELEGRAM_WEBHOOK_SECRET")
      (System/getProperty "tapalakbot.webhook-secret")))

(defn- secure-equal? [expected actual]
  (and (string? expected)
       (string? actual)
       (java.security.MessageDigest/isEqual
        (.getBytes expected "UTF-8")
        (.getBytes actual "UTF-8"))))

(defn- webhook-authorized? [request]
  (secure-equal? (webhook-secret)
                 (get-in request [:headers "x-telegram-bot-api-secret-token"])))
;; ══════════════════════ WEBHOOK ══════════════════════

(defn- webhook-handler
  "Authenticate and dispatch a Telegram webhook update.
   extended-handler owns async dispatch, so this returns immediately without
   adding another unbounded future."
  [request]
  (if-not (webhook-authorized? request)
    (do
      (log/warn :webhook-unauthorized)
      {:status 403
       :headers {"Content-Type" "application/json"}
       :body "{\"ok\":false}"})
    (do
      (try
        (let [body (slurp (:body request))
              parsed (tg/parse-update (json/parse-string body false))]
          (when parsed
            (bot/extended-handler parsed)))
        (catch Exception e
          (log/error e :webhook-parse-error)))
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body "{\"ok\":true}"})))

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
  "Register a reverse-proxied HTTPS webhook with Telegram authentication."
  [webhook-url token secret]
  (try
    (let [url (str "https://api.telegram.org/bot" token "/setWebhook")
          resp (http/post url
                          {:form-params {"url" webhook-url
                                         "allowed_updates"
                                         (json/generate-string ["message" "callback_query"])
                                         "secret_token" secret}
                           :as :json})
          ok? (get-in resp [:body :ok])]
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
  (let [token          (or (System/getenv "BOT_TOKEN") "")
        webhook-url    (System/getenv "WEBHOOK_URL")
        webhook-secret (webhook-secret)
        port            (or (some-> (System/getenv "PORT") parse-long)
                            (some-> (System/getenv "WEBHOOK_PORT") parse-long)
                            8080)]

    ;; mulog observability — structured event logging
    (u/set-global-context!
     {:service "tapalakbot" :env (or (System/getenv "ENV") "dev")})
    (u/start-publisher!
     {:type :console :pretty-print true})
    (log/info :mulog-started)

    ;; Always expose liveness, including polling mode. TLS terminates at Dokploy.
    (run-jetty app {:port port :join? false})
    (log/info :http-server-started :port port)

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
          ;; Webhook mode requires Telegram request authentication.
          (and webhook-url (not (str/blank? webhook-url))
               webhook-secret (not (str/blank? webhook-secret)))
          (do
            (delete-webhook! token)
            (if (set-webhook! webhook-url token webhook-secret)
              (log/info :webhook-mode :url webhook-url :port port)
              (do
                (log/warn :webhook-failed-falling-back-to-polling)
                (.start (Thread. ^Runnable (fn [] (bot/start-polling))
                                 "tapalakbot-poller"))
                (log/info :polling-started))))

          ;; Never expose an unauthenticated webhook.
          (and webhook-url (not (str/blank? webhook-url)))
          (do
            (log/error :webhook-secret-missing
                       "Set TELEGRAM_WEBHOOK_SECRET; falling back to polling.")
            (delete-webhook! token)
            (.start (Thread. ^Runnable (fn [] (bot/start-polling)) "tapalakbot-poller"))
            (log/info :polling-mode :reason :missing-webhook-secret))

          :else
          (do
            (delete-webhook! token)
            (.start (Thread. ^Runnable (fn [] (bot/start-polling)) "tapalakbot-poller"))
            (log/info :polling-mode)))))

    ;; Interactive mode fallback
    (println "TapalakBot v2 running. Press Ctrl+C to stop.")
    (println "   Bot token:" (if (str/blank? token) "NOT SET" (str (subs token 0 6) "...")))
    (when webhook-url
      (println "   Webhook:" webhook-url "port" port))

    ;; Keep main thread alive
    (while true
      (try (Thread/sleep 60000)
           (catch InterruptedException _ (throw (InterruptedException. "shutdown")))))))
