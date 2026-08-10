#!/bin/bash
set -euo pipefail
SUB=${1:-qk13p3uu5farhw5x}
UP=$(grep -E '^UPSTREAM_JSON_BASE=' /etc/3xui-happ-balancer.env | cut -d= -f2-)
echo "UP=$UP"
echo "SUB=$SUB"
echo
echo '=== DNS sctl / gw ==='
getent hosts sctl.zizmos.ru gw.zizmos.ru || true
dig +short sctl.zizmos.ru A 2>/dev/null || true
dig +short gw.zizmos.ru A 2>/dev/null || true
echo
echo '=== curl upstream resolve ==='
curl -sS -o /tmp/up.json -D /tmp/up.hdr -A '3x-ui-happ-sticky-failover/3.4' --connect-timeout 10 "${UP}${SUB}" || echo "upstream curl failed: $?"
echo '--- headers ---'
tr -d '\r' </tmp/up.hdr | head -40
echo '--- cf/worker hints ---'
tr -d '\r' </tmp/up.hdr | grep -iE 'cf-|cloudflare|worker|server|via|x-powered|x-served|location' || true
echo
python3 - <<'PY'
import json
from pathlib import Path
raw=Path('/tmp/up.json').read_text(encoding='utf-8', errors='replace')
print('body_len', len(raw))
print('body_head', raw[:120].replace('\n',' '))
try:
    data=json.loads(raw)
except Exception as e:
    print('json fail', e)
    raise SystemExit
items=data if isinstance(data, list) else [data]
print('profiles', len(items))
for item in items:
    if not isinstance(item, dict):
        continue
    remark=str(item.get('remarks') or '')
    # address guess
    addr=''
    try:
        outs=item.get('outbounds') or []
        for o in outs:
            if not isinstance(o, dict):
                continue
            proto=o.get('protocol')
            settings=o.get('settings') or {}
            if proto in ('vless','vmess','trojan') and settings.get('vnext'):
                addr=settings['vnext'][0].get('address','')
                break
            if proto=='shadowsocks' and settings.get('servers'):
                addr=settings['servers'][0].get('address','')
                break
            if proto in ('hysteria','hysteria2') and (o.get('streamSettings') or {}).get('address'):
                addr=(o.get('streamSettings') or {}).get('address')
                break
    except Exception:
        pass
    print(f'- {remark} | addr={addr}')
PY

echo
echo '=== inspect endpoint ==='
curl -sS -o /tmp/insp.json -w 'http=%{http_code}\n' "http://127.0.0.1:8099/inspect/${SUB}" || true
python3 - <<'PY'
import json
from pathlib import Path
p=Path('/tmp/insp.json')
if not p.exists() or p.stat().st_size==0:
    print('empty inspect')
    raise SystemExit
raw=p.read_text(encoding='utf-8', errors='replace')
print('inspect_head', raw[:200].replace('\n',' '))
try:
    data=json.loads(raw)
except Exception as e:
    print('inspect json fail', e)
    raise SystemExit
print('keys', sorted(data.keys()))
for key in ('manifest','candidates','tiers','active','routingProfile','upstreamRouting','balancerMode'):
    if key in data:
        print(key, data[key] if not isinstance(data[key], (list,dict)) else type(data[key]).__name__)
manifest=data.get('manifest') or []
if isinstance(manifest, list):
    print('--- manifest ---')
    for item in manifest:
        if isinstance(item, dict):
            print(f"P{item.get('priority')} order={item.get('failoverOrder')} {item.get('remark')} tag={item.get('tag')} proto={item.get('protocol')}")
# also print generated route tags if present in merged config
merged=data.get('config') or data.get('merged') or data.get('result')
if isinstance(merged, dict):
    outs=merged.get('outbounds') or []
    print('outbound tags sample:')
    for o in outs[:40]:
        if isinstance(o, dict) and str(o.get('tag','')).startswith('route-p'):
            print(' ', o.get('tag'))
PY

echo
echo '=== auto winter-mobile route tags ==='
curl -sS -A 'Winter-Mobile/1.2.3' "http://127.0.0.1:8099/auto/${SUB}" -o /tmp/auto.json
python3 - <<'PY'
import json
from pathlib import Path
data=json.loads(Path('/tmp/auto.json').read_text())
cfg=data[0] if isinstance(data, list) else data
outs=cfg.get('outbounds') or []
print('outbounds', len(outs))
for o in outs:
    tag=str((o or {}).get('tag') or '')
    if tag.startswith('route-p') or 'selectel' in tag.lower() or 'обход' in tag.lower():
        # try address
        addr=''
        settings=(o or {}).get('settings') or {}
        if settings.get('vnext'):
            addr=settings['vnext'][0].get('address')
        print(f'{tag} addr={addr} proto={o.get("protocol")}')
# balancers
for b in (cfg.get('routing') or {}).get('balancers') or []:
    print('balancer', b.get('tag'), 'selector', b.get('selector'), 'fallback', b.get('fallbackTag'))
PY
