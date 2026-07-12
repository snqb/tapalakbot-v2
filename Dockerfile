FROM clojure:temurin-21-tools-deps-bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY deps.edn ./
RUN clojure -P
COPY src ./src
COPY resources ./resources
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh \
    && mkdir -p /data \
    && clojure -M -e "(require 'tapalakbot.server) (println :compile-ok)"

ENV PORT=8080
ENV SESSION_DB_PATH=/data/tapalakbot-sessions.db
ENV MONITOR_DB_PATH=/data/tapalakbot-monitor.db
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["clojure", "-M:bot"]
