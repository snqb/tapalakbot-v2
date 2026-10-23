import assert from "node:assert/strict";
import test from "node:test";
import webhook from "../api/webhook.mjs";

function response() {
  return {
    statusCode: 200,
    setHeader() {},
    status(code) { this.statusCode = code; return this; },
    json(body) { this.body = body; return this; }
  };
}

test("untrusted callers cannot inject Telegram updates through the edge", async () => {
  const before = process.env.TELEGRAM_WEBHOOK_SECRET;
  const realFetch = globalThis.fetch;
  let reachedBot = false;
  globalThis.fetch = async () => { reachedBot = true; throw new Error("unexpected bot request"); };
  try {
    process.env.TELEGRAM_WEBHOOK_SECRET = "expected-hook-secret";
    for (const token of [undefined, "another-secret"]) {
      const result = response();
      await webhook({ method: "POST", headers: { "x-telegram-bot-api-secret-token": token }, body: {} }, result);
      assert.equal(result.statusCode, 403);
    }
    assert.equal(reachedBot, false);
  } finally {
    globalThis.fetch = realFetch;
    if (before === undefined) delete process.env.TELEGRAM_WEBHOOK_SECRET;
    else process.env.TELEGRAM_WEBHOOK_SECRET = before;
  }
});

test("missing deployment secret fails closed instead of accepting an absent header", async () => {
  const before = process.env.TELEGRAM_WEBHOOK_SECRET;
  const realFetch = globalThis.fetch;
  let reachedBot = false;
  globalThis.fetch = async () => { reachedBot = true; throw new Error("unexpected bot request"); };
  try {
    delete process.env.TELEGRAM_WEBHOOK_SECRET;
    const result = response();
    await webhook({ method: "POST", headers: {}, body: {} }, result);
    assert.equal(result.statusCode, 503);
    assert.equal(reachedBot, false);
  } finally {
    globalThis.fetch = realFetch;
    if (before === undefined) delete process.env.TELEGRAM_WEBHOOK_SECRET;
    else process.env.TELEGRAM_WEBHOOK_SECRET = before;
  }
});
