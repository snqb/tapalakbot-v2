FROM --platform=linux/amd64 clojure:latest

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 curl ca-certificates python3-pip && \
    rm -rf /var/lib/apt/lists/*

RUN pip3 install --break-system-packages 'httpx[http2]' pydantic tenacity

WORKDIR /app
COPY . .

RUN clojure -P

ENV TAPALAKBOT_BASE_DIR=/app

CMD ["clojure", "-M:bot"]
