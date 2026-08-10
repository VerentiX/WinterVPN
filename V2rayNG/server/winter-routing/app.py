from __future__ import annotations

import base64
import copy
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any

import httpx
import yaml
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, Response


def env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


UPSTREAM_JSON_BASE = os.getenv(
    "UPSTREAM_JSON_BASE",
    "https://gw.zizmos.ru:5843/json/",
).rstrip("/") + "/"

UPSTREAM_VERIFY_TLS = env_bool("UPSTREAM_VERIFY_TLS", True)
UPSTREAM_TIMEOUT_SECONDS = float(os.getenv("UPSTREAM_TIMEOUT_SECONDS", "15"))

PROFILE_REMARK = os.getenv(
    "PROFILE_REMARK",
    "Zizmos — приоритетный автоматический выбор",
)
HEALTH_DESTINATION = os.getenv(
    "HEALTH_DESTINATION",
    "https://gw.zizmos.ru/generate_204",
)
HEALTH_CONNECTIVITY = os.getenv(
    "HEALTH_CONNECTIVITY",
    "",
)
HEALTH_INTERVAL = os.getenv("HEALTH_INTERVAL", "1m")
HEALTH_SAMPLING = int(os.getenv("HEALTH_SAMPLING", "2"))
HEALTH_TIMEOUT = os.getenv("HEALTH_TIMEOUT", "4s")
HEALTH_METHOD = os.getenv("HEALTH_METHOD", "GET").strip().upper()

CLASH_HEALTH_URL = os.getenv(
    "CLASH_HEALTH_URL",
    "https://www.gstatic.com/generate_204",
)
CLASH_HEALTH_INTERVAL = int(os.getenv("CLASH_HEALTH_INTERVAL", "60"))
CLASH_HEALTH_TIMEOUT_MS = int(
    os.getenv("CLASH_HEALTH_TIMEOUT_MS", "5000")
)
CLASH_MAX_FAILED_TIMES = int(
    os.getenv("CLASH_MAX_FAILED_TIMES", "2")
)
CLASH_MAX_PRIORITY = int(os.getenv("CLASH_MAX_PRIORITY", "4"))

LEASTLOAD_EXPECTED = int(os.getenv("LEASTLOAD_EXPECTED", "1"))
LEASTLOAD_MAX_RTT = os.getenv("LEASTLOAD_MAX_RTT", "").strip()
LEASTLOAD_TOLERANCE = float(os.getenv("LEASTLOAD_TOLERANCE", "0.60"))

TCP_KEEPALIVE_IDLE = int(os.getenv("TCP_KEEPALIVE_IDLE", "30"))
TCP_KEEPALIVE_INTERVAL = int(os.getenv("TCP_KEEPALIVE_INTERVAL", "10"))
TCP_USER_TIMEOUT = int(os.getenv("TCP_USER_TIMEOUT", "15000"))
TCP_FAST_OPEN = env_bool("TCP_FAST_OPEN", True)
HAPPY_EYEBALLS_DELAY_MS = int(os.getenv("HAPPY_EYEBALLS_DELAY_MS", "250"))
PRIORITY_CONFIG_PATH = Path(
    os.getenv(
        "PRIORITY_CONFIG_PATH",
        "/etc/3xui-happ-priorities.json",
    )
)
INCLUDE_PROTOCOLS_RAW = os.getenv("INCLUDE_PROTOCOLS", "vless,hysteria")
MAX_PROFILES = int(os.getenv("MAX_PROFILES", "64"))

HAPP_ROUTING_LINK = os.getenv("HAPP_ROUTING_LINK", "").strip()
HAPP_ROUTING_ENABLE = env_bool(
    "HAPP_ROUTING_ENABLE",
    bool(HAPP_ROUTING_LINK),
)

HAPP_FORCE_DIRECT_DOMAINS = [
    item.strip()
    for item in os.getenv(
        "HAPP_FORCE_DIRECT_DOMAINS",
        "",
    ).split(",")
    if item.strip()
]

SUB_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,128}$")

INCLUDE_PROTOCOLS = {
    item.strip().lower()
    for item in INCLUDE_PROTOCOLS_RAW.split(",")
    if item.strip()
}

COPY_RESPONSE_HEADERS = (
    "subscription-userinfo",
    "profile-update-interval",
    "profile-title",
    "support-url",
    "profile-web-page-url",
    "announce",
    "hide-settings",
)

DEFAULT_PRIORITY_CONFIG: dict[str, Any] = {
    "defaultPriority": 0,
    "unmatchedBypassPriority": 900,
    "markerPattern": r"\[P(\d{1,4})\]",
    "bypassPattern": r"(?i)\bобход\b",
    "finalFallbackTag": "primary",
    "startupFallbackPattern": r"(?i)selectel\s*-\s*1\b",
    "rules": [
        {
            "priority": 10,
            "pattern": r"(?i)\bобход\s*-\s*yandex\b",
        },
        {
            "priority": 20,
            "pattern": r"(?i)\bобход\s*-\s*vk\b",
        },
        {
            "priority": 30,
            "pattern": r"(?i)\bобход\s*-\s*(?:time\s*web|timeweb)\b",
        },
    ],
}

app = FastAPI(
    title="3x-ui Happ Priority Balancer",
    version="3.4.0",
    docs_url=None,
    redoc_url=None,
)



def routing_link_from_headers(source: httpx.Headers) -> str:
    return (source.get("routing") or HAPP_ROUTING_LINK).strip()


def decode_happ_routing_profile(
    routing_link: str,
) -> dict[str, Any] | None:
    if not routing_link:
        return None

    prefixes = (
        "happ://routing/onadd/",
        "happ://routing/add/",
    )
    encoded = ""
    for prefix in prefixes:
        if routing_link.startswith(prefix):
            encoded = routing_link[len(prefix):]
            break

    if not encoded:
        return None

    try:
        padded = encoded + ("=" * (-len(encoded) % 4))
        decoded = base64.urlsafe_b64decode(
            padded.encode("ascii")
        ).decode("utf-8")
        profile = json.loads(decoded)
    except (
        ValueError,
        UnicodeDecodeError,
        json.JSONDecodeError,
    ) as exc:
        raise ValueError(
            f"Unable to decode Happ routing profile: {exc}"
        ) from exc

    if not isinstance(profile, dict):
        raise ValueError("Happ routing profile must be a JSON object")

    return profile


