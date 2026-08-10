#!/usr/bin/env python3
"""Compare 139 master vs 194 node x-ui settings for subscription completeness."""
import sys
import paramiko

MSC_PASS = sys.argv[1] if len(sys.argv) > 1 else ""
KEY = sys.argv[2] if len(sys.argv) > 2 else ""

SCRIPT = r'''
python3 - <<'PY'
import json, os, sqlite3, subprocess, re

print('HOST', subprocess.getoutput('hostname'))
print('IP', subprocess.getoutput("hostname -I | awk '{print $1}'"))

db='/etc/x-ui/x-ui.db'
con=sqlite3.connect(f'file:{db}?mode=ro', uri=True)
con.row_factory=sqlite3.Row
cur=con.cursor()

print('\n=== settings (interesting) ===')
keys_want=re.compile(r'(node|sub|json|jeson|panel|sync|master|slave|remote|uri|domain|port|listen|secret|base|path|enable|traffic|allClient|show|remark)', re.I)
for row in cur.execute('select key, value from settings order by key'):
    k=row['key'] or ''
    v=row['value'] if row['value'] is not None else ''
    if keys_want.search(k) or keys_want.search(v[:200] if isinstance(v,str) else ''):
        vv=v if len(str(v))<500 else str(v)[:500]+'...'
        # redact secrets lightly
        if re.search(r'(password|secret|token|key)', k, re.I):
            vv='***'
        print(f'{k} = {vv}')

print('\n=== ALL settings keys ===')
for row in cur.execute('select key from settings order by key'):
    print(row['key'])

print('\n=== nodes table ===')
try:
    cols=[r[1] for r in cur.execute('pragma table_info(nodes)')]
    print('cols', cols)
    for row in cur.execute('select * from nodes'):
        d=dict(row)
        for sk in list(d):
            if re.search(r'pass|token|secret|key', sk, re.I) and d[sk]:
                d[sk]='***'
        print(d)
except Exception as e:
    print('nodes err', e)

print('\n=== inbounds summary ===')
cols=[r[1] for r in cur.execute('pragma table_info(inbounds)')]
print('inbound cols', cols)
for row in cur.execute('select id, remark, protocol, port, enable, listen, tag from inbounds'):
    print(dict(row))

print('\n=== outbound_subscriptions ===')
try:
    cols=[r[1] for r in cur.execute('pragma table_info(outbound_subscriptions)')]
    print('cols', cols)
    for row in cur.execute('select * from outbound_subscriptions'):
        d=dict(row)
        for sk in list(d):
            if re.search(r'pass|token|secret|key|url', sk, re.I) and d[sk]:
                val=str(d[sk])
                d[sk]=val if 'http' in val.lower() else '***'
                if 'http' in val.lower() and len(val)>180:
                    d[sk]=val[:180]+'...'
        print(d)
except Exception as e:
    print(e)

print('\n=== client_external_links sample ===')
try:
    cols=[r[1] for r in cur.execute('pragma table_info(client_external_links)')]
    print('cols', cols)
    n=cur.execute('select count(*) from client_external_links').fetchone()[0]
    print('count', n)
    for row in cur.execute('select * from client_external_links limit 5'):
        print(dict(row))
except Exception as e:
    print(e)

print('\n=== hosts ===')
try:
    for row in cur.execute('select * from hosts'):
        print(dict(row))
except Exception as e:
    print(e)

print('\n=== client counts ===')
for t in ['clients','client_traffics','client_inbounds','client_groups']:
    try:
        print(t, cur.execute(f'select count(*) from {t}').fetchone()[0])
    except Exception as e:
        print(t, e)

# subId for qk13 if exists
print('\n=== find sub qk13 ===')
# clients settings json often has subId
try:
    # older schema may store in inbounds.settings clients
    for row in cur.execute('select id, remark, settings from inbounds'):
        settings=row['settings'] or ''
        if 'qk13p3uu5farhw5x' in settings:
            print('found in inbound', row['id'], row['remark'])
            # extract client emails with that sub
            try:
                j=json.loads(settings)
                for c in j.get('clients') or []:
                    if c.get('subId')=='qk13p3uu5farhw5x' or 'qk13' in json.dumps(c):
                        print(' client', c.get('email'), 'subId', c.get('subId'), 'enable', c.get('enable'))
            except Exception as e:
                print('parse', e)
except Exception as e:
    print(e)

try:
    for row in cur.execute('select id, email from clients'):
        # maybe extra fields
        pass
    cols=[r[1] for r in cur.execute('pragma table_info(clients)')]
    print('clients cols', cols)
    if 'sub_id' in cols or 'subId' in cols:
        pass
    # dump one client row keys
    row=cur.execute('select * from clients limit 1').fetchone()
    if row:
        print('sample client keys', list(dict(row)))
except Exception as e:
    print(e)

print('\n=== x-ui config / sub related files ===')
for p in ['/etc/x-ui/config.json','/usr/local/x-ui/bin/config.json','/etc/x-ui/x-ui.db']:
    if os.path.exists(p):
        print('exists', p, os.path.getsize(p))
print(subprocess.getoutput('ls -la /etc/x-ui 2>/dev/null; ls -la /usr/local/x-ui 2>/dev/null | head'))

# panel subscription path settings via x-ui binary help? skip
print('\n=== env balancer ===')
print(subprocess.getoutput("grep -E '^(UPSTREAM_|PRIORITY_|MAX_|INCLUDE_|PROFILE_)' /etc/3xui-happ-balancer.env 2>/dev/null"))
con.close()
PY
'''

OUT = r"C:\ZeroVPN\v2rayNG\V2rayNG\server\winter-routing\node_compare.txt"

def run_ssh_key(host: str, key_path: str, label: str) -> str:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(host, username="root", key_filename=key_path, timeout=25, allow_agent=False, look_for_keys=False)
    _, stdout, stderr = client.exec_command(SCRIPT, timeout=120)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    client.close()
    return f"\n\n########## {label} {host} ##########\n" + out + (f"\n---STDERR---\n{err}" if err else "")

def run_ssh_pass(host: str, password: str, label: str) -> str:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(host, username="root", password=password, timeout=25, allow_agent=False, look_for_keys=False)
    _, stdout, stderr = client.exec_command(SCRIPT, timeout=120)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    client.close()
    return f"\n\n########## {label} {host} ##########\n" + out + (f"\n---STDERR---\n{err}" if err else "")

def main():
    parts = []
    if KEY:
        parts.append(run_ssh_key("139.100.225.91", KEY, "MASTER"))
    if MSC_PASS:
        parts.append(run_ssh_pass("194.67.205.140", MSC_PASS, "NODE"))
    text = "".join(parts)
    open(OUT, "w", encoding="utf-8").write(text)
    # ascii preview
    sys.stdout.buffer.write(text.encode("ascii", "replace")[:16000])
    sys.stdout.buffer.write(f"\n\n[full] {OUT}\n".encode())

if __name__ == "__main__":
    main()
