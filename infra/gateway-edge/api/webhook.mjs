export default async function webhook(request, response) {
  response.setHeader("Cache-Control", "no-store");
  const secret = process.env.TELEGRAM_WEBHOOK_SECRET;
  if (!secret) return response.status(503).json({ error: "webhook_not_configured" });
  if (request.headers["x-telegram-bot-api-secret-token"] !== secret) {
    return response.status(403).json({ error: "forbidden" });
  }
  if (request.method !== "POST") {
    response.setHeader("Allow", "POST");
    return response.status(405).json({ error: "method_not_allowed" });
  }
  try {
    const body = typeof request.body === "string" || Buffer.isBuffer(request.body)
      ? request.body : JSON.stringify(request.body ?? {});
    const upstream = await fetch("https://tapalak.esen.works/webhook", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Telegram-Bot-Api-Secret-Token": secret
      },
      body,
      redirect: "error",
      signal: AbortSignal.timeout(10_000)
    });
    const payload = Buffer.from(await upstream.arrayBuffer());
    response.setHeader("Content-Type", upstream.headers.get("content-type") || "application/json");
    response.status(upstream.status).send(payload);
  } catch {
    response.status(502).json({ error: "webhook_upstream_unavailable" });
  }
}
