# First live Emby compatibility baseline

Research date: 2026-07-28  
Wayfinder ticket: [Choose the first Emby compatibility baseline](https://github.com/ykq007/ChaiChaiEmbyMobile/issues/43)

## Decision

The first production-valid live-integration specification will support **Emby Server 4.9.x and 4.8.x**, with release qualification pinned initially to the latest patches in those lines:

- **4.9.5.0** — the current stable server release when this decision was made ([official latest-versions post](https://emby.media/community/topic/21348-emby-latest-versions/), [official release tag](https://github.com/MediaBrowser/Emby.Releases/releases/tag/4.9.5.0)).
- **4.8.11.0** — the latest patch of the immediately previous minor line ([official release tag](https://github.com/MediaBrowser/Emby.Releases/releases/tag/4.8.11.0)).

The semantic promise is the two minor lines, while automated live qualification uses exact patch versions. A newly published patch replaces the pinned fixture only after the contract suite passes. A new stable minor line such as 4.10 does **not** enter the promise automatically; it requires a separate compatibility-expansion decision and qualification run. Beta server releases are outside this baseline.

This follows the repository's existing `Supported Server Line` definition in `CONTEXT.md` and is the smallest useful production promise: one current line plus one realistic upgrade-lag line.

## Required connection and identity contract

The specification must require all of the following on both supported lines:

1. Preserve the complete user-entered Server Address, including an arbitrary path prefix. Test at least a root deployment, an `/emby` prefix, and a nonstandard nested prefix. `ServerAddress` already preserves this shape and rejects web/dashboard URLs.
2. Probe unauthenticated server identity and version through `GET /System/Info/Public`, then authenticate with `POST /Users/AuthenticateByName` using the Emby client authorization header ([SystemService](https://dev.emby.media/reference/RestAPI/SystemService.html), [User Authentication](https://dev.emby.media/doc/restapi/User-Authentication.html), [AuthenticateByName reference](https://dev.emby.media/reference/RestAPI/UserService/postUsersAuthenticatebyname.html)).
3. After authentication, fetch authenticated `GET /System/Info` and use its `Id` as the canonical server identity. The authenticated identity must match the probed server before the token is persisted.
4. Scope every token to `(ServerId, UserId)` and never send it to another server authority. Any authenticated 401 or 403 expires the active session and returns the user to authentication. Explicit logout revokes the token; ordinary application exit may retain it, as documented by Emby.
5. Preserve unknown JSON fields and tolerate missing optional fields. Exact request/response shapes must be checked against each fixture server's built-in API Browser because Emby identifies the running server as the detailed version-specific reference ([REST API documentation](https://dev.emby.media/doc/restapi/index.html)).

The current probe already classifies 4.8 and 4.9 as supported, preserves prefixes, and applies authority-scoped TLS behavior. The implementation still needs an authenticated identity cross-check rather than relying only on the public probe's server id.

## Required playback contract

### Negotiation

Every playback attempt must use authenticated `POST /Items/{Id}/PlaybackInfo`, carrying user identity, start ticks, a truthful device profile, bitrate/channel ceilings, and selected media-source/audio/subtitle identities where applicable. The response's `MediaSources`, `PlaySessionId`, error code, media-source id, runtime, stream indexes, required headers, and server-provided stream URLs are authoritative ([PlaybackInfo reference](https://dev.emby.media/reference/RestAPI/MediaInfoService/postItemsByIdPlaybackinfo.html)).

The first guaranteed device profile is deliberately conservative:

- H.264 + AAC in MP4 as the acceptance-critical HTTP direct-stream case.
- H.264 + AAC HLS/MPEG-TS as the acceptance-critical transcode case.
- Two-channel audio and a 20 Mbps streaming ceiling, matching the current production capability provider.
- External SRT and WebVTT subtitle delivery.
- HEVC, VP9, AV1, Opus, additional channels, HDR, bitmap subtitles, and other container/codec combinations are advertised only when the Android runtime capability provider proves them. They are not release-critical in this baseline.

The device profile must remain generated from actual decoder support rather than copied from another client. Advertising unsupported formats can cause the server to select an unusable route.

### Playback method and source selection

For an ordinary sandboxed Android client, the first baseline supports:

1. **DirectStream** over an authenticated HTTP(S) URL supplied by the server.
2. **Transcode** over the server-supplied URL when direct streaming is unavailable.

Filesystem **DirectPlay** is deferred because Emby's definition requires the client to access the media path directly, bypassing the server ([Playback Guidelines](https://dev.emby.media/doc/restapi/Playback-Guidelines.html)). A `DirectStreamUrl` must never be reported to Emby as `DirectPlay` merely because `SupportsDirectPlay` is true. The current `EmbyPlaybackGateway.toCandidate` does this and must be corrected by a later implementation ticket.

Source selection must be deterministic:

- Reuse an explicitly selected media source during track renegotiation when it remains available.
- Otherwise prefer a valid same-authority DirectStream candidate, then a valid same-authority Transcode candidate.
- Reject cross-authority stream URLs, blank URLs, unsupported methods, and empty/error responses. Never synthesize a fallback stream URL.
- Carry the selected `MediaSourceId` and returned `PlaySessionId` through playback and reporting.

### Audio and subtitle changes

Audio and subtitle selections use the stream `Index`, never array position. A change may require a fresh PlaybackInfo request and replacement URL. The fixture suite must verify the current API Browser's request property for retaining the session: the current public reference exposes `CurrentPlaySessionId`, while the repository currently serializes `PlaySessionId`. The implementation must normalize this correctly for both supported lines instead of assuming the existing field name is accepted.

The guaranteed subtitle cases are subtitles off plus external SRT/WebVTT. Embedded or bitmap subtitles may be exposed only when the negotiated server response and runtime player capability make them valid; burn-in remains a transcode decision.

## Markers and MediaSegments

The public PlaybackInfo model documents marker-typed `Chapters` (`IntroStart`, `IntroEnd`, and `CreditsStart`), but the current public REST reference does not establish a dedicated MediaSegments endpoint as a cross-version contract. Therefore:

- Marker support is **optional and capability-gated** in this first baseline.
- Documented chapter markers may be consumed when returned and valid for the negotiated runtime.
- [Fetch real Emby intro/outro MediaSegments](https://github.com/ykq007/ChaiChaiEmbyMobile/issues/38) must verify any dedicated endpoint against the built-in API Browser and live fixtures for both 4.9.5.0 and 4.8.11.0.
- If the endpoint is absent, unauthorized, malformed, or unsupported on either line, playback continues and the skip control remains hidden. Marker retrieval may never block or fail negotiation.

This keeps the core viewing loop compatible while allowing the existing marker ticket to add a safely detected enhancement.

## Playback reporting contract

Both supported lines must accept the authenticated lifecycle:

- `POST /Sessions/Playing` after playback is committed.
- `POST /Sessions/Playing/Progress` every 10 seconds and on pause, unpause, seek, track change, playback-rate change, subtitle-offset change, and application backgrounding when relevant.
- Exactly one best-effort `POST /Sessions/Playing/Stopped` for completion, replacement, fatal failure, or teardown.

The reporting body must keep `ItemId`, `MediaSourceId`, `PlaySessionId`, `PositionTicks`, runtime, pause/mute/seek state, actual `PlayMethod`, current audio/subtitle indexes, actual playback rate, and applicable event name consistent with the negotiated plan. The official progress model exposes these fields and event types ([PlaystateService](https://dev.emby.media/reference/RestAPI/PlaystateService.html), [Playing Progress reference](https://dev.emby.media/reference/RestAPI/PlaystateService/postSessionsPlayingProgress.html)).

The current coordinator already emits a 10-second time update and tests exactly one terminal event. The gateway still hard-codes playback rate to 1.0 and omits current audio/subtitle indexes and subtitle offset; later implementation tickets must align the payload with real state.

Progress delivery may be retried durably, but retries must remain scoped to the original server, user, media source, and play session. A revoked token stops delivery and requires reauthentication rather than credential substitution.

## Qualification fixtures and evidence

The disposable environment ticket must provision one official Linux/Docker server for **4.9.5.0** and one for **4.8.11.0**, using the same synthetic library and user policy. The minimum fixture matrix is:

- Root, `/emby`, and nested path-prefix Server Addresses.
- Valid HTTPS and explicitly acknowledged HTTP; detailed transport policy remains owned by the proxy/security decision.
- Playback-enabled user, playback-disabled user, incorrect credentials, revoked token, and logout.
- H.264/AAC MP4 direct-stream media.
- A media shape that forces H.264/AAC HLS transcode.
- Multiple media sources/versions with stable ids.
- External SRT and WebVTT, subtitles off, and one unsupported subtitle shape.
- Audio/subtitle index changes during playback.
- No-compatible-stream and transcoding-not-allowed responses.
- Start, periodic progress, meaningful progress events, and exactly one stopped report, with server-side position verification.
- Optional intro/outro marker evidence when the server line exposes a supported route or documented chapter markers.

Deterministic MockWebServer fixtures should be generated from redacted captures of both server lines and run on every pull request. Live containers validate the real API Browser, authentication, playback selection, and reporting before release; routine unit tests must not depend on an external personal server.

## Diagnostic evidence

The adapter must make the following non-identifying evidence observable to qualification tests and opt-in diagnostics:

- detected server major/minor/patch and compatibility classification;
- selected playback method;
- whether a media source, direct-stream URL, transcode URL, and track indexes were present;
- device-profile counts/ceilings;
- marker capability present/absent;
- sanitized response status class or typed protocol failure;
- report kind and success/failure.

It must never expose tokens, credentials, full server or stream URLs, library titles, subtitle text, proxy credentials, or certificate-bypass state. User-visible observability and retention policy remain owned by the rollout/failure-isolation decision.

## Explicit deferrals

The first baseline does not promise:

- Emby Server beta releases, 4.7 or older, or automatic admission of 4.10+.
- Emby Connect, LAN discovery, administrator API-key login, Live TV, music playback, playlists, downloads, or server administration.
- Filesystem DirectPlay, `.strm`/remote-protocol playback, external-player handoff, or copied TV-client source-priority rules.
- Guaranteed dedicated MediaSegments support.
- Advanced codec/HDR/Dolby Vision matrices, lossless or multichannel passthrough, ASS/PGS/DVB subtitles, or every official server packaging platform.

These are later compatibility-expansion efforts, not hidden best-effort promises.

## Rejected alternatives

- **Latest stable only:** too fragile for real users who remain one minor line behind and contradicts the existing Supported Server Line definition.
- **Any 4.x server:** unverifiable and turns version tolerance into an unbounded promise.
- **Include 4.10 beta:** beta behavior is not a production baseline.
- **Treat HTTP streaming as DirectPlay:** conflicts with Emby's own definition and corrupts playback reporting.
- **Require MediaSegments across both lines:** the dedicated contract is not established by the public cross-version reference; making it mandatory would let an optional marker feature block core playback.
- **Use a personal server as the only integration fixture:** not reproducible, not resettable, and unsafe for credentials or media metadata.

## Consequences for the map

- The live-service environment can now pin exact server images and fixture cases.
- The qualification-lanes ticket can separate deterministic redacted captures from release-only live-server checks.
- The proxy/security ticket owns valid HTTPS, acknowledged cleartext, authority containment, and proxy routing details without reopening the supported server versions.
- Compatibility expansion after this map should be a separate decision effort triggered by a new stable minor line or a materially new protocol/provider surface.
