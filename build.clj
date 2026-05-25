(ns build)
;; Nixpacks Clojure provider detection trigger
;; No-op: actual deps are in deps.edn
(defn uberjar [_] (println "Skipped — TapalakBot uses deps.edn directly"))
