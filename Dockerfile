FROM ubuntu:noble

# Java
RUN apt-get update && apt-get install -y curl rlwrap default-jre-headless python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*

# Clojure CLI
RUN curl -O https://download.clojure.org/install/linux-install-1.12.0.1530.sh && \
    chmod +x linux-install-1.12.0.1530.sh && \
    ./linux-install-1.12.0.1530.sh && \
    rm linux-install-1.12.0.1530.sh

# uv
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.local/bin:$PATH"

WORKDIR /app
COPY . .

# Python deps
RUN uv sync --no-dev

# Clojure deps — pre-cache
RUN clojure -P

ENV TAPALAKBOT_BASE_DIR=/app

CMD ["clojure", "-M:bot"]
