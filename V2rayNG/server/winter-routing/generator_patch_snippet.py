# Winter dual-routing support for /auto/{sub_id}
#
# When User-Agent contains "Winter", the generator attaches:
#   Profile-Routing: base64:<json with default + whitelist Happ profiles>
#
# Edit the JSON files under /etc/winter-routing/ to change routing without
# redeploying the app. Winter clients refresh them on subscription update.

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


def auto_format_is_winter(request: Request) -> bool:
    user_agent = request.headers.get("user-agent", "").lower()
    return "winter" in user_agent


def load_winter_happ_profile(kind: str) -> dict[str, Any] | None:
    """Load Happ-style profile JSON for Winter dual routing."""
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


def attach_winter_routing_headers(
    headers: dict[str, str],
    request: Request,
    upstream: httpx.Headers | None = None,
) -> dict[str, str]:
    if not auto_format_is_winter(request):
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
    return headers
