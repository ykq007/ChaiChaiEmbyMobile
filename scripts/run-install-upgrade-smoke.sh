#!/usr/bin/env bash
#
# Install / upgrade smoke, run on a booted emulator (invoked by the release
# workflow, cwd = repo root, adb ready, signed APK in release-staging/).
#
# Proves the signed release artifact installs cleanly, upgrades in place over a
# prior install of itself (`adb install -r`, exercising the upgrade path and
# signature continuity), and launches to a stable foreground activity without
# crashing. Evidence is written to quality-gate/install-upgrade.json, which the
# report catalog reads as the critical "install-upgrade" gate.
set -uo pipefail

APP_ID="dev.chaichai.mobile"
APK="$(find release-staging -name '*.apk' | head -n1)"

mkdir -p quality-gate
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
fail() {
  echo "{\"status\":\"fail\",\"detail\":\"$(json_escape "$1")\"}" > quality-gate/install-upgrade.json
  echo "INSTALL/UPGRADE SMOKE FAILED: $1" >&2
  exit 1
}

[[ -n "$APK" ]] || fail "no release APK found in release-staging/"
echo "Smoke-testing $APK"

adb uninstall "$APP_ID" >/dev/null 2>&1 || true

echo "--- fresh install ---"
adb install "$APK" || fail "fresh install failed"

echo "--- in-place upgrade ---"
adb install -r "$APK" || fail "in-place upgrade (install -r) failed"

echo "--- launch ---"
adb shell am force-stop "$APP_ID" || true
adb logcat -c || true
adb shell am start -W -n "$APP_ID/.MainActivity" || fail "launch failed"

# Give the process a moment, then confirm it is alive and did not crash.
adb shell sleep 3
if ! adb shell pidof "$APP_ID" >/dev/null 2>&1; then
  fail "app process is not running after launch (crash on start)"
fi
if adb logcat -d 2>/dev/null | grep -qE "FATAL EXCEPTION|ANR in $APP_ID"; then
  fail "fatal exception or ANR observed during launch"
fi

echo '{"status":"pass","detail":"fresh install, in-place upgrade, and launch succeeded without crash/ANR"}' \
  > quality-gate/install-upgrade.json
echo "Install/upgrade smoke passed."
