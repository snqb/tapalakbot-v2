FROM clojure:temurin-21-tools-deps-bookworm-slim
RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY deps.edn ./
RUN clojure -P
COPY src ./src
COPY resources ./resources
RUN clojure -M -e "(require 'tapalakbot.server) (println :compile-ok)" 2>&1 | grep -v logback || true
EXPOSE 8080
ENV BOT_TOKEN=""
ENV DEEPSEEK_API_KEY=""
ENV OPENROUTER_API_KEY=""
ENV RISKBYPASS_API_KEY=""
CMD ["clojure", "-M:bot"]
