#!/bin/bash
set -euo pipefail

install -o root -g root -m 644 /tmp/app.live.py /opt/3xui-happ-balancer/app.py
python3 -m py_compile /opt/3xui-happ-balancer/app.py
systemctl restart 3xui-happ-balancer.service

# Raise nginx upstream header buffers for /auto and /clash.
python3 - <<'PY'
from pathlib import Path
path = Path('/etc/nginx/sites-enabled/xhttp-origin.conf')
text = path.read_text(encoding='utf-8')
snippet = '''
        # Winter-Mobile Profile-Routing can exceed the default 4k/8k header buffer.
        proxy_buffer_size 32k;
        proxy_buffers 8 32k;
        proxy_busy_buffers_size 64k;
'''
changed = False
for loc in ('location ^~ /auto/ {', 'location ^~ /clash/ {'):
    if loc not in text:
        continue
    start = text.index(loc)
    # insert after proxy_pass line inside this location if not already present nearby
    window = text[start:start+700]
    if 'proxy_buffer_size 32k' in window:
        continue
    marker = 'proxy_pass http://127.0.0.1:8099;'
    idx = text.index(marker, start)
    idx_end = idx + len(marker)
    text = text[:idx_end] + snippet + text[idx_end:]
    changed = True
if changed:
    path.write_text(text, encoding='utf-8')
    print('nginx buffers updated')
else:
    print('nginx buffers already present or locations missing')
PY

nginx -t
systemctl reload nginx
sleep 1

SUB=qk13p3uu5farhw5x
echo '=== Winter-Mobile via nginx ==='
curl -sk -D - -o /tmp/wm.json -A 'Winter-Mobile/1.2.3' --resolve gw.zizmos.ru:443:127.0.0.1 "https://gw.zizmos.ru/auto/${SUB}" | tr -d '\r' | grep -iE 'HTTP/|X-Generated|X-Balancer|X-Winter|Profile-Routing|Routing:|content-type'
python3 - <<'PY'
import json
from pathlib import Path
raw=json.loads(Path('/tmp/wm.json').read_text())
cfg=raw[0] if isinstance(raw,list) else raw
tags=[o.get('tag') for o in cfg.get('outbounds') or [] if str(o.get('tag','')).startswith('route-p')]
print('routes', tags)
print('has p0', any(str(t).startswith('route-p0000') for t in tags))
PY
