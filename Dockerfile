FROM clojure:temurin-21-tools-deps-bookworm-slim
RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY deps.edn ./
RUN clojure -P
COPY src ./src
COPY resources ./resources
RUN clojure -M -e "(require 'tapalakbot.server) (println :compile-ok)"
EXPOSE 8080
CMD ["clojure", "-M:bot"]
