"""Unified stealth browser for crawling and screenshots.

Uses nodriver (undetected Chrome) - launches the real Chrome browser,
inherently undetectable by WAFs (Datadome, PerimeterX, Cloudflare).
No anti-fingerprint JS needed: real Chrome = real fingerprint.
"""

import asyncio
import io
import math
import os
import time
import logging
from dataclasses import dataclass

from PIL import Image

from app.config import settings

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Viewport & compression settings
# ---------------------------------------------------------------------------
VIEWPORT_WIDTH = 1440
VIEWPORT_HEIGHT = 1500
MAX_SCREENSHOT_WIDTH = 1440
WEBP_QUALITY = 80
SCREENSHOT_TIMEOUT_S = 15  # Hard timeout on CDP capture_screenshot; on timeout we return text only

# ---------------------------------------------------------------------------
# CSS to hide common cookie/consent banners for cleaner screenshots
# ---------------------------------------------------------------------------
COOKIE_BANNER_HIDE_CSS = """
div[class*="cookie" i], div[class*="consent" i], div[class*="gdpr" i],
div[id*="cookie" i], div[id*="consent" i],
aside[class*="cookie" i], aside[class*="consent" i],
.cc-window, .cc-banner, .cc-revoke,
#onetrust-banner-sdk, #onetrust-consent-sdk,
#CybotCookiebotDialog, #CybotCookiebotDialogBodyUnderlay,
#didomi-host, #didomi-notice, .didomi-popup-container, .didomi-notice-banner,
.qc-cmp2-container, .qc-cmp-ui-container,
#popin_tc_privacy, .tc-privacy-banner, .tc-privacy-popin,
div[class*="cookiebot" i], div[class*="trustarc" i],
div[class*="privacy-notice" i], div[class*="cookie-notice" i],
div[class*="cookie-banner" i], div[class*="consent-banner" i],
div[class*="gdpr-lmd" i], div[class*="gdpr-wall" i], div[class*="gdpr_wall" i],
.modal-backdrop[style*="z-index"], div[class*="overlay"][class*="cookie" i]
{ display: none !important; visibility: hidden !important; opacity: 0 !important;
  pointer-events: none !important; }
"""

