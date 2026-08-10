#!/bin/bash
set -euo pipefail
SUB=qk13p3uu5farhw5x
echo '=== local balancer ==='
curl -sS -D - -o /tmp/local-auto.json -A 'Winter-Mobile/1.2.3' "http://127.0.0.1:8099/auto/${SUB}" | tr -d '\r' | grep -iE 'HTTP/|X-Generated|X-Auto|X-Winter|X-Balancer|Content-Type|Server|cf-|report-to'
python3 - <<'PY'
import json
from pathlib import Path
raw=json.loads(Path('/tmp/local-auto.json').read_text())
cfg=raw[0] if isinstance(raw,list) else raw
print('local outbounds', len(cfg.get('outbounds') or []))
print('local remarks/profile title?', cfg.get('remarks'))
for o in cfg.get('outbounds') or []:
    tag=str(o.get('tag') or '')
    if tag.startswith('route-p'):
        stream=o.get('streamSettings') or {}
        sni=((stream.get('realitySettings') or stream.get('tlsSettings') or {}).get('serverName'))
        print(' ', tag, o.get('protocol'), stream.get('network'), stream.get('security'), sni)
PY

echo
echo '=== via nginx on 139 host header gw ==='
curl -sk -D - -o /tmp/nginx-auto.json -A 'Winter-Mobile/1.2.3' --resolve gw.zizmos.ru:443:127.0.0.1 "https://gw.zizmos.ru/auto/${SUB}" | tr -d '\r' | grep -iE 'HTTP/|X-Generated|X-Auto|X-Winter|X-Balancer|Content-Type|Server|cf-|report-to|age|cache'

echo
echo '=== public DNS ==='
getent ahosts gw.zizmos.ru | head -20 || true
dig +short gw.zizmos.ru A || true
dig +short gw.zizmos.ru AAAA || true

echo
echo '=== nginx auto location ==='
sed -n '1,120p' /etc/nginx/sites-enabled/xhttp-origin.conf

echo
echo '=== compare fingerprints local vs nginx ==='
python3 - <<'PY'
import hashlib, json
from pathlib import Path
def fp(path):
    raw=Path(path).read_bytes()
    data=json.loads(raw)
    cfg=data[0] if isinstance(data,list) else data
    tags=[o.get('tag') for o in cfg.get('outbounds') or [] if str(o.get('tag','')).startswith('route-p')]
    return hashlib.sha256(raw).hexdigest()[:16], tags
for p in ('/tmp/local-auto.json','/tmp/nginx-auto.json'):
    try:
        h,tags=fp(p)
        print(p, h, 'tags', len(tags), tags[:8])
    except Exception as e:
        print(p, 'fail', e)
PY
