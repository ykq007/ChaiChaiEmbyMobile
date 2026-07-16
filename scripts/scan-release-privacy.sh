#!/usr/bin/env bash
#
# Privacy Quality Gate: scan release artifacts (logs, crash reports, test
# reports, diagnostics, and release staging) for sensitive data that must never
# leave the device or CI runner: server tokens/secrets, full media/stream URLs,
# and subtitle cue contents. Personal library metadata is caught via an optional
# --denylist of known-sensitive terms (e.g. real library or item titles), since
# it has no universal signature; the source-side redaction unit tests cover the
# rest (see docs/release/quality-gates.md AC7 evidence).
#
# Scope is deliberately artifacts only — never source under src/ — because
# header *names* like "X-Emby-Token" legitimately appear in code. This gate is
# about leaked *values* in generated output.
#
# Usage:
#   scripts/scan-release-privacy.sh [--out result.json] [--denylist FILE] [PATH ...]
#
# With no PATH arguments it scans the conventional artifact locations that
# exist. Exits non-zero when any high-confidence match is found. Matched
# secrets are never echoed in the emitted result (only file:line) so the gate
# output can never re-leak them.
set -euo pipefail
# globstar lets the default artifact roots expand on CI (bash 4+); harmless when
# unavailable (macOS bash 3.2) because CI always passes explicit roots.
shopt -s globstar nullglob 2>/dev/null || true

out=""
denylist=""
roots=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    --out=*) out="${1#--out=}"; shift ;;
    --denylist) denylist="$2"; shift 2 ;;
    --denylist=*) denylist="${1#--denylist=}"; shift ;;
    *) roots+=("$1"); shift ;;
  esac
done

if [[ ${#roots[@]} -eq 0 ]]; then
  for candidate in \
    "**/build/reports" \
    "**/build/outputs/logs" \
    "**/build/outputs/androidTest-results" \
    "ci-diagnostics" \
    "release-staging"; do
    while IFS= read -r dir; do
      [[ -n "$dir" ]] && roots+=("$dir")
    done < <(compgen -G "$candidate" 2>/dev/null || true)
  done
fi

# A live scan directory always exists so grep has a target; empty is a pass.
if [[ ${#roots[@]} -eq 0 ]]; then
  roots+=(".")
fi

# High-confidence value-bearing patterns. Each requires an actual value after
# the key so that a bare header-name mention does not trip the gate.
patterns=(
  'X-Emby-Token["'"'"'=: ]+[A-Za-z0-9_.-]{8,}'
  'X-Emby-Authorization.*Token="?[A-Za-z0-9_.-]{8,}'
  '(api_key|apikey|access[_-]?token|AccessToken)["'"'"'=: ]+[A-Za-z0-9_.-]{8,}'
  'Authorization:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9_.-]{8,}'
  'https?://[^[:space:]"]+/(Videos|Items)/[^[:space:]"]+/(stream|Download|Subtitles)'
  'https?://[^[:space:]"]+[?&]api_key=[A-Za-z0-9_.-]{8,}'
  'WEBVTT'
  '[0-9]{2}:[0-9]{2}:[0-9]{2}[.,][0-9]{3}[[:space:]]+-->[[:space:]]+[0-9]{2}:[0-9]{2}:[0-9]{2}'
)

# Personal library metadata: fold each non-empty, non-comment denylist term in
# as its own literal (fixed-string) alternative.
deny_terms=()
if [[ -n "$denylist" && -f "$denylist" ]]; then
  while IFS= read -r term; do
    [[ -z "$term" || "$term" == \#* ]] && continue
    # Escape regex metacharacters so terms match literally.
    patterns+=("$(printf '%s' "$term" | sed 's/[][(){}.*+?^$|\\]/\\&/g')")
    deny_terms+=("$term")
  done < "$denylist"
fi

combined="$(IFS='|'; echo "${patterns[*]}")"

hits_file="$(mktemp)"
trap 'rm -f "$hits_file"' EXIT

# -I skips binaries (APKs, mapping.txt.gz, images); exclude the scanner, the
# gate catalog, the denylist, and any prior result file so the gate never
# scans itself.
deny_exclude=()
[[ -n "$denylist" ]] && deny_exclude+=(--exclude="$(basename "$denylist")")
grep -rInE "$combined" "${roots[@]}" \
  --exclude-dir='.git' \
  --exclude='scan-release-privacy.sh' \
  --exclude='quality-gates.json' \
  --exclude='*privacy*.json' \
  ${deny_exclude[@]+"${deny_exclude[@]}"} \
  > "$hits_file" 2>/dev/null || true

count="$(wc -l < "$hits_file" | tr -d ' ')"

emit_json() {
  local status="$1"
  {
    printf '{\n'
    printf '  "status": "%s",\n' "$status"
    printf '  "gate": "privacy",\n'
    printf '  "detail": "%s",\n' "$2"
    printf '  "scanned_paths": ['
    local first=1
    for r in "${roots[@]}"; do
      [[ $first -eq 0 ]] && printf ', '
      printf '"%s"' "$r"; first=0
    done
    printf '],\n'
    printf '  "hits": ['
    if [[ "$count" -gt 0 ]]; then
      local firsth=1
      while IFS= read -r line; do
        local file="${line%%:*}"
        local rest="${line#*:}"
        local lineno="${rest%%:*}"
        # Emit only the location — never the matched content — so this gate's
        # own result can never re-leak the secret it just found.
        [[ $firsth -eq 0 ]] && printf ', '
        printf '{"file": "%s", "line": %s}' "$file" "$lineno"
        firsth=0
      done < "$hits_file"
    fi
    printf ']\n}\n'
  }
}

if [[ "$count" -gt 0 ]]; then
  echo "PRIVACY GATE FAILED: $count sensitive match(es) in release artifacts." >&2
  # Print masked file:line locations only (never the full matched value).
  cut -d: -f1,2 "$hits_file" | sed 's/^/  leak at /' >&2
  json="$(emit_json fail "$count sensitive match(es) found in release artifacts")"
  [[ -n "$out" ]] && printf '%s' "$json" > "$out"
  printf '%s' "$json"
  exit 1
fi

echo "Privacy gate passed: no sensitive data found in ${#roots[@]} scanned path(s)."
json="$(emit_json pass "no sensitive data found in scanned artifacts")"
[[ -n "$out" ]] && printf '%s' "$json" > "$out"
printf '%s' "$json"
