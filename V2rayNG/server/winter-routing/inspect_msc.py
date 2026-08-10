#!/usr/bin/env python3
"""Inspect MSC backup generator for missing Selectel routes."""
import io
import sys

import paramiko

HOST = "194.67.205.140"
USER = "root"
PASSWORD = sys.argv[1] if len(sys.argv) > 1 else ""

REMOTE = r'''
set -e
echo "=== host ==="
hostname; uname -a; ip -4 addr show | sed -n '1,40p'
echo "=== listeners ==="
ss -lntp 2>/dev/null | grep -E ':8099|:5843|:8362|:80 |:443 |x-ui|uvicorn' || true
echo "=== services ==="
systemctl list-units --type=service --state=running 2>/dev/null | grep -Ei '3x|happ|balancer|x-ui|nginx|uvicorn' || true
echo "=== opt ==="
ls -la /opt 2>/dev/null || true
ls -la /opt/3xui-happ-balancer 2>/dev/null || true
echo "=== env files ==="
for f in /etc/3xui-happ-balancer.env /opt/3xui-happ-balancer/.env /root/3xui-happ-balancer/.env /etc/default/3xui-happ-balancer; do
  if [ -f "$f" ]; then
    echo "-- $f --"
    grep -E '^(HAPP_|WINTER_|UPSTREAM_|PRIORITY_|CLASH_|PROFILE_|INCLUDE_|MAX_|HEALTH_|TCP_|LEASTLOAD_|PORT|HOST|BIND)' "$f" | sed -E 's/(PASSWORD|TOKEN|SECRET|KEY)=.*/\1=***/I' || true
  fi
done
echo "=== priorities file ==="
ls -la /etc/3xui-happ-priorities.json 2>/dev/null || echo missing_priorities
if [ -f /etc/3xui-happ-priorities.json ]; then
  python3 - <<'PY'
import json
from pathlib import Path
d=json.loads(Path('/etc/3xui-happ-priorities.json').read_text())
print(json.dumps(d, ensure_ascii=False, indent=2)[:5000])
PY
fi
echo "=== nginx bits ==="
grep -RInE 'jesonic|auto/|8099|happ|balancer|jsonic|8362' /etc/nginx 2>/dev/null | head -100 || true
echo "=== try generate ==="
SUB=qk13p3uu5farhw5x
for url in \
  "http://127.0.0.1:8099/auto/$SUB" \
  "http://127.0.0.1:8099/jesonic/$SUB" \
  "http://127.0.0.1:8362/jesonic/$SUB" \
  "http://127.0.0.1:8362/auto/$SUB" \
  "http://127.0.0.1:5843/jsonic/$SUB" \
  "https://127.0.0.1:8362/jesonic/$SUB"
do
  code=$(curl -skS -o /tmp/msc_body.json -w '%{http_code}' -A 'Winter-Mobile/1.2.3' --connect-timeout 5 "$url" || echo 000)
  echo "URL $url -> $code size=$(wc -c </tmp/msc_body.json 2>/dev/null || echo 0)"
  if [ "$code" = "200" ]; then
    python3 - <<'PY'
import json, pathlib
raw=pathlib.Path('/tmp/msc_body.json').read_text(encoding='utf-8', errors='replace')
d=json.loads(raw)
c=d[0] if isinstance(d, list) else d
outs=c.get('outbounds') or []
tags=[o.get('tag') for o in outs if str(o.get('tag','')).startswith('route-p')]
print('route_tags', tags)
print('tiers', sorted({str(t).split('-')[1] for t in tags}))
for o in outs:
  tag=str(o.get('tag',''))
  if not tag.startswith('route-p'):
    continue
  ss=o.get('streamSettings') or {}
  reality=ss.get('realitySettings') or {}
  tls=ss.get('tlsSettings') or {}
  sni=reality.get('serverNames') or tls.get('serverName')
  addr=None
  if o.get('protocol')=='vless':
    vnext=(o.get('settings') or {}).get('vnext') or []
    if vnext:
      addr=vnext[0].get('address')
  print(tag, 'proto', o.get('protocol'), 'net', ss.get('network'), 'sec', ss.get('security'), 'addr', addr, 'sni', sni)
PY
    curl -skS -D /tmp/msc_hdr.txt -o /dev/null -A 'Winter-Mobile/1.2.3' "$url" || true
    echo '--- headers ---'
    tr -d '\r' </tmp/msc_hdr.txt | grep -iE 'HTTP/|x-balancer|x-generated|profile-|x-winter|content-type' || true
    break
  fi
done
echo "=== x-ui db search ==="
python3 - <<'PY'
import os, re, sqlite3
cands=[]
for root, dirs, files in os.walk('/'):
  # prune huge trees
  dirs[:] = [d for d in dirs if d not in ('proc','sys','dev','run','snap','boot','var/lib/docker','var/cache')]
  for f in files:
    fl=f.lower()
    if fl.endswith('.db') and ('x-ui' in fl or 'xui' in fl or 'x-ui' in root.lower() or 'xui' in root.lower()):
      cands.append(os.path.join(root, f))
  if len(cands) > 30:
    break
print('dbs', cands)
for db in cands[:15]:
  try:
    con=sqlite3.connect(f'file:{db}?mode=ro', uri=True)
    cur=con.cursor()
    tables=[r[0] for r in cur.execute("select name from sqlite_master where type='table'")]
    print('DB', db, 'tables', tables)
    if 'inbounds' in tables:
      rows=cur.execute('select id, remark, protocol from inbounds').fetchall()
      print(' inbounds_count', len(rows))
      for i, rem, proto in rows:
        rem=rem or ''
        if re.search(r'selectel|селектел|\[p0\]|обход|moscow|моск|beget|timeweb|vk', rem, re.I):
          print('  inbound', i, proto, rem)
    if 'client_traffics' in tables:
      n=cur.execute('select count(*) from client_traffics').fetchone()[0]
      print(' client_traffics', n)
      hit=cur.execute("select email, inbound_id from client_traffics where email like '%qk13%' limit 20").fetchall()
      print('  qk13 hits', hit)
    con.close()
  except Exception as e:
    print(' dberr', db, e)
PY
echo "=== upstream fetch comparison ==="
python3 - <<'PY'
import json, os, re, urllib.request
sub='qk13p3uu5farhw5x'
env={}
for path in ['/etc/3xui-happ-balancer.env','/opt/3xui-happ-balancer/.env']:
  if os.path.exists(path):
    for line in open(path, encoding='utf-8', errors='replace'):
      line=line.strip()
      if not line or line.startswith('#') or '=' not in line: continue
      k,v=line.split('=',1); env[k]=v.strip().strip('"').strip("'")
base=env.get('UPSTREAM_JSON_BASE') or env.get('UPSTREAM_BASE') or ''
print('UPSTREAM_JSON_BASE', base)
cands=[]
if base:
  cands.append(base.rstrip('/') + '/' + sub)
cands += [
  f'http://127.0.0.1:5843/jsonic/{sub}',
  f'https://127.0.0.1:5843/jsonic/{sub}',
  f'http://127.0.0.1:5843/jesonic/{sub}',
  f'https://sctl.zizmos.ru:5843/jsonic/{sub}',
  f'https://msc.zizmos.ru:8362/jesonic/{sub}',
]
for url in cands:
  try:
    req=urllib.request.Request(url, headers={'User-Agent':'curl/8'})
    with urllib.request.urlopen(req, timeout=20, context=__import__('ssl')._create_unverified_context()) as resp:
      body=resp.read()
      print('OK', url, 'status', getattr(resp,'status',200), 'size', len(body))
      try:
        data=json.loads(body)
      except Exception:
        # maybe base64 subscription list
        text=body.decode('utf-8','replace')
        print('  not json, head', text[:120].replace('\n',' '))
        continue
      # xray json or list of outbounds/profiles
      if isinstance(data, list):
        remarks=[]
        for item in data:
          if isinstance(item, dict):
            rem=item.get('remark') or item.get('ps') or item.get('name') or ''
            remarks.append(rem)
          elif isinstance(item, str):
            remarks.append(item[:80])
        print('  list_len', len(data))
        for rem in remarks:
          if re.search(r'selectel|селектел|\[p0\]|обход|moscow|моск|beget|timeweb|vk', rem or '', re.I):
            print('  *', rem)
      elif isinstance(data, dict):
        outs=data.get('outbounds') or []
        print('  outbounds', len(outs))
        for o in outs:
          rem=str(o.get('tag') or o.get('remark') or '')
          if re.search(r'selectel|route-p|p0000|обход|moscow', rem, re.I):
            print('  *', rem)
  except Exception as e:
    print('FAIL', url, type(e).__name__, e)
PY
'''

def main() -> int:
    if not PASSWORD:
        print("usage: inspect_msc.py <password>", file=sys.stderr)
        return 2
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"connecting {USER}@{HOST} ...", flush=True)
    client.connect(HOST, username=USER, password=PASSWORD, timeout=25, allow_agent=False, look_for_keys=False)
    stdin, stdout, stderr = client.exec_command(REMOTE, timeout=180)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    code = stdout.channel.recv_exit_status()
    out_path = r"C:\ZeroVPN\v2rayNG\V2rayNG\server\winter-routing\inspect_msc_out.txt"
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(out)
        if err:
            f.write("\n--- STDERR ---\n")
            f.write(err)
        f.write(f"\nexit={code}\n")
    # Console-safe summary
    safe = out.encode("ascii", "replace").decode("ascii")
    sys.stdout.buffer.write(safe.encode("ascii", "replace")[:12000])
    sys.stdout.buffer.write(f"\n\n[full output] {out_path}\nexit={code}\n".encode("ascii"))
    client.close()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
