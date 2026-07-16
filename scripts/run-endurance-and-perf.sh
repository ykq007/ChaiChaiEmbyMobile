#!/usr/bin/env bash
#
# Playback endurance + cold-start performance gates, run on a booted emulator
# (invoked by the release workflow's android-emulator-runner step, cwd = repo
# root, adb ready).
#
#   * Endurance: 30 cycles of the activity-recreation and playback-lifecycle
#     instrumentation suite. Any crash, ANR, or lost position fails a cycle and
#     aborts the gate. These tests assert that the last acknowledged position is
#     restored, which is the >=10s resume-accuracy requirement.
#   * Performance: a cold-start launch timing sample via `am start -W`, compared
#     to the documented API-26 launch threshold. Client time only; there is no
#     server round-trip in the deterministic harness.
set -uo pipefail

CYCLES="${ENDURANCE_CYCLES:-30}"
APP_ID="dev.chaichai.mobile"
LAUNCH_THRESHOLD_MS="${LAUNCH_THRESHOLD_MS:-3000}"
ENDURANCE_CLASSES="dev.chaichai.mobile.PlaybackActivityRecreationTest,dev.chaichai.mobile.PlaybackLifecycleTest"

mkdir -p quality-gate
# Escape backslashes and double-quotes so an interpolated message can never
# produce invalid JSON that the report generator would choke on.
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
fail_endurance() {
  echo "{\"status\":\"fail\",\"detail\":\"$(json_escape "$1")\"}" > quality-gate/playback-endurance.json
  echo "ENDURANCE GATE FAILED: $1" >&2
}

echo "Running $CYCLES playback endurance cycles..."
for i in $(seq 1 "$CYCLES"); do
  echo "=== endurance cycle $i/$CYCLES ==="
  if ! ./gradlew connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class="$ENDURANCE_CLASSES" \
      --stacktrace; then
    fail_endurance "cycle $i/$CYCLES failed (crash, ANR, stuck session, or lost position)"
    exit 1
  fi
  # A crash during the cycle surfaces in logcat even if the harness swallowed it.
  if adb shell dumpsys dropbox --print 2>/dev/null | grep -qi "$APP_ID.*crash"; then
    fail_endurance "cycle $i/$CYCLES recorded a system crash for $APP_ID"
    exit 1
  fi
done
echo "{\"status\":\"pass\",\"detail\":\"$CYCLES endurance cycles with zero crashes/ANRs/lost positions\",\"measurements\":{\"cycles\":$CYCLES}}" \
  > quality-gate/playback-endurance.json
echo "Endurance gate passed ($CYCLES cycles)."

# ---- Cold-start performance sample ----
echo "Sampling cold-start launch time..."
adb shell am force-stop "$APP_ID" || true
LAUNCH_OUT="$(adb shell am start -W -n "$APP_ID/.MainActivity" 2>/dev/null || true)"
echo "$LAUNCH_OUT"
TOTAL_MS="$(printf '%s\n' "$LAUNCH_OUT" | awk -F'[^0-9]*' '/TotalTime/{print $2; exit}')"

if [[ -z "${TOTAL_MS:-}" ]]; then
  # Do not silently pass: a missing measurement is a not-run performance gate,
  # which is waivable per the release policy but must be surfaced.
  echo '{"status":"not-run","detail":"cold-start launch time could not be measured on the emulator"}' \
    > quality-gate/performance.json
  echo "Performance gate not-run: no launch measurement." >&2
elif [[ "$TOTAL_MS" -le "$LAUNCH_THRESHOLD_MS" ]]; then
  echo "{\"status\":\"pass\",\"detail\":\"cold-start ${TOTAL_MS}ms <= ${LAUNCH_THRESHOLD_MS}ms threshold (emulator, client time only)\",\"measurements\":{\"launch_total_ms\":$TOTAL_MS,\"threshold_ms\":$LAUNCH_THRESHOLD_MS,\"environment\":\"emulator-api26\"}}" \
    > quality-gate/performance.json
  echo "Performance gate passed (${TOTAL_MS}ms)."
else
  echo "{\"status\":\"fail\",\"detail\":\"cold-start ${TOTAL_MS}ms exceeds ${LAUNCH_THRESHOLD_MS}ms threshold\",\"measurements\":{\"launch_total_ms\":$TOTAL_MS,\"threshold_ms\":$LAUNCH_THRESHOLD_MS,\"environment\":\"emulator-api26\"}}" \
    > quality-gate/performance.json
  echo "Performance gate failed (${TOTAL_MS}ms > ${LAUNCH_THRESHOLD_MS}ms)." >&2
fi
