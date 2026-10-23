# TapalakBot deployment

## Actual production topology

Verified on 2026-10-24:

- SSH host: `krugosvet` (`147.45.158.152`). The old NixOS host is not the release target.
- Dokploy application: `zXRWauuXqMP-7XIYSWLR5`, project `tapalakbot`, environment `production`.
- Docker Swarm service: `tapalakbot-production-densux`, one replica. Do not launch a standalone replacement container or a second Telegram poller.
- Source: `https://github.com/snqb/tapalakbot-v2.git`; Dokploy uses the root Dockerfile and root build context.
- Persistent volume: `tapalakbot-production-data` mounted at `/data`.
- Retained direct-diagnostic host mapping: `149.154.167.220 api.telegram.org`; runtime `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`. Production Telegram requests use the authenticated edge relay.
- Production ingress uses `https://tapalak-gateway-edge.vercel.app/webhook`, which authenticates and forwards to the fixed VPS webhook. The bot is `@tapalakin_bot`; expect one bot JVM and no polling thread after successful registration.

Updates and rollbacks must both use stop-first ordering. Process health alone does not establish that model credentials or the external gateway work.

Persist rollout policy through Dokploy `application.update`: both
`updateConfigSwarm` and `rollbackConfigSwarm` must be objects containing
`Parallelism: 1` and `Order: "stop-first"`. Manual `docker service update` settings
were overwritten by deployment until these API fields were saved. Dokploy also
removes manual `ContainerSpec.Hosts` entries: the image entrypoint writes the
Telegram address, and the effective container mapping must be checked after rollout.

Fallback polling's pinned harness handles its first request inside the existing
poll-error loop and preserves unacknowledged updates. The old unguarded
`offset=-1` probe discarded pending messages and terminated the worker on a
connect timeout. Readiness requires the marketplace probe and revokes polling
readiness if its worker terminates; process liveness remains separate.

Cold connection handshakes from this VPS to both Railway aliases and Telegram
intermittently timed out. For Railway, packet headers showed selected
ClientHello segments receiving no ACK after TCP establishment. HTTP/2, pooling
and a TLS 1.2 cap did not
repair complete turns; the earlier tiny SSE success was insufficient evidence.

`infra/gateway-edge/` is deployed as `tapalak-gateway-edge` in the existing Vercel
account. Three exact rewrites forward health and the two model paths to the same
Railway gateway, preserving its authentication and allowlist. A separate
allowlisted Telegram function authorizes only the existing bot; its inbound URLs
contain method names, not bot tokens. It never starts a poller. Both hops retain
certificate and hostname verification, and no new retry policy is added.

Incoming updates first reach the edge `/webhook`, which verifies Telegram's
secret header and forwards only to `https://tapalak.esen.works/webhook`; the VPS
independently checks the same secret. Direct Telegram delivery to the correct VPS
IP timed out, while the authenticated relay preserves queued updates. Registration
uses `max_connections=1` so the arrival-order coalescer retains its ordered input.