# JS cookie/consent dismiss (click accept buttons - covers major CMPs)
COOKIE_DISMISS_JS = """(() => {
    // Standard cookie/consent buttons
    document.querySelectorAll(
        '[class*=cookie] button[class*=accept],' +
        '[class*=cookie] button[class*=agree],' +
        '[class*=consent] button[class*=accept],' +
        '[class*=consent] button[class*=agree],' +
        '[id*=cookie] button,' +
        '[class*=gdpr] button[class*=accept],' +
        'button[id*=accept-cookies],' +
        'button[id*=cookie-accept],' +
        '.cc-accept,.cc-allow,.cc-dismiss'
    ).forEach(b => b.click());

    // Didomi CMP (tvanouvelles, Le Monde, etc.)
    if (window.Didomi) {
        try { window.Didomi.setUserAgreeToAll(); } catch(e) {}
    }
    document.querySelectorAll(
        '#didomi-notice-agree-button,' +
        '.didomi-continue-without-agreeing,' +
        '[class*=didomi] button[class*=agree]'
    ).forEach(b => b.click());

    // OneTrust
    document.querySelectorAll(
        '#onetrust-accept-btn-handler,' +
        '.onetrust-close-btn-handler'
    ).forEach(b => b.click());

    // Quantcast / TCF
    document.querySelectorAll(
        '.qc-cmp2-summary-buttons button[mode="primary"],' +
        '.qc-cmp-button[data-click="save"]'
    ).forEach(b => b.click());

    // TrustCommander / Commanders Act (TF1, LCI, Le Figaro TV, etc.)
    var tcAccept = document.querySelector('#popin_tc_privacy_button_3');
    if (tcAccept) tcAccept.click();
    else {
        var tcContinue = document.querySelector('#popin_tc_privacy_button_2');
        if (tcContinue) tcContinue.click();
    }

    // Yahoo consent (consent.yahoo.com or embedded form)
    document.querySelectorAll(
        'button[name="agree"], button.consent-form-agree,' +
        'button[value="agree"], .consent-overlay button.accept,' +
        'form[action*="consent"] button[type="submit"]'
    ).forEach(b => b.click());

    // Google consent + generic French/English/German/Spanish accept
    document.querySelectorAll('button, a[role="button"], input[type="submit"]').forEach(b => {
        var t = (b.innerText || b.value || '').trim().toLowerCase();
        if (t === 'tout accepter' || t === 'accept all'
            || t === 'accepter' || t === 'accepter et fermer'
            || t === 'accepter et continuer'
            || t === 'accepter les cookies' || t === "j'accepte"
            || t === 'agree' || t === 'i agree'
            || t === 'ok' || t === 'got it'
            || t === 'alle akzeptieren' || t === 'aceptar todo') {
            b.click();
        }
    });

    // Le Monde / French newspaper custom GDPR walls
    document.querySelectorAll('.gdpr-lmd-wall, [class*="gdpr-wall"], [class*="gdpr_wall"]').forEach(el => el.remove());

    // Force-remove any full-viewport fixed overlay with very high z-index (consent walls)
    document.querySelectorAll('div').forEach(el => {
        var s = window.getComputedStyle(el);
        if (s.position === 'fixed' && parseInt(s.zIndex) > 100000
            && el.offsetWidth > window.innerWidth * 0.8
            && el.offsetHeight > window.innerHeight * 0.5) {
            el.remove();
        }
    });

})()"""

# JS to prevent WebRTC IP leak - stub out RTCPeerConnection entirely.
# A web crawler never needs WebRTC; keeping a broken API is worse than
# removing it (fingerprint scanners see "no WebRTC" which is common
# with privacy-focused browsers and extensions).
WEBRTC_LEAK_BLOCK_JS = (
    "window.RTCPeerConnection = function() {};"
    "window.webkitRTCPeerConnection = window.RTCPeerConnection;"
    "window.mozRTCPeerConnection = window.RTCPeerConnection;"
)

# JS to inject CSS for hiding cookie banners (nodriver has no add_style_tag)
COOKIE_HIDE_CSS_JS = """(() => {
    var style = document.createElement('style');
    style.textContent = `""" + COOKIE_BANNER_HIDE_CSS.replace('`', '\\`') + """`;
    document.head.appendChild(style);
})()"""


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------
@dataclass
class BrowsedPage:
    """Result of a single page crawl: HTML content + optional screenshot."""
    html: str
    title: str
    screenshot_bytes: bytes | None = None
    blocked_reason: str | None = None


# ---------------------------------------------------------------------------
# Single Chrome browser - multiple tabs in parallel.
# One Chrome process, N concurrent tabs (controlled by semaphore).
# Scales easily: 3, 5, 10 tabs - all within a single Chrome.
# ---------------------------------------------------------------------------
_browser = None
_browser_lock: asyncio.Lock | None = None
_tab_semaphore: asyncio.Semaphore | None = None
_proxy_forwarder = None  # Shared proxy forwarder

# Circuit breaker: prevent infinite browser restart loops
_MAX_RESTARTS = 3           # max restarts within the window
_RESTART_WINDOW_S = 120     # sliding window (seconds)
_COOLDOWN_S = 60            # pause before allowing restarts after breaker trips
_restart_times: list[float] = []   # timestamps of recent restarts
_breaker_tripped_at: float = 0     # when the circuit breaker last tripped

