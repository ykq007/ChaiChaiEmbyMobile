# Disposable live-service qualification environment

Provisioned: 2026-07-28

Wayfinder task: [Provision the disposable live-service qualification environment](https://github.com/ykq007/ChaiChaiEmbyMobile/issues/46)

## Outcome

The repository now contains a reproducible, loopback-only Docker environment at
[`tools/qualification/live`](../../tools/qualification/live). It turns the resolved Emby,
provider, and proxy decisions into observable local services without depending on a personal media
server or live third-party API during routine development.

The committed template contains no passwords, access tokens, provider keys, server databases,
certificates, generated media, or captured request evidence. Local fixture credentials are generated
under the ignored `.secrets/` directory, and transient Emby tokens are stored under ignored `.state/`.

## Topology

| Service | Host binding | Purpose |
| --- | --- | --- |
| Emby Server 4.9.5.0 | `127.0.0.1:18096` | Current stable supported server-line fixture |
| Emby Server 4.8.11.0 | `127.0.0.1:18097` | Immediately previous supported minor-line fixture |
| Dandanplay-compatible relay fake | `127.0.0.1:18081` | Read-only relay contract and upstream-signing boundary |
| OpenSubtitles.com REST v1 fake | `127.0.0.1:18082` | App-key, search, login, download, quota, and failure fixtures |
| Header-capture origin | `127.0.0.1:18083` | Proves HTTP and SOCKS routing reaches the intended origin |
| Squid without authentication | `127.0.0.1:13128` | HTTP proxy baseline |
| Squid with Basic authentication | `127.0.0.1:13129` | Per-client HTTP proxy credentials and 407 flow |
| SOCKS5 without authentication | `127.0.0.1:11080` | SOCKS5 routing and proxy-side hostname resolution |
| SOCKS5 username/password | `127.0.0.1:11081` | RFC 1929-style credential fixture |
| Caddy internal CA | `127.0.0.1:18443-18445` | Trusted-fixture and untrusted-CA TLS qualification |

Every published host port binds to `127.0.0.1`. Containers share the dedicated
`chai-qualification` Docker network. The provider upstream fake is not published to the host; the
mobile-facing relay is the only Dandanplay-compatible entry point.

## Host architecture

The environment was provisioned on an ARM64 Linux AgentDock host.

- `emby/embyserver:4.9.5.0` provides a native ARM64 image.
- `emby/embyserver:4.8.11.0` is an amd64 image and runs through the host's registered QEMU/binfmt
  emulator.
- The first clean startup of 4.8.11.0 is materially slower than the native server while it initializes
  databases, plugins, and FFmpeg capabilities. The scripts wait for the public-info endpoint rather
  than assuming a fixed startup delay.

This difference is part of the fixture evidence, not an application performance claim.

## Synthetic library

`generate-media.sh` creates two short, redistributable media fixtures locally with FFmpeg:

1. **Direct Stream Fixture**
   - H.264 video in MP4;
   - two AAC audio tracks labelled English and Japanese;
   - external English SRT and Chinese WebVTT sidecars;
   - local NFO metadata with a synthetic stable external id.
2. **Transcode Fixture**
   - MPEG-2 video and MP2 audio in Matroska;
   - intentionally outside the first guaranteed H.264/AAC direct-stream profile so negotiation can
     exercise transcoding;
   - local NFO metadata with a different synthetic stable external id.

No personal media, library names, account names, or third-party copyrighted assets are included.
Generated media is ignored by Git.

## Emby initialization

`init-emby.sh` is idempotent for both server lines. It:

1. waits for `GET /emby/System/Info/Public` to return successfully;
2. completes the startup wizard when the fixture is new;
3. authenticates the generated `qualifier` administrator;
4. creates the `Qualification Movies` library over `/media/movies`;
5. starts a library refresh;
6. creates a `blocked` user;
7. disables playback, audio/video transcoding, and remuxing for that user;
8. stores the generated administrator token only under `.state/` with restrictive permissions.

The startup sequence was verified against both bundled server versions rather than inferred from one
release line.

## Provider fixtures

### Dandanplay-compatible path

The local relay exposes only four allowlisted operations: match, search, comments, and a quota-failure
fixture. It reads a generated upstream application secret from a Docker secret, computes an upstream
signature, and forwards only to the unexposed local upstream fake.

The upstream fake verifies the signature and returns deterministic API-v2-shaped responses. Captures
redact authentication headers. No real Dandanplay credential or live request is used.

### OpenSubtitles-compatible path

The local REST-v1 fake requires a generated application key and a non-empty User-Agent. It provides
fixtures for:

- subtitle search with a stable `file_id`;
- temporary download-link creation;
- SRT download;
- optional login and bearer-token user information;
- logout;
- invalid credentials, unsupported file, and rate-limit outcomes.

The generated key is a local substitute. No OpenSubtitles account or production API key is stored in
this repository or environment definition.

## Proxy and TLS fixtures

The environment supplies HTTP and SOCKS5 routes with and without credentials. Verification sends the
same origin request through all four paths and then reads the origin's sanitized capture list. This
makes the selected route and destination observable without storing proxy credentials in evidence.

Caddy creates a local internal CA and HTTPS endpoints for both Emby versions and the provider fake.
The generated CA and leaf certificates stay under ignored `tls/data/`. Tests can use the generated
root certificate to prove a trusted path, then omit it to exercise a normal TLS trust failure.

## Secret locations

All local secrets are generated by `scripts/bootstrap-secrets.sh`:

- `.secrets/runtime.env` — non-production fixture variables;
- `.secrets/emby49_password` and `.secrets/emby48_password`;
- `.secrets/dandan_app_secret`;
- `.secrets/opensubtitles_api_key`;
- `.secrets/squid.htpasswd`.

The directory is ignored and mode-restricted. `.state/` contains transient Emby tokens and user ids
and is also ignored. The environment must never be archived or published with either directory.

## Operations

From `tools/qualification/live`:

```sh
./scripts/bootstrap-secrets.sh
./scripts/up.sh
./scripts/evidence.sh
```

`up.sh` converges an existing environment and initializes a new one. `evidence.sh` reruns all checks
and writes a local, ignored summary. Stop without deleting state with:

```sh
docker compose --env-file .secrets/runtime.env down
```

Destroy server state and generated certificates while retaining current local secrets and synthetic
media with:

```sh
./scripts/reset.sh
```

Rotate every local fixture credential by removing `.secrets/` and running
`bootstrap-secrets.sh`, or by invoking it with `--force` before rebuilding.

## Verified clean-reset evidence

The environment was validated from a destructive reset, including removal and regeneration of all
local fixture credentials. The clean reconstruction produced:

- all 11 services running;
- Emby 4.9.5.0 serving both synthetic movies;
- Emby 4.8.11.0 serving the same two movies under amd64 emulation;
- a successful Dandanplay-compatible match and a three-comment track through the relay;
- a successful OpenSubtitles-compatible search and download-link response;
- successful origin requests through unauthenticated HTTP, authenticated HTTP, unauthenticated
  SOCKS5, and authenticated SOCKS5 proxies;
- four sanitized origin captures, one for each proxy path;
- successful HTTPS public-info requests to both Emby versions using the generated internal-CA root.

The verification output ended with:

```text
services 11 running 11
4.9.5.0 chai-emby49 [('Direct Stream Fixture', 1), ('Transcode Fixture', 1)]
4.8.11.0 chai-emby48 [('Direct Stream Fixture', 1), ('Transcode Fixture', 1)]
qualification verification passed
```

No server id, token, password, API key, generated certificate, or raw provider/proxy credential is
part of the committed evidence.

## Facts this environment makes observable

The later specification and qualification-lane decisions can now verify:

- exact server version and startup compatibility for both Supported Server Lines;
- startup, authentication, playback-disabled-user, and token-scope behavior;
- identical synthetic library shape across server lines;
- direct-stream and transcode negotiation inputs;
- multiple audio tracks and external SRT/WebVTT availability;
- provider-specific search, match, comment, download, login, quota, and malformed/failure boundaries;
- HTTP and SOCKS5 routing with independent credentials;
- whether the intended origin received each proxied request;
- TLS success with the fixture CA and failure without it;
- reproducible reset and credential rotation procedures.

## Limits and deferred live evidence

This task provisions a safe qualification environment; it does not implement application features or
prove live third-party service availability.

- Real Dandanplay and OpenSubtitles credentials are not present.
- Tiny live-provider smoke checks, quotas, terms compliance, and release scheduling remain for
  [Choose the live-integration qualification lanes and gates](https://github.com/ykq007/ChaiChaiEmbyMobile/issues/47).
- The stack does not claim to reproduce every Emby packaging platform, codec, subtitle format, proxy
  authentication scheme, or public-network topology.
- A local internal CA is evidence for trust-boundary behavior, not permission to add a production
  trust-all or bundled private CA.
- The environment is loopback-only by default. Exposing it to another host requires an explicit,
  security-reviewed deployment change rather than editing the committed defaults casually.
