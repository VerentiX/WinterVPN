#!/bin/bash
set -euo pipefail
echo "=== env clash ==="
grep -E '^(CLASH_|HAPP_|UPSTREAM_|MAX_)' /etc/3xui-happ-balancer.env || true

echo "=== x-ui subClash settings ==="
python3 <<'PY'
import sqlite3
from pathlib import Path
con=sqlite3.connect('file:/etc/x-ui/x-ui.db?mode=ro', uri=True)
cur=con.cursor()
for k in ['subClashEnable','subClashPath','subClashURI','subClashEnableRouting','subClashUserAgentRegex','subPath','subPort']:
    row=cur.execute('select value from settings where key=?', (k,)).fetchone()
    print(k, '=', (row[0] if row else '')[:300])
row=cur.execute("select value from settings where key='subClashRules'").fetchone()
v=(row[0] if row else '') or ''
Path('/tmp/xui_clash_rules.yaml').write_text(v, encoding='utf-8')
print('subClashRules len', len(v))
con.close()
PY

SUB=qk13p3uu5farhw5x
echo "=== fetch clash bodies ==="
: > /tmp/clash_fetch_log.txt
for url in \
  "http://127.0.0.1:8099/clash/${SUB}" \
  "http://127.0.0.1:8099/clashmeta/${SUB}" \
  "https://sctl.zizmos.ru:5843/clash/${SUB}" \
  "https://gw.zizmos.ru/clash/${SUB}"
do
  code=$(curl -skS -o /tmp/clash_try.yaml -w '%{http_code}' -A 'ClashMeta For Android/2.11.1' --connect-timeout 10 "$url" || echo 000)
  echo "$code $url size=$(wc -c </tmp/clash_try.yaml 2>/dev/null || echo 0)" | tee -a /tmp/clash_fetch_log.txt
  if [ "$code" = "200" ] && [ "$(wc -c </tmp/clash_try.yaml)" -gt 200 ]; then
    cp /tmp/clash_try.yaml /tmp/clash_out.yaml
    echo "USING $url" | tee -a /tmp/clash_fetch_log.txt
    break
  fi
done

# also raw x-ui clash path if different
for url in \
  "https://sctl.zizmos.ru:5843/clash/${SUB}" \
  "https://127.0.0.1:5843/clash/${SUB}"
do
  code=$(curl -skS -o /tmp/clash_xui.yaml -w '%{http_code}' -A 'ClashMeta For Android/2.11.1' --connect-timeout 10 "$url" || echo 000)
  echo "xui $code $url size=$(wc -c </tmp/clash_xui.yaml 2>/dev/null || echo 0)"
  if [ "$code" = "200" ]; then break; fi
done

python3 <<'PY'
import re
from pathlib import Path

def scrub(s: str) -> str:
    s=re.sub(r'(?im)^(\s*(uuid|password|private-key|public-key|short-id|auth|token|server|servername|sni):\s*).+$', r'\1***', s)
    s=re.sub(r'(vless|hy2|hysteria2|ss|trojan)://\S+', r'\1://***', s, flags=re.I)
    return s

def rules_list(s: str):
    lines=s.splitlines()
    out=[]
    in_rules=False
    for line in lines:
        if re.match(r'^rules:\s*$', line):
            in_rules=True
            continue
        if not in_rules:
            continue
        if re.match(r'^[A-Za-z0-9_-]+:\s*$', line) and not line.strip().startswith('-'):
            break
        if line.strip().startswith('-'):
            out.append(line.strip())
        elif not line.strip():
            continue
        else:
            # indented continuation unlikely
            if line.startswith(' ') and out:
                continue
            break
    return out

def providers(s: str):
    if 'rule-providers:' not in s:
        return []
    block=s.split('rule-providers:',1)[1]
    if '\nrules:' in block:
        block=block.split('\nrules:',1)[0]
    return re.findall(r'(?m)^  ([A-Za-z0-9_-]+):\s*$', block)

def summarize(path: str, label: str):
    p=Path(path)
    if not p.exists() or p.stat().st_size < 50:
        print(f'### {label}: missing/empty')
        return
    text=p.read_text(encoding='utf-8', errors='replace')
    print(f'\n### {label} size={len(text)}')
    for key in ['mixed-port','tun:','dns:','proxy-groups:','rule-providers:','rules:','proxies:']:
        print(' ', key, key.rstrip(':') in text or key in text)
    rs=rules_list(text)
    print(' rules count', len(rs))
    for r in rs:
        print('  ', r)
    prov=providers(text)
    print(' providers', len(prov), prov)
    for needle in ['ozon','gosuslugi','nalog','category-ru','whitelist','googleapis','mtalk','google-play','MATCH,PROXY','MATCH,DIRECT']:
        print(f'  hit {needle}:', len(re.findall(needle, text, flags=re.I)))
    # show dns/nameserver snippet
    m=re.search(r'(?ms)^dns:.*?(?=^[a-z]|\Z)', text)
    if m:
        print(' dns head:\n', scrub(m.group(0)[:700]))

served=Path('/tmp/clash_out.yaml')
xui_live=Path('/tmp/clash_xui.yaml')
template=Path('/tmp/xui_clash_rules.yaml')
summarize(str(served), 'BALANCER_OR_FIRST_200')
summarize(str(xui_live), 'XUI_CLASH_ENDPOINT')
summarize(str(template), 'XUI_SUBCLASHRULES_TEMPLATE')

# compare rules
if served.exists() and template.exists() and served.stat().st_size>50 and template.stat().st_size>50:
    sr=rules_list(served.read_text(encoding='utf-8', errors='replace'))
    tr=rules_list(template.read_text(encoding='utf-8', errors='replace'))
    print('\n### compare served vs template rules')
    print('equal', sr==tr)
    if sr!=tr:
        only_t=set(tr)-set(sr)
        only_s=set(sr)-set(tr)
        print('only template', sorted(only_t)[:30])
        print('only served', sorted(only_s)[:30])
PY