_BROWSER_ARGS = [
    f"--window-size={VIEWPORT_WIDTH},{VIEWPORT_HEIGHT}",
    "--disable-background-timer-throttling",
    "--disable-backgrounding-occluded-windows",
    "--disable-renderer-backgrounding",
    "--disable-background-networking",   # No Chrome telemetry
    "--disable-component-update",        # No extension/component updates
    "--disable-domain-reliability",      # No domain reliability monitoring
    "--disable-client-side-phishing-detection",  # No safebrowsing model downloads
    "--no-pings",                        # No hyperlink auditing pings
    "--disable-sync",                    # No Google account sync
    "--disable-features=OptimizationGuideModelDownloading,"
        "OptimizationHintsFetching,"
        "OptimizationTargetPrediction,"
        "OptimizationHints,"
        "MediaRouter,"
        "Translate,"
        "SafeBrowsingEnhancedProtection,"
        "AutofillServerCommunication,"
        "SpareRendererForSitePerProcess",
    "--lang=en-US",
]


def _get_browser_lock() -> asyncio.Lock:
    global _browser_lock
    if _browser_lock is None:
        _browser_lock = asyncio.Lock()
    return _browser_lock


def _get_tab_semaphore() -> asyncio.Semaphore:
    global _tab_semaphore
    if _tab_semaphore is None:
        _tab_semaphore = asyncio.Semaphore(settings.max_browser_tabs)
    return _tab_semaphore


def _check_circuit_breaker() -> bool:
    """Return True if browser restart is allowed, False if breaker is tripped."""
    global _breaker_tripped_at
    now = time.monotonic()

    # Cooldown active - reject
    if _breaker_tripped_at and now - _breaker_tripped_at < _COOLDOWN_S:
        return False

    # Cooldown expired - reset breaker
    if _breaker_tripped_at and now - _breaker_tripped_at >= _COOLDOWN_S:
        _breaker_tripped_at = 0
        _restart_times.clear()

    return True


def _record_restart():
    """Record a browser restart; trip breaker if too many in the window."""
    global _breaker_tripped_at
    now = time.monotonic()

    # Prune old entries outside the window
    while _restart_times and now - _restart_times[0] > _RESTART_WINDOW_S:
        _restart_times.pop(0)

    _restart_times.append(now)

    if len(_restart_times) >= _MAX_RESTARTS:
        _breaker_tripped_at = now
        logger.error(
            "[BROWSER] Circuit breaker tripped: %d restarts in %ds, "
            "pausing for %ds",
            len(_restart_times), _RESTART_WINDOW_S, _COOLDOWN_S,
        )


