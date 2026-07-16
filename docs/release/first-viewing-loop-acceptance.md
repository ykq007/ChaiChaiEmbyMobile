# First Viewing Loop — Acceptance Runbook

Companion to [`quality-gates.md`](quality-gates.md). This runbook records the
procedures and manual sign-offs behind the gates that cannot be fully automated
in CI. Attach a completed copy (or a link to the run) to each release candidate.

- **Candidate tag:** `v_____`
- **Commit:** `_____`
- **Release captain:** `@_____`
- **Date:** `____-__-__`

## Automated coverage (recorded by CI)

The `release.yml` run on the candidate tag produces `quality-gate-report.md`.
Paste its verdict here and confirm it is `PASS` with no critical gate waived:

> Verdict: `____`  ·  blocking: `____`  ·  waived: `____`

## Adaptive Layout window matrix

The compact (API 26) and expanded (API 36) lanes run automatically. Confirm the
journey state (current destination, selected item, playback position) survives
each transition below. Foldable postures are verified manually on a foldable or
the Android foldable emulator until a hinge-configurable instrumentation lane
exists.

| Configuration | Transition exercised | Journey state preserved | Notes |
| --- | --- | --- | --- |
| Compact portrait ↔ landscape | rotation | ☐ | |
| Medium (split-screen) | enter/exit split | ☐ | |
| Expanded tablet portrait ↔ landscape | rotation | ☐ | |
| Foldable folded → unfolded | unfold | ☐ | |
| Foldable hinge-occluded | tabletop / book posture | ☐ | content clear of hinge |

## TalkBack manual passes

Run with **TalkBack on** at the **largest standard font and display scaling**.
Automated `AccessibilityChecks` cover contrast, target size, and labelling; these
passes cover the lived screen-reader journey.

| Device class | Full loop reachable by TalkBack | Focus order sensible | Non-color meaning present | Reduced motion honored |
| --- | --- | --- | --- | --- |
| Phone (compact) | ☐ | ☐ | ☐ | ☐ |
| Tablet (expanded) | ☐ | ☐ | ☐ | ☐ |

Confirm every interactive target is ≥ 48dp with ≥ 8dp separation and that no
status (e.g. selected track, playback state) is signalled by color alone.

## Playback endurance

`release.yml#endurance` runs **30 cycles** of the activity-recreation and
playback-lifecycle suite on API 26 and samples cold-start time. Confirm:

- ☐ 30/30 cycles green — zero crashes, ANRs, stuck sessions, or lost positions.
- ☐ Resume lands within **10 s** of the last server-acknowledged position
  (asserted by the lifecycle/recreation tests).

## Performance thresholds (API 26 representative hardware)

The cold-start sample is a coarse emulator proxy. For the release candidate,
measure on representative API 26 hardware and record client time separately from
server time. A miss here is a **waivable** performance gate.

| Metric | Threshold | Measured | Pass |
| --- | --- | --- | --- |
| Cold start to interactive | ≤ 3000 ms | | ☐ |
| Input feedback latency | ≤ 100 ms | | ☐ |
| Cached navigation | ≤ 500 ms | | ☐ |
| Frame deadline (jank) | ≤ 1% dropped | | ☐ |
| Memory growth over loop | no unbounded growth | | ☐ |
| Artwork / playback start | ≤ 2000 ms (client) | | ☐ |

## Supported Server Line contract matrix

Contract suites run in `ci.yml#build-and-test`. Confirm they pass against both
Supported Server Lines (latest stable and previous minor):

| Concern | Latest stable | Previous minor |
| --- | --- | --- |
| Transport & Server Address prefix | ☐ | ☐ |
| Authentication | ☐ | ☐ |
| Browsing (Home / libraries) | ☐ | ☐ |
| Playback start | ☐ | ☐ |
| Track selection | ☐ | ☐ |
| Progress report & resume | ☐ | ☐ |
| Version tolerance (unknown fields) | ☐ | ☐ |

## Release integrity checklist (AC8)

- ☐ Signed release APK produced from a clean tagged run.
- ☐ `SHA256SUMS.txt` checksums attached.
- ☐ Build provenance attestation created.
- ☐ `sbom.cyclonedx.json` SBOM attached.
- ☐ `dependency-license-report.md` attached.
- ☐ Install + upgrade smoke green.
- ☐ Trivy (HIGH/CRITICAL) and gitleaks scans clean (`supply-chain` gate).
- ☐ `docs/release/privacy-denylist.txt` populated with this run's library/item names before the privacy scan.
- ☐ `quality-gate-report.json` attached; no critical failure waived.

## Sign-off

Release captain: `@_____`   ·   Verdict: `SHIP / HOLD`   ·   Date: `____-__-__`
