FROM ubuntu:noble

ENV DEBIAN_FRONTEND=noninteractive
ENV PATH="/root/.local/bin:$PATH"

# Java + tools
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl rlwrap default-jre-headless python3 python3-pip ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Clojure CLI
RUN curl -O https://download.clojure.org/install/linux-install-1.12.0.1530.sh && \
    chmod +x linux-install-1.12.0.1530.sh && \
    ./linux-install-1.12.0.1530.sh && \
    rm linux-install-1.12.0.1530.sh

# uv
RUN curl -LsSf https://astral.sh/uv/install.sh | sh

WORKDIR /app
COPY . .

# Install Python deps directly (skip workspace complexity)
RUN uv pip install --system httpx\[http2\] pydantic tenacity

# Pre-fetch Clojure deps
RUN clojure -P

ENV TAPALAKBOT_BASE_DIR=/app

CMD ["clojure", "-M:bot"]
