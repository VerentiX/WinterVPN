#!/bin/bash
set -euo pipefail
echo "=== host ==="
hostname
uname -a
echo "=== listening / services ==="
ss -lntp 2>/dev/null | grep -E '8099|5843|8362|80|443|x-ui|uvicorn' || true
systemctl list-units --type=service --state=running 2>/dev/null | grep -Ei '3x|happ|balancer|x-ui|nginx|uvicorn' || true
echo "=== paths ==="
ls -la /opt 2>/dev/null || true
ls -la /opt/3xui-happ-balancer 2>/dev/null || true
ls -la /etc/3xui-happ* 2>/dev/null || true
ls -la /etc/x-ui* 2>/dev/null || true
echo "=== env (redacted) ==="
for f in /etc/3xui-happ-balancer.env /opt/3xui-happ-balancer/.env /root/3xui-happ-balancer/.env; do
  if [[ -f "$f" ]]; then
    echo "-- $f --"
    grep -E '^(HAPP_|WINTER_|UPSTREAM_|PRIORITY_|CLASH_|PROFILE_|INCLUDE_|MAX_|HEALTH_|TCP_|LEASTLOAD_|PORT|HOST)' "$f" | sed -E 's/(PASSWORD|TOKEN|SECRET|KEY)=.*/\1=***/I' || true
  fi
done
echo "=== priorities ==="
if [[ -f /etc/3xui-happ-priorities.json ]]; then
  python3 - <<'PY'
import json
from pathlib import Path
p=Path('/etc/3xui-happ-priorities.json')
d=json.loads(p.read_text())
print('keys', list(d.keys()) if isinstance(d, dict) else type(d))
print(json.dumps(d, ensure_ascii=False, indent=2)[:4000])
PY
fi
echo "=== nginx jesonic/auto ==="
grep -RInE 'jesonic|auto/|8099|happ|balancer|jsonic' /etc/nginx 2>/dev/null | head -80 || true
echo "=== sample generate ==="
SUB=qk13p3uu5farhw5x
for url in \
  "http://127.0.0.1:8099/auto/${SUB}" \
  "http://127.0.0.1:8099/jesonic/${SUB}" \
  "http://127.0.0.1:8362/jesonic/${SUB}" \
  "http://127.0.0.1:8362/auto/${SUB}" \
  "http://127.0.0.1:5843/jsonic/${SUB}" \
  "http://127.0.0.1:5843/jesonic/${SUB}"
do
  code=$(curl -sS -o /tmp/msc_body.json -w '%{http_code}' -A 'Winter-Mobile/1.2.3' --connect-timeout 5 "$url" || echo curlfail)
  echo "URL $url -> $code size=$(wc -c </tmp/msc_body.json 2>/dev/null || echo 0)"
  if [[ "$code" == "200" ]]; then
    python3 - <<'PY'
import json, pathlib
from collections import Counter
raw=pathlib.Path('/tmp/msc_body.json').read_text(encoding='utf-8', errors='replace')
try:
  d=json.loads(raw)
except Exception as e:
  print('json fail', e, raw[:200]); raise SystemExit
c=d[0] if isinstance(d, list) else d
outs=c.get('outbounds') or []
tags=[o.get('tag') for o in outs if str(o.get('tag','')).startswith('route-p')]
print('route tags', tags)
print('tiers', sorted({t.split('-')[1] for t in tags}))
# remarks/names if present in balancers or outbounds
for o in outs:
  tag=str(o.get('tag',''))
  if tag.startswith('route-p'):
    rem=o.get('remark') or o.get('email') or ''
    sni=((o.get('streamSettings') or {}).get('realitySettings') or {}).get('serverNames')
    if not sni:
      sni=((o.get('streamSettings') or {}).get('tlsSettings') or {}).get('serverName')
    addr=((o.get('settings') or {}).get('vnext') or [{}])[0].get('address') if o.get('protocol')=='vless' else None
    print(tag, 'proto', o.get('protocol'), 'addr', addr, 'sni', sni, 'rem', rem)
PY
    break
  fi
done
echo "=== x-ui db inbound remarks with Selectel ==="
python3 - <<'PY'
import sqlite3, os, json, re
candidates=[]
for root, dirs, files in os.walk('/etc'):
  for f in files:
    if f.endswith('.db') and ('x-ui' in f.lower() or 'xui' in root.lower() or 'x-ui' in root.lower()):
      candidates.append(os.path.join(root, f))
for root, dirs, files in os.walk('/usr/local'):
  for f in files:
    if f.endswith('.db') and 'x-ui' in root.lower():
      candidates.append(os.path.join(root, f))
print('db candidates', candidates[:20])
for db in candidates[:10]:
  try:
    con=sqlite3.connect(f'file:{db}?mode=ro', uri=True)
    cur=con.cursor()
    tables=[r[0] for r in cur.execute("select name from sqlite_master where type='table'")]
    print(db, 'tables', tables)
    if 'inbounds' in tables:
      rows=cur.execute('select id, remark, settings from inbounds').fetchall()
      print('inbounds', len(rows))
      for i, rem, settings in rows:
        if rem and re.search(r'selectel|селектел|P0|обход|moscow|моск', rem, re.I):
          print(' inbound', i, rem)
    if 'client_traffics' in tables:
      n=cur.execute('select count(*) from client_traffics').fetchone()[0]
      print('client_traffics', n)
      sel=cur.execute("select email, inbound_id from client_traffics where email like '%qk13%' or email like '%Selectel%' limit 20").fetchall()
      print('sample clients', sel[:10])
    con.close()
  except Exception as e:
    print('db err', db, e)
PY
