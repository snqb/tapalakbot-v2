(ns tapalakbot.monitor.main
  "Entry point for Lalafo price monitor service.
   Starts: SQLite DB → seed categories → initial scan → background scanner → HTTP API."
  (:require [tapalakbot.monitor.store :as store]
            [tapalakbot.monitor.scanner :as scanner]
            [tapalakbot.monitor.api :as api]
            [clojure.tools.logging :as log]))

(defn -main [& args]
  (let [port (or (when (seq args)
                   (some-> (first args) Integer/parseInt))
                 (or (some-> (System/getenv "MONITOR_PORT") Integer/parseInt)
                     8787))]
    (log/info :monitor-start :port port)

    ;; 1. Init database
    (store/init-db!)
    (log/info :db-ready)

    ;; 2. Seed default categories
    (store/seed-categories!)
    (log/info :categories-ready)

    ;; 3. Run initial scan (blocking)
    (log/info :initial-scan-start)
    (let [result (scanner/initial-scan!)]
      (log/info :initial-scan-done :result result))

    ;; 4. Start background scanner
    (scanner/start-scanner!)
    (log/info :scanner-started)

    ;; 5. Start HTTP API server
    (api/start-server! :port port)
    (log/info :api-started :port port)

    (println (str "📊 Monitor running on http://localhost:" port))
    (println "   Endpoints:")
    (println "     GET /health")
    (println "     GET /prices/trending")
    (println "     GET /prices/deals")
    (println "     GET /prices/stats")
    (println "     GET /prices/categories")
    (println "     GET /prices/history/:id")
    (println "     GET /prices/category/:id")
    (println "     POST /scan")
    (println)
    (println "   Press Ctrl+C to stop.")

    ;; Keep main thread alive
    (while true
      (try (Thread/sleep 60000)
           (catch InterruptedException _
             (log/info :monitor-shutdown)
             (scanner/stop-scanner!)
             (api/stop-server!)
             (System/exit 0))))))
