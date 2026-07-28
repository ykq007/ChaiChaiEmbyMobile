# Production proxy and transport-security baseline

Research date: 2026-07-28  
Wayfinder ticket: [Define the production proxy and transport-security matrix](https://github.com/ykq007/ChaiChaiEmbyMobile/issues/45)

## Decision

The first live-integration specification supports **Direct**, **HTTP proxy**, and **SOCKS5 proxy** routing as explicit, independently scoped choices. It does not define a process-wide application proxy.

The governing rule is **no silent direct fallback**:

- no proxy configuration, or a deliberately disabled proxy, means Direct;
- a valid enabled proxy means all traffic in that scope uses that proxy;
- an invalid, unreachable, unauthenticated, or failing enabled proxy blocks that scope and reports a typed failure;
- the only automatic Direct exception is an explicitly enabled, narrowly classified LAN bypass.

A proxy failure for an Emby server blocks that server's request rather than leaking it directly. A proxy failure for an optional Danmaku or subtitle provider disables/fails only that provider and never interrupts Emby playback.

## Supported routing scopes

### Emby server scope

Each configured Emby server has its own proxy policy keyed by canonical `ServerId`. The route applies uniformly to:

- public probe after the server has been associated with the saved configuration;
- authentication;
- API and image requests;
- playback negotiation and stream transport;
- external Emby subtitle streams;
- progress/start/stop reporting and durable retries;
- proxy connection tests.

One server's route or credentials never apply to another server, even when host names or proxy endpoints happen to match.

### Danmaku endpoint scope

Each user-configured endpoint has an independent Direct or proxy route keyed by stable endpoint id. For the official Dandanplay baseline, this is the mobile-to-relay route; relay-to-upstream routing is separately operated and never inherits a mobile Emby proxy.

### Subtitle-provider scope

Each subtitle provider has an independent Direct or proxy route keyed by stable provider id. Provider-account authentication and proxy authentication remain separate credential namespaces.

### No global inheritance

Changing an Emby proxy does not route Danmaku, OpenSubtitles, artwork from unrelated authorities, update checks, or any other integration. New network domains must declare their own routing owner rather than falling into a global client.

## Supported proxy matrix

| Proxy kind | No credentials | Credentials | Target DNS | Supported transport |
| --- | --- | --- | --- | --- |
| HTTP | Required | HTTP Basic through the per-client `proxyAuthenticator` | Proxy-side for proxied target host | HTTP targets and HTTPS CONNECT tunnels |
| SOCKS5 | Required | Username/password only after [Apply per-client SOCKS5 proxy credentials safely](https://github.com/ykq007/ChaiChaiEmbyMobile/issues/39) lands | Proxy-side using SOCKS5 DOMAINNAME for hostnames | TCP CONNECT only |

HTTPS-to-the-proxy, SOCKS4, PAC files, system/VPN proxy discovery, UDP ASSOCIATE, Kerberos/NTLM proxy authentication, and process-global `java.net.Authenticator` are not part of this baseline.

### HTTP proxy authentication

HTTP proxy credentials are applied only through OkHttp's per-client `proxyAuthenticator` as `Proxy-Authorization`, never as origin `Authorization`. OkHttp distinguishes origin 401 from proxy 407 and supports proxy authentication for CONNECT tunnels ([OkHttp Authenticator](https://square.github.io/okhttp/3.x/okhttp/okhttp3/Authenticator.html), [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-authenticating-clients-to-pro)).

The implementation must:

- send credentials only to the exact configured proxy host and port;
- make at most one credential attempt per challenge chain;
- never copy `Proxy-Authorization` into a tunneled origin request;
- never expose proxy credentials to interceptors, logs, diagnostics, cache keys, or target servers;
- distinguish 407 from target 401/403;
- load credentials lazily from the scope vault so rotation does not require process restart.

Basic proxy authentication is not encrypted by the authentication scheme itself. Users must be warned to use credentials only with a trusted proxy/network path; TLS to the target protects target content inside CONNECT but does not turn the proxy connection itself into an HTTPS proxy.

### SOCKS5 authentication

SOCKS5 no-auth routing is supported now. Username/password routing is accepted into the product baseline only through the existing concrete issue “Apply per-client SOCKS5 proxy credentials safely.” It must implement an isolated SOCKS5 handshake or equivalent per-client socket mechanism and must not mutate the process-global Java authenticator.

The accepted methods are:

- `NO AUTHENTICATION REQUIRED`;
- RFC 1929 username/password.

SOCKS5 supports IPv4, IPv6, and domain-name destination address types; hostname targets must be sent as DOMAINNAME so the proxy resolves them rather than leaking target DNS through the device resolver ([RFC 1928](https://www.rfc-editor.org/rfc/rfc1928.html), [RFC 1929](https://www.rfc-editor.org/rfc/rfc1929.html)). Literal IP targets remain IP address types.

RFC 1929 transmits the password without cryptographic protection. The UI and documentation must warn that credentials require a trusted SOCKS transport. Additional auth methods and SOCKS-over-TLS are deferred.

## DNS policy

### Proxy endpoint DNS

The device resolves the configured proxy host because it must connect to that proxy. Proxy-host resolution failure is a distinct `DnsFailure` and never causes direct target access.

### Proxied target DNS

For HTTP and SOCKS5 routes, the target hostname is resolved by the proxy. The device must not perform target A/AAAA lookup before proxy connection. In particular:

- SOCKS5 uses an unresolved/domain-name destination;
- HTTP proxy requests and HTTPS CONNECT carry the original host name;
- routing diagnostics may record only that proxy-side resolution was selected, never the resolved address unless supplied as a non-sensitive test fixture.

Java's normal `InetSocketAddress(String, port)` attempts local resolution, while `createUnresolved` preserves a hostname for proxy use; the implementation and tests must use the unresolved form wherever target resolution belongs to the proxy ([Java InetSocketAddress](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/InetSocketAddress.html)).

### Direct target DNS

Direct routes use Android/system DNS. DNS results are not used to retroactively change a proxy decision.

## LAN bypass

LAN bypass is off by default and configured separately for every proxy scope. It is an explicit privacy exception, not an optimization.

A target may bypass only when its original authority is:

- literal IPv4 loopback, RFC1918 private, or IPv4 link-local;
- literal IPv6 loopback, unique-local `fc00::/7`, or link-local `fe80::/10`;
- `localhost` / `.localhost`;
- an mDNS `.local` name.

Do not resolve an arbitrary public-looking hostname merely to decide whether it is private. That would leak DNS before the proxy decision and introduce rebinding-dependent behavior. A public name that happens to resolve to a private address remains proxied unless the user enters an explicit local authority.

The classifier must use real IP parsing and CIDR checks, including IPv4-mapped IPv6, rather than string prefixes. The current implementation's hostname/string-prefix classifier is sufficient as a prototype but must be hardened before qualification.

When LAN bypass selects Direct, no proxy credential is loaded or attached. The UI must show that private/local targets can escape the proxy while this option is enabled.

## Redirect and authority policy

An authority is the normalized `(scheme, host, port)` origin boundary defined by HTTP semantics ([RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-uri-origin)). Credentials and trust exceptions never cross that boundary implicitly.

### Emby setup probe

The unauthenticated `System/Info/Public` probe may follow at most five HTTP redirects manually because no token or password is present. Every hop must:

- remain HTTP or HTTPS;
- be parsed as a valid Server Address preserving its path prefix;
- use a newly selected route for the new authority;
- drop any certificate exception from the previous authority;
- stop before an HTTPS-to-HTTP request until the user acknowledges the final cleartext authority;
- show and require confirmation when the final authority differs from the entered authority.

The confirmed final Server Address becomes canonical before authentication.

### Credential-bearing Emby requests

Authentication, API, artwork, playback negotiation, playback streams, subtitles, and reporting do not follow redirects automatically. A 3xx response is a typed transport/configuration failure. The user must reconnect using the final canonical address.

Server-provided playback URLs are accepted only when their authority is the confirmed Emby authority for this baseline. Cross-authority CDN playback is deferred until it has an explicit allowlist, header policy, and qualification fixture.

### Provider APIs

Provider API requests do not carry credentials across redirects.

- OpenSubtitles' `base_url` is accepted only as an explicit response field, validated against the provider-owned HTTPS host policy, then used to build a new client/authority scope.
- An OpenSubtitles temporary download link may use a provider CDN authority, but it is fetched using a fresh credential-free request unless the provider contract explicitly requires a scoped header. API key, JWT, and account credentials are not copied to the CDN by default.
- The Dandanplay mobile client talks only to the configured relay authority; the relay owns its upstream redirect policy and strips credentials before authority changes.
- Generic user-configured providers reject cross-authority redirects by default.

## TLS trust

System trust anchors and normal hostname verification are required for:

- all official providers and provider CDNs;
- the Dandanplay relay;
- all proxy-independent HTTPS connections;
- HTTPS Emby servers in the supported compatibility baseline.

No provider or proxy may inherit the Emby Server certificate exception. No trust-all manager, wildcard hostname verifier, global custom CA, or redirect-transferred bypass is permitted.

The existing Emby-only Certificate Bypass ADR remains a separately acknowledged compatibility exception for one exact Server Address authority. This map does not expand it, make it a release-baseline requirement, or permit it for providers. If used, it remains independent of proxy routing and is cleared on authority change.

Certificate pinning is not required in the first baseline because the app connects to user-controlled servers and third-party services with independent certificate rotation. System trust plus authority containment is the required default.

## Cleartext policy

All official provider, relay, and CDN endpoints are HTTPS-only. HTTP provider URLs are rejected.

User-configured Emby HTTP remains supported only under the existing ADR:

- explicit Server Address entry or confirmed redirect;
- a clear warning before the first request containing credentials;
- acknowledgement scoped to the exact authority;
- no silent HTTPS downgrade;
- no transfer to providers or a different server.

Android disables cleartext by default for modern target SDKs and recommends avoiding a globally permissive base configuration ([Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)). The current manifest sets `usesCleartextTraffic="true"` so arbitrary runtime-configured self-hosted HTTP authorities can work. Because Android's declarative domain rules cannot enumerate arbitrary future user-entered hosts, the production specification must treat that manifest capability as **not authorization** and enforce cleartext at the application's central URL/request boundary.

Required guardrails:

- every network request carries a declared integration scope and approved scheme policy;
- only an acknowledged Emby authority or explicitly acknowledged local/self-hosted generic endpoint may use HTTP;
- official provider adapters hard-reject HTTP before client construction;
- tests enumerate every production client factory and prove no unacknowledged cleartext request can leave;
- release scanning rejects hard-coded `http://` production endpoints except documented local fixtures.

The map does not authorize new global cleartext behavior; it constrains the already-existing self-hosted compatibility mechanism.

## Secret storage and redaction

All proxy credentials are stored per scope in Android Keystore-backed AES-GCM storage. Non-secret route configuration contains only kind, host, port, enabled state, LAN-bypass state, and a credential-presence flag.

Credential namespaces must be distinct:

- Emby proxy: canonical server id;
- Danmaku endpoint proxy: endpoint id;
- subtitle-provider proxy: `proxy:<providerId>`;
- provider account/JWT: separate `auth:<providerId>` namespace;
- relay installation token: separate relay namespace.

Requirements:

- no secret in SharedPreferences plaintext, issue comments, logs, diagnostics, analytics, URLs, exception messages, cache keys, branch names, or artifacts;
- deleting a server/endpoint/provider deletes its secret;
- disabling credentials clears the secret when requested;
- credential-presence flags are derived from vault state rather than trusted persisted truth;
- backup/export excludes all credentials;
- diagnostics may include proxy kind, redacted host/port where useful, route decision, and typed failure, but never username/password or auth headers.

## Failure behavior

An enabled proxy is a privacy/security policy, not a best-effort hint.

| Failure | Emby scope | Optional provider scope |
| --- | --- | --- |
| Invalid host/port | Block request; configuration error | Disable/fail provider only |
| Proxy DNS/unreachable/timeout | Block request; typed proxy error | Provider unavailable; playback continues |
| HTTP 407 / SOCKS auth rejection | Block; prompt credential correction | Provider auth route failure only |
| Target DNS/connection through proxy | Block; distinguish target from proxy | Provider unavailable |
| TLS failure at target | Block; normal certificate policy | Provider unavailable; no bypass offer |
| Proxy disabled deliberately | Direct | Direct |
| LAN bypass match | Direct with visible bypass state | Direct for that provider only |

The current `resolveProxyRoute` returns Direct for an enabled but malformed proxy. That behavior must be replaced by validation or a distinct invalid/blocked route before the production baseline is complete.

## Qualification evidence

### Deterministic HTTP proxy fixtures

- direct, HTTP proxy, and CONNECT routing for each scope;
- no-auth and Basic 407 challenge/preemptive CONNECT;
- exactly one credential retry;
- `Proxy-Authorization` visible to the proxy and absent at the origin;
- credentials for scope A never reach scope B;
- invalid enabled config never falls back Direct;
- proxy unreachable, proxy DNS, timeout, 407, target error, and target TLS classified separately;
- all Emby traffic classes use the same server route.

### Deterministic SOCKS5 fixtures

- NO AUTH and username/password handshakes;
- distinct per-client credentials without global authenticator mutation;
- DOMAINNAME target request proving no local target DNS lookup;
- IPv4/IPv6 literal address types;
- auth rejection and every relevant SOCKS reply mapped to typed failures;
- no UDP or unsupported-method fallback.

### LAN bypass fixtures

- complete IPv4/IPv6 CIDR boundary table;
- localhost and `.local` handling;
- public hostname resolving private remains proxied;
- private literal bypasses only when enabled;
- proxy credentials are never loaded on bypass.

### Redirect/TLS/cleartext fixtures

- bounded unauthenticated probe redirect chain;
- HTTPS upgrade, HTTPS downgrade confirmation, authority-change confirmation;
- no authenticated redirect follow;
- no token, origin Authorization, provider API key, JWT, proxy credential, or certificate exception crossing authority;
- trusted and untrusted TLS for server/provider/relay independently;
- official provider HTTP rejected;
- only acknowledged Emby cleartext accepted;
- manifest/client-factory audit proving no accidental cleartext path.

Live qualification uses disposable HTTP and SOCKS5 proxies, both Emby fixture lines, the provider relay, and provider smoke accounts. Evidence records routes and typed outcomes without secrets or personal media metadata.

## Rejected alternatives

- **Fallback to Direct when a proxy fails:** leaks traffic and defeats the user's explicit policy.
- **One app-wide proxy:** breaks server/provider isolation and makes credential scope ambiguous.
- **Process-global Java authenticator for SOCKS5:** can expose one scope's credentials to another.
- **Local target DNS before SOCKS/HTTP proxying:** leaks destinations and can produce route-dependent behavior.
- **Resolve arbitrary hostnames to decide LAN bypass:** creates DNS leakage and rebinding-sensitive policy.
- **Automatic authenticated redirects:** can transfer credentials, request bodies, or trust to another authority.
- **Global trust-all or provider certificate bypass:** removes origin authentication.
- **Treat `usesCleartextTraffic=true` as blanket permission:** allows accidental HTTP outside the acknowledged self-hosted exception.
- **Store secrets inside route configuration:** makes persistence, diagnostics, and UI boundaries unsafe.

## Consequences for the map

- The disposable qualification environment can now provision exact HTTP/SOCKS5 proxy fixtures and DNS/header capture points.
- The qualification-lanes decision can mark deterministic no-leak/no-fallback tests as merge-blocking and reserve small live proxy checks for scheduled/release runs.
- Issue #39 remains the concrete prerequisite for authenticated SOCKS5 support and should be consumed, not duplicated, by `/to-spec`.
- `/to-spec` must include implementation slices for fail-closed invalid proxy state, hardened CIDR LAN classification, proxy-side target DNS, central cleartext authorization, and provider redirect/header containment.
- No additional wayfinder decision ticket is required; the remaining uncertainty is implementation decomposition, already retained in the map's fog.
