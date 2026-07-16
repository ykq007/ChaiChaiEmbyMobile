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

grep -q 'KERNEL=="kvm"' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not enable KVM permissions"

grep -q 'uses: actions/upload-artifact@' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not retain failure diagnostics"

grep -q 'if: failure()' <<<"$ui_smoke_job" || \
  fail "UI diagnostics are not limited to failed jobs"

grep -q 'timeout.*adb logcat' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not collect bounded ADB diagnostics"

grep -q '.ci-diagnostics/\*\*' <<<"$ui_smoke_job" || \
  fail "ui-smoke does not upload collected host and emulator diagnostics"

echo "CI workflow checks passed."
