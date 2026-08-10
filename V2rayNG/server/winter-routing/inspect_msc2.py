#!/usr/bin/env python3
import json
import sys
import paramiko

HOST = "194.67.205.140"
PASSWORD = sys.argv[1]
REMOTE = r'''
python3 - <<'PY'
import json, sqlite3, ssl, urllib.request, os, re

print('=== all inbounds ===')
con=sqlite3.connect('file:/etc/x-ui/x-ui.db?mode=ro', uri=True)
cur=con.cursor()
for row in cur.execute('select id, remark, protocol, port, enable, listen from inbounds'):
    print(row)
print('=== client counts by inbound ===')
try:
    for row in cur.execute('select inbound_id, count(*) from client_traffics group by inbound_id'):
        print(row)
except Exception as e:
    print('ct', e)
print('=== clients table sample ===')
try:
    n=cur.execute('select count(*) from clients').fetchone()[0]
    print('clients', n)
    for row in cur.execute('select id, email, enable from clients limit 15'):
        print(row)
except Exception as e:
    print(e)
print('=== nodes ===')
try:
    for row in cur.execute('select * from nodes'):
        print(row)
except Exception as e:
    print(e)
con.close()

ctx=ssl._create_unverified_context()
sub='qk13p3uu5farhw5x'
urls=[
 'https://msc.zizmos.ru:8362/jesonic/'+sub,
 'https://127.0.0.1:8443/auto/'+sub,
 'http://127.0.0.1:8443/auto/'+sub,
]
for url in urls:
    try:
        req=urllib.request.Request(url, headers={'User-Agent':'Winter-Mobile/1.2.3'})
        with urllib.request.urlopen(req, timeout=25, context=ctx) as resp:
            body=resp.read()
            headers={k.lower():v for k,v in resp.headers.items()}
            print('\n===', url, 'status', getattr(resp,'status',200), 'size', len(body))
            for k in ['x-balancer-tiers','x-generated-by','x-balancer-mode','profile-web-page-url','content-type']:
                if k in headers: print(' ', k, headers[k])
            try:
                data=json.loads(body)
            except Exception:
                print('  body head', body[:200]); continue
            if isinstance(data, list):
                print('  profiles', len(data))
                for item in data:
                    if not isinstance(item, dict):
                        print('  item', type(item), str(item)[:120]); continue
                    # xray outbound-ish or 3xui export
                    rem=item.get('remark') or item.get('ps') or item.get('tag') or item.get('name')
                    proto=item.get('protocol')
                    ss=item.get('streamSettings') or {}
                    print('  remark=', rem, 'proto=', proto, 'net=', ss.get('network'), 'sec=', ss.get('security'))
                    # try settings address
                    addr=None
                    if proto=='vless':
                        v=(item.get('settings') or {}).get('vnext') or []
                        if v: addr=v[0].get('address')
                    print('   addr', addr, 'sni', (ss.get('realitySettings') or {}).get('serverNames') or (ss.get('tlsSettings') or {}).get('serverName'))
            elif isinstance(data, dict):
                outs=data.get('outbounds') or []
                tags=[o.get('tag') for o in outs if str(o.get('tag','')).startswith('route-p')]
                print('  route tags', tags)
                print('  tiers', sorted({str(t).split('-')[1] for t in tags}))
                for o in outs:
                    tag=str(o.get('tag',''))
                    if tag.startswith('route-p'):
                        ss=o.get('streamSettings') or {}
                        print(' ', tag, ss.get('network'), ss.get('security'), ((o.get('settings') or {}).get('vnext') or [{}])[0].get('address'))
    except Exception as e:
        print('FAIL', url, type(e).__name__, e)

# compare priorities with expected Selectel markers
print('\n=== priorities raw ===')
print(open('/etc/3xui-happ-priorities.json',encoding='utf-8').read())
print('=== balancer listen ===')
import subprocess
print(subprocess.getoutput('systemctl cat 3xui-happ-balancer.service | sed -n "1,80p"'))
print(subprocess.getoutput('ss -lntp | grep -E "8443|8099|uvicorn"'))
PY
'''

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(HOST, username="root", password=PASSWORD, timeout=25, allow_agent=False, look_for_keys=False)
_, stdout, stderr = client.exec_command(REMOTE, timeout=120)
out = stdout.read().decode("utf-8", "replace")
err = stderr.read().decode("utf-8", "replace")
code = stdout.channel.recv_exit_status()
path = r"C:\ZeroVPN\v2rayNG\V2rayNG\server\winter-routing\inspect_msc_out2.txt"
open(path, "w", encoding="utf-8").write(out + ("\n---STDERR---\n" + err if err else "") + f"\nexit={code}\n")
sys.stdout.buffer.write(out.encode("ascii", "replace")[:14000])
sys.stdout.buffer.write(f"\n[full] {path} exit={code}\n".encode())
client.close()
