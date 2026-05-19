(ns tapalakbot.tg.format
  "Re-exported from clj-harness.telegram.format.
   All formatting functions live in the shared harness now."
  (:require [clj-harness.telegram.format :as hfmt]))

;; ── Public API (re-exports) ──

(def md->html       hfmt/md->html)
(def strip-md       hfmt/strip-md)
(def split-message  hfmt/split-message)
(def escape-html    hfmt/escape-html)
(def save-matches   hfmt/save-matches)
(def restore-matches hfmt/restore-matches)
(def telegram-max-length hfmt/telegram-max-length)

;; Backward-compat — these are used by channel.clj internally
(def md-strip-patterns nil) ;; no longer needed — moved to harness
