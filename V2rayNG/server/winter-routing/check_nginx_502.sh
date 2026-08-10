#!/bin/bash
set -euo pipefail
SUB=qk13p3uu5farhw5x
echo '=== nginx error log tail ==='
tail -n 40 /var/log/nginx/error.log | grep -iE 'auto|8099|upstream|header|too large|502' || tail -n 20 /var/log/nginx/error.log

echo
echo '=== via nginx Winter-Mobile ==='
curl -sk -D /tmp/h1.txt -o /tmp/b1.bin -A 'Winter-Mobile/1.2.3' --resolve gw.zizmos.ru:443:127.0.0.1 "https://gw.zizmos.ru/auto/${SUB}" -w 'code=%{http_code} size=%{size_download}\n'
tr -d '\r' </tmp/h1.txt | head -20
echo "body_head=$(head -c 120 /tmp/b1.bin | tr '\n' ' ')"

echo
echo '=== via nginx plain Winter ==='
curl -sk -D /tmp/h2.txt -o /tmp/b2.bin -A 'Winter/1.2.3' --resolve gw.zizmos.ru:443:127.0.0.1 "https://gw.zizmos.ru/auto/${SUB}" -w 'code=%{http_code} size=%{size_download}\n'
tr -d '\r' </tmp/h2.txt | head -20

echo
echo '=== via nginx Happ ==='
curl -sk -D /tmp/h3.txt -o /tmp/b3.bin -A 'Happ/1.0' --resolve gw.zizmos.ru:443:127.0.0.1 "https://gw.zizmos.ru/auto/${SUB}" -w 'code=%{http_code} size=%{size_download}\n'
tr -d '\r' </tmp/h3.txt | head -25

echo
echo '=== direct header sizes ==='
python3 - <<'PY'
import urllib.request
for ua in ['Winter-Mobile/1.2.3','Winter/1.2.3','Happ/1.0']:
    req=urllib.request.Request('http://127.0.0.1:8099/auto/qk13p3uu5farhw5x', headers={'User-Agent':ua})
    with urllib.request.urlopen(req, timeout=30) as resp:
        headers=resp.headers
        total=sum(len(k)+len(v)+4 for k,v in headers.items())
        pr=headers.get('Profile-Routing') or ''
        routing=headers.get('Routing') or ''
        print(ua, 'status', resp.status, 'hdr_bytes~', total, 'Profile-Routing', len(pr), 'Routing', len(routing), 'body', len(resp.read()))
PY
