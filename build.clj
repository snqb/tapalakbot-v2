;; Minimal tools.build config — triggers nixpacks to install Clojure CLI.
;; No actual uberjar needed — Railway just runs deps.edn directly.
(ns build)

(defn uberjar [_]
  (println "Skipping uberjar — TapalakBot runs via deps.edn"))