Webhook delivery avoids a continuous serverless long poll:
[provisioned memory is billed even while waiting for I/O](https://vercel.com/docs/functions/usage-and-pricing).

From the real container, independent normal-TLS connection gates passed for both
edge health and relayed Telegram `getMe`. A complete real phone → one-som →
router pipeline then passed with streaming deltas and unchanged source facts.
The unused custom hostname was not adopted after a non-retryable CA rate limit.

## Search and rendering contract

The Clojure/clj-harness stack remains in place. Migrating to Mastra or AI SDK would not replace the typed JEV endpoint and would require moving working session, tracking, callback and conversation-serialization code.

The conversation agent supplies full user intent and up to three keyword variants. Lalafo collection round-robins and deduplicates up to 200 complete source listings. Deterministic budget checks require known KGS prices or authoritative source equivalents; unknown currency is not KGS, and foreign amounts are never compared directly against a KGS budget. Provider numeric filters are not used because their mixed-currency semantics are unverified.

JEV evaluates fit, condition, evidence and risk through `/api/v1/systemone`. Complete typed responses are required for every batch; a partial or failed batch fails the entire selection. Valid empty decisions remain empty. Prices, currencies, URLs and images remain on the original source cards. Descriptions are never shortened before selection.

Typed decisions are reconciled before admission: missing product-identity evidence, explicit incompatibility and current-offer price/currency conflicts cannot be recommended. A concrete suspicious-source decision can only remain an uncertain alternative. Historical purchase prices are not current-offer conflicts, and a missing photo or description is not an automatic exclusion. JEV classifies supplied seller claims; it does not verify actual hardware or the seller.

Agent analysis is a short native Rich Markdown paragraph: two sentences, no generic numeric specifications or buying guide. The writer sees only a bounded source-safe preview; JEV still sees complete descriptions. Result cards are deterministic Rich HTML with standalone source links, original photos, one condition indication, material warnings and collapsed overflow. Collection/selection counters are hidden in “Как отбирали”. Media failure retries as text-only Rich HTML, then complete legacy HTML without dropping cards or controls.

Initial results put direct matches before review candidates, then sort by ascending authoritative KGS price inside each group. “Цена ↑”, “Цена ↓” and “По соответствию” reorder the complete cached pool without another search or model call; price modes put non-comparable/unknown amounts last and never invent FX. Sorting resets pagination and numeric indexes, invalidates the old cursor and retains the source pool’s original expiry. Even one-page or exhausted views remain sortable until expiry. Budget refinements are separate new searches; their opaque callbacks belong to the exact conversation/thread and replace old bounds, including nil to clear a bound.

Collection, selector or pre-search conversation-provider failure is unavailable, not an empty successful market. A failed tracker check must not retire unseen candidates.

## Required configuration

| Variable | Purpose |
| --- | --- |
| `BOT_TOKEN` | Existing Telegram bot identity; keep exactly one bot process |
| `OPENROUTER_API_KEY` | Dedicated credential from `pass openrouter/lalabot-token`; do not overwrite or reuse the old global entry |
| `OPENROUTER_BASE_URL` | `https://tapalak-gateway-edge.vercel.app/api/v1/chat/completions` |
| `JEV_BASE_URL` | `https://tapalak-gateway-edge.vercel.app/api/v1/systemone` |
| `TELEGRAM_PROXY_URL` | `https://tapalak-gateway-edge.vercel.app/telegram`; current bot token authenticates in a bearer header |
| `SESSION_DB_PATH` | `/data/tapalakbot-sessions.db` |
| `MONITOR_DB_PATH` | `/data/tapalakbot-monitor.db` |
| `PORT` | Plain HTTP container port; default 8080 |
| `JAVA_TOOL_OPTIONS` | `-Djava.net.preferIPv4Stack=true`; do not restore the ineffective TLS 1.2 cap |
| `WEBHOOK_URL` | `https://tapalak-gateway-edge.vercel.app/webhook` |
| `TELEGRAM_WEBHOOK_SECRET` | Existing `pass telegram/tapalakbot/webhook-secret`; same hosted secret on edge and VPS |
| `MONITOR_ADMIN_TOKEN` | Required before exposing the monitor administration API |

Model names and JEV bounds are in `resources/config.edn`: `google/gemini-3.5-flash`, `typesafe/jev-1.13`, batches at most 20, concurrency at most three, and 30-second request timeouts. Source descriptions are not truncated to fit a request; oversized individual listings fail explicitly.

Save runtime variables with Dokploy `application.saveEnvironment` without deploying. Preserve existing values and build settings; set `createEnvFile=false` because this Clojure image reads runtime environment variables and does not need a plaintext build-checkout `.env` file. `main` has auto-deploy enabled, so complete this preparation before pushing.

## Verification before release

```bash
clojure -M -e "(require 'tapalakbot.bot 'tapalakbot.server) (println :compile-ok)"
clojure -M:test
node --test infra/gateway-edge/test/*.test.mjs
clojure -M:run "дорожка для ходьбы до 15000"
```

The terminal command is one-shot and must exit. An unavailable response is useful outage evidence, not a successful positive integration test.

With working credentials, exercise the real agent, Lalafo and JEV together: walking treadmill, exact phone, budget-only follow-up, different-product isolation, valid no-match, selector failure, and real streaming deltas. Verify accepted source IDs and unchanged monetary facts. Exercise Telegram cards, source links, pagination, refinements and distinct topics in a real client, not just API acceptance.

## Gateway and bot release order

1. Verify the OpenRouter credential against both the configured conversation model and the typed System One endpoint without printing the key.
2. If gateway code changed, deploy `infra/openrouter-proxy/` to its authorized Railway service; otherwise reuse the verified running service. It must require the same bearer credential and allow only `/api/v1/chat/completions` and `/api/v1/systemone`.
3. If edge code changed, deploy only `infra/gateway-edge/` with a supported CLI: `pnpm dlx vercel@latest deploy --prod --yes --cwd infra/gateway-edge`. Its hosted production `BOT_TOKEN` and `TELEGRAM_WEBHOOK_SECRET` must reuse the existing credentials, provided through stdin as exact bytes without a trailing newline, never argv or a plaintext env file.
4. Check edge health, both authenticated model routes, Telegram `getMe`, wrong-bearer 401 and forbidden-method 404. Run the full multi-call agent from the VPS without starting polling; a healthy edge alone is not sufficient.
5. Set matching endpoints and secrets in Dokploy. Keep the webhook at the edge URL and `max_connections=1`; the VPS target is fixed inside the ingress function. Preserve the Telegram host entry, persisted volume, one replica and persisted stop-first update and rollback order.
6. Release the verified source through the existing Dokploy application. Do not use obsolete standalone `docker run` or NixOS/systemd instructions.
7. Check `/health`, `/ready`, Swarm task state and `getWebhookInfo` (edge URL, one connection, no growing queue). Expect no polling thread in webhook mode. Exercise an actual Telegram conversation and callback; `/ready` does not establish model validity or external delivery.

Do not publish code or restart production merely because deterministic tests pass while model verification is blocked.

## Observed verification

- Bot/server namespaces compiled; 118 deterministic tests / 461 assertions passed. The pinned shared harness previously passed its 17-test / 55-assertion suite.
- Real one-shot CLI: compact electric walking treadmill under 15,000 KGS, 61 JEV candidates, 15 confirmed plus 38 uncertain matches, 8 rejected; first 20 cards stayed within budget and the process exited normally.
- Real same-session phone → one-som follow-up → different-router search succeeded through the hosted gateway with actual content deltas. The follow-up retained iPhone context, returned a valid empty pool and a bounded two-sentence reply; the new product did not leak phone cards. Accepted source fields remained unchanged.
- Focused real JEV checks excluded an unidentified “Брали за 18000” offer and a current ruble/KGS conflict, retained explicitly under-desk controls, kept unsupported form factors uncertain, recognized an explicit device defect and rejected an exchange-only offer.
- Model uncertainty is not a known model mismatch: live JEV checks exclude Xiaomi A4 from an AX3000-family request, retain AX3000T, and retain both for an unrestricted router request. In-family suffixes and unrelated seller search tags are not contradictions by themselves. This remains a typed semantic decision, not a lexical blacklist or confidence threshold.
- Real two-turn treadmill evaluation enforced 20,000 then 15,000 KGS while retaining conversation context and unchanged cross-turn source facts.
- The hosted gateway deployment reached `SUCCESS`; health, Gemini, JEV and SSE forwarding passed. Wrong bearer returned 401 and forbidden routes 404. Both model routes also returned 200 from the production VPS.
- The production network's Java HTTP client fetched Lalafo directly with status 200 and complete descriptions up to 2,354 characters.
- Historical native desktop captures show raw source cards, not production JEV selections. Record actual production Rich cards, pagination, refinements and topic isolation in the consumer receipts; do not reuse that baseline as selector proof.

Gateway release identity, exact Dokploy rollout identity and post-release consumer evidence are kept in the sanitized deployment/verification receipts. Historical invalid-key and missing-gateway failures are retained as outage baselines, not current blockers.
