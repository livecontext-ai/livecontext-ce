"""
Hybrid 3-layer crawl filtering system.

Layer 1 - Pre-crawl: domain blacklist + URL pattern filtering (zero HTTP cost)
Layer 2 - Post-crawl: content validation to detect blocked/empty pages
Layer 3 - Feedback loop: in-memory domain reputation with auto-blacklist
"""

import ipaddress
import logging
import re
import socket
import time
from urllib.parse import urlparse

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Layer 1: Pre-crawl domain blacklist
# ---------------------------------------------------------------------------

# Domains that never return useful textual content in a headless browser.
# Matched against the hostname (and parent domains) of the URL.
DOMAIN_BLACKLIST: set[str] = {
    # --- Social media (login walls, dynamic content, aggressive bot detection) ---
    "facebook.com", "fb.com", "fbcdn.net",
    "twitter.com", "x.com", "t.co", "twimg.com",
    "instagram.com", "cdninstagram.com",
    "linkedin.com", "licdn.com",
    "tiktok.com",
    "snapchat.com",
    "pinterest.com",
    "threads.net",
    "discord.com", "discord.gg", "discordapp.com",
    "telegram.org", "t.me",
    "whatsapp.com",
    "mastodon.social",
    "tumblr.com",
    "reddit.com", "redd.it", "redditmedia.com",

    # --- Video / streaming (binary content, players, no crawlable text) ---
    "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com",
    "vimeo.com",
    "dailymotion.com",
    "twitch.tv",
    "netflix.com",
    "hulu.com",
    "disneyplus.com",
    "primevideo.com",
    "spotify.com",
    "soundcloud.com",
    "tidal.com",
    "crunchyroll.com",
    "deezer.com",

    # --- Maps / geo (WebGL rendering, no useful text) ---
    "maps.google.com",
    "maps.apple.com",
    "waze.com",
    "earth.google.com",
    "mapbox.com",

    # --- Auth-required / webmail / productivity ---
    "mail.google.com",
    "outlook.live.com", "outlook.office.com", "outlook.office365.com",
    "drive.google.com",
    "docs.google.com", "sheets.google.com", "slides.google.com",
    "onedrive.live.com",
    "dropbox.com",
    "box.com",
    "slack.com",
    "teams.microsoft.com",
    "zoom.us",
    "accounts.google.com",
    "login.microsoftonline.com",
    "notion.so",

    # --- App stores (dynamic JS, no crawlable text) ---
    "apps.apple.com",
    "play.google.com",
    "store.steampowered.com",

    # --- URL shorteners (redirects only, no content) ---
    "bit.ly", "goo.gl", "tinyurl.com", "ow.ly",
    "buff.ly", "rebrand.ly", "short.io", "is.gd",

    # --- File hosting (binary, auth-gated) ---
    "mega.nz",
    "mediafire.com",
    "wetransfer.com",
}

# File extensions that won't yield useful text content.
SKIP_EXTENSIONS: set[str] = {
    # Images
    ".jpg", ".jpeg", ".png", ".gif", ".svg", ".ico", ".webp", ".bmp", ".tiff",
    # Video
    ".mp4", ".avi", ".mov", ".wmv", ".flv", ".webm", ".mkv",
    # Audio
    ".mp3", ".wav", ".ogg", ".flac", ".aac", ".wma",
    # Archives
    ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2",
    # Documents (binary)
    ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
    # Executables
    ".exe", ".msi", ".dmg", ".deb", ".rpm", ".apk",
    # Fonts
    ".woff", ".woff2", ".ttf", ".eot", ".otf",
    # Data files
    ".xml", ".rss", ".atom", ".json", ".csv",
}

# URL path patterns that indicate non-content pages.
SKIP_PATH_PATTERNS: list[re.Pattern] = [
    re.compile(p, re.IGNORECASE)
    for p in [
        r"/login", r"/logout", r"/signin", r"/signout",
        r"/signup", r"/register",
        r"/password", r"/reset-password", r"/forgot-password",
        r"/auth/", r"/oauth/", r"/sso/",
        r"/cart$", r"/checkout", r"/payment",
        r"/admin", r"/wp-admin", r"/wp-login",
    ]
]


# ---------------------------------------------------------------------------
# SSRF protection: block private/internal IP ranges
# ---------------------------------------------------------------------------

