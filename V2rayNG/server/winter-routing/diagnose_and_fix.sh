#!/bin/bash
set -euo pipefail
echo "=== sizes ==="
wc -c /opt/3xui-happ-balancer/app.py /opt/3xui-happ-balancer/app.py.new-winter /tmp/app.live.py 2>/dev/null || true
echo "=== md5 ==="
md5sum /opt/3xui-happ-balancer/app.py /opt/3xui-happ-balancer/app.py.new-winter /tmp/app.live.py 2>/dev/null || true
echo "=== matches in live app.py ==="
grep -n 'attach_winter\|WINTER_ROUTING\|Profile-Routing\|auto_format_is_winter' /opt/3xui-happ-balancer/app.py | head -40 || true
echo "=== winter-routing files ==="
ls -la /etc/winter-routing/
python3 - <<'PY'
import json
from pathlib import Path
for name in ('default.json', 'whitelist.json'):
    p = Path('/etc/winter-routing') / name
    data = json.loads(p.read_text(encoding='utf-8'))
    print(name, '->', data.get('Name'), 'keys', sorted(data.keys())[:8])
PY

# Force reinstall from /tmp/app.live.py if present
if [[ -f /tmp/app.live.py ]]; then
  echo "=== reinstall from /tmp/app.live.py ==="
  TS=$(date +%Y%m%d%H%M%S)
  cp -a /opt/3xui-happ-balancer/app.py "/opt/3xui-happ-balancer/app.py.bak-force-${TS}"
  install -o root -g root -m 644 /tmp/app.live.py /opt/3xui-happ-balancer/app.py
  install -o root -g root -m 644 /tmp/app.live.py /opt/3xui-happ-balancer/app.py.new-winter
  python3 -m py_compile /opt/3xui-happ-balancer/app.py
  grep -c auto_format_is_winter_mobile /opt/3xui-happ-balancer/app.py
  systemctl restart 3xui-happ-balancer.service
  sleep 1
  systemctl is-active 3xui-happ-balancer.service
fi

python3 - <<'PY'
import base64, json, urllib.request

def check(ua: str):
    req = urllib.request.Request(
        'http://127.0.0.1:8099/auto/4u4wquv00cjcs3sm',
        headers={'User-Agent': ua},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        headers = {k.lower(): v for k, v in resp.headers.items()}
        pr = headers.get('profile-routing')
        print(f'UA={ua} profile-routing={bool(pr)} x-winter={headers.get("x-winter-routing")} client={headers.get("x-winter-client")}')
        if pr:
            raw = pr[7:] if pr.startswith('base64:') else pr
            pad = '=' * (-len(raw) % 4)
            data = json.loads(base64.urlsafe_b64decode(raw + pad))
            print(' ', data.get('default', {}).get('Name'), '/', data.get('whitelist', {}).get('Name'))

check('Winter-Mobile/1.2.3')
check('Winter/1.2.3')
PY
