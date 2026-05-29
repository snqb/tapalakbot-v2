# Tapalakbot VPS Deployment

> NixOS 25.11 on Timeweb Cloud VPS (Moscow). Bot runs as systemd service.

## Server

| | |
|---|---|
| **IP** | `85.239.40.192` |
| **Hostname** | `msk-1-vm-0cj0` |
| **OS** | NixOS 25.11 Xantusia (unstable) |
| **Disk** | 50GB (47GB free) |
| **RAM** | 3.8GB |
| **Credentials** | `pass servers/clojurebayke` |

## Layout

```
/etc/nixos/configuration.nix          ← NixOS config with tapalakbot systemd service
/etc/tapalakbot/secrets.env           ← BOT_TOKEN, DEEPSEEK_API_KEY, EXA_API_KEY (chmod 600)

/opt/tapalakbot/                      ← Python workspace (uv monorepo)
  packages/lalafo-client/             ← Async Lalafo.kg API client
  packages/bot/                       ← Telegram bot (pydantic-ai agent)
  pyproject.toml                      ← uv workspace root

/opt/tapalakbot-v2/                   ← Clojure bot (git clone github.com/snqb/tapalakbot-v2)
  packages/ → /opt/tapalakbot/packages/  (symlink for lalafo_cli.py path resolution)
```

## systemd Service

```ini
# In /etc/nixos/configuration.nix
systemd.services.tapalakbot = {
  description = "TapalakBot - Lalafo.kg Telegram AI Search Bot";
  wantedBy = ["multi-user.target"];
  after = ["network.target" "network-online.target"];
  wants = ["network-online.target"];

  path = with pkgs; [git clojure uv python3];

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
};
```

## Access

```bash
sshpass -p '...' ssh root@85.239.40.192

# Logs
journalctl -u tapalakbot -f              # follow
journalctl -u tapalakbot --since "5 min ago"

# Control
systemctl status tapalakbot
systemctl restart tapalakbot
systemctl stop tapalakbot

# Config
nixos-rebuild switch                      # after editing configuration.nix
cat /etc/tapalakbot/secrets.env           # check env vars
```

## Redeploy

### Code push (tapalakbot-v2)

```bash
# Push to GitHub, then restart VPS service (git pull in ExecStartPre)
git push origin main
ssh root@85.239.40.192 'systemctl restart tapalakbot'
```

### Sync Python project (tapalakbot)

No git remote — rsync from local:

```bash
rsync -avz --exclude '.git' --exclude '__pycache__' --exclude '*.pyc' --exclude '.venv' \
  -e "sshpass -p '...' ssh" \
  /Users/sn/Projects/tapalakbot/ root@85.239.40.192:/opt/tapalakbot/

# Reinstall deps after syncing
ssh root@85.239.40.192 'cd /opt/tapalakbot && uv sync --all-packages'
```

## Secrets

| Env var | Source | Pass path |
|---------|--------|-----------|
| `BOT_TOKEN` | Telegram bot token | `telegram/tapalakbot/token` |
| `DEEPSEEK_API_KEY` | DeepSeek LLM API | `deepseek-api/token` |
| `EXA_API_KEY` | Exa web search | `api/exa` |
| `TAPALAKBOT_BASE_DIR` | Python project path | `/opt/tapalakbot` |
| `TAPALAKBOT_DIR` | Clojure project path | `/opt/tapalakbot-v2` |

## NixOS Packages

```nix
environment.systemPackages = with pkgs; [
  clojure     # Clojure tools 1.12.3
  uv          # Python package manager
  python3     # Python 3.13
  git         # Source control
];
```

## Russia-Specific Fixes

### Telegram API Blocked

Most of Telegram's IPs are blocked from Russian VPS. Solution: `/etc/hosts` override:

```nix
networking.extraHosts = ''
  149.154.167.220 api.telegram.org
'';
```

Verified working IPs (2026-05-29):
- ✅ `149.154.167.220`
- ❌ `149.154.166.110` (primary DNS)
- ❌ `149.154.167.99`
- ❌ `149.154.167.91`
- ❌ `91.108.56.100`

### Other Services (unblocked)

- ✅ `api.deepseek.com` — accessible
- ✅ `api.exa.ai` — accessible
- ✅ `google.com` — accessible

### Failed Approaches

| Approach | Why failed |
|----------|------------|
| **proxychains-ng** | Java HttpClient uses NIO — bypasses proxychains' LD_PRELOAD of `connect()` |
| **Tor SOCKS5** | Tor itself blocked in Russia (stuck at 10% bootstrap) |
| **Smartproxy** (`pass smartproxy/userpass`) | 403 on HTTPS CONNECT — likely credentials expired or IP not whitelisted |

## Initial Setup Checklist

What we did to get here:

1. ✅ Added `clojure`, `uv` to NixOS systemPackages
2. ✅ Created systemd service with auto-restart
3. ✅ Cloned `tapalakbot-v2` from GitHub
4. ✅ rsync'd `tapalakbot` Python project (no git remote)
5. ✅ Created `/etc/tapalakbot/secrets.env` (chmod 600)
6. ✅ Linked `/opt/tapalakbot-v2/packages → /opt/tapalakbot/packages`
7. ✅ Ran `uv sync --all-packages` to install Python deps
8. ✅ Added Telegram IP to `/etc/hosts`
9. ✅ Killed local tmux bot instance (token conflict)
10. ✅ Verified: search returns 470 items, DeepSeek API accessible
