const methods = new Set([
  "getMe", "getWebhookInfo", "getUpdates", "setWebhook", "deleteWebhook",
  "sendMessage", "editMessageText", "editMessageReplyMarkup", "deleteMessage",
  "answerCallbackQuery", "sendChatAction", "setMyCommands", "sendPhoto", "sendMediaGroup",
  "sendRichMessage", "sendRichMessageDraft", "createForumTopic", "editForumTopic",
  "closeForumTopic", "reopenForumTopic", "deleteForumTopic"
]);

export default async function telegram(request, response) {
  response.setHeader("Cache-Control", "no-store");
  const token = process.env.BOT_TOKEN;
  if (!token) return response.status(503).json({ error: "telegram_not_configured" });
  if (request.headers.authorization !== `Bearer ${token}`) {
    return response.status(401).json({ error: "unauthorized" });
  }
  if (request.method !== "POST") {
    response.setHeader("Allow", "POST");
    return response.status(405).json({ error: "method_not_allowed" });
  }
  const method = request.query.method;
  if (typeof method !== "string" || !methods.has(method)) {
    return response.status(404).json({ error: "not_found" });
  }
  try {
    const body = typeof request.body === "string" || Buffer.isBuffer(request.body)
      ? request.body : JSON.stringify(request.body ?? {});
    const upstream = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      redirect: "error",
      signal: AbortSignal.timeout(55_000)
    });
    const payload = Buffer.from(await upstream.arrayBuffer());
    response.setHeader("Content-Type", upstream.headers.get("content-type") || "application/json");
    response.status(upstream.status).send(payload);
  } catch {
    // Fetch errors may contain the token-bearing upstream URL: never log them.
    response.status(502).json({ error: "telegram_unavailable" });
  }
}
