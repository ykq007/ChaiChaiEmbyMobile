#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SERVER=${1:?usage: init-emby.sh 49|48}
case "$SERVER" in
  49) PORT=18096; PASS_FILE="$ROOT/.secrets/emby49_password"; LABEL=emby49 ;;
  48) PORT=18097; PASS_FILE="$ROOT/.secrets/emby48_password"; LABEL=emby48 ;;
  *) echo "unknown server $SERVER" >&2; exit 2 ;;
esac
BASE="http://127.0.0.1:$PORT/emby"
USER=qualifier
PASS=$(cat "$PASS_FILE")
AUTH='Emby Client="ChaiQualification", Device="AgentDock", DeviceId="qualification-'"$LABEL"'", Version="1.0"'
wait_for() {
  i=0
  while [ "$i" -lt 180 ]; do
    code=$(curl -sS -o /tmp/chai-public.json -w '%{http_code}' "$BASE/System/Info/Public" || true)
    [ "$code" = 200 ] && return 0
    i=$((i+1)); sleep 2
  done
  echo "$LABEL did not become ready" >&2; return 1
}
authenticate() {
  curl -sS -o "$ROOT/.state/$LABEL-auth.json" -w '%{http_code}' \
    -H 'Content-Type: application/json' -H "X-Emby-Authorization: $AUTH" \
    -X POST "$BASE/Users/AuthenticateByName" \
    --data "{\"Username\":\"$USER\",\"Pw\":\"$PASS\"}"
}
wait_for
code=$(authenticate || true)
if [ "$code" != 200 ]; then
  curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/Startup/Configuration" --data '{"UICulture":"en-us"}' >/dev/null
  curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/Startup/User" --data "{\"Name\":\"$USER\",\"Password\":\"$PASS\"}" >/dev/null
  curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/Startup/RemoteAccess" --data '{"EnableAutomaticPortMapping":false}' >/dev/null
  curl -fsS -H 'Content-Type: application/json' -X POST "$BASE/Startup/Complete" --data '{}' >/dev/null
  code=$(authenticate)
fi
[ "$code" = 200 ] || { echo "$LABEL authentication failed: $code" >&2; exit 1; }
python3 - "$ROOT/.state/$LABEL-auth.json" "$ROOT/.state/$LABEL.token" "$ROOT/.state/$LABEL.user" <<'PY'
import json,sys,os
p=json.load(open(sys.argv[1]))
open(sys.argv[2],'w').write(p['AccessToken'])
open(sys.argv[3],'w').write(p['User']['Id'])
os.chmod(sys.argv[2],0o600); os.chmod(sys.argv[3],0o600)
PY
TOKEN=$(cat "$ROOT/.state/$LABEL.token")
USER_ID=$(cat "$ROOT/.state/$LABEL.user")
if ! curl -fsS -H "X-Emby-Token: $TOKEN" "$BASE/Library/VirtualFolders" | grep -q 'Qualification Movies'; then
  curl -fsS -H 'Content-Type: application/json' -H "X-Emby-Token: $TOKEN" \
    -X POST "$BASE/Library/VirtualFolders" \
    --data '{"Name":"Qualification Movies","CollectionType":"movies","RefreshLibrary":true,"Paths":["/media/movies"],"LibraryOptions":{"EnableRealtimeMonitor":false,"EnableMarkerDetection":true,"EnableMarkerDetectionDuringLibraryScan":true,"PreferredMetadataLanguage":"en","MetadataCountryCode":"US","MetadataSavers":["Nfo"],"DisabledSubtitleFetchers":[],"SubtitleFetcherOrder":[]}}' >/dev/null
fi
curl -fsS -H "X-Emby-Token: $TOKEN" -X POST "$BASE/Library/Refresh" >/dev/null
USERS="$ROOT/.state/$LABEL-users.json"
curl -fsS -H "X-Emby-Token: $TOKEN" "$BASE/Users" > "$USERS"
BLOCKED_ID=$(python3 - "$USERS" <<'PY'
import json,sys
for u in json.load(open(sys.argv[1])):
    if u.get('Name')=='blocked':
        print(u['Id']); break
PY
)
if [ -z "$BLOCKED_ID" ]; then
  curl -fsS -H 'Content-Type: application/json' -H "X-Emby-Token: $TOKEN" \
    -X POST "$BASE/Users/New" --data '{"Name":"blocked"}' > "$ROOT/.state/$LABEL-blocked.json"
  BLOCKED_ID=$(python3 - "$ROOT/.state/$LABEL-blocked.json" <<'PY'
import json,sys; print(json.load(open(sys.argv[1]))['Id'])
PY
)
fi
curl -fsS -H "X-Emby-Token: $TOKEN" "$BASE/Users/$BLOCKED_ID" > "$ROOT/.state/$LABEL-blocked-user.json"
python3 - "$ROOT/.state/$LABEL-blocked-user.json" "$ROOT/.state/$LABEL-blocked-policy.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]))['Policy']
for k in ('EnableMediaPlayback','EnableAudioPlaybackTranscoding','EnableVideoPlaybackTranscoding','EnablePlaybackRemuxing'):
    p[k]=False
json.dump(p,open(sys.argv[2],'w'))
PY
curl -fsS -H 'Content-Type: application/json' -H "X-Emby-Token: $TOKEN" \
  -X POST "$BASE/Users/$BLOCKED_ID/Policy" --data-binary "@$ROOT/.state/$LABEL-blocked-policy.json" >/dev/null
printf '%s initialized: user=%s blocked=%s\n' "$LABEL" "$USER_ID" "$BLOCKED_ID"
