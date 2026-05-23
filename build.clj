;; Minimal tools.build config — triggers nixpacks to install Clojure CLI.
;; Actual builds use deps.edn directly.
(ns build
  (:require [clojure.tools.build.api :as b]))

(defn uberjar [_]
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir "target/classes"})
  (b/compile-clj {:basis (b/create-basis {})
                  :src-dirs ["src"]
                  :class-dir "target/classes"}))
