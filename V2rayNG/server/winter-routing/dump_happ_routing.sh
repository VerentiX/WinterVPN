#!/bin/bash
set -euo pipefail
python3 - <<'PY'
import sqlite3, base64, re, json
con=sqlite3.connect('file:/etc/x-ui/x-ui.db?mode=ro', uri=True)
v=con.execute("select value from settings where key='subRoutingRules'").fetchone()
print('subRoutingRules present', bool(v and v[0]))
raw=v[0] if v else ''
print('prefix', raw[:80])
m=re.search(r'happ://routing/onadd/([A-Za-z0-9_=-]+)', raw)
if m:
    b=m.group(1)
    pad='='*((4-len(b)%4)%4)
    data=base64.urlsafe_b64decode(b+pad)
    j=json.loads(data)
    print(json.dumps(j, ensure_ascii=False, indent=2)[:4000])
print('--- env happ ---')
import pathlib
env=pathlib.Path('/etc/3xui-happ-balancer.env').read_text()
for line in env.splitlines():
    if 'HAPP_ROUTING' in line or 'WINTER' in line:
        print(line[:200])
print('--- winter ---')
for p in pathlib.Path('/etc/winter-routing').glob('*.json'):
    print(p.name, 'DirectSites', json.loads(p.read_text()).get('DirectSites'))
PY
