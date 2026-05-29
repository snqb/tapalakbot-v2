(ns tapalakbot.server
  "Entry point — starts TapalakBot with HTTP API + Telegram polling."
  (:require [tapalakbot.core :as t]
            [tapalakbot.bot :as bot]
            [tapalakbot.lalafo :as lalafo]
            [clj-harness.telegram :as tg]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn -main [& args]
  (let [token (or (System/getenv "BOT_TOKEN") "")]
    ;; Init bot (pre-loads categories, starts SQLite sessions)
    (log/info :tapalakbot-start)
    @t/tapalakbot
    (log/info :bot-ready)

    ;; Healthcheck — smoke test Lalafo API
    (let [hc (lalafo/smoke-test)]
      (if (:ok? hc)
        (log/info :healthcheck-pass :found (:found hc))
        (log/warn :healthcheck-fail (or (:error hc) "unknown"))))

    (if (str/blank? token)
      (log/warn :no-bot-token "Set BOT_TOKEN env var to start Telegram bot.")
      (do
        (tg/set-token! token)
        (log/info :bot-polling :token-prefix (subs token 0 6))
        ;; Start bot in background thread
        (.start (Thread. ^Runnable (fn [] (bot/start-polling)) "tapalakbot-poller"))
        (log/info :bot-started)))

    ;; Interactive mode fallback
    (println "🔍 TapalakBot v2 running. Press Ctrl+C to stop.")
    (println "   Bot token:" (if (str/blank? token) "NOT SET" (str (subs token 0 6) "...")))

    ;; Keep main thread alive
    (while true
      (try (Thread/sleep 60000)
           (catch InterruptedException _ (throw (InterruptedException. "shutdown")))))))
