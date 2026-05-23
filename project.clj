(defproject tapalakbot "0.1.0"
  :description "TapalakBot v2 — Telegram bot for Lalafo search"
  :dependencies [[org.clojure/clojure "1.12.0"]]
  :main tapalakbot.server
  :uberjar-name "tapalakbot.jar"
  :aot :all
  :jvm-opts ["-Dclojure.main.report=stderr"])
