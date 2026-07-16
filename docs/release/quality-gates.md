# First Viewing Loop — Quality Gates

This document is the human-readable companion to
[`quality-gates.json`](quality-gates.json), the machine-readable source of truth
that CI consumes. It qualifies issue
[#24 — Qualify and release the First Viewing Loop](https://github.com/ykq007/ChaiChaiEmbyMobile/issues/24).

A **Quality Gate** is a measurable condition a milestone must satisfy before its
GitHub Release (see `CONTEXT.md`). Critical accessibility, security, privacy,
data-integrity, crash-free-playback, and First Viewing Loop failures **always
block release and are never waived**. Only a lower-severity performance or
device-specific failure may ship under a time-limited waiver documented in the
release notes with an owner and target milestone.

## How qualification runs

The [`release.yml`](../../.github/workflows/release.yml) workflow runs on a `v*`
tag (or `workflow_dispatch` for a dry run). It produces one
`quality-gate/<key>.json` result per gate, then:

1. `scripts/coalesce_lane_gates.py` folds the two required window lanes
   (compact API 26, expanded API 36) into the instrumentation-backed gates,
   fail-closed.
2. `scripts/scan-release-privacy.sh` scans every collected artifact for leaked
   secrets, tokens, full media URLs, and subtitle contents.
3. `scripts/quality_gate_report.py` applies the waiver policy and emits
   `quality-gate-report.json` + `quality-gate-report.md`. Its **exit code is the
   release decision**: a blocked report fails the run and no Release is
   published.

The report generator enforces the non-waivable rule structurally — a waiver
listed against a critical gate is reported as an *invalid waiver* and the gate
stays blocking. Waivers live in [`waivers.json`](waivers.json).

Run it locally against a results directory:

```bash
python3 scripts/quality_gate_report.py \
  --results quality-gate --waivers docs/release/waivers.json \
  --out quality-gate-report.json --summary quality-gate-report.md
python3 scripts/quality_gate_report.py --self-test   # unit checks, no network
```

## Gate catalog

| Gate | Severity | Waivable | Proves |
| --- | --- | --- | --- |
| First Viewing Loop acceptance suite | critical | no | AC1 |
| Adaptive Layout journey-state preservation | critical | no | AC2 |
| Accessibility conformance (WCAG AA) | critical | no | AC3 |
| Playback endurance & progress integrity | critical | no | AC4 |
| API 26 performance thresholds | performance | **yes** | AC5 |
| Supported Server Line contract compatibility | critical | no | AC6 |
| Privacy — artifacts exclude sensitive data | critical | no | AC7 |
| Install & upgrade smoke | critical | no | AC8 |
| Dependency & secret scans (supply chain) | critical | no | AC8 |
| Release integrity & provenance | critical | no | AC8 |

## Acceptance-criteria traceability

Each acceptance criterion on #24 maps to a gate and to the evidence that proves
it. Automated evidence runs in CI; manual evidence is recorded in the
[acceptance runbook](first-viewing-loop-acceptance.md).

| AC | Requirement (abridged) | Gate(s) | Evidence |
| --- | --- | --- | --- |
| AC1 | App-level acceptance suite over the required window matrix | `first-viewing-loop` | `DeterministicHarnessTest`, `ServerSetupFlowTest`, `SpotlightHomeTest`, `MovieLibraryTest`, `SeriesLibraryTest`, `AggregatedSearchTest`, `PlaybackFlowTest`, `PlaybackTrackBindingTest`, `ProgressRecoveryUiTest`; `ci.yml#ui-smoke` + `release.yml#instrumentation` |
| AC2 | Compact/medium/expanded/foldable preserve journey state through live transitions | `adaptive-layout` | `AdaptiveNavigationPolicyTest`, `DeterministicHarnessTest` (separating hinge), `TopLevelNavigationTest`, large-window lane; runbook window matrix |
| AC3 | Automated + manual TalkBack accessibility, 48dp/8dp targets, non-color meaning, reduced motion | `accessibility` | `PlaybackFlowTest` (`AccessibilityChecks` + `ComposeAccessibilityValidator`), `SpotlightHomeTest`; runbook TalkBack passes |
| AC4 | 30-cycle endurance, zero crashes/ANRs/lost positions, resume within 10s | `playback-endurance` | `PlaybackLifecycleTest`, `PlaybackActivityRecreationTest`, `ProgressSyncManagerTest`; `release.yml#endurance` (30×) |
| AC5 | API 26 launch/feedback/frame/memory/artwork thresholds, server time separate | `performance` | `release.yml#endurance` cold-start sample; runbook thresholds |
| AC6 | Contract suites for latest + previous Supported Server Lines incl. version tolerance | `compatibility` | `EmbyProbeTest`, `AuthenticatedEmbyGatewayTest`, `EmbyPlaybackGatewayTest`, `EmbyProgressRemoteTest`, `ServerAddressTest`; `ci.yml#build-and-test` |
| AC7 | Artifacts exclude secrets, tokens, full media URLs, subtitle contents, library metadata | `privacy` | `scripts/scan-release-privacy.sh` (patterns + `--denylist` for library metadata), `ServerSetupCoordinatorTest`, `EmbyAuthenticator` redaction |
| AC8 | Tagged clean CI → signed APK, checksums, provenance, SBOM, dependency/license report, install/upgrade smoke, dependency/secret scans, machine-readable gate report; critical failures not waived | `install-upgrade`, `supply-chain`, `release-integrity` | `release.yml` (`package`, `supply-chain`, `install-upgrade-smoke`, `qualify-and-publish`); `scripts/quality_gate_report.py` |

## Waiver policy

Only gates with severity `performance` or `device-specific` may be waived. A
waiver in `waivers.json` must carry `reason`, `owner`, `target_milestone`, and a
future `expires` date. Expired, incomplete, or critical-gate waivers are
rejected and keep the gate blocking. Active waivers are surfaced in the
generated report and copied into the GitHub Release notes.