def profile_bool(
    profile: dict[str, Any],
    key: str,
    default: bool,
) -> bool:
    value = profile.get(key)
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


def profile_string_list(
    profile: dict[str, Any],
    key: str,
) -> list[str]:
    value = profile.get(key)
    if not isinstance(value, list):
        return []
    return [
        str(item).strip()
        for item in value
        if str(item).strip()
    ]


def is_broad_proxy_rule(rule: dict[str, Any]) -> bool:
    if rule.get("outboundTag") != "proxy":
        return False
    if "inboundTag" in rule:
        return False

    match_fields = {
        "domain",
        "ip",
        "port",
        "source",
        "sourcePort",
        "protocol",
        "attrs",
        "user",
    }
    if any(field in rule for field in match_fields):
        return False

    network = str(rule.get("network", "")).replace(" ", "")
    return network in {"", "tcp,udp", "udp,tcp"}


def build_happ_xray_rules(
    profile: dict[str, Any] | None,
) -> tuple[list[dict[str, Any]], str]:
    if not profile:
        forced_rules: list[dict[str, Any]] = []
        if HAPP_FORCE_DIRECT_DOMAINS:
            forced_rules.append(
                {
                    "type": "field",
                    "domain": HAPP_FORCE_DIRECT_DOMAINS,
                    "outboundTag": "direct",
                }
            )
        return forced_rules, "proxy"

    action_fields = {
        "block": ("BlockSites", "BlockIp", "block"),
        "proxy": ("ProxySites", "ProxyIp", "proxy"),
        "direct": ("DirectSites", "DirectIp", "direct"),
    }

    route_order_raw = str(
        profile.get("RouteOrder") or "block-proxy-direct"
    )
    route_order = [
        item.strip().lower()
        for item in route_order_raw.split("-")
        if item.strip().lower() in action_fields
    ]

    for action in ("block", "proxy", "direct"):
        if action not in route_order:
            route_order.append(action)

    rules: list[dict[str, Any]] = []

    if HAPP_FORCE_DIRECT_DOMAINS:
        rules.append(
            {
                "type": "field",
                "domain": HAPP_FORCE_DIRECT_DOMAINS,
                "outboundTag": "direct",
            }
        )

    for action in route_order:
        site_key, ip_key, outbound_tag = action_fields[action]
        domains = profile_string_list(profile, site_key)
        ips = profile_string_list(profile, ip_key)

        if domains:
            rules.append(
                {
                    "type": "field",
                    "domain": domains,
                    "outboundTag": outbound_tag,
                }
            )

        if ips:
            rules.append(
                {
                    "type": "field",
                    "ip": ips,
                    "outboundTag": outbound_tag,
                }
            )

    default_outbound = (
        "proxy"
        if profile_bool(profile, "GlobalProxy", True)
        else "direct"
    )
    return rules, default_outbound

def normalize_config_list(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, dict):
        return [payload]

    if not isinstance(payload, list):
        raise ValueError("3x-ui returned neither a JSON object nor an array")

    configs = [item for item in payload if isinstance(item, dict)]
    if not configs:
        raise ValueError("3x-ui returned an empty configuration array")

    if len(configs) > MAX_PROFILES:
        raise ValueError(
            f"Too many profiles: {len(configs)}; limit is {MAX_PROFILES}"
        )

    return configs


def load_priority_config() -> dict[str, Any]:
    config = copy.deepcopy(DEFAULT_PRIORITY_CONFIG)

    if PRIORITY_CONFIG_PATH.exists():
        try:
            custom = json.loads(
                PRIORITY_CONFIG_PATH.read_text(encoding="utf-8")
            )
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError(
                f"Unable to read priority config {PRIORITY_CONFIG_PATH}: {exc}"
            ) from exc

        if not isinstance(custom, dict):
            raise ValueError("Priority config root must be a JSON object")

        for key in (
            "defaultPriority",
            "unmatchedBypassPriority",
            "markerPattern",
            "bypassPattern",
            "finalFallbackTag",
            "startupFallbackPattern",
            "rules",
        ):
            if key in custom:
                config[key] = custom[key]

    try:
        re.compile(str(config["markerPattern"]))
        re.compile(str(config["bypassPattern"]))
        re.compile(str(config["startupFallbackPattern"]))
        for rule in config.get("rules", []):
            if not isinstance(rule, dict):
                raise ValueError("Each priority rule must be an object")
            re.compile(str(rule["pattern"]))
            int(rule["priority"])
    except (re.error, KeyError, TypeError, ValueError) as exc:
        raise ValueError(f"Invalid priority configuration: {exc}") from exc

    config["defaultPriority"] = int(config["defaultPriority"])
    config["unmatchedBypassPriority"] = int(
        config["unmatchedBypassPriority"]
    )

    if not str(config["finalFallbackTag"]):
        raise ValueError("finalFallbackTag must not be empty")

    return config


def find_proxy_outbound(
    config: dict[str, Any],
) -> dict[str, Any] | None:
    outbounds = config.get("outbounds")
    if not isinstance(outbounds, list):
        return None

    for outbound in outbounds:
        if isinstance(outbound, dict) and outbound.get("tag") == "proxy":
            return outbound

    system_protocols = {"freedom", "blackhole", "dns", "loopback"}
    for outbound in outbounds:
        if not isinstance(outbound, dict):
            continue
        protocol = str(outbound.get("protocol", "")).lower()
        if protocol and protocol not in system_protocols:
            return outbound

    return None


def resolve_priority(
    remark: str,
    priority_config: dict[str, Any],
) -> int:
    marker_re = re.compile(str(priority_config["markerPattern"]))
    marker = marker_re.search(remark)
    if marker:
        return int(marker.group(1))

    for rule in priority_config.get("rules", []):
        if re.search(str(rule["pattern"]), remark):
            return int(rule["priority"])

    if re.search(str(priority_config["bypassPattern"]), remark):
        return int(priority_config["unmatchedBypassPriority"])

    return int(priority_config["defaultPriority"])


