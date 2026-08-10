#!/bin/bash
set -euo pipefail
TS=$(date +%Y%m%d%H%M%S)
SRC=/opt/3xui-happ-balancer/app.py.new-winter
DST=/opt/3xui-happ-balancer/app.py
if [[ ! -f "$SRC" ]]; then
  echo "missing $SRC" >&2
  exit 1
fi
cp -a "$DST" "/opt/3xui-happ-balancer/app.py.bak-before-winter-mobile-${TS}"
install -o root -g root -m 644 "$SRC" "$DST"
chown -R root:root /etc/winter-routing
chmod 644 /etc/winter-routing/*.json
python3 -m py_compile "$DST"
grep -q 'auto_format_is_winter_mobile' "$DST"
grep -q 'Profile-Routing' "$DST"
systemctl restart 3xui-happ-balancer.service
sleep 1
systemctl is-active 3xui-happ-balancer.service
curl -sS http://127.0.0.1:8099/health
echo
# Pick a real subscription id from recent nginx access logs if present
SUB=$(grep -oE '/auto/[A-Za-z0-9_-]{8,}' /var/log/nginx/access.log 2>/dev/null | tail -1 | sed 's#.*/##') || true
if [[ -n "${SUB:-}" ]]; then
  echo "Using sub=$SUB"
  echo "=== Winter-Mobile ==="
  curl -sSI -A 'Winter-Mobile/1.2.3' "http://127.0.0.1:8099/auto/${SUB}" | tr -d '\r' | grep -iE 'HTTP/|Profile-Routing|X-Winter|X-Auto|content-type' || true
  echo "=== Legacy Winter ==="
  curl -sSI -A 'Winter/1.2.3' "http://127.0.0.1:8099/auto/${SUB}" | tr -d '\r' | grep -iE 'HTTP/|Profile-Routing|X-Winter|X-Auto|content-type' || true
else
  echo "No sub id found in nginx logs; compile/restart ok"
fi
