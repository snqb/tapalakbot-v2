(ns build)
;; No-op — nixpacks detects this and installs Clojure CLI.
;; No uberjar needed; start command uses deps.edn directly.
(defn uberjar [_])