async def _ensure_browser():
    """Lazy-create the single Chrome browser instance.

    Uses a circuit breaker to prevent infinite restart loops:
    max _MAX_RESTARTS restarts within _RESTART_WINDOW_S seconds,
    then pauses for _COOLDOWN_S seconds before allowing new starts.
    """
    global _browser, _proxy_forwarder

    if _browser is not None:
        # Health check: verify Chrome process is still running
        try:
            if getattr(_browser, 'stopped', False):
                rc = getattr(getattr(_browser, '_process', None), 'returncode', '?')
                logger.warning("[BROWSER] Chrome process died (exit=%s), will restart", rc)
                _browser = None
        except Exception:
            pass

    if _browser is not None:
        return _browser

    # Circuit breaker check - avoid infinite restart loops
    if not _check_circuit_breaker():
        remaining = int(_COOLDOWN_S - (time.monotonic() - _breaker_tripped_at))
        logger.warning("[BROWSER] Circuit breaker open, retry in %ds", remaining)
        return None

    async with _get_browser_lock():
        # Re-check after acquiring lock (another coroutine may have started it)
        if _browser is not None:
            return _browser
        if not _check_circuit_breaker():
            return None

        import nodriver as uc
        t0 = time.monotonic()
        logger.info("[BROWSER] nodriver imported: %dms",
                    int((time.monotonic() - t0) * 1000))

        headless_env = os.environ.get("BROWSER_HEADLESS", "false").lower()
        use_headless = headless_env == "true"
        args = list(_BROWSER_ARGS)

        # Rotating proxy
        proxy_url = settings.proxy_url
        if proxy_url:
            from nodriver.core.util import ProxyForwarder
            _proxy_forwarder = ProxyForwarder(proxy_url)
            logger.info("[BROWSER] Proxy forwarder started: %s → %s",
                        _proxy_forwarder.proxy_server, settings.proxy_host)
            args.append(f"--proxy-server={_proxy_forwarder.proxy_server}")
            args.append("--force-webrtc-ip-handling-policy=disable_non_proxied_udp")
            args.append("--enable-features=WebRtcHideLocalIpsWithMdns")

        import tempfile
        user_data_dir = tempfile.mkdtemp(prefix="uc_browser_")
        if proxy_url:
            import json as _json
            prefs_dir = os.path.join(user_data_dir, "Default")
            os.makedirs(prefs_dir, exist_ok=True)
            prefs = {"webrtc": {"ip_handling_policy": "disable_non_proxied_udp"}}
            with open(os.path.join(prefs_dir, "Preferences"), "w") as f:
                _json.dump(prefs, f)

        t1 = time.monotonic()
        try:
            _browser = await uc.start(
                headless=use_headless,
                user_data_dir=user_data_dir,
                browser_args=args,
            )
        except Exception as e:
            logger.error("[BROWSER] Chrome failed to start: %s: %s", type(e).__name__, e)
            _record_restart()
            return None
        _record_restart()
        mode = "headless" if use_headless else "headed"
        proxy_info = f", proxy={settings.proxy_host}" if proxy_url else ""
        logger.info("[BROWSER] Chrome started: %dms (%s%s, max_tabs=%d)",
                    int((time.monotonic() - t1) * 1000), mode, proxy_info,
                    settings.max_browser_tabs)
        return _browser


async def close_browsers():
    """Close the browser at shutdown."""
    global _browser
    if _browser is not None:
        try:
            _browser.stop()
        except Exception:
            pass
        _browser = None
    logger.info("[BROWSER] Chrome closed")


# ---------------------------------------------------------------------------
# Blank screenshot detection (3 checks: file size, pixel range, entropy)
# ---------------------------------------------------------------------------
def is_blank_screenshot(img_bytes: bytes) -> bool:
    """Detect if a screenshot is truly blank (solid color / empty page)."""
    try:
        if len(img_bytes) < 15_000:
            logger.info("Blank detection: file size %d bytes too small", len(img_bytes))
            return True

        img = Image.open(io.BytesIO(img_bytes))
        if img.mode in ("RGBA", "P"):
            img = img.convert("RGB")

        extrema = img.getextrema()
        for channel_min, channel_max in extrema:
            if (channel_max - channel_min) >= 10:
                break
        else:
            logger.info("Blank detection: pixel range too narrow %s", extrema)
            return True

        histogram = img.histogram()

        total = sum(histogram)
        entropy = 0.0
        for count in histogram:
            if count > 0:
                p = count / total
                entropy -= p * math.log2(p)
        if entropy < 1.0:
            logger.info("Blank detection: entropy %.2f too low", entropy)
            return True


        return False

    except Exception:
        logger.warning("Blank detection failed, assuming not blank")
        return False


def _check_and_compress(webp_bytes: bytes) -> bytes | None:
    """Blank check on WebP bytes (CDP outputs WebP directly). Runs in thread."""
    if is_blank_screenshot(webp_bytes):
        return None
    # CDP already outputs WebP at the right quality - no conversion needed.
    # Only resize if wider than MAX_SCREENSHOT_WIDTH (shouldn't happen with our viewport).
    img = Image.open(io.BytesIO(webp_bytes))
    try:
        if img.width > MAX_SCREENSHOT_WIDTH:
            ratio = MAX_SCREENSHOT_WIDTH / img.width
            new_size = (MAX_SCREENSHOT_WIDTH, int(img.height * ratio))
            img = img.resize(new_size, Image.LANCZOS)
            buf = io.BytesIO()
            img.save(buf, format="WEBP", quality=WEBP_QUALITY)
            return buf.getvalue()
    finally:
        img.close()
    return webp_bytes


