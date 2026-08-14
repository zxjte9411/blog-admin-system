#!/usr/bin/env bash
set -Eeuo pipefail

API_BASE=${API_BASE:-http://localhost:8080}
FRONTEND_BASE=${FRONTEND_BASE:-http://localhost:4200}
: "${APP_BOOTSTRAP_ADMIN_EMAIL:?set APP_BOOTSTRAP_ADMIN_EMAIL}"
: "${APP_BOOTSTRAP_ADMIN_PASSWORD:?set APP_BOOTSTRAP_ADMIN_PASSWORD}"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail() { printf 'SMOKE FAIL: %s\n' "$*" >&2; exit 1; }
call() {
  local name=$1 method=$2 url=$3 body=${4-} token=${5-} code
  local args=(-sS -o "$tmp/$name" -w '%{http_code}' -X "$method" "$url" -H 'Accept: application/json')
  [[ -n $body ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  code=$(curl "${args[@]}" || true)
  [[ $code =~ ^2[0-9][0-9]$ ]] || { printf 'SMOKE FAIL: %s HTTP %s\n' "$name" "$code" >&2; cat "$tmp/$name" >&2; exit 1; }
}
expect() {
  local name=$1 method=$2 url=$3 wanted=$4 token=${5-} code
  local args=(-sS -o "$tmp/$name" -w '%{http_code}' -X "$method" "$url")
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  code=$(curl "${args[@]}" || true); [[ $code == "$wanted" ]] || fail "$name HTTP $code (expected $wanted)"
}
wait_for_service() {
  local name=$1 url=$2 code attempt
  for ((attempt=1; attempt<=30; attempt++)); do
    code=$(curl -sS -o "$tmp/$name" -w '%{http_code}' "$url" 2>/dev/null || true)
    [[ $code != 000 ]] && {
      [[ $code =~ ^2[0-9][0-9]$ ]] || fail "$name HTTP $code"
      return
    }
    sleep 1
  done
  fail "$name did not become reachable after 30 seconds (last HTTP $code)"
}
value() { python3 - "$tmp/$1" "$2" <<'PY'
import json,sys
v=json.load(open(sys.argv[1]))
for key in sys.argv[2].split('.'): v=v[int(key)] if isinstance(v,list) else v[key]
print(v)
PY
}
payload() { python3 - "$@" <<'PY'
import json,sys
print(json.dumps(dict(zip(sys.argv[1::2],sys.argv[2::2]))))
PY
}

wait_for_service health "$API_BASE/actuator/health"
wait_for_service frontend "$FRONTEND_BASE/"
login=$(python3 - "$APP_BOOTSTRAP_ADMIN_EMAIL" "$APP_BOOTSTRAP_ADMIN_PASSWORD" <<'PY'
import json,sys
print(json.dumps({'email':sys.argv[1],'password':sys.argv[2]}))
PY
)
call login POST "$API_BASE/api/v1/auth/login" "$login"
token=$(value login accessToken)
smoke_tag="compose-smoke-$RANDOM"
article=$(python3 - "$smoke_tag" <<'PY'
import json,sys
print(json.dumps({'title':'Compose smoke article','content':'HTTP smoke','status':'DRAFT','tagNames':[sys.argv[1]]}))
PY
)
call create POST "$API_BASE/api/v1/articles" "$article" "$token"
id=$(value create id); tag=$(value create tagIds.0)
call admin-users GET "$API_BASE/api/v1/admin/users" '' "$token"
call created-admin GET "$API_BASE/api/v1/articles/$id" '' "$token"
version=$(value created-admin version)
expect draft GET "$API_BASE/api/v1/public/articles/$id" 404
publish=$(python3 - "$version" "$tag" <<'PY'
import json,sys
print(json.dumps({'title':'Compose smoke article','content':'HTTP smoke','status':'PUBLISHED','version':int(sys.argv[1]),'tagIds':[sys.argv[2]],'tagNames':[]}))
PY
)
call publish PUT "$API_BASE/api/v1/articles/$id" "$publish" "$token"
call tags GET "$API_BASE/api/v1/public/tags"
python3 - "$tmp/tags" "$smoke_tag" <<'PY'
import json,sys
assert any(x['name']==sys.argv[2] for x in json.load(open(sys.argv[1]))['content'])
PY
version=$(value publish version); call public GET "$API_BASE/api/v1/public/articles/$id"
python3 - "$tmp/public" "$tag" <<'PY'
import json,sys
a=json.load(open(sys.argv[1])); assert any(str(x['id'])==sys.argv[2] for x in a['tags'])
PY
expect delete DELETE "$API_BASE/api/v1/articles/$id" 204 "$token"
expect deleted GET "$API_BASE/api/v1/public/articles/$id" 404
call deleted-tags GET "$API_BASE/api/v1/public/tags"
python3 - "$tmp/deleted-tags" "$smoke_tag" <<'PY'
import json,sys
assert not any(x['name']==sys.argv[2] for x in json.load(open(sys.argv[1]))['content'])
PY
call restore POST "$API_BASE/api/v1/articles/$id/restore" '' "$token"
call restored-admin GET "$API_BASE/api/v1/articles/$id" '' "$token"
version=$(value restored-admin version)
publish=$(python3 - "$version" "$tag" <<'PY'
import json,sys
print(json.dumps({'title':'Compose smoke article','content':'HTTP smoke','status':'PUBLISHED','version':int(sys.argv[1]),'tagIds':[sys.argv[2]],'tagNames':[]}))
PY
)
call republish PUT "$API_BASE/api/v1/articles/$id" "$publish" "$token"
call restored GET "$API_BASE/api/v1/public/articles/$id"
printf 'SMOKE OK: health, frontend, Admin, tagged Article, public visibility, delete/restore\n'
