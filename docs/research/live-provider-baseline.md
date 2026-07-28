# First live Danmaku and subtitle-provider baseline

Research date: 2026-07-28  
Wayfinder ticket: [Choose the first live Danmaku and subtitle-provider baseline](https://github.com/ykq007/ChaiChaiEmbyMobile/issues/44)

## Decision

The first supported live-provider set is deliberately limited to:

1. **Danmaku:** the official **弹弹play开放弹幕网络 API v2**, read-only, accessed through a project-controlled relay that holds and applies the application credential.
2. **Subtitles:** the official **OpenSubtitles.com REST API v1**, using an application API key and mandatory application User-Agent, with optional user authentication for account-specific download allowances.

Both integrations are optional, disabled until their production credentials are provisioned, isolated from Emby playback, and replaceable behind provider-specific adapters. A failure, quota limit, authentication rejection, malformed response, or service outage must leave playback and the currently active subtitle untouched.

The existing generic user-configured Danmaku and subtitle-provider contracts remain supported as an advanced/self-hosted extension point, but they are **not** the first production-qualified provider baseline. They do not match either official provider's real wire protocol or authentication model.

## Danmaku baseline: 弹弹play开放弹幕网络

### Contract to freeze

The provider-specific adapter will implement the official API v2 flow:

- `POST /api/v2/match` for automatic matching when adequate file identity is available.
- `GET /api/v2/search/episodes` for title/TMDB/episode-assisted search and manual correction.
- `GET /api/v2/comment/{episodeId}?withRelated=true` for the time-indexed comment track.

The current machine-readable API exposes those routes, and the provider's April 2026 changelog directs clients away from the removed `/api/v2/related` and `/api/v2/extcomment` endpoints toward `withRelated=true` on the comment route ([official OpenAPI](https://api.dandanplay.net/swagger/v2/swagger.json), [official changelog](https://github.com/kaedei/dandanplay-doc/blob/master/docs/open/changelog.md)).

The July 2026 `v2=true` search-engine opt-in is **not** acceptance-critical initially. The adapter may expose it behind a remotely reversible provider configuration after live comparison, but the first contract must continue working with the provider's default search behavior until the new engine becomes the documented default.

The normalized domain result remains small:

- stable `episodeId` as the provider media identity;
- canonical title plus episode/season context used for human confirmation;
- comments normalized to time, text, color, and position;
- provider provenance and match method;
- no provider DTOs outside `platform:danmaku`.

### Authentication and secret boundary

Dandanplay requires an assigned `AppId` and `AppSecret`. It supports direct credential headers or a signature computed from `AppId + Timestamp + Path + AppSecret`, and recommends signed mode for client applications. Its documentation also requires protecting the secret and suggests server-side forwarding as one option ([official integration guide](https://github.com/kaedei/dandanplay-doc/blob/master/docs/open/README.md)).

An Android package cannot make a shared application secret non-extractable. Therefore the production baseline will **not** embed the Dandanplay `AppSecret` in source, resources, BuildConfig, native code, local preferences, or the Android Keystore. Instead:

- a minimal project-controlled HTTPS relay stores the credential;
- the relay accepts only the three allowlisted read-only operations above;
- it adds the official authentication signature immediately before the upstream request;
- it never returns or logs the credential;
- it strips authentication headers before following any cross-authority redirect;
- it applies bounded request size, timeout, cache, quota, and abuse controls;
- it returns a stable, independently authored normalized contract to the app.

The mobile client authenticates to the relay with a revocable installation-scoped token or equivalent abuse-control mechanism; that token is not an upstream Dandanplay credential. The exact relay deployment belongs to the disposable-environment and implementation-ticket phases.

### Usage, attribution, and quota policy

The provider requires applications to identify the integration as **弹弹play开放弹幕网络**, use the full 弹弹play name in UI, avoid bulk scraping, call in response to real user activity, and cache results. Commercial use requires explicit authorization. Application tiers and quotas have been active since 2026-06-25 ([official usage agreement and cache guidance](https://github.com/kaedei/dandanplay-doc/blob/master/docs/open/README.md), [official quota changelog](https://github.com/kaedei/dandanplay-doc/blob/master/docs/open/changelog.md)).

The first specification must therefore require:

- an About/Settings attribution with the official service name and website;
- read-only use; no sending comments, follows, history, login, or other restricted-user APIs;
- no background catalogue crawling or bulk comment download;
- cached match/search results and comment tracks, with provider-aware expiry and manual refresh;
- explicit handling for quota/abuse rejection without retry storms;
- no commercial release using this provider until the project's authorization tier permits it.

### Matching order

For a currently playing Emby item:

1. Reuse a remembered, server-scoped `episodeId` if the user previously confirmed it.
2. Prefer TMDB/title/season/episode-assisted search when stable metadata exists.
3. Use file-match only when the client legitimately has the required filename/hash/size inputs; do not fabricate a local-file hash for remote Emby media.
4. Present manual candidates when automatic confidence is insufficient.
5. Fetch comments only after an `episodeId` is selected.

The existing `DanmakuMatchQuery` lacks TMDB identity and has no provider-specific match mode. A later implementation ticket must add stable external IDs and a provider adapter without leaking Dandanplay DTOs into feature modules.

## Subtitle baseline: OpenSubtitles.com REST API v1

### Contract to freeze

The provider-specific adapter will use:

- `GET /api/v1/subtitles` for search;
- `POST /api/v1/download` with the selected **file id**, not the subtitle record id;
- `POST /api/v1/login` and bearer-token authentication only when the user chooses to connect an OpenSubtitles.com account;
- `DELETE /api/v1/logout` when disconnecting that account;
- the `base_url` returned by login for subsequent authenticated requests.

Every request carries the application `Api-Key` and a non-empty `User-Agent` identifying ChaiChai and its version. OpenSubtitles' official implementation uses this REST surface, and its documentation treats login as an expensive, rate-limited operation and instructs clients to continue on the returned base host ([official API documentation](https://ai.opensubtitles.com/docs), [official Kodi provider implementation](https://github.com/opensubtitles-dev/service.subtitles.opensubtitles-com/blob/master/resources/lib/osclient/provider.py)).

Search should prefer stable identifiers in this order:

1. movie hash plus size when those are legitimately available;
2. IMDB or TMDB identity;
3. season and episode identity;
4. normalized title query as a fallback.

The current `SubtitleProviderQuery` only carries title/language/season/episode/runtime. It must gain stable external IDs and optional legitimate hash/size fields. Search responses must preserve provider `file_id`, language, release name, hearing-impaired flag, uploader/quality metadata needed for ranking, and nullable/unknown fields without failing the whole response.

### Authentication and credentials

The application API key identifies the ChaiChai integration and is provisioned through release secrets/configuration; it is not entered by each user. The specification must still treat it as controlled configuration, redact it from logs, and support revocation/rotation.

User sign-in is optional:

- search and provider-permitted free downloads can operate in app-key mode;
- a user may connect an OpenSubtitles.com account to obtain a JWT and account-specific allowances;
- username/password are used only for the login request and are not persisted after token exchange;
- the JWT is stored in the existing Keystore-backed credential boundary, scoped to the OpenSubtitles provider;
- 401 invalidates the token and prompts reauthentication without repeatedly replaying credentials;
- logout destroys the remote token and clears local state.

The existing generic provider's HTTP Basic credential model is not suitable and must not be reused for OpenSubtitles login.

### Downloads, formats, and rate limits

The search candidate stores `file_id`, not a long-lived download URL. Selection performs `POST /download`; the returned link is consumed immediately, and only the downloaded bytes are persisted in the app's private subtitle cache. The temporary link is never placed in preferences, diagnostics, analytics, or issue evidence.

The first supported activation formats remain **SRT and WebVTT**. The adapter may request SRT normalization where the API supports it. Archives, ASS/SSA styling, bitmap formats, AI transcription/translation, upload, and subtitle editing are deferred.

The client must:

- honor 429 and response rate-limit headers with bounded backoff and no concurrent retry storm;
- surface daily/download allowance exhaustion distinctly from authentication or network failure;
- contain 406, 401, 429, 5xx, malformed JSON, empty files, and temporary-link failure;
- tolerate provider JSON properties being absent or null;
- never replace the current subtitle until the new file has downloaded, validated, stored, and activated successfully.

### Ranking

Within the requested languages, rank candidates using provider evidence rather than title-only order:

- exact stable-ID/hash match before text fallback;
- season/episode agreement;
- release-name similarity when available;
- supported format;
- hearing-impaired preference selected by the user;
- provider quality/download metadata as a final tie-breaker.

Always show provenance and enough match information for manual correction. Do not silently select a weak title-only match.

## Shared failure-isolation and transport rules

Both provider adapters must reuse the existing per-provider/per-endpoint HTTP client isolation:

- independent timeout, connection pool, cache, and optional proxy route;
- system TLS verification only;
- no transfer of an Emby Server certificate exception;
- no transfer of Emby tokens or headers;
- credentials attached only to the intended provider/relay authority;
- authorization removed on cross-authority redirects;
- bounded response body and decompression limits;
- cancellation when the playback identity changes;
- partial results survive one provider failing.

Provider failures can change provider status and hide/disable optional controls, but cannot pause, stop, renegotiate, or otherwise weaken core playback.

## Deterministic fixtures

Every pull request must use local fixtures, never live provider calls.

### Dandanplay fixtures

Freeze redacted fixtures for:

- match success, no match, ambiguous candidates, and business-level `success:false`;
- title/TMDB/episode search and empty results;
- `withRelated=true` comment response, position/color variants, malformed comments, and large bounded tracks;
- upstream 401/403, quota/abuse response, 429, timeout, 5xx, and relay rejection;
- authentication-header non-leakage across redirect;
- cache hit, expiry, manual refresh, and cancellation.

### OpenSubtitles fixtures

Freeze redacted fixtures for:

- search by IMDB/TMDB/episode and title fallback;
- multiple files under one subtitle record, proving `file_id` selection;
- nullable/unknown fields and unsupported formats;
- anonymous/app-key download and authenticated download;
- login success with `base_url`, 401, logout, and token expiry;
- allowance exhaustion, 406, 429 plus rate-limit headers, 5xx, malformed response, expired download link, and empty file;
- successful SRT/VTT validation and prior-subtitle preservation on every failure.

Live checks run only in the qualification lane selected later by the map. They verify credentials, one known search, one small permitted download/comment fetch, attribution/configuration, quota headers, and failure containment. They do not crawl catalogues or run on every pull request.

## Explicit deferrals

The first baseline does not include:

- Dandanplay write/send-comment, user login, following, history, recommendations, rankings, or bulk export;
- embedding an upstream Dandanplay secret in the Android app;
- unofficial public mirrors as automatic fallbacks;
- scraping Bilibili, streaming sites, subtitle websites, or other providers without a documented API and authorization;
- OpenSubtitles.org XML-RPC or other legacy `.org` APIs;
- subtitle upload, rating, editing, AI translation/transcription, archives, ASS/SSA styling, or bitmap subtitle conversion;
- a generic promise that any arbitrary URL implementing a vaguely similar JSON shape is production-supported;
- more live providers until this pair has reproducible fixtures, qualification evidence, and stable operational ownership.

## Rejected alternatives

- **Direct Dandanplay signing inside the APK:** a shared upstream secret can be extracted and abused; Android Keystore cannot protect a secret that must ship identically to every installation.
- **Unofficial Dandanplay mirror as the default:** ownership, data provenance, uptime, credential handling, and compatibility are not controlled.
- **Keep only the existing generic JSON adapters:** their routes, authentication, search fields, and response models do not match either official provider.
- **OpenSubtitles.org XML-RPC:** it is the legacy service rather than the current OpenSubtitles.com REST contract.
- **Title-only subtitle search:** too weak for automatic activation and ignores stable IDs already available from Emby metadata.
- **Enable several providers immediately:** multiplies credentials, terms, fixtures, ranking, and outage behavior before one path is proven.

## Consequences for the map

- The disposable environment must include a local Dandanplay-relay fake, secure secret injection, and OpenSubtitles API fixtures; live credentials remain outside Git and issue comments.
- The proxy/security decision owns redirect/header containment and relay/provider routing details without reopening provider choice.
- The qualification-lanes decision can keep deterministic fixtures merge-blocking while making tiny live probes scheduled or release-only.
- `/to-spec` must produce separate provider-adapter tickets rather than extending the generic wire clients with provider conditionals.
- Later provider expansion should be a separate map or roadmap after operational evidence from this baseline exists.
