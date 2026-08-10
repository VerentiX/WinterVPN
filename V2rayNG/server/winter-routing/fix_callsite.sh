#!/bin/bash
set -euo pipefail
install -o root -g root -m 644 /tmp/app.live.py /opt/3xui-happ-balancer/app.py
python3 -m py_compile /opt/3xui-happ-balancer/app.py
grep -n 'response_headers = attach_winter_mobile_routing_headers' /opt/3xui-happ-balancer/app.py
systemctl restart 3xui-happ-balancer.service
sleep 1
systemctl is-active 3xui-happ-balancer.service
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
        print(f'UA={ua}')
        print('  profile-routing', bool(pr))
        print('  x-winter-routing', headers.get('x-winter-routing'))
        print('  x-winter-client', headers.get('x-winter-client'))
        if pr:
            raw = pr[7:] if pr.startswith('base64:') else pr
            pad = '=' * (-len(raw) % 4)
            data = json.loads(base64.urlsafe_b64decode(raw + pad))
            print('  default', data.get('default', {}).get('Name'))
            print('  whitelist', data.get('whitelist', {}).get('Name'))

check('Winter-Mobile/1.2.3')
print('---')
check('Winter/1.2.3')
PY