# ---------------------------------------------------------------------------
# Early block detection (runs during content wait - no extra cost)
# ---------------------------------------------------------------------------
# Lightweight subset of anti-bot indicators for early detection in the browser.
# Full validation still runs in pipeline.py as a safety net.
_EARLY_BLOCK_KEYWORDS: list[str] = [
    "enable javascript and cookies",
    "please enable javascript",
    "please turn javascript on",
    "checking your browser",
    "just a moment",
    "attention required",
    "access denied",
    "you have been blocked",
    "verify you are human",
    "prove you are human",
    "captcha",
    "hcaptcha",
    "recaptcha",
    "cloudflare ray id",
    "ray id:",
    "cf-browser-verification",
    # Cloudflare multilingual
    "vérification de sécurité",
    "sécurité pour se protéger",
    "überprüfung ihrer verbindung",
    "comprobación de seguridad",
    "verificando a segurança",
    "ddos protection by",
    "px-captcha",
    "security check",
    "unusual traffic",
    "activez javascript",
    "veuillez patienter",
    "vérification en cours",
    "accès refusé",
    "zugriff verweigert",
    "acceso denegado",
]


def _detect_blocked_page(text: str) -> str | None:
    """Check if page text indicates a block/challenge page. Returns reason or None."""
    if not text or len(text) > 500:
        return None  # Real pages have substantial content
    lower = text.lower()
    for kw in _EARLY_BLOCK_KEYWORDS:
        if kw in lower:
            return f"anti-bot page detected: '{kw}'"
    return None


# ---------------------------------------------------------------------------
# Content wait (polling - nodriver has no wait_for_function)
# ---------------------------------------------------------------------------
async def _wait_for_content(tab, timeout_s: float = 8.0) -> str | None:
    """Wait for SPA content to render: min 1.0s + poll until text > 500 chars.

    Returns blocked_reason if an anti-bot page is detected early, None otherwise.
    Early detection avoids wasting 8-10s on challenge pages.
    """
    await asyncio.sleep(1.0)

    start = time.monotonic()
    while time.monotonic() - start < timeout_s:
        try:
            text = await tab.evaluate(
                "document.body ? document.body.innerText.trim() : ''"
            )
            if text and len(text) > 500:
                return None  # Real content loaded

            # After 2s of polling (3.0s total), check for anti-bot on short pages
            if time.monotonic() - start > 2.0 and text:
                blocked = _detect_blocked_page(text)
                if blocked:
                    return blocked
        except Exception:
            pass
        await asyncio.sleep(0.5)

    return None  # Timeout - not necessarily blocked


# ---------------------------------------------------------------------------
# Core crawl function - single navigation, HTML + viewport screenshot
# ---------------------------------------------------------------------------
async def crawl_page(
    url: str,
    take_screenshot: bool = True,
    timeout_ms: int = 30000,
) -> BrowsedPage:
    """Navigate to URL with undetected Chrome, extract HTML + viewport screenshot.

    Single Chrome, N concurrent tabs (controlled by semaphore).
    """
    browser = await _ensure_browser()
    if browser is None:
        logger.warning("No browser available for %s (circuit breaker open)", url)
        return BrowsedPage(html="", title="", screenshot_bytes=None,
                           blocked_reason="browser_unavailable")
    domain = url.split("/")[2] if "/" in url else url

    async with _get_tab_semaphore():
        return await _crawl_in_tab(browser, url, domain, take_screenshot, timeout_ms)


