# TapalakBot Deployment

## Server

- **IP**: `85.239.40.192`
- **OS**: NixOS 25.11 (Xantusia, unstable channel)
- **Provider**: Timeweb Cloud (Moscow, Russia)
- **Root access**: password in `pass servers/clojurebayke`

## Layout

```
/opt/tapalakbot-v2/            ← Clojure project (git: snqb/tapalakbot-v2)
  src/tapalakbot/
    core.clj                    — Agent: system prompt, 3 tools, pre-hook
    lalafo.clj                  — Lalafo HTTP client + Exa research
    bot.clj                     — Telegram handler
    server.clj                  — Entry point + healthcheck
/etc/tapalakbot/secrets.env    ← BOT_TOKEN, DEEPSEEK_API_KEY, EXA_API_KEY (chmod 600)
```

## Dependencies

- **Clojure 1.12** + tools.deps
- **Java 21** (JDK)
- **clj-harness** (git dep, pinned SHA in `deps.edn`)
- **No Python** — all tools use Java HttpClient directly
- **No uv** — not needed for runtime

## Service

```ini
# /etc/systemd/system/tapalakbot.service
[Service]
EnvironmentFile=/etc/tapalakbot/secrets.env
ExecStartPre=bash -c 'cd /opt/tapalakbot-v2 && git pull origin main || true'
ExecStart=clojure -M:bot
WorkingDirectory=/opt/tapalakbot-v2
Restart=always
RestartSec=10
```

```bash
systemctl restart tapalakbot    # Deploy latest code
systemctl status tapalakbot     # Check status
journalctl -u tapalakbot -f     # Follow logs
journalctl -u tapalakbot | grep healthcheck  # Verify health
```

## NixOS Config

```nix
# /etc/nixos/configuration.nix
services.openssh = { enable = true; settings.PasswordAuthentication = true; };

networking.extraHosts = "149.154.167.220 api.telegram.org";  # Russia workaround
environment.systemPackages = with pkgs; [ git clojure ];     # build tools only

systemd.services.tapalakbot = {
  description = "TapalakBot - Lalafo.kg Telegram AI Search Bot";
  after = ["network.target" "network-online.target"];
  wants = ["network-online.target"];
  serviceConfig = {
    ExecStartPre = "${pkgs.bash}/bin/bash -c 'cd /opt/tapalakbot-v2 && ${pkgs.git}/bin/git pull origin main 2>/dev/null || true'";
    ExecStart = "${pkgs.clojure}/bin/clojure -M:bot";
    WorkingDirectory = "/opt/tapalakbot-v2";
    Restart = "always";
    RestartSec = "10";
    EnvironmentFile = "/etc/tapalakbot/secrets.env";
    User = "root";
    StandardOutput = "journal";
    StandardError = "journal";
    SyslogIdentifier = "tapalakbot";
  };
  wantedBy = ["multi-user.target"];
};
```

## Initial Setup (done)

1. Connected to VPS, explored system
2. Saved server credentials to `pass servers/clojurebayke`
3. Configured NixOS with `clojure`, `git` packages + systemd tapalakbot service
4. Cloned tapalakbot-v2 from GitHub to `/opt/tapalakbot-v2`
5. Created `/etc/tapalakbot/secrets.env` (chmod 600) with API keys
6. Fixed Telegram API routing in Russia: `networking.extraHosts`
7. Built and deployed: `nixos-rebuild switch`

## Redeploy

```bash
# Push code from local
cd ~/Projects/tapalakbot-v2
git push origin main

# Restart on VPS
ssh root@85.239.40.192 systemctl restart tapalakbot

# Verify
ssh root@85.239.40.192 journalctl -u tapalakbot -n 5 | grep healthcheck
```

## Healthcheck

On startup, the bot runs `tapalakbot.lalafo/smoke-test` — searches for "iphone" on Lalafo API:
- `:healthcheck-pass :found N` — API reachable
- `:healthcheck-fail` — API unreachable or blocked

Check: `journalctl -u tapalakbot | grep healthcheck`
