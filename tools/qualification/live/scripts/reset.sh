#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
docker compose --env-file .secrets/runtime.env down -v --remove-orphans || true
rm -rf config/emby49 config/emby48 tls/data .state evidence
mkdir -p config/emby49 config/emby48 tls/data .state evidence
chmod 700 .state evidence
printf '%s\n' 'reset complete; test-only secrets and synthetic media retained'