async def _crawl_in_tab(
    browser, url: str, domain: str,
    take_screenshot: bool, timeout_ms: int,
) -> BrowsedPage:
    """Run a crawl in a new tab (called under semaphore)."""
    # Open new tab and navigate
    t0 = time.monotonic()
    try:
        tab = await asyncio.wait_for(
            browser.get(url, new_tab=True),
            timeout=timeout_ms / 1000,
        )
    except asyncio.TimeoutError:
        logger.warning("Navigation timed out for %s after %dms", url, timeout_ms)
        return BrowsedPage(html="", title="", screenshot_bytes=None,
                           blocked_reason="navigation_timeout")
    except Exception as e:
        logger.warning("Navigation failed for %s: %s: %s", url, type(e).__name__, e)
        # Browser may have crashed - mark for restart on next call.
        # Only reset if _browser is still the same instance (avoid racing
        # with concurrent tabs that hit the same crash).
        global _browser
        if _browser is browser:
            try:
                _browser.stop()
            except Exception:
                pass
            _browser = None
            logger.info("[BROWSER] Marked for restart after navigation failure")
        return BrowsedPage(html="", title="", screenshot_bytes=None,
                           blocked_reason="browser_crashed")
    logger.info("[TIMING] %s navigate: %dms", domain, int((time.monotonic() - t0) * 1000))

    try:
        # 0. Neutral timezone (avoid FR timezone fingerprint leak)
        try:
            import nodriver.cdp.emulation as cdp_emu
            await tab.send(cdp_emu.set_timezone_override("America/New_York"))
        except Exception:
            pass

        # 1. Wait for content + early anti-bot detection
        t1 = time.monotonic()
        blocked_reason = await _wait_for_content(tab, timeout_s=8.0)
        logger.info("[TIMING] %s content ready: %dms", domain, int((time.monotonic() - t1) * 1000))


        # 2. If blocked, extract title and bail out immediately
        if blocked_reason:
            logger.info("[BLOCKED] %s early exit: %s", domain, blocked_reason)
            title = ""
            try:
                title = await tab.evaluate("document.title")
                title = str(title) if title else ""
            except Exception:
                pass
            return BrowsedPage(
                html="", title=title,
                screenshot_bytes=None, blocked_reason=blocked_reason,
            )

        # 3. Dismiss consent popups - fast path + slow poll only if overlay detected
        t2 = time.monotonic()
        consent_dismissed = False

        CONSENT_CLICK_JS = """(() => {
            var didomiBtn = document.querySelector('#didomi-notice-agree-button');
            if (didomiBtn) { didomiBtn.click(); return 'didomi'; }
            if (window.Didomi) {
                try { window.Didomi.setUserAgreeToAll(); return 'didomi-api'; } catch(e) {}
            }
            var otBtn = document.querySelector('#onetrust-accept-btn-handler');
            if (otBtn) { otBtn.click(); return 'onetrust'; }
            var tcBtn = document.querySelector('#popin_tc_privacy_button_3');
            if (tcBtn) { tcBtn.click(); return 'trustcommander'; }
            var yahooBtn = document.querySelector(
                'button[name="agree"], button.consent-form-agree,' +
                'form[action*="consent"] button[type="submit"]');
            if (yahooBtn) { yahooBtn.click(); return 'yahoo'; }
            var found = false;
            document.querySelectorAll('button, a[role="button"], input[type="submit"]').forEach(b => {
                var t = (b.innerText || b.value || '').trim().toLowerCase();
                if (t === 'tout accepter' || t === 'accept all'
                    || t === 'accepter et continuer'
                    || t === 'accepter les cookies' || t === "j'accepte"
                    || t === 'agree' || t === 'i agree'
                    || t === 'ok' || t === 'got it') {
                    b.click(); found = true;
                }
            });
            return found ? 'generic' : null;
        })()"""

        # Fast path: try once immediately
        try:
            dismissed = await tab.evaluate(CONSENT_CLICK_JS)
            if dismissed:
                logger.info("[CONSENT] %s dismissed via %s (fast)", domain, dismissed)
                consent_dismissed = True
        except Exception:
            pass

        # Slow path: only poll if a consent overlay is detected
        if not consent_dismissed:
            try:
                has_overlay = await tab.evaluate("""
                    !!(document.querySelector(
                        '#didomi-host, #didomi-notice, .didomi-popup-container,' +
                        '#onetrust-consent-sdk, #onetrust-banner-sdk,' +
                        '#CybotCookiebotDialog,' +
                        '.qc-cmp2-container,' +
                        '#popin_tc_privacy,' +
                        'form[action*="consent"],' +
                        '[class*="consent-overlay"], [class*="cookie-wall"]'
                    ) || document.body.innerText.length < 200)
                """)
            except Exception:
                has_overlay = False

            if has_overlay:
                logger.info("[CONSENT] %s overlay detected, polling...", domain)
                for _attempt in range(6):
                    await asyncio.sleep(0.5)
                    try:
                        dismissed = await tab.evaluate(CONSENT_CLICK_JS)
                        if dismissed:
                            logger.info("[CONSENT] %s dismissed via %s (attempt %d)", domain, dismissed, _attempt + 1)
                            consent_dismissed = True
                            break
                    except Exception:
                        pass

        # Post-consent: wait for SPA to re-render
        if consent_dismissed:
            await asyncio.sleep(1.0)
            t_post = time.monotonic()
            while time.monotonic() - t_post < 5.0:
                try:
                    clen = await tab.evaluate(
                        "document.body ? document.body.innerText.trim().length : 0"
                    )
                    if clen and int(clen) > 500:
                        logger.info("[CONSENT] %s content appeared after consent: %d chars", domain, int(clen))
                        break
                except Exception:
                    pass
                await asyncio.sleep(0.5)

        # Fallback: run the full dismiss script (Quantcast, Google, misc CMPs)
        if not consent_dismissed:
            try:
                await tab.evaluate(COOKIE_DISMISS_JS)
            except Exception:
                pass
            await asyncio.sleep(0.2)

        # Hide remaining banners via CSS injection
        try:
            await tab.evaluate(COOKIE_HIDE_CSS_JS)
        except Exception:
            pass

        await asyncio.sleep(0.2)

        # Check if consent redirect happened (Google, etc.)
        try:
            current_url = await tab.evaluate("window.location.href")
            if current_url and url not in str(current_url):
                logger.info("[TIMING] %s consent redirect detected (%s), waiting for new page", domain, current_url)
                start_redir = time.monotonic()
                while time.monotonic() - start_redir < 5.0:
                    await asyncio.sleep(0.5)
                    try:
                        new_len = await tab.evaluate(
                            "document.body ? document.body.innerText.trim().length : 0"
                        )
                        if new_len and int(new_len) > 500:
                            break
                    except Exception:
                        pass
        except Exception:
            pass

        logger.info("[TIMING] %s consent dismiss: %dms", domain, int((time.monotonic() - t2) * 1000))

        # 3b. Scroll down to trigger lazy-loading, then back to top
        t_scroll = time.monotonic()
        try:
            await tab.evaluate("""(() => {
                document.querySelectorAll('button, a, [role="button"]').forEach(b => {
                    var t = (b.innerText || '').trim().toLowerCase();
                    if (t === 'lire la suite' || t === 'read more'
                        || t === 'voir plus' || t === 'afficher plus'
                        || t === 'see more' || t === 'show more'
                        || t === 'continuer la lecture'
                        || t === 'lire l\\'article') {
                        b.click();
                    }
                });
            })()""")
            await tab.evaluate("""
                new Promise(resolve => {
                    let pos = 0;
                    const step = Math.max(window.innerHeight, 800);
                    const maxH = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
                    const timer = setInterval(() => {
                        pos += step;
                        window.scrollTo(0, pos);
                        if (pos >= maxH) { clearInterval(timer); window.scrollTo(0, 0); resolve(); }
                    }, 150);
                    setTimeout(() => { clearInterval(timer); window.scrollTo(0, 0); resolve(); }, 5000);
                })
            """)
            await asyncio.sleep(0.5)
        except Exception:
            pass
        logger.info("[TIMING] %s scroll+expand: %dms", domain, int((time.monotonic() - t_scroll) * 1000))

        # 4. Extract title
        t3 = time.monotonic()
        title = ""
        try:
            title = await tab.evaluate("document.title")
            title = str(title) if title else ""
        except Exception:
            pass
        logger.info("[TIMING] %s title extract: %dms", domain, int((time.monotonic() - t3) * 1000))

        # 5. Extract visible text content
        t4 = time.monotonic()
        text_content = ""
        try:
            text_content = await tab.evaluate(
                "document.body ? document.body.innerText : ''"
            )
            text_content = str(text_content).strip() if text_content else ""
        except Exception:
            logger.warning("Failed to extract text from %s", url)
        logger.info("[TIMING] %s innerText extract: %dms (%d chars)", domain, int((time.monotonic() - t4) * 1000), len(text_content))

        # 5b. Blank page detection - no meaningful content loaded
        if len(text_content) < 500:
            reason = "blank page (no content)" if not text_content else _detect_blocked_page(text_content)
            if not reason and len(text_content) < 500:
                reason = f"blank page ({len(text_content)} chars)"
            logger.info("[BLANK] %s %s", domain, reason)
            return BrowsedPage(
                html=text_content, title=title,
                screenshot_bytes=None, blocked_reason=reason,
            )

        # 6. Viewport screenshot via CDP (viewport-only, hard-timeout)
        #    capture_beyond_viewport=True with large clips forced Chrome to
        #    rasterize offscreen regions up to 15M pixels, cascading lazy-loaded
        #    images and stalling 4-8 min on Wikipedia. Viewport-only capture
        #    takes ~100ms because pixels are already painted.
        screenshot_bytes = None
        if take_screenshot:
            t5 = time.monotonic()
            try:
                import base64
                import nodriver.cdp.page as cdp_page

                clip = cdp_page.Viewport(
                    x=0, y=0,
                    width=VIEWPORT_WIDTH,
                    height=VIEWPORT_HEIGHT,
                    scale=1,
                )
                data = await asyncio.wait_for(
                    tab.send(cdp_page.capture_screenshot(
                        format_="webp",
                        quality=WEBP_QUALITY,
                        clip=clip,
                        capture_beyond_viewport=False,
                    )),
                    timeout=SCREENSHOT_TIMEOUT_S,
                )
                webp_bytes = base64.b64decode(data) if isinstance(data, str) else data
                logger.info("[TIMING] %s CDP screenshot: %dms (%d KB, viewport %dx%d)",
                            domain, int((time.monotonic() - t5) * 1000),
                            len(webp_bytes) // 1024, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

                t7 = time.monotonic()
                screenshot_bytes = await asyncio.to_thread(
                    _check_and_compress, webp_bytes
                )
                logger.info("[TIMING] %s blank check: %dms", domain, int((time.monotonic() - t7) * 1000))

                if screenshot_bytes:
                    logger.info(
                        "Screenshot for %s: %d KB WebP (viewport %dx%d)",
                        url, len(screenshot_bytes) // 1024, VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                    )
                else:
                    logger.info("Screenshot blank for %s (SPA or empty page)", url)
            except asyncio.TimeoutError:
                logger.warning(
                    "[TIMING] %s CDP screenshot timed out after %ds - returning text without screenshot",
                    domain, SCREENSHOT_TIMEOUT_S,
                )
            except Exception as e:
                logger.warning("Screenshot failed for %s: %r", url, e)

        return BrowsedPage(
            html=text_content,
            title=title,
            screenshot_bytes=screenshot_bytes,
        )
    finally:
        try:
            await tab.close()
        except Exception:
            pass
