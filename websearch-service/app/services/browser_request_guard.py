"""Browser-wide SSRF guard enforced through CDP `Fetch` request interception.

Why this exists: checking only the URL a caller hands us is not enough. A
public page can answer 302 to `http://169.254.169.254/`, embed an iframe on
`http://10.0.0.5/`, or run a worker that fetches an internal host. Chrome
follows all of those on its own. This guard pauses EVERY request Chrome makes
and releases it only when `crawl_filter.is_request_url_allowed` accepts the
target (scheme, internal hostname suffixes, IP literal, DNS resolution).

Fetch is enabled on the BROWSER target, not on a page: verified against a
real Chrome, a browser-level interceptor also sees redirect hops,
cross-site (out-of-process) iframes, dedicated workers and service workers,
which a page-level interceptor misses.

Known limit: Chrome does not route WebSocket handshakes through Fetch, so
this guard never sees `ws://`/`wss://`. The in-process browsers cover them
at connect time instead: Chrome reaches the network only through the egress
guard proxy (`egress_guard_proxy`), loopback and link-local included
(`--proxy-bypass-list=<-loopback>`), and the proxy refuses any non-public
destination. With an upstream rotating proxy configured (crawler), a
WebSocket goes to that proxy and is resolved and dialled from its network.

Two adapters, one per CDP client in this service:
  - `install_on_nodriver`  -> the crawler's nodriver browser connection
  - `install_on_cdp_use`   -> browser-use's root `cdp_use.CDPClient`
Both raise when the guard cannot be installed; callers must then refuse to
browse (fail closed).
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Any, Callable, Optional
from urllib.parse import urlparse

from app.services.crawl_filter import is_request_url_allowed, is_unresolvable_reason

logger = logging.getLogger(__name__)

# Pause every request at the request stage (before anything leaves Chrome).
_PATTERN_URL = "*"
_PATTERN_STAGE = "Request"
# Network.ErrorReason used to fail a refused request.
_BLOCK_ERROR_REASON = "BlockedByClient"
# Network.ErrorReason for a name with no DNS answer: the same failure (and
# error page) Chrome shows on its own for a typo'd or dead domain.
_UNRESOLVED_ERROR_REASON = "NameNotResolved"
# Strong references to in-flight cdp-use responses (the loop only keeps weak ones).
_pending_responses: set[asyncio.Task] = set()


@dataclass(frozen=True)
class BlockedRequest:
    """A request the guard refused, as reported to `on_blocked` listeners."""

    url: str
    resource_type: str
    frame_id: str
    reason: str

    @property
    def is_document(self) -> bool:
        """True for a frame navigation (top-level page or iframe)."""
        return self.resource_type.lower() == "document"

    @property
    def is_unresolvable(self) -> bool:
        """True when the host has no DNS answer: the ordinary "site can't be
        reached" failure of a typo'd, dead or briefly unresolvable domain, not
        an SSRF attempt. Listeners must not treat it as a blocked domain."""
        return is_unresolvable_reason(self.reason)


class RequestGuard:
    """Decides whether a paused browser request may continue.

    `decide` defaults to `crawl_filter.is_request_url_allowed`.

    Allowed decisions are cached per (scheme, hostname) for `cache_ttl_s`
    seconds so a page with hundreds of subresources on a few hosts does not
    trigger one DNS lookup per request. A refusal is cached only for
    `refusal_cache_ttl_s` (a burst of requests to one refused host costs one
    lookup, a fixed record is picked up almost at once), and a name that
    does not resolve or a decision error is never cached: a transient DNS
    failure must not keep a real site refused. The decision function runs
    in a worker thread because it may block on DNS. Any error while deciding
    refuses the request.
    """

    def __init__(
        self,
        decide: Optional[Callable[[str], tuple[bool, str]]] = None,
        cache_ttl_s: float = 30.0,
        max_cache_entries: int = 2048,
        on_blocked: Optional[Callable[[BlockedRequest], None]] = None,
        refusal_cache_ttl_s: float = 2.0,
    ) -> None:
        self._decide = decide
        self._cache_ttl_s = cache_ttl_s
        self._refusal_cache_ttl_s = refusal_cache_ttl_s
        self._max_cache_entries = max_cache_entries
        self._cache: dict[tuple[str, str], tuple[float, bool, str]] = {}
        self._on_blocked = on_blocked

    async def allow(self, url: str) -> tuple[bool, str]:
        key = _cache_key(url)
        now = time.monotonic()
        if key is not None:
            hit = self._cache.get(key)
            if hit is not None and hit[0] > now:
                return hit[1], hit[2]
        ttl = self._cache_ttl_s
        try:
            decide = self._decide or is_request_url_allowed
            allowed, reason = await asyncio.to_thread(decide, url)
            if not allowed:
                ttl = 0.0 if is_unresolvable_reason(reason) else self._refusal_cache_ttl_s
        except Exception as e:  # noqa: BLE001 - any failure must refuse
            allowed, reason, ttl = False, f"request guard error: {type(e).__name__}", 0.0
        if key is not None and ttl > 0:
            if len(self._cache) >= self._max_cache_entries:
                self._cache.clear()
            self._cache[key] = (now + ttl, allowed, reason)
        return allowed, reason

    async def verdict(self, url: str, resource_type: str, frame_id: str) -> Optional[str]:
        """None to continue the request, else the Network.ErrorReason to fail it with.

        A host that does not resolve fails with `NameNotResolved`, like
        Chrome's own DNS error, and reaches `on_blocked` flagged
        `is_unresolvable`; every other refusal fails with `BlockedByClient`.
        """
        allowed, reason = await self.allow(url)
        if allowed:
            return None
        blocked = BlockedRequest(
            url=url, resource_type=resource_type or "", frame_id=frame_id or "",
            reason=reason,
        )
        if blocked.is_unresolvable:
            logger.info("Browser request failed, host does not resolve: %s (%s)",
                        url[:200], blocked.resource_type)
        else:
            logger.warning(
                "Browser request blocked by SSRF guard: %s (%s) - %s",
                url[:200], blocked.resource_type, reason,
            )
        if self._on_blocked is not None:
            try:
                self._on_blocked(blocked)
            except Exception:  # noqa: BLE001 - listener bugs never unblock
                logger.debug("on_blocked listener failed", exc_info=True)
        return _UNRESOLVED_ERROR_REASON if blocked.is_unresolvable else _BLOCK_ERROR_REASON

    async def review(self, url: str, resource_type: str, frame_id: str) -> bool:
        """Return True to continue the request, False to fail it."""
        return await self.verdict(url, resource_type, frame_id) is None


def _cache_key(url: str) -> Optional[tuple[str, str]]:
    try:
        parsed = urlparse(url)
    except Exception:
        return None
    scheme = (parsed.scheme or "").lower()
    if scheme not in ("http", "https", "ws", "wss"):
        return None
    host = (parsed.hostname or "").lower().rstrip(".")
    return (scheme, host) if host else None


def _enum_value(value: Any) -> str:
    """nodriver passes CDP enums; cdp-use passes plain strings."""
    return str(getattr(value, "value", value) or "")


# ---------------------------------------------------------------------------
# nodriver adapter (crawler)
# ---------------------------------------------------------------------------

def nodriver_browser_connection(browser: Any) -> Any:
    """The BROWSER-target connection of a nodriver `Browser`.

    nodriver up to 0.4x kept it in `browser.connection`. From 0.50 the
    `Browser` object IS that connection (it subclasses `Connection`) and
    `browser.connection` stays None, which made the guard install fail and
    the crawler refuse every page.
    """
    connection = getattr(browser, "connection", None)
    return connection if connection is not None else browser


async def install_on_nodriver(connection: Any, guard: RequestGuard) -> None:
    """Intercept every request of the browser behind `connection`.

    `connection` must be the BROWSER connection
    (`nodriver_browser_connection(browser)`), not a tab. Raises when Fetch
    cannot be enabled.
    """
    from nodriver import cdp

    async def _on_paused(event: Any, conn: Any = None) -> None:
        target = conn or connection
        request_id = event.request_id
        try:
            error_reason = await guard.verdict(
                event.request.url, _enum_value(event.resource_type), _enum_value(event.frame_id),
            )
        except Exception:  # noqa: BLE001
            error_reason = _BLOCK_ERROR_REASON
        try:
            if error_reason is None:
                await target.send(cdp.fetch.continue_request(request_id=request_id))
            else:
                await target.send(cdp.fetch.fail_request(
                    request_id=request_id,
                    error_reason=cdp.network.ErrorReason(error_reason),
                ))
        except Exception:  # noqa: BLE001 - request may already be gone (tab closed)
            logger.debug("Fetch response for %s failed", request_id, exc_info=True)

    connection.add_handler(cdp.fetch.RequestPaused, _on_paused)
    await connection.send(cdp.fetch.enable(patterns=[
        cdp.fetch.RequestPattern(
            url_pattern=_PATTERN_URL,
            request_stage=cdp.fetch.RequestStage(_PATTERN_STAGE),
        ),
    ]))


# ---------------------------------------------------------------------------
# cdp-use adapter (browser-use agent)
# ---------------------------------------------------------------------------

async def install_on_cdp_use(
    cdp_client: Any,
    guard: RequestGuard,
    proxy_credentials: Optional[tuple[str, str]] = None,
) -> None:
    """Intercept every request of the browser behind a root `cdp_use` client.

    cdp-use awaits event handlers inside its message loop, so the handler
    must NOT await a command itself (the response could never be read): it
    schedules the decision as a task and returns. Paused events carry the
    session id of the session that paused them; the answer goes back on it.
    Raises when Fetch cannot be enabled.

    cdp-use keeps ONE handler per event and a later `register` replaces it,
    and `Fetch.enable` replaces the previous Fetch configuration of the
    session. browser-use's own proxy-auth setup does both (its handler
    continues every paused request), so it must not run next to the guard:
    with `proxy_credentials` this function takes that job over in the same
    registration. Paused requests still go through the guard alone, and
    proxy authentication challenges (`Fetch.authRequired`, enabled by
    `handleAuthRequests`) are answered with the credentials.
    """

    async def _respond(params: dict, session_id: Optional[str]) -> None:
        request_id = params.get("requestId")
        request = params.get("request") or {}
        try:
            error_reason = await guard.verdict(
                str(request.get("url") or ""),
                str(params.get("resourceType") or ""),
                str(params.get("frameId") or ""),
            )
        except Exception:  # noqa: BLE001
            error_reason = _BLOCK_ERROR_REASON
        try:
            if error_reason is None:
                await cdp_client.send.Fetch.continueRequest(
                    params={"requestId": request_id}, session_id=session_id,
                )
            else:
                await cdp_client.send.Fetch.failRequest(
                    params={"requestId": request_id, "errorReason": error_reason},
                    session_id=session_id,
                )
        except Exception:  # noqa: BLE001 - request may already be gone
            logger.debug("Fetch response for %s failed", request_id, exc_info=True)

    def _on_paused(params: dict, session_id: Optional[str] = None) -> None:
        _schedule(_respond(params or {}, session_id))

    async def _answer_auth(params: dict, session_id: Optional[str]) -> None:
        request_id = params.get("requestId")
        challenge = params.get("authChallenge") or {}
        if str(challenge.get("source") or "").lower() == "proxy" and proxy_credentials:
            response = {"response": "ProvideCredentials",
                        "username": proxy_credentials[0], "password": proxy_credentials[1]}
        else:
            # A server (not proxy) challenge: let Chrome handle it as usual.
            response = {"response": "Default"}
        try:
            await cdp_client.send.Fetch.continueWithAuth(
                params={"requestId": request_id, "authChallengeResponse": response},
                session_id=session_id,
            )
        except Exception:  # noqa: BLE001 - request may already be gone
            logger.debug("Fetch auth answer for %s failed", request_id, exc_info=True)

    def _on_auth_required(params: dict, session_id: Optional[str] = None) -> None:
        _schedule(_answer_auth(params or {}, session_id))

    cdp_client.register.Fetch.requestPaused(_on_paused)
    enable_params: dict = {
        "patterns": [{"urlPattern": _PATTERN_URL, "requestStage": _PATTERN_STAGE}],
    }
    if proxy_credentials:
        cdp_client.register.Fetch.authRequired(_on_auth_required)
        enable_params["handleAuthRequests"] = True
    await cdp_client.send.Fetch.enable(params=enable_params)


def _schedule(coro: Any) -> None:
    task = asyncio.get_running_loop().create_task(coro)
    _pending_responses.add(task)
    task.add_done_callback(_pending_responses.discard)
