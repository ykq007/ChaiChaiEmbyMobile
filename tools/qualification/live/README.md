# ChaiChai live-integration qualification environment

Private AgentDock fixture stack for wayfinder map #41. Every host port binds to `127.0.0.1`; the environment uses synthetic media and local test-only credentials.

## Services

- Emby 4.9.5.0: `http://127.0.0.1:18096`
- Emby 4.8.11.0: `http://127.0.0.1:18097` (amd64 through QEMU on this ARM64 host)
- Dandanplay-compatible read-only relay fake: `http://127.0.0.1:18081`
- OpenSubtitles.com REST v1 fake: `http://127.0.0.1:18082`
- Header-capture origin: `http://127.0.0.1:18083`
- HTTP proxies: `127.0.0.1:13128` (no auth), `127.0.0.1:13129` (Basic)
- SOCKS5 proxies: `127.0.0.1:11080` (no auth), `127.0.0.1:11081` (username/password)
- Caddy internal-CA TLS: `127.0.0.1:18443`, `18444`, `18445`

## Commands

- Generate or rotate local fixture credentials: `./scripts/bootstrap-secrets.sh [--force]`
- Start or converge: `./scripts/up.sh`
- Verify and capture sanitized evidence: `./scripts/evidence.sh`
- Stop: `docker compose --env-file .secrets/runtime.env down`
- Destructive reset while retaining test-only secrets and media: `./scripts/reset.sh`

Secrets are under `.secrets/` with mode 0600. Emby tokens and transient setup state are under `.state/`. Neither directory belongs in Git or published artifacts. These are local substitutes, not production provider credentials.

The committed template contains no credentials. `bootstrap-secrets.sh` generates all local passwords, provider keys, and proxy authentication material.
