# TapalakBot Deployment

## Server

- **IP**: `85.239.40.192`
- **OS**: NixOS 25.11 (Xantusia, unstable channel)
- **Provider**: Timeweb Cloud (Moscow, Russia)
- **Root access**: password in `pass servers/clojurebayke`

## Layout

```
/opt/tapalakbot-v2/              ← Clojure project (git: snqb/tapalakbot-v2)
  src/tapalakbot/
    core.clj                      — Agent: system prompt, tools, pre-hook
    lalafo.clj                    — Lalafo HTTP client + Exa research
    bot.clj                       — Telegram handler + streaming
    server.clj                    — Entry point + healthcheck + monitor auto-start
    monitor/
      store.clj                   — SQLite: categories, items, price snapshots
      scanner.clj                 — Background Lalafo scanner (every 4h)
      api.clj                     — Ring/Jetty HTTP API (:8787)
      client.clj                  — HTTP client for monitor API
      main.clj                    — Monitor standalone entry point
/etc/tapalakbot/secrets.env      ← BOT_TOKEN, DEEPSEEK_API_KEY, EXA_API_KEY, WEBHOOK_URL (chmod 600)
/opt/tapalakbot-v2/certs/        ← Self-signed TLS cert (key.pem, cert.pem, keystore.p12)
/tmp/tapalakbot-monitor.db       ← SQLite DB (ephemeral, recreated on reboot)
```

## Dependencies

- **Clojure 1.12** + tools.deps
- **Java 21** (JDK)
- **clj-harness** (git dep, pinned SHA in `deps.edn`)
- **ring/ring-core + ring-jetty-adapter + ring-json** — Monitor HTTP API
- **next.jdbc + sqlite-jdbc** — Monitor SQLite storage
- **No Python** — all tools use Java HttpClient directly

## Services

The bot runs as a single systemd service. On startup, `server.clj`:
1. Initializes the agent (clj-harness + DeepSeek)
2. Auto-starts the price monitor in a background thread (scanner + API on :8787)
3. Runs Lalafo healthcheck
4. Starts Telegram webhook (HTTPS on :8443) or falls back to polling

Webhook mode is enabled via `WEBHOOK_URL` in secrets.env. Self-signed TLS cert
is generated on first run in `certs/` (uploaded to Telegram via setWebhook).

```bash
systemctl restart tapalakbot    # Deploy latest code
systemctl status tapalakbot     # Check status
journalctl -u tapalakbot -f     # Follow logs
journalctl -u tapalakbot | grep healthcheck  # Verify health
```

### Webhook (port 8443)

Telegram sends updates to `https://<IP>:8443/webhook`. Self-signed cert, CN=IP.
Endpoints: `/webhook` (Telegram updates), `/health` (healthcheck).

```bash
# Verify webhook status from Telegram's perspective
curl -s "https://api.telegram.org/bot$BOT_TOKEN/getWebhookInfo"

# Local health check (from VPS)
curl -sk https://localhost:8443/health
```

**⚠️ Timeweb security group**: port 8443 must be open for inbound TCP.
Check in Timeweb dashboard → Security Groups → add rule for port 8443.

### Monitor API (port 8787)

Available after startup. Used by bot for `/start` and `/prices` commands.

```bash
curl http://localhost:8787/health            # Health check
curl http://localhost:8787/prices/start      # Market digest text
curl http://localhost:8787/prices/categories # Category stats JSON
curl http://localhost:8787/prices/trending   # Top items per category
curl http://localhost:8787/prices/search?q=macbook  # Search items
curl http://localhost:8787/prices/deals      # Items below average
curl http://localhost:8787/prices/history/:id  # Price history
curl -X POST http://localhost:8787/scan      # Trigger immediate scan
```

## NixOS Config

Key points in `/etc/nixos/configuration.nix`:

```nix
# Telegram API routing (Russia workaround)
networking.extraHosts = "149.154.167.220 api.telegram.org";

# System packages
environment.systemPackages = with pkgs; [ git clojure ];

# Systemd service
systemd.services.tapalakbot = {
  description = "TapalakBot - Lalafo.kg Telegram AI Search Bot";
  after = [ "network.target" "network-online.target" ];
  wants = [ "network-online.target" ];
  serviceConfig = {
    Type = "simple";
    User = "root";
    WorkingDirectory = "/opt/tapalakbot-v2";
    EnvironmentFile = "/etc/tapalakbot/secrets.env";
    ExecStartPre = "${pkgs.bash}/bin/bash -c 'cd /opt/tapalakbot-v2 && git pull origin main 2>/dev/null || true'";
    ExecStart = "${pkgs.clojure}/bin/clojure -M:bot";
    Restart = "always";
    RestartSec = 10;
    StandardOutput = "journal";
    StandardError = "journal";
    SyslogIdentifier = "tapalakbot";
  };
  # CRITICAL: git must be in PATH for Clojure tools.deps git dep resolution
  environment = {
    PATH = lib.mkForce (lib.makeBinPath [
      pkgs.coreutils pkgs.findutils pkgs.gnugrep pkgs.gnused
      pkgs.git pkgs.clojure
    ]);
  };
  wantedBy = [ "multi-user.target" ];
};
```

⚠️ **Do not forget `environment.PATH`** — Without `pkgs.git` in PATH, Clojure crashes with `Cannot run program "git": No such file or directory` when resolving git deps.

## Initial Setup (done)

1. Connected to VPS, explored system
2. Saved server credentials to `pass servers/clojurebayke`
3. Configured NixOS with `clojure`, `git` packages + systemd tapalakbot service
4. Cloned tapalakbot-v2 from GitHub to `/opt/tapalakbot-v2`
5. Created `/etc/tapalakbot/secrets.env` (chmod 600) with API keys
6. Fixed Telegram API routing in Russia: `networking.extraHosts`
7. Added `pkgs.git` to systemd PATH for git dep resolution
8. Built and deployed: `nixos-rebuild switch`

## Redeploy

```bash
# Push code from local
cd ~/Projects/tapalakbot-v2
git push origin main

# Restart on VPS (ExecStartPre does git pull automatically)
ssh root@85.239.40.192 systemctl restart tapalakbot

# Verify
ssh root@85.239.40.192 journalctl -u tapalakbot -n 5 | grep healthcheck
```

If deps.edn changed (new deps, SHA update), clear classpath cache first:
```bash
ssh root@85.239.40.192 "rm -rf /opt/tapalakbot-v2/.cpcache && systemctl restart tapalakbot"
```

If NixOS config changed:
```bash
ssh root@85.239.40.192 "nixos-rebuild switch"
```

## Healthcheck

On startup, the bot runs `tapalakbot.lalafo/smoke-test` — searches for "iphone" on Lalafo API:
- `:healthcheck-pass :found N` — API reachable
- `:healthcheck-fail` — API unreachable or blocked

Monitor health: `curl http://localhost:8787/health`

Check: `journalctl -u tapalakbot | grep healthcheck`
