(ns tapalakbot.tg.channel
  "Re-exported from clj-harness.telegram.
   All Telegram API functions live in the shared harness now."
  (:require [clj-harness.telegram :as htg]))

;; ── Public API (re-exports) ──

(def send-message     htg/send-message)
(def edit-message     htg/edit-message)
(def send-typing      htg/send-typing)
(def delete-message   htg/delete-message)
(def send-md          htg/send-md)
(def get-updates      htg/get-updates)
(def poll-loop        htg/poll-loop)
(def set-token!       htg/set-token!)
(def api-base         htg/api-base)
