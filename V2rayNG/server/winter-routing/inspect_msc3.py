#!/usr/bin/env python3
import base64, json, re, ssl, sys, urllib.request
import paramiko

PASSWORD = sys.argv[1]
REMOTE = r'''
python3 - <<'PY'
import base64, json, re, ssl, urllib.request

def b64decode(s):
    s=s.strip()
    pad='='*((4-len(s)%4)%4)
    return base64.urlsafe_b64decode(s+pad)

ctx=ssl._create_unverified_context()
sub='qk13p3uu5farhw5x'
url='https://msc.zizmos.ru:8362/jesonic/'+sub
body=urllib.request.urlopen(urllib.request.Request(url), timeout=20, context=ctx).read()
print('size', len(body), 'head', body[:80])
data=json.loads(body)
print('type', type(data), 'len', len(data) if hasattr(data,'__len__') else None)
for i, item in enumerate(data):
    print('--- item', i, type(item).__name__)
    if isinstance(item, dict):
        print(json.dumps({k:item.get(k) for k in list(item)[:20]}, ensure_ascii=False)[:800])
        continue
    if isinstance(item, str):
        s=item
        print('str head', s[:120])
        # maybe nested json
        try:
            j=json.loads(s)
            print(' nested json keys', list(j)[:20] if isinstance(j,dict) else type(j))
            if isinstance(j, dict):
                print('  remark', j.get('remark'), 'outbounds', len(j.get('outbounds') or []))
                for o in (j.get('outbounds') or [])[:5]:
                    print('  out', o.get('tag'), o.get('protocol'))
            continue
        except Exception:
            pass
        # vless uri
        if s.startswith('vless://') or s.startswith('hy2://') or s.startswith('hysteria'):
            print(' uri', s[:200])
            continue
        # base64 blob
        try:
            raw=b64decode(s)
            text=raw.decode('utf-8','replace')
            print(' b64 text head', text[:200].replace('\n',' | '))
            for line in text.splitlines():
                if '://' in line:
                    # extract name after #
                    name=line.split('#',1)[-1] if '#' in line else ''
                    print('  link', line.split('://',1)[0], 'name', urllib.parse.unquote(name) if False else name)
            import urllib.parse
            for line in text.splitlines():
                if '://' in line:
                    name=urllib.parse.unquote(line.split('#',1)[-1]) if '#' in line else ''
                    print('  link', line.split('://',1)[0], 'name', name)
        except Exception as e:
            print(' b64 fail', e)

# also decode auto output remarks via balancer headers/body properly
url2='https://127.0.0.1:8443/auto/'+sub
req=urllib.request.Request(url2, headers={'User-Agent':'Happ/1.0'})
body2=urllib.request.urlopen(req, timeout=20, context=ctx).read()
print('\n=== auto body ===')
d=json.loads(body2)
# could be single config object wrapped?
print('auto type', type(d))
if isinstance(d, list):
    d=d[0]
outs=d.get('outbounds') or []
print('outbounds', len(outs))
for o in outs:
    tag=o.get('tag')
    if not str(tag).startswith('route-p') and tag not in ('proxy','primary'):
        continue
    ss=o.get('streamSettings') or {}
    vnext=((o.get('settings') or {}).get('vnext') or [{}])
    addr=vnext[0].get('address') if vnext else None
    sni=(ss.get('realitySettings') or {}).get('serverNames') or (ss.get('tlsSettings') or {}).get('serverName')
    print(tag, o.get('protocol'), ss.get('network'), ss.get('security'), addr, sni)

# show how many clients linked to Moscow vs old inbound ids
import sqlite3
con=sqlite3.connect('file:/etc/x-ui/x-ui.db?mode=ro', uri=True)
cur=con.cursor()
print('\n=== client_inbounds ===')
try:
    for row in cur.execute('select inbound_id, count(*) from client_inbounds group by inbound_id'):
        print(row)
except Exception as e:
    print(e)
print('=== orphan traffics inbound ids ===')
for row in cur.execute('select inbound_id, count(*), group_concat(email,"|") from client_traffics group by inbound_id'):
    print(row[0], row[1], (row[2] or '')[:200])
con.close()
PY
'''
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("194.67.205.140", username="root", password=PASSWORD, timeout=25, allow_agent=False, look_for_keys=False)
_, stdout, stderr = client.exec_command(REMOTE, timeout=90)
out = stdout.read().decode("utf-8", "replace")
open(r"C:\ZeroVPN\v2rayNG\V2rayNG\server\winter-routing\inspect_msc_out3.txt","w",encoding="utf-8").write(out)
import sys
sys.stdout.buffer.write(out.encode("ascii","replace"))
client.close()
