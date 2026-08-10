#!/usr/bin/env python3
import sys
import paramiko

PASSWORD = sys.argv[1]
REMOTE = r'''
set -euo pipefail
ENV=/etc/3xui-happ-balancer.env
cp -a "$ENV" "${ENV}.bak-revert-local-jesonic-$(date +%Y%m%d%H%M%S)"
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/3xui-happ-balancer.env')
text=p.read_text(encoding='utf-8')
old='UPSTREAM_JSON_BASE=https://sctl.zizmos.ru:5843/jsonic/'
new='UPSTREAM_JSON_BASE=https://msc.zizmos.ru:8362/jesonic/'
if new in text and old not in text:
    print('already_local')
elif old not in text:
    raise SystemExit('unexpected UPSTREAM:\n'+'\n'.join(l for l in text.splitlines() if l.startswith('UPSTREAM_')))
else:
    p.write_text(text.replace(old,new,1), encoding='utf-8')
    print('reverted')
for l in p.read_text(encoding='utf-8').splitlines():
    if l.startswith('UPSTREAM_'):
        print(l)
PY
systemctl restart 3xui-happ-balancer.service
sleep 2
systemctl is-active 3xui-happ-balancer.service
python3 - <<'PY'
import json, ssl, urllib.request
ctx=ssl._create_unverified_context()
sub='qk13p3uu5farhw5x'
req=urllib.request.Request('https://127.0.0.1:8443/auto/'+sub, headers={'User-Agent':'Winter-Mobile/1.2.3'})
with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
    body=resp.read()
    hdr={k.lower():v for k,v in resp.headers.items()}
    print('auto_status', getattr(resp,'status',200), 'size', len(body))
    for k in ['x-balancer-tiers','profile-web-page-url','x-generated-by']:
        if k in hdr: print(k, hdr[k])
    d=json.loads(body)
    c=d[0] if isinstance(d,list) else d
    tags=[o.get('tag') for o in (c.get('outbounds') or []) if str(o.get('tag','')).startswith('route-p')]
    print('tiers', sorted({t.split('-')[1] for t in tags}))
    print('tags', tags)
PY
'''

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("194.67.205.140", username="root", password=PASSWORD, timeout=25, allow_agent=False, look_for_keys=False)
_, stdout, stderr = client.exec_command(REMOTE, timeout=120)
out = stdout.read().decode("utf-8", "replace")
err = stderr.read().decode("utf-8", "replace")
code = stdout.channel.recv_exit_status()
sys.stdout.buffer.write(out.encode("ascii", "replace"))
if err:
    sys.stdout.buffer.write(("\n---STDERR---\n"+err).encode("ascii", "replace"))
sys.stdout.buffer.write(f"\nexit={code}\n".encode())
client.close()
raise SystemExit(code)
