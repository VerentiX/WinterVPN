#!/bin/bash
set -euo pipefail
echo '=== ENV ==='
grep -E '^(UPSTREAM_|HAPP_|PRIORITY_|INCLUDE_|PROFILE_)' /etc/3xui-happ-balancer.env || true
echo
echo '=== PRIORITY CONFIG ==='
sed -n '1,160p' /etc/3xui-happ-priorities.json 2>/dev/null || true
echo
echo '=== NGINX AUTO ==='
grep -RInE 'auto|8099|happ-balancer|cloudflare|worker|sctl|jsonic|/json/' /etc/nginx 2>/dev/null | head -60 || true
echo
echo '=== HEALTH ==='
curl -sS http://127.0.0.1:8099/health; echo
echo
echo '=== INSPECT SUB ==='
SUB=$(grep -oE '/auto/[A-Za-z0-9_-]{8,}' /var/log/nginx/access.log 2>/dev/null | tail -1 | sed 's#.*/##') || true
echo "sub=$SUB"
if [[ -n "${SUB:-}" ]]; then
  curl -sS "http://127.0.0.1:8099/inspect/${SUB}" | python3 - <<'PY'
import json,sys
data=json.load(sys.stdin)
# print compact summary of keys and routes/priorities if present
if isinstance(data, dict):
    print('keys', sorted(data.keys())[:40])
    for k in ('manifest','routes','priorities','balancer','upstream','source','profile','candidates'):
        if k in data:
            v=data[k]
            print(k, type(v).__name__, (len(v) if hasattr(v,'__len__') and not isinstance(v,str) else ''))
            if isinstance(v, list):
                for item in v[:20]:
                    if isinstance(item, dict):
                        print(' ', {kk:item.get(kk) for kk in ('remark','priority','tag','protocol','failoverOrder') if kk in item or True})
                    else:
                        print(' ', item)
            elif isinstance(v, dict):
                print(' ', list(v.keys())[:20])
    # try common shapes
    manifest = data.get('manifest') or data.get('candidates') or data.get('routes')
    if isinstance(manifest, list):
        print('--- ordered manifest ---')
        for item in manifest:
            if isinstance(item, dict):
                print(f"P{item.get('priority')} {item.get('remark')} tag={item.get('tag')} proto={item.get('protocol')}")
else:
    print(type(data), str(data)[:200])
PY
fi
echo
echo '=== UPSTREAM HEADERS TEST ==='
UP=$(grep -E '^UPSTREAM_JSON_BASE=' /etc/3xui-happ-balancer.env | cut -d= -f2-)
echo "UPSTREAM_JSON_BASE=$UP"
if [[ -n "${SUB:-}" && -n "$UP" ]]; then
  curl -sSI -A '3x-ui-happ-sticky-failover/3.4' "${UP}${SUB}" | tr -d '\r' | head -30 || true
  echo '--- body sample remarks ---'
  curl -sS -A '3x-ui-happ-sticky-failover/3.4' "${UP}${SUB}" | python3 - <<'PY'
import json,sys
try:
    data=json.load(sys.stdin)
except Exception as e:
    print('parse fail', e)
    sys.exit(0)
items=data if isinstance(data,list) else [data]
print('count', len(items))
for item in items[:30]:
    if not isinstance(item, dict):
        continue
    remark=item.get('remarks') or item.get('ps') or ''
    # try outbound tags / vnext
    print('-', remark)
PY
fi
