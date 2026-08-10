#!/bin/bash
set -euo pipefail
TS=$(date +%Y%m%d%H%M%S)
cp -a /opt/3xui-happ-balancer/app.py "/opt/3xui-happ-balancer/app.py.bak-mtalk-order-${TS}"
install -o root -g root -m 644 /tmp/app.live.py /opt/3xui-happ-balancer/app.py
install -o root -g root -m 644 /tmp/winter-default.json /etc/winter-routing/default.json
install -o root -g root -m 644 /tmp/winter-whitelist.json /etc/winter-routing/whitelist.json
python3 -m py_compile /opt/3xui-happ-balancer/app.py
systemctl restart 3xui-happ-balancer.service
sleep 2
systemctl is-active 3xui-happ-balancer.service
python3 <<'PY'
import json, urllib.request, base64
req=urllib.request.Request('http://127.0.0.1:8099/auto/qk13p3uu5farhw5x', headers={'User-Agent':'Winter-Mobile/1.2.3'})
with urllib.request.urlopen(req, timeout=30) as resp:
    hdr={k.lower():v for k,v in resp.headers.items()}
    body=json.loads(resp.read())
cfg=body[0] if isinstance(body,list) else body
rules=(cfg.get('routing') or {}).get('rules') or []
print('first 12 rules:')
for i,r in enumerate(rules[:12]):
    print(i, 'in=', r.get('inboundTag'), 'bal=', r.get('balancerTag'), 'out=', r.get('outboundTag'), 'port=', r.get('port'), 'dom=', (r.get('domain') or [])[:3] if isinstance(r.get('domain'), list) else r.get('domain'))
pr=json.loads(base64.b64decode(hdr['profile-routing'].split(':',1)[1]))
print('ProxySites', pr['default'].get('ProxySites'))
print('DirectSites head', pr['default'].get('DirectSites')[:4])
assert rules[0].get('outboundTag')=='direct'
assert '5228' in str(rules[0].get('port') or '') or 'mtalk' in str(rules[0].get('domain'))
assert 'google-play' not in (pr['default'].get('ProxySites') or [])
print('OK')
PY