BLOCKED_NETWORKS = [
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("169.254.0.0/16"),
    ipaddress.ip_network("0.0.0.0/8"),
    ipaddress.ip_network("100.64.0.0/10"),
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("fc00::/7"),
    ipaddress.ip_network("fe80::/10"),
    # Never a legitimate public destination either: multicast, broadcast,
    # "reserved for future use", and the unspecified IPv6 address.
    ipaddress.ip_network("224.0.0.0/4"),
    ipaddress.ip_network("240.0.0.0/4"),
    ipaddress.ip_network("::/128"),
    ipaddress.ip_network("ff00::/8"),
]

# IPv6 prefixes that embed an IPv4 address in their low 32 bits and are
# translated to it by the network (NAT64 well-known prefix + local-use
# prefix, RFC 6052 / RFC 8215). The embedded IPv4 is what gets reached.
_NAT64_NETWORKS = [
    ipaddress.ip_network("64:ff9b::/96"),
    ipaddress.ip_network("64:ff9b:1::/48"),
]


# IPv4-compatible IPv6 addresses (::a.b.c.d, RFC 4291 section 2.5.5.1). Deprecated
# and never a legitimate destination, but the stdlib reports them as global
# (`::7f00:1`, i.e. ::127.0.0.1, has is_global=True) and a dual-stack host may
# still route them to the embedded IPv4. The whole /96 is refused; `::` and
# `::1` sit inside it and keep their own, more specific reasons.
_IPV4_COMPATIBLE_NETWORK = ipaddress.ip_network("::/96")


def _embedded_ipv4(ip: ipaddress.IPv6Address) -> ipaddress.IPv4Address | None:
    """Return the IPv4 address an IPv6 address really targets, if any.

    Covers IPv4-mapped (::ffff:a.b.c.d), 6to4 (2002::/16) and NAT64.
    Without this, `http://[::ffff:169.254.169.254]/` slips past a check that
    compares an IPv6 address against IPv4 networks only. IPv4-compatible
    addresses (::a.b.c.d) are refused outright by `ip_block_reason`.
    """
    if ip.ipv4_mapped is not None:
        return ip.ipv4_mapped
    if ip.sixtofour is not None:
        return ip.sixtofour
    for network in _NAT64_NETWORKS:
        if ip in network:
            return ipaddress.IPv4Address(int(ip) & 0xFFFFFFFF)
    return None


