from pathlib import Path

path = Path(r"C:\ZeroVPN\v2rayNG\V2rayNG\server\winter-routing\app.live.py")
text = path.read_text(encoding="utf-8")

needle = '''HAPP_ROUTING_ENABLE = env_bool(
    "HAPP_ROUTING_ENABLE",
    bool(HAPP_ROUTING_LINK),
)
'''
insert_cfg = needle + '''
WINTER_ROUTING_DIR = Path(
    os.getenv("WINTER_ROUTING_DIR", "/etc/winter-routing")
)
WINTER_WHITELIST_MIN_PRIORITY = int(
    os.getenv("WINTER_WHITELIST_MIN_PRIORITY", "5")
)
HAPP_ROUTING_WHITELIST_LINK = os.getenv(
    "HAPP_ROUTING_WHITELIST_LINK",
    "",
).strip()
'''
if "WINTER_ROUTING_DIR" not in text:
    if needle not in text:
        raise SystemExit("config needle not found")
    text = text.replace(needle, insert_cfg, 1)

helpers = '''
def auto_format_is_winter_mobile(request: Request) -> bool:
    """Only Winter-Mobile gets dual Profile-Routing (not legacy Winter / Desktop)."""
    user_agent = request.headers.get("user-agent", "").lower()
    return "winter-mobile" in user_agent


def load_winter_happ_profile(kind: str) -> dict[str, Any] | None:
    path = WINTER_ROUTING_DIR / f"{kind}.json"
    if path.exists():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError(
                f"Unable to read Winter routing profile {path}: {exc}"
            ) from exc
        if isinstance(data, dict):
            return data

    if kind == "default":
        link = HAPP_ROUTING_LINK
    elif kind == "whitelist":
        link = HAPP_ROUTING_WHITELIST_LINK
    else:
        link = ""
    if link:
        return decode_happ_routing_profile(link)
    return None


def build_winter_profile_routing_header(
    default_profile: dict[str, Any] | None = None,
    whitelist_profile: dict[str, Any] | None = None,
) -> str | None:
    default = default_profile or load_winter_happ_profile("default")
    whitelist = whitelist_profile or load_winter_happ_profile("whitelist")
    if not default or not whitelist:
        return None
    payload = {
        "whitelistMinPriority": WINTER_WHITELIST_MIN_PRIORITY,
        "default": default,
        "whitelist": whitelist,
    }
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    encoded = base64.urlsafe_b64encode(raw.encode("utf-8")).decode("ascii").rstrip("=")
    return f"base64:{encoded}"


def attach_winter_mobile_routing_headers(
    headers: dict[str, str],
    request: Request,
    upstream: httpx.Headers | None = None,
) -> dict[str, str]:
    if not auto_format_is_winter_mobile(request):
        return headers

    default_profile = load_winter_happ_profile("default")
    if default_profile is None and upstream is not None:
        default_profile = decode_happ_routing_profile(
            routing_link_from_headers(upstream)
        )
    whitelist_profile = load_winter_happ_profile("whitelist")
    header_value = build_winter_profile_routing_header(
        default_profile,
        whitelist_profile,
    )
    if header_value:
        headers["Profile-Routing"] = header_value
        headers["X-Winter-Routing"] = "default,whitelist"
        headers["X-Winter-Client"] = "mobile"
    return headers

'''

if "def auto_format_is_winter_mobile" not in text:
    marker = "def auto_format_is_clash(request: Request) -> bool:"
    if marker not in text:
        raise SystemExit("clash helper marker not found")
    text = text.replace(marker, helpers + marker, 1)

old = '''    is_onexray = auto_format_is_onexray(request)
    response = JSONResponse(
        content=merged if is_onexray else [merged],
        headers=copy_subscription_headers(
            upstream.headers,
            manifest,
        ),
    )
    response.headers["X-Auto-Format"] = (
        "onexray" if is_onexray else "xray"
    )
    if "incy" in request.headers.get("user-agent", "").lower():
        response.headers["no-limit-enabled"] = "1"
    return response
'''
new = '''    is_onexray = auto_format_is_onexray(request)
    response_headers = copy_subscription_headers(
        upstream.headers,
        manifest,
    )
    response_headers = attach_winter_mobile_routing_headers(
        response_headers,
        request,
        upstream.headers,
    )
    response = JSONResponse(
        content=merged if is_onexray else [merged],
        headers=response_headers,
    )
    response.headers["X-Auto-Format"] = (
        "onexray" if is_onexray else "xray"
    )
    if "incy" in request.headers.get("user-agent", "").lower():
        response.headers["no-limit-enabled"] = "1"
    return response
'''
if "attach_winter_mobile_routing_headers" not in text:
    if old not in text:
        raise SystemExit("balanced_subscription block not found")
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("patched ok")
print("winter-mobile helper", "auto_format_is_winter_mobile" in text)
print("attach call", "attach_winter_mobile_routing_headers" in text)
