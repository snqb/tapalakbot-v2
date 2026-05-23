FROM clojure:latest

# Python + uv
RUN apt-get update && apt-get install -y --no-install-recommends python3 && \
    rm -rf /var/lib/apt/lists/*

RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.local/bin:$PATH"

WORKDIR /app
COPY . .

# Python deps
RUN uv pip install --system httpx\[http2\] pydantic tenacity

# Pre-fetch Clojure deps
RUN clojure -P

ENV TAPALAKBOT_BASE_DIR=/app

CMD ["clojure", "-M:bot"]
