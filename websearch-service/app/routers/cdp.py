"""CDP WebSocket bridge for the browser-agent live view.

Path: ``GET /cdp/{session_id}?token=<jwt>``  (upgraded to WebSocket)

Flow:
  1. Frontend receives a 5-min HS256 JWT alongside the SSE event that
     announces the browser-agent session has started (the Java side issues
     it and forwards it through the orchestrator's per-step metadata).
  2. Frontend opens ``new WebSocket("wss://websearch-host/cdp/{sid}?token=...")``.
  3. This router verifies the token (signature + exp + sid match) and
     looks up the live ``BrowserAgentSession`` for that ``session_id``.
  4. Once authenticated, the router bridges client ↔ live Chromium CDP:
       - Chromium-bound: the screencast-frame stream, mouse/keyboard
         events the user produces in the takeover overlay.
       - Client-bound: ``Page.screencastFrame`` JSON, ``Input.dispatch*``
         acks, mouse/keyboard echoes.
  5. The first inbound user input (mouse/keyboard) LPUSHes
     ``{cmd: "PAUSE"}`` to ``agent:run:{r}:node:{n}:control`` so the
     runner pauses on its next step boundary. The signal stays blocking
     until the orchestrator-side resume endpoint resolves the
     ``BROWSER_USER_TAKEOVER`` signal - closing the WS does NOT
     auto-resume.

Auth:
  - The JWT is the only auth check. It carries ``sid``, ``rid``, ``nid``,
    ``sub`` (user id at issue time) and ``exp``. We re-check ``sid`` matches
    the URL path so a token for one session can't be replayed against
    another. The shared ``GatewayAuthMiddleware`` is bypassed for WS
    upgrades - the JWT is the equivalent gate.

Limitations:
  - browser-use 0.12.x does NOT publicly expose the underlying CDP
    endpoint URL or Playwright Page off the Agent. Until that lands (or
    the Docker-isolated runner exposes its Chromium DevTools port over
    `browser-agent-net`), this router is a STUB: it accepts the
    connection, validates the JWT, sends a single greeting frame, and
    holds the socket open while echoing PAUSE / RESUME control commands
    back through Redis.
  - The TODO marker is in the response payload itself so the frontend
    knows to render a fallback (static screenshot poll) until the bridge
    is live.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any, Optional

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect, status

from app.config import settings
from app.services.browser_agent import get_session_state
from app.services.browser_agent.cdp_jwt import JWTError, verify_cdp_token
from app.services.browser_agent.redis_io import control_key
from app.services.redis_client import get_redis

logger = logging.getLogger(__name__)
router = APIRouter()


# WS close codes - keep in sync with the Java/frontend side so error
# messages survive minification.
CDP_CLOSE_NO_SECRET = 1011         # internal error: no secret configured
CDP_CLOSE_BAD_TOKEN = 4401         # auth failure (custom 4xxx range)
CDP_CLOSE_SESSION_GONE = 4404      # session no longer active
CDP_CLOSE_SID_MISMATCH = 4403      # token sid != url sid


def _mark_session_activity(sess) -> None:
    """Refresh the takeover activity clock from a live user input.

    The FIRST input flags the takeover (``takeover_active`` + ``takeover_started_at``);
    EVERY input refreshes ``last_activity`` so the runner's post-completion hold
    keeps Chromium open while the user is actively driving and releases it on
    inactivity. Runs in the same event loop as the runner, so a plain field
    write is race-free for cooperative tasks - no lock needed. No-op on a None
    session (the WS may outlive the in-process registry entry by a beat).
    """
    if sess is None:
        return
    now = time.time()
    if not sess.takeover_active:
        sess.takeover_active = True
        sess.takeover_started_at = now
    sess.last_activity = now


@router.websocket("/cdp/{session_id}")
async def cdp_websocket(
    ws: WebSocket,
    session_id: str,
    token: str = Query(default="", description="HS256 JWT issued by the Java orchestrator"),
):
    """WebSocket bridge between the frontend and the live Chromium tab.

    See module docstring for the full flow.
    """
    # 1. Pre-handshake validation (closes WITHOUT accepting if anything is off).
    if not settings.cdp_jwt_secret:
        logger.warning("cdp ws rejected: no CDP_JWT_SECRET configured")
        await ws.close(code=CDP_CLOSE_NO_SECRET, reason="server misconfigured")
        return

    try:
        claims = verify_cdp_token(token, secret=settings.cdp_jwt_secret)
    except JWTError as e:
        logger.info("cdp ws rejected: jwt error: %s", e)
        await ws.close(code=CDP_CLOSE_BAD_TOKEN, reason=str(e))
        return

    if claims.get("sid") != session_id:
        logger.info(
            "cdp ws rejected: sid mismatch (token=%r url=%r)",
            claims.get("sid"), session_id,
        )
        await ws.close(code=CDP_CLOSE_SID_MISMATCH, reason="sid mismatch")
        return

    run_id = str(claims.get("rid") or "")
    node_id = str(claims.get("nid") or "")
    user_id = str(claims.get("sub") or "")

    # 2. Look up the live session - if it ended already, nothing to bridge.
    sess = get_session_state(session_id)
    if sess is None:
        logger.info("cdp ws rejected: session not active: %s", session_id)
        await ws.close(code=CDP_CLOSE_SESSION_GONE, reason="session not active")
        return

    # 3. Accept and announce.
    await ws.accept()
    logger.info(
        "cdp ws accepted: session_id=%s run_id=%s node_id=%s user_id=%s",
        session_id, run_id, node_id, user_id,
    )

    # Resolve the live Chromium CDP WS URL. Two sources, in order:
    #   1. In-process runner: BrowserAgentSession.cdp_endpoint_url + the
    #      sibling .cdp_browser_url, both set by
    #      runner.py::_extract_cdp_endpoints right after browser-use boots.
    #   2. Docker-isolated runner: container publishes the URL on
    #      `agent:browser:cdp:{session_id}` (15min TTL). cdp.py reads it.
    #      Docker mode currently exposes only the page-level URL - tab
    #      follow falls back to legacy single-page screencast in that case.
    cdp_endpoint = sess.cdp_endpoint_url
    cdp_browser_url = sess.cdp_browser_url
    if not cdp_endpoint:
        try:
            redis = get_redis()
            cdp_endpoint = await redis.get(f"agent:browser:cdp:{session_id}")
            if isinstance(cdp_endpoint, bytes):
                cdp_endpoint = cdp_endpoint.decode("utf-8", errors="ignore")
        except Exception:
            logger.debug("cdp endpoint Redis lookup failed", exc_info=True)
            cdp_endpoint = None

    bridge_mode = "live" if cdp_endpoint else "stub"

    await _send_json(ws, {
        "type": "greeting",
        "session_id": session_id,
        "run_id": run_id,
        "node_id": node_id,
        "step_index": sess.step_index,
        "last_url": sess.last_url,
        "bridge_mode": bridge_mode,
    })

    # 4. Drive the bridge.
    #    LIVE mode: full bidirectional proxy to the Chromium CDP endpoint.
    #    Frames flow client ↔ Chromium; we tap inbound to push PAUSE on
    #    first Input.* and to tail the steps stream onto the same socket.
    #    STUB mode: passive - only step-stream tail + PAUSE on first input.
    pause_pushed = False

    def _mark_user_activity() -> None:
        # Every live user input (click/key/scroll/mouse-move) refreshes the
        # activity clock + flags the takeover, so the runner's post-completion
        # hold keeps Chromium open while the user is actively driving and
        # releases it on inactivity.
        _mark_session_activity(sess)

    try:
        tail = asyncio.create_task(_tail_steps_to_ws(ws, run_id, node_id))
        try:
            if bridge_mode == "live":
                await _proxy_live_cdp(
                    ws=ws,
                    cdp_url=cdp_endpoint,
                    browser_url=cdp_browser_url,
                    run_id=run_id,
                    node_id=node_id,
                    on_pause_pushed=lambda: setattr(sess, "paused", True),
                    on_user_activity=_mark_user_activity,
                )
            else:
                while True:
                    msg = await ws.receive_text()
                    if _is_user_input(msg):
                        _mark_user_activity()
                        if not pause_pushed:
                            await _push_pause(run_id, node_id)
                            pause_pushed = True
                            await _send_json(ws, {"type": "paused", "reason": "user_takeover"})
                    await _send_json(ws, {"type": "ack", "echo": msg[:200]})
        finally:
            tail.cancel()
            try:
                await tail
            except (asyncio.CancelledError, Exception):
                pass
    except WebSocketDisconnect:
        logger.info("cdp ws disconnected: session_id=%s pause_pushed=%s mode=%s",
                    session_id, pause_pushed, bridge_mode)
    except Exception:
        logger.exception("cdp ws bridge error: session_id=%s", session_id)
        try:
            await ws.close(code=status.WS_1011_INTERNAL_ERROR)
        except Exception:
            pass


# Tuned screencast params - pulled out so the multi-tab swap path uses the
# same payload as the single-page path. Frame budget (~120 KB at q=92,
# 1200×675) sized so 15 fps stays under ~1.8 MB/s through Caddy + the
# disabled-deflate Cloudflare WS tunnel. Tightening quality below 92 in
# earlier tuning runs produced visible JPEG ringing on rendered text in
# the side panel; widening past 1200 hit a fractional CSS scale ratio
# that softened text via bilinear interpolation.
_SCREENCAST_PARAMS: dict[str, Any] = {
    "format": "jpeg",
    "quality": 92,
    "everyNthFrame": 1,
    "maxWidth": 1200,
    "maxHeight": 675,
}


def _build_page_url_from_browser_url(browser_url: str, target_id: str) -> str:
    """Derive the page-level CDP WS URL from the browser-level one.

    Both share the same host:port; only the trailing
    ``/devtools/{browser,page}/<id>`` segment differs. Used to open a
    fresh page-level connection when ``Target.attachedToTarget`` /
    ``Target.targetCreated`` surfaces a new tab without giving us its
    full URL.
    """
    # Find the `/devtools/` boundary so we keep scheme://host:port intact
    # even when browser_url has a query string or trailing slash.
    marker = "/devtools/"
    idx = browser_url.find(marker)
    if idx < 0:
        # Defensive: malformed URL → return as-is so caller logs an error
        # rather than silently constructing a junk URL.
        return browser_url
    return browser_url[: idx + len(marker)] + "page/" + target_id


async def _proxy_live_cdp(
    *,
    ws: WebSocket,
    cdp_url: str,
    browser_url: Optional[str] = None,
    run_id: str,
    node_id: str,
    on_pause_pushed,
    on_user_activity=lambda: None,
) -> None:
    """Bidirectional WS proxy between the frontend and the live Chromium CDP.

    Two operating modes:
      * ``browser_url=None`` - legacy single-page proxy. Connect to
        ``cdp_url`` (page-level), pump frames in both directions. Used
        when the runner could not capture the browser-level URL (Docker
        isolation, /json/version probe failure).
      * ``browser_url`` set - multi-tab follow proxy. Connect to BOTH the
        browser-level URL (control plane: ``Target.targetCreated`` events)
        and the initial page-level URL (data plane: screencast frames).
        When browser-use auto-switches to a popup or a new tab opened by
        the page, we detach from the old page WS and attach a fresh one
        to the new target before restarting the screencast. Frontend is
        unchanged: it keeps consuming `Page.screencastFrame` exactly as
        before - only the underlying source target rotates.

    Errors are logged but do not propagate - the outer caller cleans up
    the front-facing WS. The billing path is untouched: this proxy is
    purely a presentation-layer bridge; cost extraction
    (``_extract_token_usage``) and observability (``recordObservability``)
    fire on the runner result regardless of which target the screencast
    was streaming.
    """
    import websockets  # lazy: only needed in live mode

    if not browser_url:
        await _proxy_live_cdp_single_page(
            ws=ws, cdp_url=cdp_url, run_id=run_id, node_id=node_id,
            on_pause_pushed=on_pause_pushed, on_user_activity=on_user_activity,
            websockets=websockets,
        )
    else:
        await _proxy_live_cdp_multi_tab(
            ws=ws, initial_page_url=cdp_url, browser_url=browser_url,
            run_id=run_id, node_id=node_id, on_pause_pushed=on_pause_pushed,
            on_user_activity=on_user_activity, websockets=websockets,
        )


async def _proxy_live_cdp_single_page(
    *, ws: WebSocket, cdp_url: str, run_id: str, node_id: str,
    on_pause_pushed, on_user_activity=lambda: None, websockets,
) -> None:
    """Legacy single-page proxy. See :func:`_proxy_live_cdp` docstring."""
    pause_pushed = False
    try:
        async with websockets.connect(cdp_url, max_size=8 * 1024 * 1024) as cdp:
            try:
                await cdp.send(json.dumps({
                    "id": 1, "method": "Page.startScreencast",
                    "params": _SCREENCAST_PARAMS,
                }))
            except Exception:
                logger.debug("startScreencast send failed", exc_info=True)

            async def upstream_pump():
                nonlocal pause_pushed
                while True:
                    try:
                        msg = await ws.receive_text()
                    except WebSocketDisconnect:
                        return
                    if _is_user_input(msg):
                        on_user_activity()
                        if not pause_pushed:
                            await _push_pause(run_id, node_id)
                            pause_pushed = True
                            on_pause_pushed()
                            await _send_json(ws, {"type": "paused", "reason": "user_takeover"})
                    try:
                        await cdp.send(msg)
                    except Exception:
                        logger.debug("upstream cdp send failed", exc_info=True)
                        return

            async def downstream_pump():
                while True:
                    try:
                        frame = await cdp.recv()
                    except Exception:
                        return
                    try:
                        if isinstance(frame, bytes):
                            await ws.send_bytes(frame)
                        else:
                            await ws.send_text(frame)
                    except Exception:
                        return

            up = asyncio.create_task(upstream_pump())
            down = asyncio.create_task(downstream_pump())
            done, pending = await asyncio.wait(
                {up, down}, return_when=asyncio.FIRST_COMPLETED
            )
            for t in pending:
                t.cancel()
            for t in pending:
                try:
                    await t
                except (asyncio.CancelledError, Exception):
                    pass
    except Exception:
        logger.exception("live CDP proxy failed; closing WS")


async def _proxy_live_cdp_multi_tab(
    *, ws: WebSocket, initial_page_url: str, browser_url: str,
    run_id: str, node_id: str, on_pause_pushed,
    on_user_activity=lambda: None, websockets,
) -> None:
    """Multi-tab proxy. Subscribes to browser-level ``Target.*`` events and
    rotates the page-level screencast when browser-use moves to a new tab.

    Locking strategy: a single ``asyncio.Lock`` (``page_lock``) serialises
    every read AND write on the swappable page WS. Without it, a switch
    triggered by the browser pump could race with a frame already being
    read by the downstream pump on the closing socket.

    Frame format: emitted ``Page.screencastFrame`` payloads keep the same
    shape the frontend already expects - the proxy makes no schema change.
    Switching a target only restarts the screencast; the frontend keeps
    drawing on the same canvas with the new target's frames.
    """
    pause_pushed = False
    page_lock = asyncio.Lock()
    page_holder: dict[str, Any] = {"ws": None, "target_id": None}
    closing = asyncio.Event()

    async def _open_page(target_id: str, url: str):
        """Open a page-level CDP WS and start the screencast. Caller holds
        ``page_lock``. On any failure returns None; the caller stays on
        the previous page (or stub if there was none)."""
        try:
            new_ws = await websockets.connect(url, max_size=8 * 1024 * 1024)
        except Exception:
            logger.warning("multi-tab: failed to open page WS for target=%s url=%s",
                           target_id, url, exc_info=True)
            return None
        try:
            await new_ws.send(json.dumps({
                "id": 1, "method": "Page.startScreencast",
                "params": _SCREENCAST_PARAMS,
            }))
        except Exception:
            logger.debug("multi-tab: startScreencast send failed (target=%s)",
                         target_id, exc_info=True)
        return new_ws

    async def _switch_to(new_target_id: str, new_url: str):
        """Stop screencast on the old page, open a new page-level WS,
        restart screencast there. Idempotent: a second call with the same
        target is a no-op (avoids redundant churn when ``Target.targetCreated``
        fires for an already-tracked tab).

        Lock discipline: we hold ``page_lock`` only long enough to swap
        the holder reference. Closing the OLD ws is done OUTSIDE the
        critical section - `Page.stopScreencast` + `close()` can block on
        a half-closed peer (TCP RST in flight, write buffer full), and
        holding the lock through that would freeze every downstream_pump
        and upstream_pump iteration that needs to read/send via the
        holder. Worst case still produces a brief frame gap (~50-150 ms),
        not a multi-second stall.
        """
        async with page_lock:
            if page_holder["target_id"] == new_target_id and page_holder["ws"] is not None:
                return
            new_ws = await _open_page(new_target_id, new_url)
            if new_ws is None:
                # New tab open failed - keep the old page WS active so the
                # user still sees something. Frames keep flowing on the
                # background tab; the agent operates invisibly on the new
                # tab. Better than swapping to None and freezing entirely.
                return
            old = page_holder["ws"]
            page_holder["ws"] = new_ws
            page_holder["target_id"] = new_target_id
        # Lock released - old WS close runs concurrently with everyone else.
        if old is not None:
            try:
                await old.send(json.dumps({"id": 99, "method": "Page.stopScreencast"}))
            except Exception:
                pass
            try:
                await old.close()
            except Exception:
                pass
        logger.info("multi-tab: screencast switched to target=%s (run=%s node=%s)",
                    new_target_id, run_id, node_id)

    # Initial page connection.
    initial_target_id = _extract_target_id(initial_page_url) or "initial"
    async with page_lock:
        page_holder["ws"] = await _open_page(initial_target_id, initial_page_url)
        page_holder["target_id"] = initial_target_id
        if page_holder["ws"] is None:
            logger.warning("multi-tab: initial page open failed url=%s", initial_page_url)
            return  # nothing to proxy - outer caller closes the front WS

    try:
        async with websockets.connect(browser_url, max_size=8 * 1024 * 1024) as bcdp:
            # Discover existing targets and auto-track new ones. We do NOT
            # use `Target.setAutoAttach(flatten=true)` because that would
            # make ALL Chromium messages flow through the single browser
            # WS with sessionId routing - far more invasive than reading
            # `Target.targetCreated` and opening a fresh page WS per tab.
            try:
                await bcdp.send(json.dumps({
                    "id": 1, "method": "Target.setDiscoverTargets",
                    "params": {"discover": True},
                }))
            except Exception:
                logger.debug("setDiscoverTargets failed", exc_info=True)

            async def browser_pump():
                """Consume browser-level CDP messages, swap on new
                page-target. Filters: only ``type='page'`` targets matter
                (skip background_page, service_worker, iframe, …). Skips
                the initial-page event since we already attached above."""
                while not closing.is_set():
                    try:
                        raw = await bcdp.recv()
                    except Exception:
                        return
                    try:
                        msg = json.loads(raw) if isinstance(raw, str) else None
                    except Exception:
                        continue
                    if not isinstance(msg, dict):
                        continue
                    method = msg.get("method")
                    params = msg.get("params") or {}
                    if method == "Target.targetCreated":
                        info = params.get("targetInfo") or {}
                        if info.get("type") != "page":
                            continue
                        tid = info.get("targetId")
                        if not tid or tid == page_holder["target_id"]:
                            continue
                        new_url = _build_page_url_from_browser_url(browser_url, tid)
                        try:
                            await _switch_to(tid, new_url)
                        except Exception:
                            logger.warning("multi-tab: switch failed for target=%s",
                                           tid, exc_info=True)

            async def upstream_pump():
                """Frontend → Chromium. Forwards client messages to the
                CURRENT page WS (whichever target the screencast is on).
                The first ``Input.*`` triggers a takeover PAUSE - the same
                contract as the single-page path so existing frontend
                handlers keep working unchanged."""
                nonlocal pause_pushed
                while True:
                    try:
                        msg = await ws.receive_text()
                    except WebSocketDisconnect:
                        return
                    if _is_user_input(msg):
                        on_user_activity()
                        if not pause_pushed:
                            await _push_pause(run_id, node_id)
                            pause_pushed = True
                            on_pause_pushed()
                            await _send_json(ws, {"type": "paused", "reason": "user_takeover"})
                    async with page_lock:
                        page_ws = page_holder["ws"]
                    if page_ws is None:
                        continue
                    try:
                        await page_ws.send(msg)
                    except Exception:
                        logger.debug("multi-tab: upstream send failed", exc_info=True)
                        # Don't return - the page WS may have been swapped
                        # mid-send. Loop back, re-read holder, try again
                        # next message. If the holder is now None and
                        # never recovers, the next iteration silently
                        # drops user input until the front WS closes.

            async def downstream_pump():
                """Chromium → Frontend. Reads from whichever page WS is
                currently attached. On a swap, the OLD ws is closed by
                ``_switch_to``; the in-flight ``recv()`` raises and we
                pick up the NEW ws on the next loop iteration. Frontend
                sees a brief frame gap (~50-150 ms) - the canvas keeps
                showing the last drawn frame until the new screencast
                emits its first frame."""
                while True:
                    async with page_lock:
                        page_ws = page_holder["ws"]
                    if page_ws is None:
                        await asyncio.sleep(0.05)
                        continue
                    try:
                        frame = await page_ws.recv()
                    except Exception:
                        # Swap-in-progress, or page WS closed. Loop and
                        # re-read the holder; if the holder is still the
                        # closed ws (no swap happened), sleep briefly to
                        # avoid a tight error loop, then exit if we still
                        # can't recover.
                        async with page_lock:
                            still_same = page_holder["ws"] is page_ws
                        if still_same:
                            return
                        await asyncio.sleep(0.02)
                        continue
                    try:
                        if isinstance(frame, bytes):
                            await ws.send_bytes(frame)
                        else:
                            await ws.send_text(frame)
                    except Exception:
                        return

            tasks = {
                asyncio.create_task(browser_pump()),
                asyncio.create_task(upstream_pump()),
                asyncio.create_task(downstream_pump()),
            }
            try:
                done, pending = await asyncio.wait(
                    tasks, return_when=asyncio.FIRST_COMPLETED,
                )
            finally:
                closing.set()
                for t in tasks:
                    if not t.done():
                        t.cancel()
                for t in tasks:
                    try:
                        await t
                    except (asyncio.CancelledError, Exception):
                        pass
                # Cleanup: close whatever page WS is currently attached.
                async with page_lock:
                    page_ws = page_holder["ws"]
                    page_holder["ws"] = None
                if page_ws is not None:
                    try:
                        await page_ws.close()
                    except Exception:
                        pass
    except Exception:
        logger.exception("live CDP multi-tab proxy failed; closing WS")


def _extract_target_id(page_url: str) -> Optional[str]:
    """Pull the trailing target ID out of a page-level CDP URL, e.g.
    ``ws://127.0.0.1:9222/devtools/page/4065F423`` → ``4065F423``.

    Returns None when the URL doesn't have the expected shape - the
    caller falls back to a sentinel (``"initial"``) so swap idempotency
    still works."""
    marker = "/devtools/page/"
    idx = page_url.find(marker)
    if idx < 0:
        return None
    tail = page_url[idx + len(marker):]
    # Strip any query string / trailing slash.
    for sep in ("?", "/", "#"):
        if sep in tail:
            tail = tail.split(sep, 1)[0]
    return tail or None


# ── helpers ──────────────────────────────────────────────────────────────


async def _send_json(ws: WebSocket, payload: dict[str, Any]) -> None:
    await ws.send_text(json.dumps(payload))


def _is_user_input(msg: str) -> bool:
    """Heuristic: a user-input frame is a JSON object whose ``method``
    starts with ``Input.``.

    Strict CDP-format messages start with ``{"id":...,"method":"Input.<x>"...}``
    Anything else (heartbeat, navigation hint) falls through.
    """
    msg = (msg or "").lstrip()
    if not msg.startswith("{"):
        return False
    try:
        obj = json.loads(msg)
    except json.JSONDecodeError:
        return False
    method = obj.get("method") if isinstance(obj, dict) else None
    return isinstance(method, str) and method.startswith("Input.")


async def _push_pause(run_id: str, node_id: str) -> None:
    """LPUSH a PAUSE control to the runner so it pauses at the next step.

    The runner's ``_drain_control`` picks this up before its next
    ``agent.step()`` call. Mirrors the agent.py /agent/sessions/{id}/abort
    pattern.
    """
    if not run_id or not node_id:
        logger.warning("push_pause: missing run_id/node_id, skipping")
        return
    redis = get_redis()
    key = control_key(run_id, node_id)
    await redis.lpush(key, json.dumps({"cmd": "PAUSE"}))
    await redis.expire(key, 600)


async def _tail_steps_to_ws(ws: WebSocket, run_id: str, node_id: str) -> None:
    """Tail the per-session steps stream and forward each entry as a WS frame.

    Uses ``XREAD BLOCK`` with a small timeout so cancellation lands
    promptly. Errors are logged but do not propagate - the WS bridge has
    its own teardown path.
    """
    if not run_id or not node_id:
        return
    redis = get_redis()
    key = f"agent:run:{run_id}:node:{node_id}:steps"
    last_id = "$"  # only new entries
    try:
        while True:
            try:
                # Block up to 5s per read so cancellation can pick us up.
                resp = await redis.xread({key: last_id}, count=10, block=5000)
            except Exception:
                logger.exception("xread failed in cdp tail (will retry)")
                await asyncio.sleep(1.0)
                continue
            if not resp:
                continue
            for _, entries in resp:
                for entry_id, fields in entries:
                    last_id = entry_id
                    raw = fields.get("json") or fields.get(b"json") or "{}"
                    if isinstance(raw, bytes):
                        raw = raw.decode("utf-8", errors="replace")
                    try:
                        payload = json.loads(raw)
                    except json.JSONDecodeError:
                        continue
                    payload["__entry_id"] = entry_id if isinstance(entry_id, str) else entry_id.decode("ascii", errors="replace")
                    try:
                        await _send_json(ws, {"type": "step_event", "payload": payload})
                    except Exception:
                        # WS closed while we were sending; bail out.
                        return
    except asyncio.CancelledError:
        raise