def ip_block_reason(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> str:
    """Return why `ip` is not a safe public destination, or "" when it is.

    Blocks loopback, RFC 1918, link-local (incl. cloud metadata 169.254/16),
    CGNAT 100.64/10, IPv6 ULA / link-local, multicast, reserved, unspecified,
    any IPv6 form that embeds one of those IPv4 addresses, and every
    deprecated IPv4-compatible address (::a.b.c.d). Anything the stdlib does
    not consider globally routable is blocked too, so a range missing from
    BLOCKED_NETWORKS still fails closed.
    """
    if isinstance(ip, ipaddress.IPv6Address):
        embedded = _embedded_ipv4(ip)
        if embedded is not None:
            inner = ip_block_reason(embedded)
            return f"{inner} (embedded in {ip})" if inner else ""
        if ip in _IPV4_COMPATIBLE_NETWORK and int(ip) > 1:
            return (f"{ip} is a deprecated IPv4-compatible address "
                    f"(embeds {ipaddress.IPv4Address(int(ip))})")
    for network in BLOCKED_NETWORKS:
        if ip in network:
            return f"{ip} is in {network}"
    if not ip.is_global:
        return f"{ip} is not a globally routable address"
    return ""


# Hostname-suffix denylist applied BEFORE DNS resolution. Guards against
# DNS poisoning, Host-header tricks, and split-horizon DNS where the same
# name resolves to a public IP externally and a private one inside the mesh.
# Called by both `is_url_blacklisted` (cheap fetch) and
# `is_url_safe_for_navigation` (every browser request, see browser_request_guard).
BLOCKED_HOSTNAME_SUFFIXES: set[str] = {
    # Cloud metadata IMDS endpoints
    "metadata.google.internal",
    "metadata.aws.internal",
    "metadata.azure.com",
    "metadata",                  # bare 'metadata' hostname trick
    "169.254.169.254",           # IMDS literal - also caught by 169.254/16
    # Kubernetes / container orchestrators
    "cluster.local",
    "svc.cluster.local",
    "pod.cluster.local",
    # Generic local / internal TLDs
    "local",
    "internal",
    "localdomain",
    # Chrome resolves *.localhost to loopback itself, without asking DNS.
    "localhost",
}


def _normalize_hostname(hostname: str) -> str:
    """Lowercase and drop the trailing root dot ("metadata.google.internal.")."""
    return (hostname or "").lower().rstrip(".")


def _hostname_blocked_by_suffix(hostname: str) -> tuple[bool, str]:
    """Check hostname against BLOCKED_HOSTNAME_SUFFIXES (exact or sub-domain).

    Returns (blocked, matched_suffix).
    """
    hostname = _normalize_hostname(hostname)
    for suffix in BLOCKED_HOSTNAME_SUFFIXES:
        if hostname == suffix or hostname.endswith("." + suffix):
            return True, suffix
    return False, ""


def _ip_literal_blocked(hostname: str) -> tuple[bool, str]:
    """If `hostname` is an IP literal, check it with `ip_block_reason`.

    Returns (blocked, reason). Returns (False, "") if hostname is not an IP.
    """
    try:
        ip = ipaddress.ip_address(_normalize_hostname(hostname))
    except ValueError:
        return False, ""
    reason = ip_block_reason(ip)
    if reason:
        return True, f"SSRF blocked: literal IP {reason}"
    return False, ""


# Reasons for a name that has no DNS answer start with this prefix. Such a
# request is still refused (there is nothing to connect to), but it is the
# ordinary "site can't be reached" failure of a typo'd, dead or briefly
# unresolvable domain, NOT an SSRF attempt: callers must not report it as
# one, stop a browser-agent run on it, cache it, or count it against the
# domain's reputation. See `is_unresolvable_reason`.
UNRESOLVABLE_REASON_PREFIX = "unresolvable host: "


def is_unresolvable_reason(reason: str) -> bool:
    """True when a refusal reason means "the name does not resolve"."""
    return (reason or "").startswith(UNRESOLVABLE_REASON_PREFIX)


# Crawl result `blocked_reason` kinds for a page the SSRF checks refused.
CRAWL_BLOCK_SSRF = "ssrf_blocked"
CRAWL_BLOCK_DNS = "dns_unresolved"


def crawl_block_reason(reason: str) -> str:
    """The crawl result's `blocked_reason` for a refusal of the SSRF checks:
    `dns_unresolved: ...` when the host does not resolve, `ssrf_blocked: ...`
    otherwise."""
    kind = CRAWL_BLOCK_DNS if is_unresolvable_reason(reason) else CRAWL_BLOCK_SSRF
    return f"{kind}: {reason}"


def _resolve_public_addresses(hostname: str) -> tuple[list[str], str]:
    """Resolve `hostname`; return (addresses, "") only when EVERY one is public.

    Returns ([], reason) when the name does not resolve (reason starts with
    UNRESOLVABLE_REASON_PREFIX) or when any address it resolves to is
    non-public (fail closed: one private answer refuses the whole name).
    """
    try:
        infos = socket.getaddrinfo(hostname, None, socket.AF_UNSPEC, socket.SOCK_STREAM)
    except (socket.gaierror, ValueError, OSError) as e:
        return [], f"{UNRESOLVABLE_REASON_PREFIX}{hostname} does not resolve ({type(e).__name__})"
    if not infos:
        return [], f"{UNRESOLVABLE_REASON_PREFIX}{hostname} does not resolve"
    addresses: list[str] = []
    for _family, _, _, _, sockaddr in infos:
        try:
            ip = ipaddress.ip_address(sockaddr[0])
        except ValueError:
            return [], f"{hostname} resolves to an unparsable address"
        reason = ip_block_reason(ip)
        if reason:
            return [], f"{hostname} resolves to a private/internal address: {reason}"
        if str(ip) not in addresses:
            addresses.append(str(ip))
    return addresses, ""


def _dns_refusal(hostname: str) -> str:
    """Why `hostname` may not be fetched after DNS, or "" when it may.

    A name that does not resolve gets its own reason (see
    `is_unresolvable_reason`); a name with any non-public answer gets the
    SSRF reason. Both refuse the request.
    """
    addresses, reason = _resolve_public_addresses(hostname)
    if addresses:
        return ""
    if is_unresolvable_reason(reason):
        return reason
    return f"SSRF blocked: {hostname} resolves to private/internal IP"


def resolve_egress_addresses(host: str) -> tuple[list[str], str]:
    """Addresses a browser connection to `host` may use, or ([], reason).

    Used at CONNECT time by `egress_guard_proxy`, which then connects to one
    of the returned addresses itself. Because the address checked here is
    the address connected to, a DNS answer that changes between the request
    check and the connection (DNS rebinding) cannot reach a private host.
    Applies the same rules as `is_url_safe_for_navigation`: internal
    hostname suffixes, IP literals, then DNS with every answer public.
    """
    hostname = _normalize_hostname(host)
    if not hostname:
        return [], "no hostname"
    blocked, suffix = _hostname_blocked_by_suffix(hostname)
    if blocked:
        return [], f"blocked internal/metadata hostname: {hostname} (suffix: {suffix})"
    try:
        literal = ipaddress.ip_address(hostname)
    except ValueError:
        literal = None
    if literal is not None:
        reason = ip_block_reason(literal)
        return ([], f"SSRF blocked: literal IP {reason}") if reason else ([str(literal)], "")
    return _resolve_public_addresses(hostname)


def _extract_domain_parts(hostname: str) -> list[str]:
    """Return all parent domains for matching.

    e.g. 'www.maps.google.com' → ['www.maps.google.com', 'maps.google.com', 'google.com']
    """
    parts = hostname.lower().split(".")
    domains = []
    for i in range(len(parts)):
        candidate = ".".join(parts[i:])
        if "." in candidate:  # skip bare TLDs
            domains.append(candidate)
    return domains


def is_url_blacklisted(url: str) -> tuple[bool, str]:
    """Check if a URL should be skipped before crawling.

    Returns (blocked, reason) tuple.
    """
    try:
        parsed = urlparse(url)
    except Exception:
        return True, "invalid URL"

    # Scheme check: only http/https allowed (block file://, ftp://, gopher://, etc.)
    if parsed.scheme not in ("http", "https"):
        return True, f"blocked scheme: {parsed.scheme}"

    hostname = _normalize_hostname(parsed.hostname or "")
    if not hostname:
        return True, "no hostname"

    # SSRF - internal hostnames (cloud metadata, k8s services, .local TLDs)
    blocked, suffix = _hostname_blocked_by_suffix(hostname)
    if blocked:
        return True, f"blocked internal/metadata hostname: {hostname} (suffix: {suffix})"

    # SSRF - IP literals (covers 169.254.169.254 explicitly, plus all RFC1918)
    blocked, reason = _ip_literal_blocked(hostname)
    if blocked:
        return True, reason

    # SSRF - DNS-resolved private IPs (or a name with no DNS answer)
    reason = _dns_refusal(hostname)
    if reason:
        return True, reason

    # Domain blacklist check (match any parent domain)
    for domain in _extract_domain_parts(hostname):
        if domain in DOMAIN_BLACKLIST:
            return True, f"blacklisted domain: {domain}"

    # File extension check
    path_lower = parsed.path.lower()
    for ext in SKIP_EXTENSIONS:
        if path_lower.endswith(ext):
            return True, f"binary file extension: {ext}"

    # URL path pattern check
    for pattern in SKIP_PATH_PATTERNS:
        if pattern.search(parsed.path):
            return True, f"skip path pattern: {pattern.pattern}"

    return False, ""


# ---------------------------------------------------------------------------
# Layer 2: Post-crawl content validation
# ---------------------------------------------------------------------------

# Strings found in anti-bot challenge pages.
ANTI_BOT_INDICATORS: list[str] = [
    # Cloudflare
    "attention required",
    "just a moment",
    "checking your browser",
    "cloudflare ray id",
    "cf-browser-verification",
    "ddos protection by",
    "error 1020",
    "error 1015",
    "error 1003",
    "cf-challenge",
    "challenge-platform",
    "turnstile",
    # Generic WAF / bot detection
    "access denied",
    "enable javascript and cookies",
    "please enable javascript",
    "please turn javascript on",
    "please verify you are a human",
    "verify you are human",
    "prove you are human",
    "prove your humanity",
    "are you a robot",
    "you have been blocked",
    "security check",
    "bot protection",
    "automated access",
    "suspicious activity",
    "unusual traffic",
    "rate limit exceeded",
    "too many requests",
    "request blocked",
    "captcha",
    "hcaptcha",
    "recaptcha",
    # Commercial WAFs
    "datadome",
    "px-captcha",
    "perimeterx",
    "imperva",
    "incapsula",
    "sucuri",
    "akamai",
    "distil networks",
    "shape security",
    # Paywall / login walls (content not accessible)
    "subscribe to continue reading",
    "sign in to continue",
    "create an account to continue",
    "this content is for subscribers",
    "premium content",
    "members only",
    # French
    "comportement du navigateur",
    "veuillez confirmer que vous",
    "accès refusé",
    "accès interdit",
    "vérification de sécurité",
    "protection anti-bot",
    "vous avez été bloqué",
    "activez javascript",
    "veuillez activer javascript",
    "veuillez patienter",
    "vérification en cours",
    "prouvez que vous êtes humain",
    # German
    "zugriff verweigert",
    "bitte aktivieren sie javascript",
    "sicherheitsüberprüfung",
    # Spanish
    "acceso denegado",
    "habilite javascript",
    "verificación de seguridad",
]

# Minimum thresholds for valid content.
MIN_MARKDOWN_CHARS = 150
MIN_WORD_COUNT = 30


def validate_crawl_content(markdown: str, metadata: dict) -> tuple[bool, str]:
    """Validate that crawled content is real page content, not a block page.

    Returns (valid, reason) tuple.
    """
    if not markdown:
        return False, "empty content"

    text = markdown.strip()
    word_count = len(text.split())

    # Minimum content length
    if len(text) < MIN_MARKDOWN_CHARS:
        return False, f"too short: {len(text)} chars (min {MIN_MARKDOWN_CHARS})"

    if word_count < MIN_WORD_COUNT:
        return False, f"too few words: {word_count} (min {MIN_WORD_COUNT})"

    # Anti-bot page detection
    lower_text = text.lower()
    for indicator in ANTI_BOT_INDICATORS:
        if indicator in lower_text and word_count < 200:
            return False, f"anti-bot page detected: '{indicator}'"

    # Title-based detection
    title = metadata.get("title", "").lower()
    block_titles = [
        "access denied", "just a moment", "attention required",
        "blocked", "forbidden", "security check", "error 403",
        "error 404", "page not found", "not found",
        "error 1020", "you have been blocked",
        "403 forbidden", "401 unauthorized",
        "accès refusé", "accès interdit", "vérification",
        "page introuvable", "erreur 403", "erreur 404",
    ]
    for bt in block_titles:
        if bt in title:
            return False, f"blocked page title: '{bt}'"

    # Very short content with no real substance (challenge pages, error pages)
    if word_count < 100 and any(
        kw in lower_text for kw in [
            "javascript", "cookies", "browser", "verify",
            "blocked", "denied", "forbidden",
            "navigateur", "javascript", "bloqué",
        ]
    ):
        return False, f"suspected challenge page: {word_count} words with block keywords"

    return True, ""


# ---------------------------------------------------------------------------
# Layer 3: Feedback loop - in-memory domain reputation
# ---------------------------------------------------------------------------

class DomainReputation:
    """Track crawl success/failure per domain and auto-blacklist repeat offenders.

    In-memory storage - resets on service restart.
    Domains with >=FAILURE_THRESHOLD consecutive failures within the TTL window
    are temporarily blacklisted for BLACKLIST_DURATION_S seconds.
    """

    FAILURE_THRESHOLD = 3        # consecutive failures before auto-blacklist
    BLACKLIST_DURATION_S = 3600  # 1 hour temp blacklist
    RECORD_TTL_S = 7200          # 2 hours - forget old records

    def __init__(self) -> None:
        # domain → {"failures": int, "last_failure": float, "blacklisted_until": float}
        self._records: dict[str, dict] = {}

    def _domain_key(self, url: str) -> str:
        """Extract registrable domain from URL for grouping."""
        try:
            hostname = urlparse(url).hostname or ""
            parts = hostname.lower().split(".")
            # Use last 2 parts (e.g. 'example.com') or 3 for country TLDs
            if len(parts) >= 2:
                return ".".join(parts[-2:])
            return hostname
        except Exception:
            return url

    def _cleanup_stale(self) -> None:
        """Remove records older than TTL."""
        now = time.monotonic()
        stale = [
            k for k, v in self._records.items()
            if now - v.get("last_failure", 0) > self.RECORD_TTL_S
            and now > v.get("blacklisted_until", 0)
        ]
        for k in stale:
            del self._records[k]

    def is_temporarily_blacklisted(self, url: str) -> tuple[bool, str]:
        """Check if a domain is temporarily blacklisted from past failures."""
        domain = self._domain_key(url)
        record = self._records.get(domain)
        if not record:
            return False, ""

        if time.monotonic() < record.get("blacklisted_until", 0):
            return True, f"auto-blacklisted domain (failed {record['failures']}x): {domain}"

        return False, ""

    def record_success(self, url: str) -> None:
        """Reset failure count on successful crawl."""
        domain = self._domain_key(url)
        if domain in self._records:
            del self._records[domain]

    def record_failure(self, url: str, reason: str) -> None:
        """Record a crawl failure. Auto-blacklist after threshold."""
        domain = self._domain_key(url)
        now = time.monotonic()

        record = self._records.get(domain, {"failures": 0, "last_failure": 0, "blacklisted_until": 0})
        record["failures"] = record.get("failures", 0) + 1
        record["last_failure"] = now

        if record["failures"] >= self.FAILURE_THRESHOLD:
            record["blacklisted_until"] = now + self.BLACKLIST_DURATION_S
            logger.warning(
                "Domain %s auto-blacklisted for %ds after %d failures (last: %s)",
                domain, self.BLACKLIST_DURATION_S, record["failures"], reason,
            )

        self._records[domain] = record

        # Periodic cleanup
        if len(self._records) > 100:
            self._cleanup_stale()


# Global singleton
domain_reputation = DomainReputation()


# ---------------------------------------------------------------------------
# Combined filter: all 3 layers
# ---------------------------------------------------------------------------

def should_skip_url(url: str) -> tuple[bool, str]:
    """Run all pre-crawl checks (Layer 1 + Layer 3).

    Returns (skip, reason).
    """
    # Layer 1: static blacklist
    blocked, reason = is_url_blacklisted(url)
    if blocked:
        return True, reason

    # Layer 3: feedback loop
    blocked, reason = domain_reputation.is_temporarily_blacklisted(url)
    if blocked:
        return True, reason

    return False, ""


def is_url_safe_for_navigation(url: str) -> tuple[bool, str]:
    """Strict SSRF check for a URL the browser is about to fetch.

    Called for EVERY request Chrome makes (each redirect hop, iframe,
    subresource, worker and service-worker fetch) through
    `is_request_url_allowed` and `browser_request_guard`, not only on the
    user-supplied initial URL. This guards against redirect chains and
    meta-refresh tricks that bypass a check done only at the entry point.

    Differs from `is_url_blacklisted` in that:
      - returns (safe, reason) where safe=True means navigation is allowed
      - applies ONLY the SSRF / scheme / metadata checks (no domain
        blacklist, no SKIP_PATH_PATTERNS, no extension filter - those are
        product/UX choices irrelevant to the security boundary)

    Designed to be cheap on every call: hostname-suffix and IP-literal
    checks happen before any DNS resolution.
    """
    try:
        parsed = urlparse(url)
    except Exception:
        return False, "invalid URL"

    if parsed.scheme not in ("http", "https"):
        return False, f"blocked scheme: {parsed.scheme}"

    hostname = _normalize_hostname(parsed.hostname or "")
    if not hostname:
        return False, "no hostname"

    blocked, suffix = _hostname_blocked_by_suffix(hostname)
    if blocked:
        return False, f"blocked internal/metadata hostname: {hostname} (suffix: {suffix})"

    blocked, reason = _ip_literal_blocked(hostname)
    if blocked:
        return False, reason

    reason = _dns_refusal(hostname)
    if reason:
        return False, reason

    return True, ""


# Schemes the browser may load without any network egress: inline data,
# in-memory blobs and the blank page. They cannot reach an internal host.
_NON_NETWORK_SCHEMES = {"data", "blob", "about"}
_WEBSOCKET_SCHEMES = {"ws": "http", "wss": "https"}


def is_request_url_allowed(url: str) -> tuple[bool, str]:
    """Decide whether a browser request (any resource type) may go out.

    http/https and ws/wss go through `is_url_safe_for_navigation`; data:,
    blob: and about: carry no network egress and are allowed; any other
    scheme (file:, ftp:, chrome:, gopher:, ...) is refused.
    """
    try:
        scheme = urlparse(url).scheme.lower()
    except Exception:
        return False, "invalid URL"
    if scheme in _NON_NETWORK_SCHEMES:
        return True, ""
    if scheme in _WEBSOCKET_SCHEMES:
        url = _WEBSOCKET_SCHEMES[scheme] + url[len(scheme):]
    return is_url_safe_for_navigation(url)


# Callbacks only ever target the orchestrator's internal API.
CALLBACK_PATH_PREFIX = "/api/internal/"
_DEFAULT_PORTS = {"http": 80, "https": 443}


def _origin(url: str) -> tuple[str, str, int] | None:
    try:
        parsed = urlparse(url.strip())
        scheme = parsed.scheme.lower()
        if scheme not in _DEFAULT_PORTS or not parsed.hostname:
            return None
        return scheme, _normalize_hostname(parsed.hostname), parsed.port or _DEFAULT_PORTS[scheme]
    except ValueError:
        return None


def parse_callback_origins(allowed_origins: str) -> tuple[set[tuple[str, str, int]], list[str]]:
    """Parse WEBSEARCH_CALLBACK_ALLOWED_ORIGINS into (origins, invalid entries).

    Each comma-separated entry must be a bare origin, `scheme://host[:port]`
    (a trailing "/" is tolerated). An entry with a path, query, credentials
    or a non-http(s) scheme is reported as invalid and ignored, so a typo
    can never widen the allow-list.
    """
    origins: set[tuple[str, str, int]] = set()
    invalid: list[str] = []
    for entry in (e.strip() for e in (allowed_origins or "").split(",")):
        if not entry:
            continue
        try:
            parsed = urlparse(entry)
            extra = (parsed.path not in ("", "/") or parsed.query or parsed.fragment
                     or parsed.username or parsed.password)
        except ValueError:
            extra = True
        origin = None if extra else _origin(entry)
        if origin is None:
            invalid.append(entry)
        else:
            origins.add(origin)
    return origins, invalid


def callback_origin_warnings(allowed_origins: str) -> list[str]:
    """Operator-facing warnings about the callback allow-list, logged at startup.

    The orchestrator attaches a step callback to every browser-agent run;
    with an empty allow-list every one of them is dropped, silently from
    the user's point of view (no live step trace in Docker mode).
    """
    origins, invalid = parse_callback_origins(allowed_origins)
    warnings = [f"WEBSEARCH_CALLBACK_ALLOWED_ORIGINS entry ignored (not a bare http(s) origin): {e!r}"
                for e in invalid]
    if not origins:
        warnings.append(
            "WEBSEARCH_CALLBACK_ALLOWED_ORIGINS is empty: every callback_url "
            "(browser-agent step callbacks, crawl screenshot callbacks) will be "
            "dropped. Set it to the orchestrator origin of WEBSEARCH_CALLBACK_BASE_URL."
        )
    return warnings


def is_callback_url_allowed(url: str, allowed_origins: str) -> tuple[bool, str]:
    """Check a caller-supplied callback URL against the configured origins.

    `allowed_origins` is the comma-separated WEBSEARCH_CALLBACK_ALLOWED_ORIGINS
    value (e.g. "http://10.0.0.2:8099"). The callback must use one of those
    exact origins (scheme, host, port), carry no credentials, and target the
    internal API path. An empty allow-list refuses every callback: this
    service POSTs to private addresses here, so the SSRF guard cannot be
    used instead and the default must fail closed.
    """
    if not url:
        return False, "no callback URL"
    origins, _invalid = parse_callback_origins(allowed_origins)
    if not origins:
        return False, "no callback origin configured (WEBSEARCH_CALLBACK_ALLOWED_ORIGINS)"
    try:
        parsed = urlparse(url)
    except ValueError:
        return False, "invalid callback URL"
    if parsed.username or parsed.password:
        return False, "callback URL must not carry credentials"
    origin = _origin(url)
    if origin is None or origin not in origins:
        return False, f"callback origin not allowed: {parsed.scheme}://{parsed.netloc}"
    if not parsed.path.startswith(CALLBACK_PATH_PREFIX) or ".." in parsed.path.split("/"):
        return False, f"callback path must stay under {CALLBACK_PATH_PREFIX}"
    return True, ""


def filter_urls(urls: list[str]) -> tuple[list[str], list[tuple[str, str]]]:
    """Filter a list of URLs, returning (allowed, skipped) where skipped has (url, reason)."""
    allowed = []
    skipped = []
    for url in urls:
        skip, reason = should_skip_url(url)
        if skip:
            logger.info("Skipping URL %s: %s", url, reason)
            skipped.append((url, reason))
        else:
            allowed.append(url)
    return allowed, skipped
