#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="$repo_root/.github/workflows/ci.yml"

fail() {
  echo "CI workflow check failed: $*" >&2
  exit 1
}

while IFS= read -r action; do
  action_path="${action%@*}"
  repository="$(cut -d/ -f1-2 <<<"$action_path")"
  ref="${action##*@}"

  [[ "$repository" == ./* || "$repository" == docker://* ]] && continue

  if ! git ls-remote \
    "https://github.com/${repository}.git" \
    "refs/tags/$ref" "refs/heads/$ref" | grep -q .; then
    fail "action $action does not resolve to an upstream tag or branch"
  fi
done < <(sed -nE 's/^[[:space:]]*-[[:space:]]*uses:[[:space:]]*([^[:space:]#]+).*/\1/p' "$workflow")

ui_smoke_job="$(awk '
  /^  ui-smoke:/ { in_ui_smoke = 1 }
  in_ui_smoke && /^  [[:alnum:]_-]+:/ && !/^  ui-smoke:/ { exit }
  in_ui_smoke { print }
' "$workflow")"

security_job="$(awk '
  /^  security:/ { in_security = 1 }
  in_security && /^  [[:alnum:]_-]+:/ && !/^  security:/ { exit }
  in_security { print }
' "$workflow")"

grep -q 'KERNEL=="kvm"' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not enable KVM permissions"

grep -q 'uses: actions/upload-artifact@' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not retain failure diagnostics"

grep -q 'if: failure()' <<<"$ui_smoke_job" || \
  fail "UI diagnostics are not limited to failed jobs"

grep -q 'timeout.*adb logcat' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not collect bounded ADB diagnostics"

grep -q '"\$ANDROID_SDK_ROOT/emulator/emulator" -accel-check' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not invoke emulator diagnostics through the SDK path"

grep -q 'ci-diagnostics/\*\*' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not upload collected host and emulator diagnostics"

grep -q 'notAnnotation=dev.chaichai.mobile.RequiresLargeTestWindow' <<<"$ui_smoke_job" || \
  fail "API 26 does not exclude tests that require a large instrumentation root"

grep -q 'profile: pixel_c' <<<"$ui_smoke_job" || \
  fail "large-window tests do not run on a sufficiently large emulator profile"

grep -B2 -A2 "api-level: '36'" <<<"$ui_smoke_job" | grep -q 'target: google_apis' || \
  fail "large-window tests do not use the newest published standard Google APIs image"

if grep -q "api-level: '37.0'" <<<"$ui_smoke_job"; then
  fail "ui-smoke requests an unpublished standard API 37 system image"
fi

if grep -q 'target: google_apis_ps16k' <<<"$ui_smoke_job"; then
  fail "ui-smoke still uses the experimental 16 KB system image that cannot boot"
fi

grep -A2 'name: Scan repository history for secrets' <<<"$security_job" | grep -q 'if: always()' || \
  fail "secret scanning does not run after a failed vulnerability scan"

echo "CI workflow checks passed."
