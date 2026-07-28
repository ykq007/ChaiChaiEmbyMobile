#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
[ -f .secrets/runtime.env ] || ./scripts/bootstrap-secrets.sh
docker rm -f chai-probe-emby49 >/dev/null 2>&1 || true
./scripts/generate-media.sh
docker compose --env-file .secrets/runtime.env up -d --build
./scripts/init-emby.sh 49
./scripts/init-emby.sh 48
./scripts/verify.sh
