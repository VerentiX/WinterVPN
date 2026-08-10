#!/bin/bash
python3 <<'PY'
import json, urllib.request, base64
sub='qk13p3uu5farhw5x'
req=urllib.request.Request('http://127.0.0.1:8099/auto/'+sub, headers={'User-Agent':'Winter-Mobile/1.2.3'})
with urllib.request.urlopen(req, timeout=30) as resp:
    hdr={k.lower():v for k,v in resp.headers.items()}
    body=json.loads(resp.read())
cfg=body[0] if isinstance(body, list) else body
rules=(cfg.get('routing') or {}).get('rules') or []
print('=== embedded rule order ===')
for i,r in enumerate(rules):
    dom=r.get('domain'); port=r.get('port'); out=r.get('outboundTag') or r.get('balancerTag')
    if not (dom or port or out):
        continue
    doms = dom[:6] if isinstance(dom, list) else dom
    print(i, 'out=', out, 'port=', port, 'dom=', doms)

# simulate Winter apply order problem
pr=json.loads(base64.b64decode(hdr['profile-routing'].split(':',1)[1]))
default=pr['default']
print('\nWinter default RouteOrder', default.get('RouteOrder'))
print('ProxySites', default.get('ProxySites'))
print('DirectSites head', default.get('DirectSites')[:4])

# check geosite category membership via xray if available
import subprocess, shutil, os
xray=shutil.which('xray') or '/usr/local/bin/xray'
for dat in ['/usr/local/share/xray/geosite.dat','/usr/share/xray/geosite.dat']:
    if os.path.exists(dat):
        print('geosite', dat, os.path.getsize(dat))
PY
