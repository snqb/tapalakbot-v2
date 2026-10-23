# TapalakBot deployment

## Actual production topology

Verified on 2026-10-23:

- SSH host: `krugosvet` (`147.45.158.152`). The old NixOS host is not the release target.
- Dokploy application: `zXRWauuXqMP-7XIYSWLR5`, project `tapalakbot`, environment `production`.
- Docker Swarm service: `tapalakbot-production-densux`, one replica. Do not launch a standalone replacement container or a second Telegram poller.
- Source: `https://github.com/snqb/tapalakbot-v2.git`; Dokploy uses the root Dockerfile and root build context.
- Persistent volume: `tapalakbot-production-data` mounted at `/data`.
- Required effective host mapping: `149.154.167.220 api.telegram.org`, plus `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`.
- Current Telegram transport is polling: `getWebhookInfo` returned an empty URL. The bot is `@tapalakin_bot`.

Updates and rollbacks must both use stop-first ordering. Process health alone does not establish that model credentials or the external gateway work.

Persist rollout policy through Dokploy `application.update`: both
`updateConfigSwarm` and `rollbackConfigSwarm` must be objects containing
`Parallelism: 1` and `Order: "stop-first"`. Manual `docker service update` settings
were overwritten by deployment until these API fields were saved. Dokploy also
removes manual `ContainerSpec.Hosts` entries: the image entrypoint writes the
Telegram address, and the effective container mapping must be checked after rollout.

The pinned harness handles its first polling request inside the existing
poll-error loop and preserves unacknowledged updates. The old unguarded
`offset=-1` startup probe both discarded pending updates and terminated the worker
on a connect timeout. Readiness now requires the marketplace probe and is revoked
if the polling worker terminates; process liveness remains separate.

## Search and rendering contract

The Clojure/clj-harness stack remains in place. Migrating to Mastra or AI SDK would not replace the typed JEV endpoint and would require moving working session, tracking, callback and conversation-serialization code.

The conversation agent supplies full user intent and up to three keyword variants. Lalafo collection round-robins and deduplicates up to 200 complete source listings. Deterministic budget checks require known KGS prices or authoritative source equivalents; unknown currency is not KGS, and foreign amounts are never compared directly against a KGS budget. Provider numeric filters are not used because their mixed-currency semantics are unverified.

JEV evaluates fit, condition, evidence and risk through `/api/v1/systemone`. Complete typed responses are required for every batch; a partial or failed batch fails the entire selection. Valid empty decisions remain empty. Prices, currencies, URLs and images remain on the original source cards. Descriptions are never shortened before selection.

Typed decisions are reconciled before admission: missing product-identity evidence, explicit incompatibility and current-offer price/currency conflicts cannot be recommended. A concrete suspicious-source decision can only remain an uncertain alternative. Historical purchase prices are not current-offer conflicts, and a missing photo or description is not an automatic exclusion. JEV classifies supplied seller claims; it does not verify actual hardware or the seller.

Agent analysis is native Rich Markdown. Result cards are deterministic Rich HTML, with standalone source links, original photos and collapsed overflow. Media failure retries as text-only Rich HTML, then complete legacy HTML without dropping cards or controls. Pagination and opaque refinement callbacks belong to the exact conversation/thread. Explicit refinement bounds replace old budgets, including nil to clear a bound.

Collection, selector or pre-search conversation-provider failure is unavailable, not an empty successful market. A failed tracker check must not retire unseen candidates.

## Required configuration

| Variable | Purpose |
| --- | --- |
| `BOT_TOKEN` | Existing Telegram bot identity; keep exactly one poller |
| `OPENROUTER_API_KEY` | Dedicated credential from `pass openrouter/lalabot-token`; do not overwrite or reuse the old global entry |
| `OPENROUTER_BASE_URL` | `https://openrouter-proxy-production-1ad8.up.railway.app/api/v1/chat/completions` |
| `JEV_BASE_URL` | `https://openrouter-proxy-production-1ad8.up.railway.app/api/v1/systemone` |
| `SESSION_DB_PATH` | `/data/tapalakbot-sessions.db` |
| `MONITOR_DB_PATH` | `/data/tapalakbot-monitor.db` |
| `PORT` | Plain HTTP container port; default 8080 |
| `JAVA_TOOL_OPTIONS` | `-Djava.net.preferIPv4Stack=true` for the production Telegram network |
| `WEBHOOK_URL`, `TELEGRAM_WEBHOOK_SECRET` | Both required to switch to an authenticated webhook |
| `MONITOR_ADMIN_TOKEN` | Required before exposing the monitor administration API |

