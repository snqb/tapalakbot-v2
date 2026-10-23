import assert from "node:assert/strict";
import test from "node:test";
import telegram from "../api/telegram.mjs";

function response() {
  return {
    statusCode: 200,
    setHeader() {},
    status(code) { this.statusCode = code; return this; },
    json(body) { this.body = body; return this; },
    send(body) { this.body = body; return this; }
  };
}

test("relay rejects requests that cannot authorize this specific bot", async () => {
  const before = process.env.BOT_TOKEN;
  const realFetch = globalThis.fetch;
  let contactedUpstream = false;
  globalThis.fetch = async () => { contactedUpstream = true; throw new Error("unexpected upstream"); };
  try {
    process.env.BOT_TOKEN = "only-this-bot";
    for (const authorization of [undefined, "Bearer another-bot"]) {
      const result = response();
      await telegram({ method: "POST", headers: { authorization }, query: { method: "getMe" } }, result);
      assert.equal(result.statusCode, 401);
    }
    assert.equal(contactedUpstream, false);
  } finally {
    globalThis.fetch = realFetch;
    if (before === undefined) delete process.env.BOT_TOKEN; else process.env.BOT_TOKEN = before;
  }
});

test("authorized callers cannot turn the relay into an arbitrary upstream request", async () => {
  const before = process.env.BOT_TOKEN;
  const realFetch = globalThis.fetch;
  let contactedUpstream = false;
  globalThis.fetch = async () => { contactedUpstream = true; throw new Error("unexpected upstream"); };
  try {
    process.env.BOT_TOKEN = "only-this-bot";
    for (const method of ["../getMe", "https://example.com", ["getMe", "deleteForumTopic"]]) {
      const result = response();
      await telegram({ method: "POST", headers: { authorization: "Bearer only-this-bot" }, query: { method } }, result);
      assert.equal(result.statusCode, 404);
    }
    assert.equal(contactedUpstream, false);
  } finally {
    globalThis.fetch = realFetch;
    if (before === undefined) delete process.env.BOT_TOKEN; else process.env.BOT_TOKEN = before;
  }
});
