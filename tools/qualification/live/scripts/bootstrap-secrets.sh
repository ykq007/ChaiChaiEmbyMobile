#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SECRETS="$ROOT/.secrets"
if [ -e "$SECRETS/runtime.env" ] && [ "${1:-}" != "--force" ]; then
  printf '%s\n' 'secrets already exist; use --force to rotate local fixture credentials'
  exit 0
fi
mkdir -p "$SECRETS"
chmod 700 "$SECRETS"
python3 - "$SECRETS" <<'PY'
from pathlib import Path
import secrets,sys
root=Path(sys.argv[1])
values={
    'HTTP_PROXY_USER':'chai_http',
    'HTTP_PROXY_PASSWORD':secrets.token_urlsafe(20),
    'SOCKS_PROXY_USER':'chai_socks',
    'SOCKS_PROXY_PASSWORD':secrets.token_urlsafe(20),
    'DANDAN_APP_ID':'chai-local-fixture',
    'DANDAN_APP_SECRET':secrets.token_urlsafe(24),
    'OPENSUBTITLES_API_KEY':secrets.token_urlsafe(24),
}
lines=['COMPOSE_PROJECT_NAME=chaichai-qualification']+[f'{k}={v}' for k,v in values.items()]
(root/'runtime.env').write_text('\n'.join(lines)+'\n')
(root/'dandan_app_secret').write_text(values['DANDAN_APP_SECRET']+'\n')
(root/'opensubtitles_api_key').write_text(values['OPENSUBTITLES_API_KEY']+'\n')
(root/'emby49_password').write_text(secrets.token_urlsafe(24)+'\n')
(root/'emby48_password').write_text(secrets.token_urlsafe(24)+'\n')
for p in root.iterdir(): p.chmod(0o600)
PY
set -a
. "$SECRETS/runtime.env"
set +a
HASH=$(openssl passwd -apr1 "$HTTP_PROXY_PASSWORD")
printf '%s:%s\n' "$HTTP_PROXY_USER" "$HASH" > "$SECRETS/squid.htpasswd"
chmod 644 "$SECRETS/squid.htpasswd"
printf '%s\n' 'local fixture credentials created under .secrets/'
