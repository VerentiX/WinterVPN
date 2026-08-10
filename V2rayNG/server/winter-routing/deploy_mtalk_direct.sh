#!/bin/bash
set -euo pipefail
TS=$(date +%Y%m%d%H%M%S)

# Winter profiles
mkdir -p /etc/winter-routing
cp -a /etc/winter-routing/default.json "/etc/winter-routing/default.json.bak-${TS}" 2>/dev/null || true
cp -a /etc/winter-routing/whitelist.json "/etc/winter-routing/whitelist.json.bak-${TS}" 2>/dev/null || true
install -o root -g root -m 644 /tmp/winter-default.json /etc/winter-routing/default.json
install -o root -g root -m 644 /tmp/winter-whitelist.json /etc/winter-routing/whitelist.json

# Balancer app + env force-direct
cp -a /opt/3xui-happ-balancer/app.py "/opt/3xui-happ-balancer/app.py.bak-mtalk-${TS}"
install -o root -g root -m 644 /tmp/app.live.py /opt/3xui-happ-balancer/app.py
python3 -m py_compile /opt/3xui-happ-balancer/app.py

ENV=/etc/3xui-happ-balancer.env
cp -a "$ENV" "${ENV}.bak-mtalk-${TS}"
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/3xui-happ-balancer.env')
lines=[l for l in p.read_text(encoding='utf-8').splitlines()
       if not l.startswith('HAPP_FORCE_DIRECT_DOMAINS=')
       and not l.startswith('HAPP_FORCE_DIRECT_PORTS=')]
lines.append('HAPP_FORCE_DIRECT_DOMAINS=keyword:mtalk.google.com,domain:mtalk.google.com')
lines.append('HAPP_FORCE_DIRECT_PORTS=5228')
p.write_text('\n'.join(lines)+'\n', encoding='utf-8')
print('env updated')
for l in p.read_text(encoding='utf-8').splitlines():
    if 'FORCE_DIRECT' in l or l.startswith('UPSTREAM_'):
        print(l)
PY

# Update x-ui Happ subRoutingRules DirectSites
python3 - <<'PY'
import base64, json, re, sqlite3, time
con=sqlite3.connect('/etc/x-ui/x-ui.db')
cur=con.cursor()
row=cur.execute("select value from settings where key='subRoutingRules'").fetchone()
if not row or not row[0]:
    raise SystemExit('no subRoutingRules')
raw=row[0]
m=re.search(r'^(happ://routing/(?:onadd|add)/)([A-Za-z0-9_=-]+)$', raw.strip())
if not m:
    # maybe prefix only
    m=re.search(r'(happ://routing/(?:onadd|add)/)([A-Za-z0-9_=-]+)', raw)
if not m:
    raise SystemExit('cannot parse happ routing link')
prefix, b64=m.group(1), m.group(2)
pad='='*((4-len(b64)%4)%4)
profile=json.loads(base64.urlsafe_b64decode(b64+pad))
ds=profile.get('DirectSites') or []
for item in ('keyword:mtalk.google.com','domain:mtalk.google.com'):
    if item not in ds:
        ds.insert(0, item)
profile['DirectSites']=ds
profile['LastUpdated']=str(int(time.time()))
encoded=base64.urlsafe_b64encode(
    json.dumps(profile, ensure_ascii=False, separators=(',',':')).encode()
).decode().rstrip('=')
new=prefix+encoded
cur.execute("update settings set value=? where key='subRoutingRules'", (new,))
con.commit()
con.close()
print('subRoutingRules updated DirectSites=', profile['DirectSites'][:6])
PY

systemctl restart 3xui-happ-balancer.service
sleep 2
systemctl is-active 3xui-happ-balancer.service

python3 - <<'PY'
import base64, json, ssl, urllib.request
ctx=ssl._create_unverified_context()
sub='qk13p3uu5farhw5x'
req=urllib.request.Request(
    f'http://127.0.0.1:8099/auto/{sub}',
    headers={'User-Agent':'Winter-Mobile/1.2.3'},
)
with urllib.request.urlopen(req, timeout=30) as resp:
    body=json.loads(resp.read())
    hdr={k.lower():v for k,v in resp.headers.items()}
    print('tiers', hdr.get('x-balancer-tiers'))
    pr=hdr.get('profile-routing','')
    assert pr.startswith('base64:')
    raw=base64.b64decode(pr.split(':',1)[1])
    bundle=json.loads(raw)
    for kind in ('default','whitelist'):
        ds=(bundle.get(kind) or {}).get('DirectSites') or []
        print(kind, 'mtalk' , [x for x in ds if 'mtalk' in x])
    cfg=body[0] if isinstance(body, list) else body
    rules=(cfg.get('routing') or {}).get('rules') or []
    hits=[]
    for r in rules:
        dom=r.get('domain') or []
        if isinstance(dom, str): dom=[dom]
        port=str(r.get('port') or '')
        if any('mtalk' in str(d) for d in dom) or port=='5228' or '5228' in port:
            hits.append({'outbound': r.get('outboundTag'), 'domain': dom, 'port': r.get('port'), 'network': r.get('network')})
    print('embedded force rules', hits[:5])
    assert hits, 'expected mtalk/5228 direct rules in generated config'
print('OK')
PY
