#!/bin/bash
set -euo pipefail
echo "winter-mobile count: $(grep -c winter-mobile /opt/3xui-happ-balancer/app.py || true)"
SUB=4u4wquv00cjcs3sm
python3 - <<'PY'
import base64, json, urllib.request

def check(ua: str):
    req = urllib.request.Request(
        'http://127.0.0.1:8099/auto/4u4wquv00cjcs3sm',
        headers={'User-Agent': ua},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = resp.read()
        headers = {k.lower(): v for k, v in resp.headers.items()}
        pr = headers.get('profile-routing')
        print(f'UA={ua}')
        print('  status', getattr(resp, 'status', 200))
        print('  body_len', len(body))
        print('  x-winter-routing', headers.get('x-winter-routing'))
        print('  x-winter-client', headers.get('x-winter-client'))
        print('  profile-routing', bool(pr))
        if pr:
            raw = pr[7:] if pr.startswith('base64:') else pr
            pad = '=' * (-len(raw) % 4)
            data = json.loads(base64.urlsafe_b64decode(raw + pad))
            print('  default', data.get('default', {}).get('Name'))
            print('  whitelist', data.get('whitelist', {}).get('Name'))
            print('  minPriority', data.get('whitelistMinPriority'))

check('Winter-Mobile/1.2.3')
print('---')
check('Winter/1.2.3')
PY
