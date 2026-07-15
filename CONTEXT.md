# ChaiChai Emby Client

This context describes the user-facing media client shared across Android device types.

## Language

**TV Client**:
The existing Android TV application designed around remote-control focus navigation.
_Avoid_: Desktop version, legacy app

**Mobile Client**:
The touch-first Android application for both phones and tablets.
_Avoid_: Phone app, phone-only version

**Adaptive Layout**:
A Mobile Client layout that deliberately adapts its navigation, information density, and controls to the available window size across phones, tablets, split-screen windows, and foldables. It preserves the user's journey through rotation, resize, and fold/unfold transitions, uses window characteristics rather than device-name detection, and keeps essential content clear of folds, hinges, cutouts, and system insets. A posture-specific tabletop arrangement is not required for the first milestone.
_Avoid_: Tablet mode, stretched phone layout, device-specific layout

**First Viewing Loop**:
The first-milestone journey in which a user adds one Emby server, signs in, finds an on-demand movie or episode through Home, library browsing, or search, opens its details, starts playback, selects audio or subtitles, exits, and later resumes from the server-synced position, including after restarting the Mobile Client. The same journey must work through an Adaptive Layout on phones, tablets, split-screen windows, and foldables. Live TV, music playback, playlists, administration, and advanced playback controls are not acceptance-critical parts of this loop.
_Avoid_: MVP, basic playback

**Quality Gate**:
A measurable condition a Mobile Client milestone must satisfy before its GitHub Release. Critical accessibility, security, privacy, data-integrity, crash-free playback, and First Viewing Loop failures always block release. A lower-severity performance or device-specific failure may ship only under a time-limited waiver documented in the release notes with an owner and target milestone.
_Avoid_: Quality target, best-effort check

**Server Address**:
The user-provided HTTP or HTTPS base URL of an Emby server. A path prefix is part of the address and must be preserved when constructing every API, image, and playback URL; for example, `https://emby.example/xyz` identifies an Emby deployment below `/xyz`. Safe normalization may standardize details such as a trailing slash, but must not discard or reinterpret the path prefix.
_Avoid_: Server host, server origin

**Supported Server Line**:
An Emby Server release line the Mobile Client explicitly verifies and promises compatibility with. Initially this includes the latest stable release and the immediately previous minor release across official editions.
_Avoid_: Any Emby version, best-effort compatibility

**Certificate Bypass**:
An explicit, disabled-by-default exception that allows one confirmed Server Address authority to use an HTTPS certificate Android does not trust. It never transfers to redirected authorities or affects other servers, providers, proxies, or endpoints.
_Avoid_: Trust all certificates, global insecure mode

**Aggregated Search**:
Search across every configured server, combining accessible movies, series, seasons, and episodes while retaining each result's server identity. With one configured server it behaves as server-wide search; true cross-server aggregation becomes acceptance-critical with the later Multiple Servers capability. A failure from one server does not suppress results from reachable servers.
_Avoid_: Global search, universal search

**Playback Personalization**:
The post-loop capability for adjusting playback speed and subtitle timing without interrupting playback. Speed is a user preference, while subtitle delay is scoped to the affected media rather than applied globally.
_Avoid_: Advanced playback settings

**Danmaku**:
Optional time-synchronized comments rendered over video from one or more compatible endpoints, with automatic matching and manual correction. Danmaku remains independent of video playback, so endpoint or rendering failure never interrupts the media.
_Avoid_: Bullet comments, scrolling comments

**Multiple Servers**:
The capability to configure, distinguish, and switch among more than one Emby server while keeping credentials, state, failures, and media identity server-scoped. It activates cross-server behavior in Aggregated Search.
_Avoid_: Multi-account mode, server profiles

**Proxy Routing**:
Routing selected Emby-server or danmaku-endpoint traffic through a configured HTTP or SOCKS5 proxy, with optional credentials and LAN bypass. It does not imply certificate-verification bypass.
_Avoid_: Proxy mode, network relay

**Subtitle Expansion**:
The post-loop capability for finding, selecting, and presenting subtitles from configured online providers, including accessible appearance controls. It excludes font-file upload and cross-device settings synchronization.
_Avoid_: Online subtitles

**Playback Polish**:
Optional playback refinements that build on a complete viewing loop, including trustworthy intro/outro skipping, aspect and resize controls, and opt-in diagnostics. Unsupported refinements disappear without weakening core playback.
_Avoid_: Player extras, miscellaneous playback controls