def outbound_identity(
    remark: str,
    outbound: dict[str, Any],
) -> str:
    stream = outbound.get("streamSettings")
    settings = outbound.get("settings")
    identity = {
        "remark": remark,
        "protocol": outbound.get("protocol"),
        "settings": settings,
        "streamSettings": stream,
    }
    encoded = json.dumps(
        identity,
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()[:12]


def priority_key(priority: int) -> str:
    return f"p{priority:04d}"



def apply_mobile_sockopts(
    outbound: dict[str, Any],
) -> dict[str, Any]:
    """Add conservative TCP recovery options without overwriting custom values."""
    protocol = str(outbound.get("protocol", "")).lower()
    stream = outbound.get("streamSettings")
    if not isinstance(stream, dict):
        return outbound

    network = str(stream.get("network", "")).lower()
    if protocol == "hysteria" or network == "hysteria":
        return outbound

    sockopt = stream.get("sockopt")
    if not isinstance(sockopt, dict):
        sockopt = {}
        stream["sockopt"] = sockopt

    sockopt.setdefault("tcpKeepAliveIdle", TCP_KEEPALIVE_IDLE)
    sockopt.setdefault("tcpKeepAliveInterval", TCP_KEEPALIVE_INTERVAL)
    sockopt.setdefault("tcpUserTimeout", TCP_USER_TIMEOUT)
    # Fast Open is negotiated by both endpoints and silently falls back to a
    # normal TCP handshake when an Android kernel or the network does not
    # support it. It mainly improves new connections after a network switch.
    sockopt.setdefault("tcpFastOpen", TCP_FAST_OPEN)

    happy = sockopt.get("happyEyeballs")
    if not isinstance(happy, dict):
        happy = {}
        sockopt["happyEyeballs"] = happy

    happy.setdefault("prioritizeIPv6", False)
    happy.setdefault("tryDelayMs", HAPPY_EYEBALLS_DELAY_MS)
    happy.setdefault("interleave", 1)
    happy.setdefault("maxConcurrentTry", 4)

    return outbound


def least_load_strategy() -> dict[str, Any]:
    settings: dict[str, Any] = {
        "expected": LEASTLOAD_EXPECTED,
        "tolerance": LEASTLOAD_TOLERANCE,
    }
    # In sticky failover mode latency is diagnostic, not a reason to leave a
    # working route. An explicit environment value can restore an RTT ceiling.
    if LEASTLOAD_MAX_RTT:
        settings["maxRTT"] = LEASTLOAD_MAX_RTT
    return {
        "type": "leastLoad",
        "settings": settings,
    }


def select_startup_fallback(
    candidates: list[dict[str, Any]],
    primary_priority: int,
    priority_config: dict[str, Any],
) -> dict[str, Any]:
    primary_candidates = [
        item
        for item in candidates
        if int(item["priority"]) == primary_priority
    ]
    if not primary_candidates:
        raise ValueError("Primary priority group is empty")

    pattern = str(priority_config["startupFallbackPattern"])
    for item in primary_candidates:
        if re.search(pattern, str(item["remark"])):
            return item

    return primary_candidates[0]


def order_sticky_candidates(
    candidates: list[dict[str, Any]],
    priority_config: dict[str, Any],
) -> list[dict[str, Any]]:
    """Return a deterministic failover order with the preferred primary first."""
    primary_priority = min(int(item["priority"]) for item in candidates)
    preferred = select_startup_fallback(
        candidates,
        primary_priority,
        priority_config,
    )
    return [preferred] + [item for item in candidates if item is not preferred]

def build_candidates(
    configs: list[dict[str, Any]],
    priority_config: dict[str, Any],
) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    used_tags: set[str] = set()

    for source_index, config in enumerate(configs, start=1):
        source_outbound = find_proxy_outbound(config)
        if source_outbound is None:
            continue

        protocol = str(source_outbound.get("protocol", "")).lower()
        if INCLUDE_PROTOCOLS and protocol not in INCLUDE_PROTOCOLS:
            continue

        remark = str(config.get("remarks") or f"profile-{source_index}")
        priority = resolve_priority(remark, priority_config)
        pkey = priority_key(priority)
        digest = outbound_identity(remark, source_outbound)
        base_tag = f"route-{pkey}-{digest}"
        tag = base_tag
        suffix = 2

        while tag in used_tags:
            tag = f"{base_tag}-{suffix}"
            suffix += 1

        used_tags.add(tag)

        outbound = copy.deepcopy(source_outbound)
        outbound["tag"] = tag
        outbound = apply_mobile_sockopts(outbound)

        candidates.append(
            {
                "remark": remark,
                "priority": priority,
                "protocol": protocol,
                "tag": tag,
                "outbound": outbound,
            }
        )

    if not candidates:
        raise ValueError(
            "No suitable proxy outbounds remained after filtering"
        )

    candidates.sort(
        key=lambda item: (
            int(item["priority"]),
            str(item["remark"]).casefold(),
            str(item["tag"]),
        )
    )
    return candidates


def xray_vless_credentials(
    settings: dict[str, Any],
) -> tuple[str, int, dict[str, Any]]:
    """Read both 3x-ui's flat VLESS schema and Xray's standard vnext schema."""
    if isinstance(settings.get("vnext"), list) and settings["vnext"]:
        server_item = settings["vnext"][0]
        if not isinstance(server_item, dict):
            raise ValueError("Invalid VLESS vnext server")
        users = server_item.get("users")
        if (
            not isinstance(users, list)
            or not users
            or not isinstance(users[0], dict)
        ):
            raise ValueError("VLESS vnext server has no user")
        return (
            str(server_item.get("address") or ""),
            int(server_item.get("port") or 0),
            users[0],
        )

    return (
        str(settings.get("address") or ""),
        int(settings.get("port") or 0),
        settings,
    )


def xray_xhttp_to_mihomo(
    source: dict[str, Any],
) -> dict[str, Any]:
    """Translate Xray camelCase XHTTP options to Mihomo's kebab-case schema."""
    key_map = {
        "path": "path",
        "host": "host",
        "mode": "mode",
        "headers": "headers",
        "noGRPCHeader": "no-grpc-header",
        "xPaddingBytes": "x-padding-bytes",
        "xPaddingObfsMode": "x-padding-obfs-mode",
        "xPaddingKey": "x-padding-key",
        "xPaddingHeader": "x-padding-header",
        "xPaddingPlacement": "x-padding-placement",
        "xPaddingMethod": "x-padding-method",
        "uplinkHTTPMethod": "uplink-http-method",
        "sessionIDPlacement": "session-placement",
        "sessionIDKey": "session-key",
        "sessionIDTable": "session-table",
        "sessionIDLength": "session-length",
        "seqPlacement": "seq-placement",
        "seqKey": "seq-key",
        "uplinkDataPlacement": "uplink-data-placement",
        "uplinkDataKey": "uplink-data-key",
        "uplinkChunkSize": "uplink-chunk-size",
        "scMaxEachPostBytes": "sc-max-each-post-bytes",
        "scMinPostsIntervalMs": "sc-min-posts-interval-ms",
    }
    result: dict[str, Any] = {}
    for source_key, target_key in key_map.items():
        value = source.get(source_key)
        if value is None or value == "":
            continue
        if (
            source_key == "sessionIDTable"
            and value
            == "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        ):
            value = "Base62"
        result[target_key] = copy.deepcopy(value)
    return result


def candidate_to_mihomo(candidate: dict[str, Any]) -> dict[str, Any]:
    outbound = candidate["outbound"]
    protocol = str(outbound.get("protocol", "")).lower()
    if protocol != "vless":
        raise ValueError(
            f"Clash converter does not support protocol {protocol!r}"
        )

    settings = outbound.get("settings")
    if not isinstance(settings, dict):
        raise ValueError("VLESS outbound settings are missing")
    address, port, user = xray_vless_credentials(settings)
    user_id = str(user.get("id") or "")
    if not address or not port or not user_id:
        raise ValueError("VLESS outbound has incomplete credentials")

    proxy: dict[str, Any] = {
        "name": str(candidate["remark"]),
        "type": "vless",
        "server": address,
        "port": port,
        "uuid": user_id,
        "udp": True,
        "packet-encoding": "xudp",
        "encryption": (
            ""
            if str(
                user.get("encryption")
                or settings.get("encryption")
                or "none"
            )
            == "none"
            else str(
                user.get("encryption")
                or settings.get("encryption")
            )
        ),
    }

    flow = str(user.get("flow") or settings.get("flow") or "")
    if flow:
        proxy["flow"] = flow

    stream = outbound.get("streamSettings")
    if not isinstance(stream, dict):
        stream = {}
    network = str(stream.get("network") or "tcp").lower()
    proxy["network"] = network

    security = str(stream.get("security") or "").lower()
    tls_settings = stream.get("tlsSettings")
    reality_settings = stream.get("realitySettings")

    if security == "reality" and isinstance(reality_settings, dict):
        proxy["tls"] = True
        servername = str(reality_settings.get("serverName") or "")
        if servername:
            proxy["servername"] = servername
        fingerprint = str(reality_settings.get("fingerprint") or "")
        if fingerprint:
            proxy["client-fingerprint"] = fingerprint
        proxy["reality-opts"] = {
            "public-key": str(reality_settings.get("publicKey") or ""),
            "short-id": str(reality_settings.get("shortId") or ""),
        }
    elif security == "tls" and isinstance(tls_settings, dict):
        proxy["tls"] = True
        servername = str(tls_settings.get("serverName") or "")
        if servername:
            proxy["servername"] = servername
        alpn = tls_settings.get("alpn")
        if isinstance(alpn, list) and alpn:
            proxy["alpn"] = copy.deepcopy(alpn)
        fingerprint = str(
            tls_settings.get("fingerprint")
            or (tls_settings.get("settings") or {}).get("fingerprint")
            or ""
        )
        if fingerprint:
            proxy["client-fingerprint"] = fingerprint
        proxy["skip-cert-verify"] = bool(
            tls_settings.get("allowInsecure", False)
        )

    if network == "xhttp":
        xhttp = stream.get("xhttpSettings")
        if not isinstance(xhttp, dict):
            raise ValueError("XHTTP outbound has no xhttpSettings")
        proxy["xhttp-opts"] = xray_xhttp_to_mihomo(xhttp)

    return proxy


def build_clash_config(
    configs: list[dict[str, Any]],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    priority_config = load_priority_config()
    candidates = [
        item
        for item in build_candidates(configs, priority_config)
        if 0 <= int(item["priority"]) <= CLASH_MAX_PRIORITY
        and str(item["protocol"]).lower() == "vless"
    ]
    if not candidates:
        raise ValueError(
            f"No supported profiles with priority P0-P{CLASH_MAX_PRIORITY}"
        )

    # build_candidates already sorts by priority and name. A Clash fallback
    # group always chooses the first healthy member, so this implements the
    # strict P0 -> P1 -> ... -> P4 order without latency-based hopping.
    proxies = [candidate_to_mihomo(item) for item in candidates]
    used_names: dict[str, int] = {}
    for proxy in proxies:
        base_name = str(proxy["name"])
        used_names[base_name] = used_names.get(base_name, 0) + 1
        if used_names[base_name] > 1:
            proxy["name"] = f"{base_name} ({used_names[base_name]})"

    proxy_names = [str(item["name"]) for item in proxies]
    group_name = "🇷🇺 АВТОВЫБОР P0-P4"
    vpn_group = "🛡️ VPN"
    youtube_group = "📺 YouTube"
    discord_group = "💬 Discord"
    games_group = "🎮 Игры"

    rule_provider_base = (
        "https://cdn.jsdelivr.net/gh/hydraponique/"
    )
    geosite_base = (
        f"{rule_provider_base}roscomvpn-geosite/release/mihomo/"
    )
    geoip_base = (
        f"{rule_provider_base}roscomvpn-geoip/release/mihomo/"
    )

    def domain_provider(name: str, *, interval: int = 86400) -> dict[str, Any]:
        return {
            "type": "http",
            "behavior": "domain",
            "format": "mrs",
            "url": f"{geosite_base}{name}.mrs",
            "path": f"./ruleset/geosite-{name}.mrs",
            "proxy": group_name,
            "interval": interval,
        }

    rule_providers: dict[str, dict[str, Any]] = {
        "private-domains": domain_provider("private", interval=2592000),
        "category-ru": domain_provider("category-ru"),
        "whitelist": domain_provider("whitelist"),
        "microsoft": domain_provider("microsoft"),
        "apple": domain_provider("apple"),
        "google-play": domain_provider("google-play"),
        "epicgames": domain_provider("epicgames"),
        "origin": domain_provider("origin"),
        "riot": domain_provider("riot"),
        "escapefromtarkov": domain_provider("escapefromtarkov"),
        "steam": domain_provider("steam"),
        "twitch": domain_provider("twitch"),
        "pinterest": domain_provider("pinterest"),
        "faceit": domain_provider("faceit"),
        "github": domain_provider("github"),
        "twitch-ads": domain_provider("twitch-ads"),
        "youtube": domain_provider("youtube"),
        "telegram": domain_provider("telegram"),
        "win-spy": domain_provider("win-spy"),
        "torrent-domains": domain_provider("torrent"),
        "category-ads": domain_provider("category-ads"),
        "private-ips": {
            "type": "http",
            "behavior": "ipcidr",
            "format": "mrs",
            "url": f"{geoip_base}private.mrs",
            "path": "./ruleset/geoip-private.mrs",
            "proxy": group_name,
            "interval": 2592000,
        },
        "direct-ips": {
            "type": "http",
            "behavior": "ipcidr",
            "format": "mrs",
            "url": f"{geoip_base}direct.mrs",
            "path": "./ruleset/direct-ips.mrs",
            "proxy": group_name,
            "interval": 86400,
        },
        "torrent-clients": {
            "type": "http",
            "behavior": "classical",
            "format": "yaml",
            "url": (
                "https://raw.githubusercontent.com/legiz-ru/"
                "mihomo-rule-sets/main/other/torrent-clients.yaml"
            ),
            "path": "./ruleset/torrent-clients.yaml",
            "proxy": group_name,
            "interval": 86400,
        },
        "games": {
            "type": "http",
            "behavior": "classical",
            "format": "yaml",
            "url": (
                "https://raw.githubusercontent.com/roscomvpn/"
                "custom-category/release/mihomo/games.yaml"
            ),
            "path": "./ruleset/games.yaml",
            "proxy": group_name,
            "interval": 86400,
        },
        "ru-apps": {
            "type": "http",
            "behavior": "classical",
            "format": "yaml",
            "url": (
                "https://raw.githubusercontent.com/roscomvpn/"
                "custom-category/release/mihomo/ru-apps.yaml"
            ),
            "path": "./ruleset/ru-apps.yaml",
            "proxy": group_name,
            "interval": 86400,
        },
    }

    private_networks = [
        "224.0.0.0/3",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "100.64.0.0/10",
        "172.16.0.0/12",
        "169.254.0.0/16",
        "192.168.0.0/16",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.88.99.0/24",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "fc00::/7",
        "ff00::/8",
        "fe80::/10",
        "::/127",
    ]

    config = {
        "mixed-port": 7890,
        "allow-lan": False,
        "mode": "rule",
        "log-level": "silent",
        "ipv6": False,
        "unified-delay": True,
        "tcp-concurrent": True,
        "tun": {
            "enable": True,
            "stack": "system",
            "auto-route": True,
            "auto-detect-interface": True,
            "dns-hijack": ["any:53"],
            "strict-route": True,
            "route-exclude-address": private_networks,
        },
        "dns": {
            "enable": True,
            "ipv6": False,
            "enhanced-mode": "fake-ip",
            "fake-ip-range": "198.18.0.1/16",
            "fake-ip-filter": ["rule-set:private-domains"],
            "default-nameserver": [
                "https://77.88.8.8/dns-query",
                "https://8.8.8.8/dns-query",
            ],
            "proxy-server-nameserver": [
                "https://77.88.8.8/dns-query",
                "https://8.8.8.8/dns-query",
            ],
            "direct-nameserver": [
                "https://77.88.8.8/dns-query",
                "https://8.8.8.8/dns-query",
            ],
            "nameserver": ["https://8.8.8.8/dns-query#PROXY"],
        },
        "sniffer": {
            "enable": True,
            "override-destination": True,
            "parse-pure-ip": True,
            "sniff": {
                "HTTP": {"ports": [80, "8080-8880"]},
                "TLS": {"ports": [443, 8443]},
                "QUIC": {"ports": [443]},
            },
            "skip-dst-address": private_networks + ["198.18.0.0/15"],
        },
        "find-process-mode": "strict",
        "profile": {
            "store-selected": True,
            "store-fake-ip": True,
        },
        "proxies": proxies,
        "proxy-groups": [
            {
                "name": group_name,
                "type": "fallback",
                "proxies": proxy_names,
                "url": CLASH_HEALTH_URL,
                "interval": CLASH_HEALTH_INTERVAL,
                "lazy": True,
                "timeout": CLASH_HEALTH_TIMEOUT_MS,
                "max-failed-times": CLASH_MAX_FAILED_TIMES,
            },
            {
                "name": vpn_group,
                "type": "select",
                "proxies": [group_name] + proxy_names,
                "url": CLASH_HEALTH_URL,
            },
            {
                "name": youtube_group,
                "type": "select",
                "proxies": [vpn_group] + proxy_names,
            },
            {
                "name": discord_group,
                "type": "select",
                "proxies": [vpn_group] + proxy_names,
            },
            {
                "name": games_group,
                "type": "select",
                "proxies": ["DIRECT", vpn_group] + proxy_names,
            },
            {
                "name": "PROXY",
                "type": "select",
                "hidden": True,
                "proxies": [vpn_group],
            },
            {
                "name": "🔓 Без VPN",
                "type": "select",
                "hidden": True,
                "proxies": ["DIRECT"],
            },
            {
                "name": "⛔ Блок",
                "type": "select",
                "hidden": True,
                "proxies": ["REJECT"],
            },
            {
                "name": "⏭️ Пропуск",
                "type": "select",
                "hidden": True,
                "proxies": ["PASS"],
            },
        ],
        "rule-providers": rule_providers,
        "rules": [
            "RULE-SET,private-ips,DIRECT,no-resolve",
            "IP-CIDR,::/0,REJECT-DROP,no-resolve",
            "RULE-SET,private-domains,DIRECT",
            "RULE-SET,category-ads,REJECT-DROP",
            "RULE-SET,win-spy,REJECT-DROP",
            "RULE-SET,torrent-domains,DIRECT",
            "RULE-SET,google-play,PROXY",
            "RULE-SET,twitch-ads,PROXY",
            f"RULE-SET,youtube,{youtube_group}",
            "RULE-SET,telegram,PROXY",
            "RULE-SET,github,PROXY",
            f"RULE-SET,epicgames,{games_group}",
            f"RULE-SET,origin,{games_group}",
            f"RULE-SET,riot,{games_group}",
            f"RULE-SET,escapefromtarkov,{games_group}",
            f"RULE-SET,steam,{games_group}",
            f"RULE-SET,faceit,{games_group}",
            "RULE-SET,twitch,DIRECT",
            "RULE-SET,microsoft,DIRECT",
            "RULE-SET,apple,DIRECT",
            "RULE-SET,pinterest,DIRECT",
            "RULE-SET,category-ru,DIRECT",
            "RULE-SET,whitelist,DIRECT",
            "RULE-SET,torrent-clients,DIRECT",
            f"PROCESS-NAME-REGEX,discord,{discord_group}",
            f"PROCESS-NAME-REGEX,vesktop,{discord_group}",
            f"RULE-SET,games,{games_group}",
            "RULE-SET,ru-apps,DIRECT",
            "RULE-SET,direct-ips,DIRECT",
            "DOMAIN-SUFFIX,googleapis.com,DIRECT",
            "DOMAIN-KEYWORD,googleapis,DIRECT",
            "MATCH,PROXY",
        ],
    }
    return config, candidates


def collect_service_outbounds(
    base: dict[str, Any],
) -> list[dict[str, Any]]:
    service_outbounds: list[dict[str, Any]] = []
    seen_tags: set[str] = set()

    for outbound in base.get("outbounds", []):
        if not isinstance(outbound, dict):
            continue

        tag = str(outbound.get("tag", ""))
        if tag == "proxy" or tag.startswith(("route-", "chain-")):
            continue
        if tag and tag in seen_tags:
            continue

        service_outbounds.append(copy.deepcopy(outbound))
        if tag:
            seen_tags.add(tag)

    if "direct" not in seen_tags:
        service_outbounds.append(
            {
                "protocol": "freedom",
                "tag": "direct",
                "settings": {"domainStrategy": "AsIs"},
            }
        )
        seen_tags.add("direct")

    if "block" not in seen_tags:
        service_outbounds.append(
            {
                "protocol": "blackhole",
                "tag": "block",
                "settings": {"response": {"type": "http"}},
            }
        )

    return service_outbounds


def normalize_proxy_routes(
    rules: list[Any],
) -> tuple[list[dict[str, Any]], bool]:
    """
    Keep the conventional outboundTag='proxy' visible to Happ.

    Happ's application-level routing profile targets the standard tags
    proxy/direct/block. The proxy outbound is implemented below as a loopback
    bridge into the priority balancer. Existing legacy balancer rules are
    normalized back to outboundTag='proxy'.
    """
    result: list[dict[str, Any]] = []
    has_proxy_rule = False

    for rule in rules:
        if not isinstance(rule, dict):
            continue

        new_rule = copy.deepcopy(rule)
        balancer_tag = str(new_rule.get("balancerTag", ""))

        if (
            new_rule.get("outboundTag") == "proxy"
            or new_rule.get("balancerTag") == "auto-switch"
            or balancer_tag.startswith("tier-")
        ):
            new_rule.pop("balancerTag", None)
            new_rule["outboundTag"] = "proxy"
            has_proxy_rule = True

        result.append(new_rule)

    return result, has_proxy_rule


def merge_configs(
    configs: list[dict[str, Any]],
    happ_routing_profile: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    priority_config = load_priority_config()
    candidates = order_sticky_candidates(
        build_candidates(configs, priority_config),
        priority_config,
    )

    base = copy.deepcopy(configs[0])
    priorities = sorted({int(item["priority"]) for item in candidates})
    primary_priority = priorities[0]
    primary_balancer_tag = "tier-s0000"
    configured_final_fallback = str(priority_config["finalFallbackTag"])

    # Immediately after Xray reload, leastPing may not yet have observation
    # data. In that state every tier returns an empty selection. Using a real
    # primary outbound as the end of the fallback chain prevents an immediate
    # fall-through to blackhole ("socket is closed") while probes warm up.
    if configured_final_fallback == "primary":
        primary_candidate = select_startup_fallback(
            candidates,
            primary_priority,
            priority_config,
        )
        final_fallback_tag = str(primary_candidate["tag"])
    else:
        final_fallback_tag = configured_final_fallback

    proxy_outbounds = [
        copy.deepcopy(item["outbound"])
        for item in candidates
    ]
    service_outbounds = collect_service_outbounds(base)
    service_tags = {
        str(item.get("tag", ""))
        for item in service_outbounds
        if isinstance(item, dict)
    }

    candidate_tags = {str(item["tag"]) for item in candidates}
    if (
        final_fallback_tag not in service_tags
        and final_fallback_tag not in candidate_tags
    ):
        raise ValueError(
            f"finalFallbackTag '{final_fallback_tag}' is neither "
            "a service outbound nor a generated proxy outbound"
        )

    chain_outbounds: list[dict[str, Any]] = []
    chain_rules: list[dict[str, Any]] = []
    balancers: list[dict[str, Any]] = []

    # One candidate per balancer is intentional. Xray can only leave the
    # current route when its health check fails; a lower RTT on another route
    # cannot reshuffle new connections. Higher priorities (white-list bypasses)
    # therefore remain dormant until every normal route before them is down.
    for index, candidate in enumerate(candidates):
        step_key = f"s{index:04d}"
        next_index = index + 1

        if next_index >= len(candidates):
            fallback_tag = final_fallback_tag
        else:
            next_key = f"s{next_index:04d}"
            fallback_tag = f"chain-{next_key}"
            inbound_tag = f"chain-in-{next_key}"

            chain_outbounds.append(
                {
                    "protocol": "loopback",
                    "tag": fallback_tag,
                    "settings": {
                        "inboundTag": inbound_tag,
                    },
                }
            )
            chain_rules.append(
                {
                    "type": "field",
                    "inboundTag": [inbound_tag],
                    "network": "tcp,udp",
                    "balancerTag": f"tier-{next_key}",
                }
            )

        balancers.append(
            {
                "tag": f"tier-{step_key}",
                "selector": [str(candidate["tag"])],
                "fallbackTag": fallback_tag,
                "strategy": least_load_strategy(),
            }
        )

    proxy_bridge_inbound_tag = "auto-proxy-in"
    proxy_bridge_outbound = {
        "protocol": "loopback",
        "tag": "proxy",
        "settings": {
            "inboundTag": proxy_bridge_inbound_tag,
        },
    }

    base["outbounds"] = (
        proxy_outbounds
        + chain_outbounds
        + [proxy_bridge_outbound]
        + service_outbounds
    )
    base["remarks"] = PROFILE_REMARK

    routing = copy.deepcopy(base.get("routing") or {})
    routing.setdefault("domainStrategy", "AsIs")

    old_rules = routing.get("rules")
    if not isinstance(old_rules, list):
        old_rules = []

    normal_rules, _ = normalize_proxy_routes(
        old_rules,
    )

    # Remove the source profile's broad catch-all. It must be reconstructed
    # after the Happ rules; otherwise DirectSites can never be reached.
    specific_normal_rules = [
        rule
        for rule in normal_rules
        if not is_broad_proxy_rule(rule)
    ]

    happ_rules, default_outbound = build_happ_xray_rules(
        happ_routing_profile
    )

    catch_all_rule = {
        "type": "field",
        "network": "tcp,udp",
        "outboundTag": default_outbound,
    }

    proxy_bridge_rule = {
        "type": "field",
        "inboundTag": [proxy_bridge_inbound_tag],
        "network": "tcp,udp",
        "balancerTag": primary_balancer_tag,
    }

    if happ_routing_profile:
        domain_strategy = str(
            happ_routing_profile.get("DomainStrategy")
            or routing.get("domainStrategy")
            or "IPIfNonMatch"
        )
        routing["domainStrategy"] = domain_strategy

    routing["balancers"] = balancers
    routing["rules"] = (
        chain_rules
        + [proxy_bridge_rule]
        + specific_normal_rules
        + happ_rules
        + [catch_all_rule]
    )
    base["routing"] = routing

    base.pop("observatory", None)
    base["burstObservatory"] = {
        "subjectSelector": ["route-"],
        "pingConfig": {
            "destination": HEALTH_DESTINATION,
            "connectivity": HEALTH_CONNECTIVITY,
            "interval": HEALTH_INTERVAL,
            "sampling": HEALTH_SAMPLING,
            "timeout": HEALTH_TIMEOUT,
            "httpMethod": HEALTH_METHOD,
        },
    }

    manifest = [
        {
            "remark": item["remark"],
            "priority": item["priority"],
            "failoverOrder": index,
            "protocol": item["protocol"],
            "tag": item["tag"],
        }
        for index, item in enumerate(candidates)
    ]

    return base, manifest


def copy_subscription_headers(
    source: httpx.Headers,
    manifest: list[dict[str, Any]],
) -> dict[str, str]:
    result: dict[str, str] = {}

    for header_name in COPY_RESPONSE_HEADERS:
        value = source.get(header_name)
        if value:
            result[header_name] = value

    # Happ supports routing profiles in the HTTP Routing header.
    # Prefer the current 3x-ui value, but allow an explicit environment
    # fallback for older panel builds or custom upstreams.
    routing_link = source.get("routing") or HAPP_ROUTING_LINK
    routing_enable = source.get("routing-enable")

    if routing_link:
        result["Routing"] = routing_link
        result["Routing-Enable"] = (
            routing_enable
            or str(
                HAPP_ROUTING_ENABLE or bool(routing_link)
            ).lower()
        )
    elif routing_enable:
        result["Routing-Enable"] = routing_enable

    tier_list = sorted(
        {str(item["priority"]) for item in manifest},
        key=int,
    )
    result["Cache-Control"] = "no-store"
    result["Content-Disposition"] = (
        'attachment; filename="zizmos-priority-auto.json"'
    )
    result["X-Generated-By"] = "3x-ui-happ-sticky-failover-v3.4"
    result["X-Balancer-Mode"] = "sticky-failover"
    result["X-Balancer-Tiers"] = ",".join(tier_list)
    return result


async def fetch_upstream(sub_id: str) -> httpx.Response:
    upstream_url = UPSTREAM_JSON_BASE + sub_id

    try:
        async with httpx.AsyncClient(
            timeout=UPSTREAM_TIMEOUT_SECONDS,
            verify=UPSTREAM_VERIFY_TLS,
            follow_redirects=True,
        ) as client:
            return await client.get(
                upstream_url,
                headers={
                    "Accept": "application/json",
                    "User-Agent": "3x-ui-happ-sticky-failover/3.4",
                },
            )
    except httpx.RequestError as exc:
        raise HTTPException(
            status_code=502,
            detail=f"Unable to reach 3x-ui subscription endpoint: {exc}",
        ) from exc


def validate_upstream(upstream: httpx.Response) -> None:
    if upstream.status_code == 404:
        raise HTTPException(
            status_code=404,
            detail="Subscription not found",
        )

    if upstream.status_code != 200:
        raise HTTPException(
            status_code=502,
            detail=f"3x-ui returned HTTP {upstream.status_code}",
        )


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "version": "3.4.0"}


def auto_format_is_clash(request: Request) -> bool:
    """Select Clash YAML for known Clash clients, otherwise Xray JSON."""
    explicit_format = request.query_params.get("format", "").strip().lower()
    if explicit_format in {"clash", "mihomo", "yaml"}:
        return True
    if explicit_format in {"xray", "json", "happ", "v2ray"}:
        return False

    user_agent = request.headers.get("user-agent", "").lower()
    clash_markers = (
        "clash",
        "mihomo",
        "flclash",
        "clash-verge",
        "clashmeta",
        "clash meta",
        "stash",
    )
    return any(marker in user_agent for marker in clash_markers)


def auto_format_is_onexray(request: Request) -> bool:
    """Return a single root config object for OneXray imports."""
    explicit_format = request.query_params.get("format", "").strip().lower()
    if explicit_format in {"onexray", "xray-object", "json-object"}:
        return True

    user_agent = request.headers.get("user-agent", "").lower()
    return "onexray" in user_agent


@app.get("/auto/{sub_id}")
async def balanced_subscription(
    sub_id: str,
    request: Request,
) -> Response:
    if auto_format_is_clash(request):
        response = await clash_subscription(sub_id)
        response.headers["X-Auto-Format"] = "clash"
        return response

    if not SUB_ID_RE.fullmatch(sub_id):
        raise HTTPException(
            status_code=400,
            detail="Invalid subscription ID",
        )

    upstream = await fetch_upstream(sub_id)
    validate_upstream(upstream)

    try:
        source_configs = normalize_config_list(upstream.json())
        routing_link = routing_link_from_headers(
            upstream.headers
        )
        happ_routing_profile = decode_happ_routing_profile(
            routing_link
        )
        merged, manifest = merge_configs(
            source_configs,
            happ_routing_profile,
        )
    except (ValueError, TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(
            status_code=502,
            detail=f"Unable to transform 3x-ui JSON: {exc}",
        ) from exc

    is_onexray = auto_format_is_onexray(request)
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
    return response


@app.get("/clash/{sub_id}")
@app.get("/auto/clash/{sub_id}")
async def clash_subscription(sub_id: str) -> Response:
    if not SUB_ID_RE.fullmatch(sub_id):
        raise HTTPException(
            status_code=400,
            detail="Invalid subscription ID",
        )

    upstream = await fetch_upstream(sub_id)
    validate_upstream(upstream)

    try:
        source_configs = normalize_config_list(upstream.json())
        config, candidates = build_clash_config(source_configs)
    except (ValueError, TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(
            status_code=502,
            detail=f"Unable to build Clash subscription: {exc}",
        ) from exc

    headers: dict[str, str] = {
        "Cache-Control": "no-store",
        "Content-Disposition": (
            'attachment; filename="zizmos-clash-auto-p0-p4.yaml"'
        ),
        "X-Generated-By": "3x-ui-clash-priority-fallback-v1",
        "X-Balancer-Mode": "priority-fallback",
        "X-Balancer-Priorities": f"P0-P{CLASH_MAX_PRIORITY}",
        "X-Balancer-Profiles": str(len(candidates)),
    }
    subscription_info = upstream.headers.get("subscription-userinfo")
    if subscription_info:
        headers["Subscription-Userinfo"] = subscription_info

    return Response(
        content=yaml.safe_dump(
            config,
            allow_unicode=True,
            sort_keys=False,
            default_flow_style=False,
        ),
        media_type="application/yaml",
        headers=headers,
    )


@app.get("/inspect/{sub_id}")
async def inspect_subscription(sub_id: str) -> JSONResponse:
    if not SUB_ID_RE.fullmatch(sub_id):
        raise HTTPException(
            status_code=400,
            detail="Invalid subscription ID",
        )

    upstream = await fetch_upstream(sub_id)
    validate_upstream(upstream)

    try:
        source_configs = normalize_config_list(upstream.json())
        routing_link = routing_link_from_headers(
            upstream.headers
        )
        happ_routing_profile = decode_happ_routing_profile(
            routing_link
        )
        merged, manifest = merge_configs(
            source_configs,
            happ_routing_profile,
        )
    except (ValueError, TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(
            status_code=502,
            detail=f"Unable to inspect 3x-ui JSON: {exc}",
        ) from exc

    return JSONResponse(
        content={
            "profiles": manifest,
            "tiers": sorted(
                {item["priority"] for item in manifest}
            ),
            "startupFallbackTag": (
                merged["routing"]["balancers"][-1]["fallbackTag"]
            ),
            "strategy": "leastLoad",
            "proxyCompatibility": {
                "outboundTag": "proxy",
                "bridgeInboundTag": "auto-proxy-in",
                "balancerTag": (
                    merged["routing"]["balancers"][0]["tag"]
                ),
            },
            "embeddedHappRouting": {
                "profileName": (
                    happ_routing_profile.get("Name")
                    if happ_routing_profile
                    else None
                ),
                "globalProxy": (
                    profile_bool(
                        happ_routing_profile,
                        "GlobalProxy",
                        True,
                    )
                    if happ_routing_profile
                    else None
                ),
                "routeOrder": (
                    happ_routing_profile.get("RouteOrder")
                    if happ_routing_profile
                    else None
                ),
                "ruleCount": len(
                    build_happ_xray_rules(
                        happ_routing_profile
                    )[0]
                ),
                "forcedDirectDomains": (
                    HAPP_FORCE_DIRECT_DOMAINS
                ),
            },
            "burstObservatory": merged["burstObservatory"],
            "happRouting": {
                "present": bool(
                    upstream.headers.get("routing")
                    or HAPP_ROUTING_LINK
                ),
                "enabled": (
                    upstream.headers.get("routing-enable")
                    or str(
                        HAPP_ROUTING_ENABLE
                        or bool(
                            upstream.headers.get("routing")
                            or HAPP_ROUTING_LINK
                        )
                    ).lower()
                ),
                "source": (
                    "upstream"
                    if upstream.headers.get("routing")
                    else (
                        "environment"
                        if HAPP_ROUTING_LINK
                        else "none"
                    )
                ),
            },
        },
        headers={"Cache-Control": "no-store"},
    )