Model names and JEV bounds are in `resources/config.edn`: `google/gemini-3.5-flash`, `typesafe/jev-1.13`, batches at most 20, concurrency at most three, and 30-second request timeouts. Source descriptions are not truncated to fit a request; oversized individual listings fail explicitly.

Save runtime variables with Dokploy `application.saveEnvironment` without deploying. Preserve existing values and build settings; set `createEnvFile=false` because this Clojure image reads runtime environment variables and does not need a plaintext build-checkout `.env` file. `main` has auto-deploy enabled, so complete this preparation before pushing.

## Verification before release

```bash
clojure -M -e "(require 'tapalakbot.bot 'tapalakbot.server) (println :compile-ok)"
clojure -M:test
clojure -M:run "дорожка для ходьбы до 15000"
```

The terminal command is one-shot and must exit. An unavailable response is useful outage evidence, not a successful positive integration test.

With working credentials, exercise the real agent, Lalafo and JEV together: walking treadmill, exact phone, budget-only follow-up, different-product isolation, valid no-match, selector failure, and real streaming deltas. Verify accepted source IDs and unchanged monetary facts. Exercise Telegram cards, source links, pagination, refinements and distinct topics in a real client, not just API acceptance.

## Gateway and bot release order

1. Verify the OpenRouter credential against both the configured conversation model and the typed System One endpoint without printing the key.
2. If gateway code changed, deploy `infra/openrouter-proxy/` to its authorized Railway service; otherwise reuse the verified running service. It must require the same bearer credential and allow only `/api/v1/chat/completions` and `/api/v1/systemone`.
3. Observe the exact Railway deployment reach `SUCCESS`; check `/health`, both authenticated routes, and rejection of unauthorized requests. Obtain its actual public domain rather than reusing a stale hostname.
4. Set the matching gateway endpoints and credential in Dokploy. Preserve the Telegram host entry, persisted volume, one replica and stop-first update order. Ensure rollback also cannot overlap two pollers.
5. Release the verified source through the existing Dokploy application. Do not use the obsolete standalone `docker run` or NixOS/systemd instructions.
6. Check `/health` and `/ready`, Swarm task state, transport logs, and an actual Telegram conversation. `/ready` covers transport startup and marketplace probing, not model-credential validity.

Do not publish code or restart production merely because deterministic tests pass while model verification is blocked.

## Observed verification

- Bot/server namespaces compiled; 105 deterministic tests / 433 assertions passed after the readiness correction. The shared-harness startup regression also passed within its 17-test / 55-assertion suite.
- Real one-shot CLI: compact electric walking treadmill under 15,000 KGS, 61 JEV candidates, 15 confirmed plus 38 uncertain matches, 8 rejected; first 20 cards stayed within budget and the process exited normally.
- Real same-session phone → one-som follow-up → different-router search succeeded through the hosted gateway with actual content deltas. The follow-up retained iPhone context, returned a valid empty pool and a bounded two-sentence reply; the new product did not leak phone cards. Accepted source fields remained unchanged.
- Focused real JEV checks excluded an unidentified “Брали за 18000” offer and a current ruble/KGS conflict, retained explicitly under-desk controls, kept unsupported form factors uncertain, recognized an explicit device defect and rejected an exchange-only offer.
- Real two-turn treadmill evaluation enforced 20,000 then 15,000 KGS while retaining conversation context and unchanged cross-turn source facts.
- The hosted gateway deployment reached `SUCCESS`; health, Gemini, JEV and SSE forwarding passed. Wrong bearer returned 401 and forbidden routes 404. Both model routes also returned 200 from the production VPS.
- The production network's Java HTTP client fetched Lalafo directly with status 200 and complete descriptions up to 2,354 characters.
- Telegram accepted native Rich HTML with six source photos and standalone links; native desktop rendering was observed in the isolated QA topic. Production release must additionally exercise its actual pagination/refinement callbacks and topic ownership.

Gateway release identity, exact Dokploy rollout identity and post-release consumer evidence are kept in the sanitized deployment/verification receipts. Historical invalid-key and missing-gateway failures are retained as outage baselines, not current blockers.
