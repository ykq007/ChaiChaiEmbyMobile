#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
./scripts/verify.sh
printf '%s\n' '--- service state ---'
docker compose --env-file .secrets/runtime.env ps
printf '%s\n' '--- sanitized evidence ---'
cat evidence/summary.json
