#!/usr/bin/env bash
# Start Clerk notebook server for tapalakbot marketplace search
cd "$(dirname "$0")"

clojure -Sdeps '{:deps {io.github.nextjournal/clerk {:mvn/version "0.18.1158"}}}' \
  -M -e "(require '[nextjournal.clerk :as clerk])
         (clerk/serve! {:browse true
                        :watch-paths [\"notebooks\" \"src\"]})
         (println \"\\n✅ Clerk running at http://localhost:7777\\n\")
         (println \"Watching notebooks/ and src/ for changes...\")
         (println \"Press Ctrl+C to stop.\")"
