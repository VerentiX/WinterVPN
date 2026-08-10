#!/bin/bash
set -euo pipefail
SUB=${1:-qk13p3uu5farhw5x}
UP=$(grep -E '^UPSTREAM_JSON_BASE=' /etc/3xui-happ-balancer.env | cut -d= -f2-)

python3 - <<PY
import json, socket, ssl, urllib.request
from pathlib import Path

sub = "$SUB"
up = "$UP"

# Resolve how this host reaches sctl:5843
host = "sctl.zizmos.ru"
print("=== resolve from this host ===")
try:
    infos = socket.getaddrinfo(host, 5843, type=socket.SOCK_STREAM)
    addrs = sorted({i[4][0] for i in infos})
    print(host, addrs)
except Exception as e:
    print("resolve fail", e)

req = urllib.request.Request(up + sub, headers={"User-Agent": "3x-ui-happ-sticky-failover/3.4", "Accept": "application/json"})
ctx = ssl.create_default_context()
# panel may use self-signed / custom cert depending on setup; env says VERIFY true
with urllib.request.urlopen(req, context=ctx, timeout=20) as resp:
    print("peer via urllib:", resp.geturl())
    data = json.loads(resp.read().decode())

items = data if isinstance(data, list) else [data]
print("profiles", len(items))

def extract_endpoint(item):
    for o in item.get("outbounds") or []:
        if not isinstance(o, dict):
            continue
        proto = o.get("protocol")
        settings = o.get("settings") or {}
        stream = o.get("streamSettings") or {}
        if proto in ("vless", "vmess", "trojan"):
            vnext = (settings.get("vnext") or [{}])[0]
            users = (vnext.get("users") or [{}])[0]
            return {
                "proto": proto,
                "address": vnext.get("address"),
                "port": vnext.get("port"),
                "security": stream.get("security"),
                "network": stream.get("network"),
                "sni": ((stream.get("realitySettings") or stream.get("tlsSettings") or {}).get("serverName")),
                "fp": ((stream.get("realitySettings") or {}).get("fingerprint")),
                "dest": ((stream.get("realitySettings") or {}).get("dest")),
                "id": users.get("id"),
            }
        if proto in ("hysteria", "hysteria2"):
            return {
                "proto": proto,
                "address": settings.get("address") or stream.get("address"),
                "port": settings.get("port"),
            }
        if proto == "shadowsocks":
            srv = (settings.get("servers") or [{}])[0]
            return {"proto": proto, "address": srv.get("address"), "port": srv.get("port")}
    return {}

print("=== upstream endpoints ===")
for item in items:
    remark = item.get("remarks")
    ep = extract_endpoint(item)
    print(f"{remark}")
    print(f"  {ep}")

# Generated auto config
req2 = urllib.request.Request(
    f"http://127.0.0.1:8099/auto/{sub}",
    headers={"User-Agent": "Winter-Mobile/1.2.3"},
)
with urllib.request.urlopen(req2, timeout=30) as resp:
    raw = json.loads(resp.read().decode())
cfg = raw[0] if isinstance(raw, list) else raw
print("=== generated route endpoints ===")
for o in cfg.get("outbounds") or []:
    tag = str(o.get("tag") or "")
    if not tag.startswith("route-p"):
        continue
    settings = o.get("settings") or {}
    stream = o.get("streamSettings") or {}
    addr = None
    port = None
    if settings.get("vnext"):
        addr = settings["vnext"][0].get("address")
        port = settings["vnext"][0].get("port")
    sni = ((stream.get("realitySettings") or stream.get("tlsSettings") or {}).get("serverName"))
    network = stream.get("network")
    security = stream.get("security")
    print(f"{tag} {o.get('protocol')} {addr}:{port} net={network} sec={security} sni={sni}")

print("=== inspect summary ===")
with urllib.request.urlopen(f"http://127.0.0.1:8099/inspect/{sub}", timeout=30) as resp:
    insp = json.loads(resp.read().decode())
print("strategy", insp.get("strategy"))
print("startupFallbackTag", insp.get("startupFallbackTag"))
print("tiers", insp.get("tiers"))
print("happRouting", (insp.get("happRouting") or {}).get("Name") if isinstance(insp.get("happRouting"), dict) else insp.get("happRouting"))
print("embeddedHappRouting", insp.get("embeddedHappRouting"))
for p in insp.get("profiles") or []:
    print(f"  P{p.get('priority')} #{p.get('failoverOrder')} {p.get('remark')} {p.get('tag')}")
PY

echo
echo '=== is 5843 via local x-ui? ==='
ss -lntp | grep 5843 || true
curl -sk --resolve sctl.zizmos.ru:5843:127.0.0.1 -A 'diag' "https://sctl.zizmos.ru:5843/jsonic/${SUB}" -o /dev/null -w 'local_resolve http=%{http_code} size=%{size_download}\n' || true
curl -sk --resolve sctl.zizmos.ru:5843:104.21.24.237 -A 'diag' "https://sctl.zizmos.ru:5843/jsonic/${SUB}" -o /dev/null -w 'cf_ip http=%{http_code} size=%{size_download}\n' || true
