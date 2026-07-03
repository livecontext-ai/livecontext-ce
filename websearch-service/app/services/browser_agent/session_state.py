"""In-process registry of live browser-agent sessions.

The control-plane endpoints (`/agent/sessions/{id}/{status,intervene,abort,
screenshot}`) read and mutate this state. The runner registers a session on
start and unregisters on completion/failure.

Lives in process memory: with concurrency=1 host-wide there is at most one
active session per FastAPI worker, and the worker does not survive a restart
anyway (Redis-side state is sufficient for crash-recovery).
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Optional

logger = logging.getLogger(__name__)


@dataclass
class BrowserAgentSession:
    """Live state for one in-flight browser-agent session.

    Mutated by the runner (step_index, last_url, last_action, last_eval,
    tokens_in/out, paused) and read by the control-plane endpoints.
    """

    session_id: str
    job_id: str
    run_id: str
    node_id: str
    started_at: float = field(default_factory=time.time)
    step_index: int = 0
    last_url: str = ""
    last_action: str = ""
    last_eval: str = ""
    tokens_in: int = 0
    tokens_out: int = 0
    llm_calls: int = 0
    paused: bool = False
    aborted: bool = False
    pending_hint: Optional[str] = None
    # Set by the runner so control endpoints can poke it without holding
    # cross-task references.
    abort_event: Optional[asyncio.Event] = None
    resume_event: Optional[asyncio.Event] = None
    # Live CDP WebSocket URL of the Chromium driving this session, captured
    # from browser-use's BrowserSession after start. The cdp.py WS router
    # proxies client frames to/from this URL when set; when None it falls
    # back to the steps-stream-only "stub" mode.
    cdp_endpoint_url: Optional[str] = None
    # BROWSER-level CDP WebSocket URL (`ws://.../devtools/browser/<id>`),
    # captured alongside the page-level one. cdp.py uses this to subscribe
    # to `Target.targetCreated` / `Target.targetDestroyed` events so the
    # screencast follows the agent when browser_use auto-switches to a
    # popup/new tab (e.g. a "Réserver" button opening a fresh booking
    # window). When None, cdp.py keeps the legacy single-page screencast
    # behaviour.
    cdp_browser_url: Optional[str] = None
    # Per-step trace accumulated by `_make_on_step_callback`. Drained into
    # the final result's `steps[]` so the orchestrator's
    # `BrowserAgentNode.recordObservability` can persist
    # `agent_execution_iterations` + `tool_calls` + `messages` rows. Without
    # this list the observability tables stay empty even though the header
    # row in `agent_executions` is written.
    steps_log: list = field(default_factory=list)

    # ── User-takeover hold ────────────────────────────────────────────────
    # The cdp.py WS bridge sets these as the user drives the live browser.
    # When `takeover_active` is True and the agent's task completes, the
    # runner HOLDS Chromium open (instead of tearing it down) so the user can
    # keep filling forms / clicking, bounded by inactivity + the wallclock.
    #   - takeover_active: True once the user has produced ANY live input.
    #   - last_activity: monotonic-ish wallclock of the last user input;
    #     each click/keystroke/scroll/mouse-move refreshes it, so the hold
    #     releases on inactivity (the primary, presence-based timeout).
    #   - takeover_started_at: wallclock the takeover began (for diagnostics +
    #     the idle fallback before the first post-takeover input lands).
    takeover_active: bool = False
    last_activity: float = 0.0
    takeover_started_at: float = 0.0
    # ── Live-view viewer presence ────────────────────────────────────────
    # Maintained by the cdp.py WS bridge: +1 on every accepted live-view
    # socket, -1 when it closes. The post-completion detached hold keeps
    # Chromium open while viewer_count > 0 and releases shortly after the
    # last viewer leaves (see runner._post_completion_hold). Same-loop
    # cooperative writes - no lock needed.
    viewer_count: int = 0
    # Wallclock of the LAST viewer disconnect (0.0 = never had one leave).
    # Lets the hold apply a short grace after the panel closes instead of
    # tearing Chromium down mid-remount.
    last_viewer_disconnect: float = 0.0
    # True once the runner handed teardown to a detached post-completion
    # hold task. run_browser_agent_session's cleanup skips unregistering
    # the session in that case - the hold task owns the teardown.
    hold_detached: bool = False
    # Set by the hold-cap eviction (one hold per user / host-wide hold cap):
    # the hold loop observes it and releases immediately. Created by the
    # runner when the hold detaches.
    hold_release_event: Optional[asyncio.Event] = None
    # The user the hold was registered under (for registry cleanup).
    hold_user_id: str = ""
    # Absolute wallclock the runner's hard cap expires (started + timeout_s),
    # set by `run_browser_agent_session`. Diagnostics only since the hold
    # went detached (the post-completion hold has its own hard cap,
    # settings.browser_agent_hold_max_seconds - the agent result has
    # already been returned by the time it runs).
    wallclock_deadline: float = 0.0


_sessions: dict[str, BrowserAgentSession] = {}
_lock = asyncio.Lock()


async def register_session(session: BrowserAgentSession) -> None:
    async with _lock:
        _sessions[session.session_id] = session
        logger.info(
            "browser_agent session registered: session_id=%s job_id=%s "
            "run_id=%s node_id=%s",
            session.session_id, session.job_id, session.run_id, session.node_id,
        )


async def unregister_session(session_id: str) -> None:
    async with _lock:
        s = _sessions.pop(session_id, None)
        if s:
            logger.info(
                "browser_agent session unregistered: session_id=%s steps=%d "
                "tokens_in=%d tokens_out=%d duration_s=%.1f",
                session_id, s.step_index, s.tokens_in, s.tokens_out,
                time.time() - s.started_at,
            )


def get_session_state(session_id: str) -> Optional[BrowserAgentSession]:
    return _sessions.get(session_id)


def list_active_sessions() -> list[BrowserAgentSession]:
    return list(_sessions.values())
