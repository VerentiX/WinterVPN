#!/bin/bash
set +e
echo "=== ERROR/WARN ==="
grep -E '\[Error\]|\[Warning\]' /var/log/x-ui/error.log | tail -100
echo "=== TG ==="
grep -E 'telegram|cdn-telegram|91\.108\.|149\.154\.|telesco\.pe' /var/log/x-ui/error.log | tail -100
echo "=== FAIL ==="
grep -E 'failed to (dial|open)|i/o timeout|broken pipe|reset by peer' /var/log/x-ui/error.log | tail -60
echo "=== CFG 8030 ==="
python3 <<'PY'
import json
from pathlib import Path
p=Path('/usr/local/x-ui/bin/config.json')
d=json.loads(p.read_text())
for ib in d.get('inbounds',[]):
    if ib.get('port')==8030:
        xs=ib.get('streamSettings',{}) or {}
        xh=xs.get('xhttpSettings',{}) or {}
        print('tag', ib.get('tag'))
        print('network', xs.get('network'), 'security', xs.get('security'))
        print('path', xh.get('path'), 'mode', xh.get('mode'), 'method', xh.get('uplinkHTTPMethod'))
        print('scMaxBufferedPosts', xh.get('scMaxBufferedPosts'), 'scStreamUpServerSecs', xh.get('scStreamUpServerSecs'))
        print('externalProxy', json.dumps(xs.get('externalProxy'), ensure_ascii=False)[:800])
        so=xs.get('sockopt') or {}
        print('sockopt', {k: so.get(k) for k in ['tcpUserTimeout','tcpKeepAliveIdle','tcpKeepAliveInterval','tcpMaxSeg','tcpcongestion','tcpFastOpen']})
print('domainStrategy', d.get('routing',{}).get('domainStrategy'))
print('outbounds', [o.get('tag') for o in d.get('outbounds',[])])
for r in d.get('routing',{}).get('rules',[]):
    s=json.dumps(r)
    if any(x in s.lower() for x in ['telegram','geoip:telegram','geosite:telegram','cdn-telegram','telesco','91.108','149.154']):
        print('RULE', s[:600])
PY
echo "=== NGINX vkcdn/api-tw ==="
grep -R -n -E 'vkcdn|api-tw|8030' /etc/nginx 2>/dev/null | head -60
echo "=== NGINX ERR last ==="
tail -n 50 /var/log/nginx/error.log
echo "=== recent TG opens live sample ==="
timeout 3 tail -n 0 -F /var/log/x-ui/error.log 2>/dev/null | head -5 || true
