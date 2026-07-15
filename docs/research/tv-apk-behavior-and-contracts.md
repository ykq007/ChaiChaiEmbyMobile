# TV Client APK: recoverable behavior and contracts

Research date: 2026-07-15
Scope: latest stable TV Client release, behavior/contracts useful when planning the touch-first Mobile Client
Conclusion: the APK is a strong behavioral and wire-contract oracle, but not a safe source-code or asset donor.

## Evidence and method

The Mobile Client repository has no releases. Its [README](../../README.md) directs TV downloads to `dh374374/ChaiChaiEmbyTV`; that repository contains only its README and release assets. The latest non-prerelease is [TV Client v0.3.0](https://github.com/dh374374/ChaiChaiEmbyTV/releases/tag/v0.3.0), published 2026-07-13. GitHub lists the TV-preferred `armeabi-v7a` APK as 21,995,310 bytes with SHA-256 `265da13fa918069a948bc5b4443a7945197f7c11f739b76aa71901da6f1f67b5`; an independent `shasum -a 256` matched, and `unzip -t` reported no errors. The inspected file was `/tmp/chaichai-wayfinder/ChaiChaiEmbyTV-0.3.0-armeabi-v7a.apk`.

The APK was inspected statically with Androguard 4.1.4 (manifest/resource decoding and DAD decompilation), macOS `strings`, `unzip`, and `shasum`. The trace paths below are relative to the generated `/tmp/chaichai-wayfinder/decompiled/` tree; they identify reproducible evidence, not files intended for committing. R8 has obfuscated much of the app and DAD reported control-flow reconstruction errors, so named data classes/interfaces and literal resources are high-confidence evidence; reconstructed control flow is not.

## Recoverable product shape

The release is unambiguously a TV application: `AndroidManifest.xml` declares both launcher and Leanback launcher categories, a landscape `MainActivity`, no required touchscreen, package `com.dh.myembyapp`, version 0.3.0, minimum SDK 23, and target SDK 36. It also declares a foreground media-playback DLNA service, camera support for QR configuration, internet/Wi-Fi access, cleartext traffic, and queries for HTTP(S) external video handlers (decoded manifest).

The stable top-level behavior recoverable from the TV Client is:

- Multiple Emby servers with alias, endpoint components, credentials/token, persistent device ID, verification/login recency, server ordering, custom icon, backup routes and last-playback recency. A server can be direct-only, permit direct playback of `.strm`, use an `/emby` prefix, or trust all certificates (`com/dh/myembyapp/data/model/ServerConfig.java`; `data/preferences/ServerPreferences.java`).
- Library/home browsing contracts for views, latest, resume, recently played, favorites, item/person detail, seasons, episodes, similar titles and search. The named service surface is in `data/api/EmbyApiService.java`; its presence is stronger evidence than precise TV screen composition, whose Compose functions are largely obfuscated.
- Playback that negotiates a media source and device profile, selects audio/subtitle streams, supports direct stream and transcoding, reports start/progress/stop, and can hand HTTP(S) video to external players. Playback-related manifest queries plus `PlaybackInfoRequest.java`, `PlaybackInfoResponse.java`, `MediaSource.java`, and `PlaybackProgressInfo.java` support these claims.
- Favorite, played/unplayed, hide-from-resume, user-configuration and per-item user-data mutations (`data/api/EmbyApiService.java`).
- DLNA receiving/control through a foreground service (`dlna/DlnaService.java`, `DlnaHttpServer.java`, and manifest service declaration).
- QR/web-assisted configuration is not only marketing copy: the manifest includes ZXing's capture activity, and local HTTP handlers exist for server config, proxy config, danmaku config/search, online-subtitle config/search, WebDAV sync, server-icon selection and subtitle-font upload (`server/*.java`).

What cannot be recovered reliably is a canonical mobile navigation hierarchy. Obfuscated Compose call sites reveal TV focus handling and many dialogs, but not a trustworthy screen graph or touch/adaptive-layout design. Reuse the capability inventory, not the TV information architecture.

## Emby wire contracts worth re-specifying

`data/api/EmbyApiService.java` exposes the durable operation set: authenticate by name; fetch the current user, views, counts, items/latest/resume/favorites/recent/search, details, seasons/episodes, similar items, chapters and person details; fetch `Items/{itemId}/PlaybackInfo`; mutate favorites/played/resume/user data; and post `Sessions/Playing`, `Sessions/Playing/Progress`, and `Sessions/Playing/Stopped`. DEX literals retain those exact route fragments. Cross-server matching also queries provider IDs and collections.

The request/response shapes are highly reusable as specifications:

- `PlaybackInfoRequest`: device profile, bitrate and dimension ceilings, start ticks, selected audio/subtitle index, media-source ID, max audio channels, and direct-play/direct-stream/transcode flags (`data/model/PlaybackInfoRequest.java`).
- `DeviceProfile`: default 40,000,000-bit/s streaming/static/music ceilings plus direct-play, transcode, codec, subtitle, response and container profiles (`data/model/DeviceProfile.java`). Treat the default as observed behavior, not a recommended Mobile Client constant.
- `MediaSource`: ID/name/path/container/size/runtime/bitrate/video type, stream list, direct-stream/transcode support and URLs (`data/model/MediaSource.java`).
- Playback reporting includes item/source/session IDs, position/runtime ticks, paused/muted/seekable state, play method, playback rate, repeat mode and queue position (`data/api/PlaybackProgressInfo.java`).

These should be rewritten against current Emby server behavior and tests. Retrofit annotations and Kotlin/Gson models recovered from a minimized APK are evidence of v0.3.0's wire assumptions, not an upstream compatibility guarantee.

## Playback rules and settings semantics

The APK distinguishes direct playback/streaming/transcoding and exposes source/version priorities rather than selecting the first source. Named models cover video resolution/quality, HDR/video format, value sort direction, audio codec/language, and subtitle language/format priorities (`data/model/VideoVersionPrioritySettings.java`, `AudioVersionPrioritySettings.java`, `SubtitleVersionPrioritySettings.java`, and `MediaSourcePriorityKt.java`). The exact comparator control flow is partly decompiler-damaged; preserve the dimensions and re-derive/test ordering.

Observed player semantics include:

- Video and audio decoder modes default to `auto`; independent toggles exist for prioritizing audio passthrough and Dolby Vision 7 compatibility (`data/DecoderSettings$DecoderConfig.java`).
- Resize mode, default view mode and frame-rate matching are persisted enums (`data/model/PlayerResizeMode.java`, `PlayerDefaultViewMode.java`, `PlayerFrameRateMatchingMode.java`).
- Subtitle display state contains vertical offset, scale, time offset in milliseconds and color; selection/display preferences are remembered separately for movies and series (`data/SubtitlePreferences*.java`).
- Intro/outro settings default to automatic-marker priority enabled, but skip-intro and skip-outro disabled; season-level memory and manual marker models also exist (`data/IntroOutroSettings$Settings.java`, `IntroOutroMemory.java`).
- Server-level `directOnly` and `enableStrmDirectPlay` can constrain negotiation (`data/model/ServerConfig.java`).

The release notes say v0.3.0 fixes dropped video frames, which makes low-level renderer/decoder logic particularly unsafe to transplant: the APK tells us which knobs exist, not which implementation is correct on phones and tablets.

## Danmaku behavior and contract

Danmaku is optional, disabled by default, and auto-match defaults on. Up to five API endpoints can be named independently and routed through the proxy independently (`data/model/DanmakuConfig.java`, constant `MAX_API_COUNT = 5`). DEX literals show the default ecosystem endpoint `api.dandanplay.net` and compatible routes `api/v2/search/episodes`, `api/v2/bangumi/{animeId}`, and `api/v2/comment/{episodeId}` (`data/api/DanmakuApiService.java` and DEX string table).

The recoverable workflow is: attempt title/episode matching, permit manual search/selection, fetch comments, retain match/memory at movie/season/episode granularity, and overlay timed comments during playback (`data/model/DanmakuMatch*.java`, `DanmakuSearchResponse.java`, `DanmakuResponse.java`, and `data/DanmakuMemory.java`). Rendering settings are speed 1.0, size 1.0, opacity 100, screen fraction 1.0 and zero-second offset by default; top comments are shown and bottom comments hidden (`data/DanmakuSettings.java`). The APK contains AkDanmaku's `DanmakuPlayer` symbols, consistent with the upstream README's declared engine.

For the Mobile Client, retain the endpoint abstraction, match states, per-title memory, timing offset and display controls. Do not copy signing/authentication, match heuristics or renderer internals without independent tests and API-owner documentation; decompilation cannot establish their correctness, service terms or forward compatibility.

## Configuration semantics

Proxy configuration includes enabled state, HTTP/SOCKS5 protocol, host/port, optional credentials and LAN bypass (`data/ProxyConfig.java`, `ProxyProtocol.java`). A separate per-danmaku-endpoint proxy map exists. The APK also contains `trustAllCerts` paths and a default proxy host `192.168.5.235:7890`; both are implementation artifacts and must not become Mobile Client defaults. Certificate bypass is a deliberate security downgrade and cleartext traffic is globally permitted by the TV manifest.

Server endpoint semantics are protocol + host + port + optional path, with optional `/emby` prefix and backup routes (`ServerConfig.java`, `NormalizedServerEndpoint.java`). Configuration persists secrets (password/access token and proxy password) in preference-oriented models. Static analysis does not demonstrate encryption at rest; the Mobile Client must design secure credential storage rather than reproduce this persistence format.

Other visible configuration domains include decoder/audio passthrough, subtitle font/display/priority, intro/outro behavior, online-subtitle services, player resize/view/frame-rate behavior, theme/card density, time/network-speed overlays, server icons, WebDAV sync, Trakt integration and DLNA (`data/`, `data/model/`, `server/`, and `dlna/` named classes). They form a useful backlog taxonomy, not an implied requirement that every TV feature belongs in the first Mobile Client scope.

## Resources and reuse boundary

The APK contains launcher/banner/logo resources, Compose/AndroidX resources, native FFmpeg/codec libraries, AkDanmaku code, ZXing, Media3, Retrofit/OkHttp and generated HTML for QR-assisted configuration. Resource filenames are R8/resource-shrinker names, and extraction on a default case-insensitive macOS filesystem produced name collisions. Native libraries in the inspected artifact are `armeabi-v7a` only.

Safe to reuse as planning inputs:

- Capability names, user-visible setting semantics, state-machine concepts, endpoint/path names, JSON field names, persisted-domain dimensions and default values that are explicitly cited above.
- The release APK as a regression oracle in an isolated device/emulator, subject to authorization and network safety.

Unsafe or incomplete to reuse directly:

- Decompiled Java/Kotlin: identifiers and control flow are obfuscated, DAD reported reconstruction failures, nullability/generics/annotations can be lost, and output does not compile as source.
- UI/navigation code: TV focus behavior is not touch behavior, and the Compose graph is among the least reliably reconstructed parts.
- Images, fonts, HTML, icons and native `.so` files: provenance and redistribution rights are not established. The upstream repository declares no license in GitHub metadata and its README says only that the project is for learning/exchange.
- Bundled third-party code or protocol/auth logic: version/license/security context is incomplete; reacquire dependencies from their publishers and implement against first-party APIs.
- TLS bypass, cleartext policy, embedded defaults, secrets/storage formats and proxy routing: these require a fresh threat model.
- Exact playback comparator/decoder behavior: it is device-specific, partly obfuscated and implicated by the current release's dropped-frame fix.

## Planning decision

Treat v0.3.0 as a behavioral compatibility reference. Re-specify the named Emby and danmaku contracts, validate them with fixture/server integration tests, and design Mobile Client navigation and adaptive layout independently. No binary, source, native library or visual asset should cross into the Mobile Client until its provenance, license, security posture and behavior have been independently established.
