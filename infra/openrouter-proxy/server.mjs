import http from "node:http";

const port = Number(process.env.PORT || 8080);
const apiKey = process.env.OPENROUTER_API_KEY;
const allowedPaths = new Set([
  "/api/v1/chat/completions",
  "/api/v1/systemone"
]);

if (!apiKey) {
  throw new Error("OPENROUTER_API_KEY is required");
}

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"status":"ok"}');
    return;
  }

  if (request.method !== "POST" || !allowedPaths.has(request.url)) {
    response.writeHead(404, { "content-type": "application/json" });
    response.end('{"error":"not found"}');
    return;
  }

  if (request.headers.authorization !== `Bearer ${apiKey}`) {
    response.writeHead(401, { "content-type": "application/json" });
    response.end('{"error":"unauthorized"}');
    return;
  }

  try {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);

    const upstream = await fetch(`https://openrouter.ai${request.url}`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${apiKey}`,
        "content-type": "application/json",
        "http-referer": "https://tapalak.esen.works",
        "x-title": "TapalakBot",
        "user-agent": "Mozilla/5.0 TapalakBot/1.0"
      },
      body: Buffer.concat(chunks)
    });

    response.writeHead(upstream.status, {
      "content-type": upstream.headers.get("content-type") || "application/json",
      "cache-control": "no-store"
    });
    if (upstream.body) {
      for await (const chunk of upstream.body) response.write(chunk);
    }
    response.end();
  } catch (error) {
    console.error("proxy error", error);
    response.writeHead(502, { "content-type": "application/json" });
    response.end('{"error":"upstream unavailable"}');
  }
});

server.listen(port, "0.0.0.0", () => console.log(`listening on ${port}`));
