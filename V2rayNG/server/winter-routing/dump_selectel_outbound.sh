#!/bin/bash
set -euo pipefail
SUB=${1:-qk13p3uu5farhw5x}
python3 - <<PY
import json, urllib.request, ssl
sub="$SUB"
up="https://sctl.zizmos.ru:5843/jsonic/"+sub
ctx=ssl.create_default_context()
with urllib.request.urlopen(urllib.request.Request(up, headers={"User-Agent":"diag"}), context=ctx, timeout=20) as r:
    data=json.loads(r.read().decode())
item=data[0]
print('remarks', item.get('remarks'))
# dump outbound skeleton
for i,o in enumerate(item.get('outbounds') or []):
    print('--- outbound', i, 'tag=', o.get('tag'), 'proto=', o.get('protocol'))
    settings=o.get('settings')
    stream=o.get('streamSettings')
    print(' settings keys', list(settings.keys()) if isinstance(settings, dict) else type(settings))
    if isinstance(settings, dict):
        print(' settings sample', json.dumps(settings, ensure_ascii=False)[:500])
    print(' stream keys', list(stream.keys()) if isinstance(stream, dict) else type(stream))
    if isinstance(stream, dict):
        print(' stream sample', json.dumps({k:stream.get(k) for k in stream if k!='realitySettings'}, ensure_ascii=False)[:400])
        rs=stream.get('realitySettings')
        if isinstance(rs, dict):
            print(' reality', {k:rs.get(k) for k in ('serverName','fingerprint','publicKey','shortId','spiderX','dest','target')})
PY
