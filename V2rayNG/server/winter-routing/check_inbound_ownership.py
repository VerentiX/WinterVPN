#!/usr/bin/env python3
import json, re, sys
import paramiko

MSC_PASS = sys.argv[1]
KEY = sys.argv[2]

Q = r'''
python3 - <<'PY'
import json, sqlite3, subprocess
print('HOST', subprocess.getoutput('hostname -I'))
con=sqlite3.connect('file:/etc/x-ui/x-ui.db?mode=ro', uri=True)
con.row_factory=sqlite3.Row
cur=con.cursor()
print('=== inbounds node ownership ===')
for row in cur.execute('select id, remark, enable, port, tag, node_id, origin_node_guid, share_addr_strategy, share_addr from inbounds order by id'):
    print(dict(row))
print('=== settings sub* / panel* / node* ===')
for row in cur.execute("select key, value from settings where key like 'sub%' or key like 'panel%' or key like '%Node%' or key like '%node%' or key like '%Show%' order by key"):
    v=row['value']
    if v and len(str(v))>300: v=str(v)[:300]+'...'
    if 'secret' in (row['key'] or '').lower() or 'token' in (row['key'] or '').lower() or 'key' in (row['key'] or '').lower():
        v='***'
    print(row['key'], '=', v)
# client Petya inbound links
print('=== Petya client_inbounds ===')
try:
    cid=cur.execute("select id, email, sub_id, enable from clients where email='Petya' or sub_id='qk13p3uu5farhw5x'").fetchall()
    print(cid)
    for c in cid:
        rows=cur.execute('select * from client_inbounds where client_id=?', (c['id'],)).fetchall()
        print('links', [dict(r) for r in rows])
except Exception as e:
    print(e)
print('=== panelGuid ===')
print(cur.execute("select value from settings where key='panelGuid'").fetchone()[0])
con.close()
PY
'''

def run(host, auth):
    c=paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    if auth.get('key'):
        c.connect(host, username='root', key_filename=auth['key'], timeout=25, allow_agent=False, look_for_keys=False)
    else:
        c.connect(host, username='root', password=auth['password'], timeout=25, allow_agent=False, look_for_keys=False)
    _,o,e=c.exec_command(Q, timeout=60)
    out=o.read().decode('utf-8','replace'); err=e.read().decode('utf-8','replace'); c.close()
    return out, err

parts=[]
for label, host, auth in [
    ('MASTER', '139.100.225.91', {'key': KEY}),
    ('NODE', '194.67.205.140', {'password': MSC_PASS}),
]:
    out, err = run(host, auth)
    parts.append(f'\n##### {label} #####\n'+out+(('\nERR\n'+err) if err else ''))
text=''.join(parts)
open(r'C:\ZeroVPN\v2rayNG\V2rayNG\server\winter-routing\node_ownership.txt','w',encoding='utf-8').write(text)
sys.stdout.buffer.write(text.encode('ascii','replace'))
