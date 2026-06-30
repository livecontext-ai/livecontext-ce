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
]

# Hostname-suffix denylist applied BEFORE DNS resolution. Guards against
# DNS poisoning, Host-header tricks, and split-horizon DNS where the same
# name resolves to a public IP externally and a private one inside the mesh.
# Called by both `is_url_blacklisted` (cheap fetch) and
# `is_url_safe_for_navigation` (browser-agent on every page event).
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
}


def _hostname_blocked_by_suffix(hostname: str) -> tuple[bool, str]:
    """Check hostname against BLOCKED_HOSTNAME_SUFFIXES (exact or sub-domain).

    Returns (blocked, matched_suffix).
    """
    for suffix in BLOCKED_HOSTNAME_SUFFIXES:
        if hostname == suffix or hostname.endswith("." + suffix):
            return True, suffix
    return False, ""


def _ip_literal_blocked(hostname: str) -> tuple[bool, str]:
    """If `hostname` is an IP literal, check it against BLOCKED_NETWORKS.

    Returns (blocked, reason). Returns (False, "") if hostname is not an IP.
    """
    try:
        ip = ipaddress.ip_address(hostname)
    except ValueError:
        return False, ""
    for network in BLOCKED_NETWORKS:
        if ip in network:
            return True, f"SSRF blocked: literal IP {hostname} is in {network}"
    return False, ""


def _is_private_ip(hostname: str) -> bool:
    """Resolve hostname and check if it points to a private/internal IP."""
    try:
        infos = socket.getaddrinfo(hostname, None, socket.AF_UNSPEC, socket.SOCK_STREAM)
        for family, _, _, _, sockaddr in infos:
            ip = ipaddress.ip_address(sockaddr[0])
            for network in BLOCKED_NETWORKS:
                if ip in network:
                    return True
    except (socket.gaierror, ValueError, OSError):
        # DNS resolution failed - block to be safe
        return True
    return False


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

    hostname = (parsed.hostname or "").lower()
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

    # SSRF - DNS-resolved private IPs
    if _is_private_ip(hostname):
        return True, f"SSRF blocked: {hostname} resolves to private/internal IP"

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
    """Strict SSRF check designed for browser-agent navigation events.

    Called on EVERY page-navigated CDP event (post-redirect, post-iframe,
    post-window.open), not only on the user-supplied initial URL. This
    guards against redirect chains and meta-refresh tricks that bypass
    a check done only at the entry point.

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

    hostname = (parsed.hostname or "").lower()
    if not hostname:
        return False, "no hostname"

    blocked, suffix = _hostname_blocked_by_suffix(hostname)
    if blocked:
        return False, f"blocked internal/metadata hostname: {hostname} (suffix: {suffix})"

    blocked, reason = _ip_literal_blocked(hostname)
    if blocked:
        return False, reason

    if _is_private_ip(hostname):
        return False, f"SSRF blocked: {hostname} resolves to private/internal IP"

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
