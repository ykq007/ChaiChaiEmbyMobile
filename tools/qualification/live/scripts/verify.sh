#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
set -a
. .secrets/runtime.env
set +a
mkdir -p evidence
chmod 700 evidence
docker compose --env-file .secrets/runtime.env ps --format json > .state/compose-ps.json
python3 - .state/compose-ps.json <<'PY'
import json,sys
raw=open(sys.argv[1]).read().strip()
if not raw:
    raise SystemExit('no compose services found')
try:
    value=json.loads(raw)
    rows=value if isinstance(value,list) else [value]
except json.JSONDecodeError:
    rows=[json.loads(line) for line in raw.splitlines() if line.strip()]
bad=[r for r in rows if str(r.get('State','')).lower()!='running']
print('services',len(rows),'running',len(rows)-len(bad))
if bad:
    print('not running',bad)
    raise SystemExit(1)
PY
for spec in '49 18096' '48 18097'; do
  set -- $spec
  label=$1
  port=$2
  curl -fsS "http://127.0.0.1:$port/emby/System/Info/Public" > "evidence/emby$label-public.json"
  token=$(cat ".state/emby$label.token")
  user=$(cat ".state/emby$label.user")
  i=0
  count=0
  while [ "$i" -lt 120 ]; do
    curl -fsS -H "X-Emby-Token: $token" "http://127.0.0.1:$port/emby/Users/$user/Items?Recursive=true&IncludeItemTypes=Movie&Fields=MediaSources,MediaStreams,Path" > "evidence/emby$label-items.json"
    count=$(python3 - "evidence/emby$label-items.json" <<'PY'
import json,sys
print(len(json.load(open(sys.argv[1])).get('Items',[])))
PY
)
    [ "$count" -ge 2 ] && break
    i=$((i+1))
    sleep 2
  done
  [ "$count" -ge 2 ] || { echo "emby$label scan found only $count movies" >&2; exit 1; }
  python3 - "evidence/emby$label-public.json" "evidence/emby$label-items.json" <<'PY'
import json,sys
public=json.load(open(sys.argv[1]))
items=json.load(open(sys.argv[2])).get('Items',[])
print(public['Version'], public['ServerName'], [(x.get('Name'),len(x.get('MediaSources',[]))) for x in items])
PY
done
curl -fsS http://127.0.0.1:18081/health > evidence/dandan-relay-health.json
curl -fsS -H 'Content-Type: application/json' -X POST http://127.0.0.1:18081/match --data '{"fileName":"synthetic.mkv"}' > evidence/dandan-match.json
curl -fsS http://127.0.0.1:18081/comments/700101 > evidence/dandan-comments.json
curl -fsS -H "Api-Key: $OPENSUBTITLES_API_KEY" -H 'User-Agent: ChaiQualification/1.0' 'http://127.0.0.1:18082/api/v1/subtitles?tmdb_id=990001&languages=en' > evidence/opensubtitles-search.json
curl -fsS -H "Api-Key: $OPENSUBTITLES_API_KEY" -H 'User-Agent: ChaiQualification/1.0' -H 'Content-Type: application/json' -X POST http://127.0.0.1:18082/api/v1/download --data '{"file_id":9001}' > evidence/opensubtitles-download.json
curl -fsS -x http://127.0.0.1:13128 http://origin:8080/health >/dev/null
curl -fsS --proxy-user "$HTTP_PROXY_USER:$HTTP_PROXY_PASSWORD" -x http://127.0.0.1:13129 http://origin:8080/health >/dev/null
curl -fsS --socks5-hostname 127.0.0.1:11080 http://origin:8080/health >/dev/null
curl -fsS --socks5-hostname 127.0.0.1:11081 --proxy-user "$SOCKS_PROXY_USER:$SOCKS_PROXY_PASSWORD" http://origin:8080/health >/dev/null
curl -fsS http://127.0.0.1:18083/captures > evidence/origin-captures.json
ROOT_CA=tls/data/caddy/pki/authorities/local/root.crt
[ -s "$ROOT_CA" ]
curl -fsS --cacert "$ROOT_CA" --resolve emby49.localhost:18443:127.0.0.1 https://emby49.localhost:18443/emby/System/Info/Public > evidence/emby49-tls.json
curl -fsS --cacert "$ROOT_CA" --resolve emby48.localhost:18444:127.0.0.1 https://emby48.localhost:18444/emby/System/Info/Public > evidence/emby48-tls.json
python3 - <<'PY'
import json,pathlib
root=pathlib.Path('evidence')
summary={}
for p in sorted(root.glob('*.json')):
    try:
        value=json.load(open(p))
    except Exception:
        value={'parse':'failed'}
    if 'items' in p.name:
        value={'movie_count':len(value.get('Items',[])),'movies':[x.get('Name') for x in value.get('Items',[])]}
    if 'captures' in p.name:
        value={'capture_count':len(value.get('captures',[])),'routes':[x.get('path') for x in value.get('captures',[])]}
    summary[p.name]=value
json.dump(summary,open(root/'summary.json','w'),indent=2)
PY
printf '%s\n' 'qualification verification passed'
