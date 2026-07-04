"""Async runner for browser-agent (browser-use) sessions.

Public entry: `run_browser_agent_session(job_id, parameters, redis)`.

Lifecycle:
  1. Validate parameters, build a BrowserAgentSession, register it.
  2. Spawn the browser-use Agent loop. For v1 we run the agent IN-PROCESS
     (no Docker isolation yet - that lands in PR #7 with the security
     gates). The runner is structured so the IN-PROCESS call can be
     swapped for a `docker_session.run_in_container(...)` call without
     touching the orchestrator-facing contract.
  3. Around each step:
     - drain the control LIST (PAUSE / RESUME / ABORT / INTERVENE)
     - SSRF-check the next URL via `crawl_filter.is_url_safe_for_navigation`
     - XADD a step event with eval/memory/goal/action/screenshot_key
  4. On completion: push the final result to `agent:result:{job_id}` (LIST)
     so the Java orchestrator's BLPOP returns. Also XADD a `final` event
     to the steps stream so the live frontend gets closure.

Safety:
  - Hard wallclock cap (default 600s).
  - Hard step cap (default 50).
  - SSRF re-check on every navigation target (post-redirect handled by
    browser-use's own action layer).
  - Stop reasons map to shared-contract enum values for observability
    (MAX_STEPS → MAX_ITERATIONS, USER_TAKEOVER → STOPPED_BY_USER, etc.)
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import asdict
from pathlib import Path
from typing import Any, Optional

import redis.asyncio as aioredis

from app.config import browser_agent_budget, settings
from app.services.crawl_filter import is_url_safe_for_navigation

from .budget_gate import (
    BudgetExhaustedError,
    acquire_concurrent_slot_queued,
    increment_daily_steps,
    release_concurrent_slot,
)
from .llm_resolver import LlmConfigError, _resolve_llm
from .redis_io import (
    blpop_control,
    lpop_control,
    make_step_event,
    push_result,
    requeue_control,
    store_final_screenshot,
    xadd_step,
)
from .screenshot_redactor import redact_screenshot
from .session_state import (
    BrowserAgentSession,
    get_session_state,
    register_session,
    unregister_session,
)

logger = logging.getLogger(__name__)


# ── Host-wide session cap ─────────────────────────────────────────────────
# Each browser session pins a Chromium process; unbounded parallelism (e.g.
# a workflow split fanning N branches from N users) OOMs the box. Excess
# sessions wait FIFO on this in-process semaphore (asyncio wakes waiters in
# order), bounded by the same queue-wait budget as the per-user gate. The
# cap is per websearch PROCESS, which is the right scope: there is exactly
# one websearch host per deployment (systemd box in cloud, one container in
# CE), while the per-user gate stays Redis-backed and therefore global.
_global_session_semaphore: Optional[asyncio.Semaphore] = None


def _get_global_session_semaphore() -> Optional[asyncio.Semaphore]:
    """Lazily create the host-wide semaphore from settings (None = uncapped).

    Created once per process; a settings change requires a restart, which
    matches how the rest of the WEBSEARCH_/BROWSER_AGENT_ env config behaves.
    """
    global _global_session_semaphore
    cap = browser_agent_budget.max_global_sessions
    if cap <= 0:
        return None
    if _global_session_semaphore is None:
        _global_session_semaphore = asyncio.Semaphore(cap)
    return _global_session_semaphore


# ── Post-completion hold registry ─────────────────────────────────────────
# Strong references to detached hold tasks (asyncio only keeps weak ones -
# a GC'd task would leak Chromium) + bookkeeping for the hold caps:
#   - at most ONE hold per user: the user's next session evicts their
#     previous hold (its Chromium closes, freeing the page they abandoned),
#   - at most `max_global_sessions` holds host-wide: the OLDEST hold is
#     evicted first. Holds release the agent capacity gates at completion
#     (a watched page must not block anyone's next agent run), so these
#     caps are what keeps held Chromiums from accumulating unbounded.
_hold_tasks: set = set()
_holds_by_user: dict[str, "BrowserAgentSession"] = {}
_holds_in_order: list = []  # BrowserAgentSession, oldest first


def _evict_hold(session) -> None:
    """Ask a hold to release NOW (its loop observes hold_release_event)."""
    ev = getattr(session, "hold_release_event", None)
    if ev is not None and not ev.is_set():
        logger.info("browser_agent hold EVICTED: session=%s", session.session_id)
        ev.set()


def _register_hold(session, user_id: str) -> None:
    if user_id:
        prev = _holds_by_user.get(user_id)
        if prev is not None and prev is not session:
            _evict_hold(prev)
        _holds_by_user[user_id] = session
    session.hold_user_id = user_id
    _holds_in_order.append(session)
    cap = browser_agent_budget.max_global_sessions
    if cap > 0:
        # Evict oldest-first until the number of NOT-yet-evicted holds fits
        # the cap. Evicted holds stay in the list until their own teardown
        # (don't pop here - a slow teardown would under-report the count),
        # so skip the ones already signalled instead of re-signalling the
        # same head while holds 2..N stay alive past the cap.
        alive = [h for h in _holds_in_order
                 if h.hold_release_event is None or not h.hold_release_event.is_set()]
        while len(alive) > cap:
            _evict_hold(alive.pop(0))


def _unregister_hold(session) -> None:
    try:
        _holds_in_order.remove(session)
    except ValueError:
        pass
    uid = getattr(session, "hold_user_id", "")
    if uid and _holds_by_user.get(uid) is session:
        _holds_by_user.pop(uid, None)


async def _cmd_is_for_session(session, cmd, redis) -> bool:
    """Whether a popped control command belongs to THIS session.

    Commands are tagged with a session_id by their producers (cdp.py PAUSE,
    the /agent/sessions/{sid}/resume+abort endpoints). Two sessions can
    share the same (run_id, node_id) control key - a loop iteration, a
    re-trigger of the same run, or the previous session's post-completion
    hold - so a consumer must REQUEUE (at the tail) any command tagged for
    a sibling instead of swallowing it. Untagged commands (legacy
    producers, tests) are treated as ours.
    """
    sid = cmd.get("session_id")
    if not sid or sid == session.session_id:
        return True
    if get_session_state(sid) is None:
        # The tagged owner is gone (crashed / already torn down): requeueing
        # would cycle the command between live consumers forever, refreshing
        # the key TTL on every pass. Drop it - the session it addressed can
        # never consume it.
        logger.info("dropping control cmd=%r for defunct session %s",
                    cmd.get("cmd"), sid)
        return False
    await requeue_control(redis, session.run_id, session.node_id, cmd)
    return False


# ── Guardrails JS - loaded once at import ─────────────────────────────────

def _load_guardrails_js() -> str:
    """Read the DOM guardrail script bundled next to this module.

    Loaded once at import so a missing or unreadable file fails fast at
    process startup rather than on the first step. The string is also
    surfaced via the public `GUARDRAILS_JS` constant for tests.
    """
    return Path(__file__).parent.joinpath("guardrails.js").read_text(encoding="utf-8")


GUARDRAILS_JS: str = _load_guardrails_js()
logger.info(
    "browser_agent guardrails.js prepared (%d bytes, enabled=%s)",
    len(GUARDRAILS_JS.encode("utf-8")),
    settings.browser_agent_guardrails_enabled,
)


# JS the runner evaluates on the live page to collect bounding boxes for
# any DOM element matching the same selector list as `guardrails.js`. The
# selectors are kept in lockstep with the JS file's `SENSITIVE_SELECTORS`
# so the post-capture redactor masks the same fields the DOM overlay
# already covers (belt-and-braces).
_REDACTION_REGIONS_JS = """
(() => {
  const SELECTORS = [
    'input[type="password"]',
    'input[autocomplete*="cc-"]',
    'input[autocomplete*="card"]',
    'input[autocomplete="new-password"]',
    'input[autocomplete="current-password"]',
    'input[name*="cvv" i]',
    'input[name*="cvc" i]',
    'input[name*="cardnumber" i]',
    'input[name*="card-number" i]',
    'input[name*="creditcard" i]',
    'input[name*="credit-card" i]',
    'input[name*="securitycode" i]',
    'input[name*="security-code" i]',
    'input[name*="cardholder" i]',
    'input[id*="cvv" i]',
    'input[id*="cardnumber" i]',
    'input[id*="creditcard" i]',
    'input[type="tel"][autocomplete*="cc"]'
  ];
  const out = [];
  let nodes;
  try { nodes = document.querySelectorAll(SELECTORS.join(',')); }
  catch (_) { return out; }
  for (const el of nodes) {
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    out.push({
      x: Math.round(r.left),
      y: Math.round(r.top),
      width: Math.round(r.width),
      height: Math.round(r.height),
      reason: 'sensitive_input'
    });
  }
  return out;
})()
"""

# Wallclock bound for the best-effort final-page capture at teardown. The
# CDP round-trip (get_or_create_cdp_session + Page.captureScreenshot) runs
# in the `_drive_browser_use` finally, which is reachable via the 600s
# wallclock-cap cancellation path. A hung Chromium would otherwise block
# the finally (and the session cleanup) indefinitely - `except Exception`
# does NOT catch a hang. wait_for caps each await so teardown always
# progresses. Module-level so tests can shrink it.
_FINAL_SHOT_CAPTURE_TIMEOUT_S = 5.0

# ── Hard caps (defense-in-depth - also enforced by the Java side) ─────────
DEFAULT_TIMEOUT_S = 600
DEFAULT_MAX_STEPS = 50
MAX_TIMEOUT_S = 600
MAX_STEPS_HARD_CAP = 50

# Control commands the runner understands (case-insensitive, normalized
# to upper internally).
CMD_PAUSE = "PAUSE"
CMD_RESUME = "RESUME"
CMD_ABORT = "ABORT"
CMD_INTERVENE = "INTERVENE"

# ── Post-completion hold tuning ───────────────────────────────────────────
# When the agent's task completes while a live-view viewer is connected (or
# the user took control mid-task), a DETACHED task keeps Chromium open so
# the user can keep driving the final page (fill a form, navigate). The
# agent's result returns immediately; the hold only governs the page's
# lifetime. It is PRESENCE-based, never a fixed countdown that cuts active
# work - see `_hold_until_released` for the release conditions (viewer
# disconnect + grace, inactivity, explicit RESUME/ABORT, hard cap; the
# grace and hard cap are settings: browser_agent_hold_*).
TAKEOVER_IDLE_TIMEOUT_S = 300        # 5 min of zero user input -> release
TAKEOVER_HOLD_POLL_S = 5             # control-queue poll cadence during hold


class _AbortRequested(Exception):
    """Internal signal raised when the orchestrator pushes ABORT."""


async def run_browser_agent_session(
    job_id: str,
    parameters: dict[str, Any],
    redis: aioredis.Redis,
) -> dict[str, Any]:
    """Main entry called from `routers/jobs.py::_dispatch` for action='agent_browse'.

    Returns a dict matching the BrowserAgentNode output schema. Also pushes
    the same dict to Redis `agent:result:{job_id}` so the Java BLPOP returns.
    """
    task = parameters.get("task")
    if not task or not isinstance(task, str):
        result = _error_result("missing required parameter 'task'")
        await push_result(redis, job_id, result)
        return result

    # Run/node id resolution. Two naming conventions land here:
    #   - workflow path (BrowserAgentNode): credentials.__streamId__ +
    #     credentials.__toolCallId__ (Java's "callback addressing" pair)
    #   - top-level params: run_id / node_id (legacy / chat-tool path)
    # Both must resolve to the same key the cdp.py JWT carries (it reads
    # rid/nid from the token, which Java mints from __streamId__/
    # __toolCallId__) - otherwise PAUSE/RESUME/step-stream tail all
    # target the wrong Redis keys and the live takeover silently
    # no-ops. Accept both spellings here so the bridge works regardless
    # of caller path.
    run_id = (
        parameters.get("run_id")
        or _ctx_id(parameters, "__runId__")
        or _ctx_id(parameters, "__streamId__")
        or "run_unknown"
    )
    node_id = (
        parameters.get("node_id")
        or _ctx_id(parameters, "__nodeId__")
        or _ctx_id(parameters, "__toolCallId__")
        or "node_unknown"
    )
    session_id = f"ses_{uuid.uuid4().hex[:16]}"
    user_id = _ctx_id(parameters, "__userId__") or ""

    timeout_s = min(int(parameters.get("timeout_seconds") or DEFAULT_TIMEOUT_S), MAX_TIMEOUT_S)
    max_steps = min(int(parameters.get("max_steps") or DEFAULT_MAX_STEPS), MAX_STEPS_HARD_CAP)

    session = BrowserAgentSession(
        session_id=session_id,
        job_id=job_id,
        run_id=run_id,
        node_id=node_id,
        abort_event=asyncio.Event(),
        resume_event=asyncio.Event(),
    )
    await register_session(session)

    # Publish the job_id → session_id mapping so external observers (the
    # live-view client, the operator dashboard, the e2e CDP smoke test)
    # can resolve which session belongs to a freshly-submitted job
    # without reaching into this process's in-memory registry. Best-effort
    # - the runner's correctness does not depend on this entry. 10 min
    # TTL covers session lifetime + a generous post-completion grace.
    try:
        await redis.set(f"agent:browser:job:{job_id}", session_id, ex=600)
    except Exception:
        logger.debug("publish job→session mapping failed", exc_info=True)

    started = time.time()
    # Per-user concurrent-session gate (Redis-backed, FIFO-queued): a
    # workflow split/fork firing N branches for the same user queues the
    # extras instead of failing them. Java's `BrowserAgentBudgetGuard`
    # deliberately does NOT pre-reject on the concurrent counter anymore -
    # this queued acquire is the single authority. A host-wide semaphore
    # (all users) then bounds simultaneously RUNNING Chromiums; both waits
    # share the same bounded budget so the job either starts or returns a
    # clean BUDGET_EXHAUSTED within queue_wait_max_seconds.
    # Clamp the queue wait so `queue + run-floor + teardown` always fits the
    # submit-side budget (timeout_s <= MAX_TIMEOUT_S = the Java BLPOP window):
    # without this, a near-budget queue wait + the 30s run floor would land
    # the result just past the BLPOP (operator trap: setting
    # BROWSER_AGENT_QUEUE_WAIT_MAX_SECONDS ~= the BLPOP recreates the
    # lost-result overrun the deduction exists to prevent).
    queue_wait_s = min(
        max(0, browser_agent_budget.queue_wait_max_seconds),
        max(0, timeout_s - 60),
    )
    concurrent_acquired = False
    global_sem: Optional[asyncio.Semaphore] = None
    try:
        try:
            queue_wait_started = time.monotonic()
            queue_deadline = queue_wait_started + queue_wait_s
            await acquire_concurrent_slot_queued(redis, user_id, session_id, queue_wait_s)
            concurrent_acquired = True
            sem = _get_global_session_semaphore()
            if sem is not None:
                remaining = max(1.0, queue_deadline - time.monotonic()) if queue_wait_s > 0 else 1.0
                try:
                    await asyncio.wait_for(sem.acquire(), timeout=remaining)
                    global_sem = sem
                except asyncio.TimeoutError:
                    raise BudgetExhaustedError(
                        BudgetExhaustedError.REASON_HOST_CAPACITY,
                        "timed out waiting for browser capacity on this host "
                        f"(waited {int(queue_wait_s)}s)",
                    )
        except BudgetExhaustedError as be:
            logger.info(
                "browser_agent rejected by per-user budget: session=%s user=%s reason=%s",
                session_id, user_id, be.reason,
            )
            result = _build_result(
                session, started,
                stop_reason="BUDGET_EXHAUSTED",
                final_result=be.message,
            )
        else:
            # Deadline invariant: queue wait + run time must fit inside the
            # ORIGINAL timeout budget, because the orchestrator's result
            # BLPOP (websearch.service.browser-agent-blpop-timeout, 600s
            # default = MAX_TIMEOUT_S) started ticking at submit. Deduct
            # the time spent queueing from the run budget so a queued
            # branch can never push its result past the BLPOP and lose it
            # while Chromium still runs. Floor of 30s keeps a heavily
            # queued job able to do SOMETHING rather than 0-stepping.
            queued_for = time.monotonic() - queue_wait_started
            effective_timeout_s = max(30, int(timeout_s - queued_for))
            session.wallclock_deadline = time.time() + effective_timeout_s
            try:
                result = await asyncio.wait_for(
                    _run_loop(session, task, parameters, max_steps, redis, user_id),
                    timeout=effective_timeout_s,
                )
            except asyncio.TimeoutError:
                logger.warning("browser_agent timeout: session=%s elapsed=%ds (queued %.0fs)",
                               session_id, effective_timeout_s, queued_for)
                result = _build_result(
                    session, started, stop_reason="TIMEOUT",
                    final_result=f"Session timed out after {effective_timeout_s}s",
                )
            except _AbortRequested:
                logger.info("browser_agent aborted: session=%s", session_id)
                result = _build_result(
                    session, started, stop_reason="CANCELLED",
                    final_result="Session aborted by user",
                )
            except BudgetExhaustedError as be:
                # Mid-loop daily-steps quota hit. Preserve any partial trace.
                logger.info(
                    "browser_agent halted by per-user budget mid-loop: "
                    "session=%s user=%s reason=%s steps=%d",
                    session_id, user_id, be.reason, session.step_index,
                )
                result = _build_result(
                    session, started,
                    stop_reason="BUDGET_EXHAUSTED",
                    final_result=be.message,
                )
            except LlmConfigError as e:
                logger.warning("browser_agent llm config error: session=%s err=%s", session_id, e)
                result = _build_result(
                    session, started, stop_reason="LLM_FAILED",
                    final_result=str(e),
                )
            except Exception as e:
                logger.error("browser_agent crashed: session=%s err=%s", session_id, e, exc_info=True)
                result = _build_result(
                    session, started, stop_reason="LLM_FAILED",
                    final_result=f"Runner error: {e}",
                )
    finally:
        # The per-user concurrent slot + the host capacity slot are released
        # as soon as the AGENT is done - a post-completion hold is a
        # lightweight leftover page, not an agent run, so it must not block
        # the user's (or anyone's) next session.
        if global_sem is not None:
            global_sem.release()
        if concurrent_acquired:
            await release_concurrent_slot(redis, user_id, session_id)
        # When a detached post-completion hold owns the browser, IT does
        # the unregister + job-mapping cleanup at release time - the
        # session must stay registered so the cdp.py bridge keeps
        # accepting the viewer's (re)connections while the page is open.
        if not session.hold_detached:
            await unregister_session(session_id)
            # Drop the job→session mapping NOW (rather than relying on the
            # 10-min TTL set at registration). Stale mappings cause a
            # frontend reconnecting late to look up session_id, get a
            # 4404 session-gone WS close, and a confusing "session
            # disappeared" UX. Best-effort - runner correctness does not
            # depend on this entry.
            try:
                await redis.delete(f"agent:browser:job:{job_id}")
            except Exception:
                logger.debug("delete job→session mapping failed", exc_info=True)
        # NOTE: the `agent:browse:meta:{runId}:{nodeId}` hash is deliberately
        # NOT deleted here. It is the ownership record the orchestrator's
        # BrowserAgentTakeoverController checks for CHAT runs (no
        # workflow_runs row) - deleting it at teardown made every
        # post-completion final-screenshot / token-refresh / resume call
        # 404 for chat. Its 1h TTL (set by BrowserAgentModule at submit)
        # outlives the final screenshot's own TTL, so the fallback image
        # stays fetchable for exactly as long as it exists.

    # Push final event to live stream + final result to Java BLPOP key.
    try:
        await xadd_step(redis, run_id, node_id, make_step_event(
            type_="final",
            step_index=session.step_index,
            extra={
                "stop_reason": result["stop_reason"],
                "final_url": result.get("final_url", ""),
            },
        ))
    except Exception:
        logger.warning("failed to xadd final step event", exc_info=True)
    await push_result(redis, job_id, result)
    return result


async def _run_loop(
    session: BrowserAgentSession,
    task: str,
    parameters: dict[str, Any],
    max_steps: int,
    redis: aioredis.Redis,
    user_id: str = "",
) -> dict[str, Any]:
    """Core browser-use loop with control-plane checks between steps.

    The actual browser-use Agent invocation is delegated to
    `_drive_browser_use(...)` so tests can monkey-patch it without
    spinning up a real Chromium.

    `user_id` flows through to `_drive_browser_use` so the per-step daily
    quota counter can be incremented mid-loop. Empty string disables the
    gate (local dev / unit tests that don't pass a credentials block).
    """
    started = time.time()

    # Optional start_url SSRF check - before we even touch browser-use.
    start_url = parameters.get("start_url")
    if start_url:
        safe, reason = is_url_safe_for_navigation(start_url)
        if not safe:
            return _build_result(
                session, started, stop_reason="DOMAIN_BLOCKED",
                final_result=f"start_url rejected: {reason}",
                final_url=start_url,
            )
        # Seed the live-view address bar with the requested start_url so
        # the frontend status poll (which reads session.last_url) shows
        # the destination immediately instead of "" while Chromium boots
        # and the first Page.frameNavigated event arrives.
        session.last_url = start_url

    # The runner core. browser-use exposes an Agent with a step-by-step
    # async API; we drive it manually so we can interleave control-plane
    # polls. `_drive_browser_use` is intentionally tiny - it's the seam
    # tests replace.
    on_step = _make_on_step_callback(session, redis)
    on_navigation = _make_on_navigation_callback(session)

    try:
        outcome = await _drive_browser_use(
            task=task,
            parameters=parameters,
            session=session,
            max_steps=max_steps,
            redis=redis,
            on_step=on_step,
            on_navigation=on_navigation,
            user_id=user_id,
        )
    except _AbortRequested:
        raise
    except Exception:
        raise

    return _build_result(
        session, started,
        stop_reason=outcome.get("stop_reason", "COMPLETED"),
        final_result=outcome.get("final_result", ""),
        extracted_data=outcome.get("extracted_data"),
        final_url=outcome.get("final_url", session.last_url),
        pages_visited=outcome.get("pages_visited", []),
        cost_raw=outcome.get("cost_raw"),
    )


# ── Seam: the actual browser-use invocation ───────────────────────────────
# Tests replace this with a fake that runs deterministically without a
# Chromium. The real implementation will call browser-use's Agent.run() in
# a way that lets us intercept each step.

async def _drive_browser_use(
    *,
    task: str,
    parameters: dict[str, Any],
    session: BrowserAgentSession,
    max_steps: int,
    redis: aioredis.Redis,
    on_step,
    on_navigation,
    user_id: str = "",
) -> dict[str, Any]:
    """Default real-life implementation: import browser-use lazily, run it.

    This is replaced in tests via monkeypatch to avoid the Chromium
    dependency.

    Docker-isolated path:

        When ``settings.browser_agent_use_docker`` is True, this delegates
        the whole session to ``docker_session.run_in_container(...)``
        which spawns ``livecontext/browser-agent:1`` with hard cgroup
        caps and an egress-filtered network. The container itself owns
        the browser-use loop and pushes the final result to Redis; this
        wrapper just supervises the subprocess and returns a summary
        dict. THIS IS THE SWAP POINT - no other call site in runner.py
        needs to change.

        See `app/services/browser_agent/docker_session.py` for the full
        contract; the in-process body below remains the fallback for
        environments where the Docker image is not yet provisioned.

    Routing (in-process path only):
      - `parameters['llm']` is forwarded verbatim to `_resolve_llm`.
      - When `provider_kind == 'bridge'` the resolver returns a client
        that POSTs to `lc-bridge:8093`, NEVER directly to Anthropic /
        OpenAI. This is mandatory - see CLAUDE.md "Bridges - never
        short-circuit billing".
    """
    if settings.browser_agent_use_docker:
        from app.services.browser_agent import docker_session

        # Forward the session+job context the container needs as env vars.
        # Falls through the same Redis result/control LIST contract, so the
        # host-side BLPOP and SSE consumer don't change.
        container_params = dict(parameters)
        container_params.setdefault("task", task)
        container_params.setdefault("session_id", session.session_id)
        container_params.setdefault("job_id", session.job_id)
        container_params.setdefault("run_id", session.run_id)
        container_params.setdefault("node_id", session.node_id)
        container_params.setdefault("max_steps", max_steps)
        return await docker_session.run_in_container(
            container_params, redis_url=settings.redis_url,
        )

    try:
        from browser_use import Agent  # type: ignore
    except ImportError:
        # Library not installed (e.g. local dev with skip-browser). Surface
        # a clear stop_reason so the orchestrator can route to error path.
        logger.warning("browser_use lib not installed; aborting session=%s", session.session_id)
        return {
            "stop_reason": "LLM_FAILED",
            "final_result": "browser_use library not installed on this host",
        }

    # Resolve LLM. Direct providers (google/anthropic/openai) get their
    # browser-use ChatXxx client; bridge models go through `BridgeChatClient`
    # (HTTP → lc-bridge:8093). The resolver raises `LlmConfigError` on
    # missing/invalid config, which is caught by `run_browser_agent_session`
    # and surfaced as stop_reason='LLM_FAILED'.
    #
    # Bridges - never short-circuit billing (CLAUDE.md): the resolver MUST
    # NOT instantiate a direct anthropic/openai SDK when provider_kind
    # is 'bridge'. The runner doesn't enforce that here; the resolver
    # does (and tests assert the negative).
    llm_cfg = parameters.get("llm") or {}
    llm = _resolve_llm(llm_cfg)

    # `calculate_cost=False` disables browser-use's internal LiteLLM
    # pricing-fetch from raw.githubusercontent.com (blocked by the
    # browser-agent container egress filter), which would silently
    # leave token capture intact but log warnings on every session.
    # We only need the raw token counts; pricing lives Java-side in
    # `auth.model_pricing` and is applied by `CreditService.consumeForAgent`.
    #
    # `BrowserProfile(headless=True, window_size=...)` forces a headless
    # Chromium with a sized viewport. Without this, browser-use auto-
    # detects the host display via Xvfb / NSScreen / screeninfo and
    # opens a real Chromium window on the developer's desktop (the
    # "le vrai chrome qui s'ouvre en très grande taille" symptom).
    # 1280×720 keeps the agent's viewport close to a typical desktop
    # render while matching our screencast cap so the live-view canvas
    # gets full-resolution frames.
    # Honour start_url: name it as the FIRST step of the task so browser-use
    # navigates there before doing the rest. We steer through the TASK rather
    # than browser-use `initial_actions`: passing the start_url as an
    # initial go_to_url action is accepted as a kwarg but fails at RUNTIME on
    # the deployed browser-use ("Runner error: 'go_to_url'"), whereas the model
    # driving go_to_url itself inside the normal loop works. Without this
    # Chromium boots on about:blank and the agent reports "no page URL was
    # provided" when the task itself doesn't name a URL.
    start_url = parameters.get("start_url")
    agent_task = _task_with_start_url(task, start_url)

    try:
        from browser_use import BrowserProfile  # type: ignore
        # 1920×1080 viewport: the agent sees a typical desktop-wide
        # layout (most sites' main breakpoint), AND the live-view
        # frame captures a wider page → effectively a "zoomed-out"
        # rendering at ~50% of the previous 1280-px scale once the
        # screencast caps it back to 1200-px-wide. User wanted "page
        # affichée à 50%" → wider native viewport gives that without
        # touching browser zoom (which would also change what the
        # agent perceives for click coordinates).
        # keep_alive=True: browser-use 0.12's `agent.run()` dispatches an
        # internal BrowserStopEvent at task completion which RESETS the
        # session (drops the root CDP client) before our teardown code
        # runs - that's what broke the final-page screenshot in prod
        # ("Root CDP client not initialized") and killed Chromium under
        # the post-completion hold. With keep_alive the runner owns the
        # browser lifetime: `_close_browser_session` (kill/close) is
        # called explicitly on EVERY exit path, immediately or at the end
        # of the detached hold.
        profile_kwargs = {
            "headless": True,
            "window_size": {"width": 1920, "height": 1080},
            "viewport": {"width": 1920, "height": 1080},
        }
        try:
            profile = BrowserProfile(keep_alive=True, **profile_kwargs)
        except Exception:
            # browser-use build without the keep_alive field: fall back to a
            # sized profile. The final screenshot/hold then race the internal
            # BrowserStopEvent reset (pre-fix behaviour) but the agent runs.
            logger.warning("BrowserProfile(keep_alive=True) unsupported; "
                           "falling back without it", exc_info=True)
            profile = BrowserProfile(**profile_kwargs)
        agent = Agent(task=agent_task, llm=llm, browser_profile=profile,
                      calculate_cost=False)
    except (ImportError, TypeError):
        # browser-use < 0.12 didn't expose BrowserProfile or used a different
        # kwarg name. Fall back to a plain Agent so we don't crash existing
        # local installs (the user sees the large Chromium window on those
        # versions). agent_task already carries the start_url navigation step.
        agent = Agent(task=agent_task, llm=llm, calculate_cost=False)

    # browser-use 0.12.x setup is done internally by `Agent.run()`. Direct
    # `Agent.step()` calls fail on the bubus event handler chain because
    # `BrowserSession` isn't started until `run()` runs. We therefore use
    # `agent.run(max_steps=N, on_step_start=..., on_step_end=...)` and do
    # our control-plane drain + step-event emission inside the callbacks.
    #
    # Trade-off: PAUSE/RESUME granularity is per-step, not per-action -
    # we honour them at step boundaries via `_StopAgent` (raised from the
    # callback to terminate run() early). This matches the design's
    # "at next step boundary" semantics.

    guardrails_installed = False
    cdp_captured = False
    nav_filter_installed = False

    class _StopAgent(Exception):
        """Raised in on_step_* to break out of agent.run() early.

        Carries the stop_reason so the surrounding try/except can shape
        the final result appropriately.
        """
        def __init__(self, reason: str, message: str = ""):
            self.reason = reason
            self.message = message

    async def on_step_start(running_agent: Any) -> None:
        nonlocal guardrails_installed, cdp_captured, nav_filter_installed
        # Drain pending control commands first so PAUSE/ABORT are honoured
        # before we burn another LLM call.
        await _drain_control(session, redis)
        if session.aborted:
            raise _StopAgent("CANCELLED", "Session aborted by user")

        # CDP endpoint capture happens BEFORE the pause-check so a
        # PAUSE-before-step-1 (manual mode, supervised mode, user takeover
        # via the live-view bridge) doesn't strand the live-view client
        # in stub mode. browser-use has booted Chromium by the time
        # on_step_start fires for step 1, so all probes work here.
        if not cdp_captured:
            try:
                browser_url, ep = await _extract_cdp_endpoints(running_agent)
                if ep:
                    session.cdp_endpoint_url = ep
                    session.cdp_browser_url = browser_url  # may be None for page-only probes
                    cdp_captured = True
                    logger.info(
                        "browser_agent CDP endpoint captured on_step_start: "
                        "session=%s endpoint=%s browser=%s",
                        session.session_id, ep.split("?", 1)[0],
                        (browser_url.split("?", 1)[0] if browser_url else "<none>"),
                    )
                    # Live-view bootstrap: dual-emit so both the historical
                    # archive AND the realtime fanout work.
                    # 1. XADD on the per-session steps Stream - TTL 1h,
                    #    available to ad-hoc consumers (CLI tools, replay).
                    # 2. PUBLISH on the orchestrator's coordinator pub/sub
                    #    channel - single subscribe by the JVM consumer
                    #    (`BrowserSessionLifecycleSubscriber`) so we don't
                    #    have to dynamically register N stream listeners
                    #    (one per session) at runtime. Pub/sub matches the
                    #    project's existing event fanout pattern.
                    # Best-effort: a Redis hiccup must NOT abort the agent.
                    bootstrap_extra = {
                        "session_id": session.session_id,
                        "cdp_ws_url": ep,
                    }
                    try:
                        await xadd_step(redis, session.run_id, session.node_id,
                            make_step_event(
                                type_="cdp_ready",
                                step_index=session.step_index,
                                url=session.last_url or "",
                                extra=bootstrap_extra,
                            ))
                    except Exception:
                        logger.debug("cdp_ready XADD failed (non-fatal)",
                                     exc_info=True)
                    try:
                        import json as _json
                        bootstrap_msg = {
                            "run_id": session.run_id,
                            "node_id": session.node_id,
                            "session_id": session.session_id,
                            "cdp_ws_url": ep,
                            "current_url": session.last_url or "",
                            "step_index": session.step_index,
                        }
                        await redis.publish(
                            "agent:browse:cdp_ready",
                            _json.dumps(bootstrap_msg),
                        )
                    except Exception:
                        logger.debug("cdp_ready PUBLISH failed (non-fatal)",
                                     exc_info=True)
                    # Record the session id on the run-context hash the Java
                    # side wrote at submit. The orchestrator's cdp-token-refresh
                    # uses it to verify a requested session_id actually belongs
                    # to (runId, nodeId) when there is no workflow takeover
                    # signal to check against (chat runs; post-completion
                    # holds) - without it a caller could mint a token bound to
                    # ANOTHER user's session id. Best-effort like the rest of
                    # the bootstrap.
                    try:
                        await redis.hset(
                            f"agent:browse:meta:{session.run_id}:{session.node_id}",
                            "sessionId", session.session_id,
                        )
                    except Exception:
                        logger.debug("cdp_ready meta HSET failed (non-fatal)",
                                     exc_info=True)
            except Exception:
                logger.debug("CDP endpoint on_step_start retry failed", exc_info=True)

        # Block while paused (with periodic abort re-check).
        while session.paused:
            logger.info("session paused: session=%s", session.session_id)
            cmd = await blpop_control(redis, session.run_id, session.node_id, timeout_s=30)
            if cmd is not None:
                if await _cmd_is_for_session(session, cmd, redis):
                    await _apply_control(session, cmd)
                else:
                    await asyncio.sleep(0.2)
            if session.aborted:
                raise _StopAgent("CANCELLED", "Session aborted by user")
            # Takeover inactivity (mid-task): if the user took control then
            # stopped interacting, lift the pause so the agent finishes rather
            # than holding an idle Chromium paused until the wallclock cap.
            # The cdp.py bridge refreshes last_activity on every user input, so
            # an actively-driving user never trips this.
            if (session.takeover_active and session.last_activity > 0
                    and time.time() - session.last_activity >= TAKEOVER_IDLE_TIMEOUT_S):
                logger.info("session pause auto-resume (takeover idle): session=%s",
                            session.session_id)
                session.paused = False

        # Per-user daily steps budget (one increment per step start).
        try:
            await increment_daily_steps(redis, user_id)
        except Exception:
            # Budget functions raise BudgetExhaustedError; let the caller
            # of run_browser_agent_session map it to BUDGET_EXHAUSTED.
            raise

        # First-step setup: guardrails + navigation filter. browser-use
        # has now booted Chromium; all probes work. (CDP endpoint capture
        # moved above the pause-check so it runs in supervised/manual
        # modes too - see comment block at the top of this function.)
        if not guardrails_installed and settings.browser_agent_guardrails_enabled:
            try:
                guardrails_installed = await _install_guardrails(running_agent)
            except Exception:
                logger.debug("guardrails on_step_start retry failed", exc_info=True)
        # Install the post-redirect SSRF filter ONCE on the live CDP
        # session. The handler runs inside browser-use's CDP loop and
        # marks `session.aborted` when a frame navigates to a blocked
        # URL - the next on_step_start call will raise _StopAgent and
        # short-circuit the run.
        if not nav_filter_installed:
            try:
                nav_filter_installed = await _install_navigation_filter(
                    running_agent, session
                )
            except Exception:
                logger.debug("nav-filter on_step_start install failed", exc_info=True)
        # Honour DOMAIN_BLOCKED set by the nav filter.
        if session.last_eval and session.last_eval.startswith("navigation blocked:"):
            raise _StopAgent("DOMAIN_BLOCKED", session.last_eval)

    async def on_step_end(running_agent: Any) -> None:
        # browser-use exposes the latest history entry on the agent's
        # state - best-effort extraction; falls back to a thin synthetic
        # event when the binding isn't introspectable.
        latest = _safe_get(running_agent, "history")
        last_step: Any = None
        if latest is not None:
            try:
                items = list(latest)
                if items:
                    last_step = items[-1]
            except (TypeError, AttributeError):
                pass

        if last_step is None:
            last_step = type("H", (), {})()  # empty shell

        session.step_index += 1
        await on_step(last_step)

    try:
        try:
            history = await agent.run(
                max_steps=max_steps,
                on_step_start=on_step_start,
                on_step_end=on_step_end,
            )
        except _StopAgent as e:
            return {
                "stop_reason": e.reason,
                "final_result": e.message or "",
                "final_url": session.last_url,
                # Capture tokens consumed BEFORE the early stop so the Java
                # observability writer can debit credits for what already ran.
                # `usage_history` covers 7/7 exit paths regardless of where
                # `agent.run()` exits (it's append-only via `tracked_ainvoke`).
                "cost_raw": _extract_token_usage(agent),
            }
        except Exception as e:
            logger.error("agent.run crashed: %s", e, exc_info=True)
            return {
                "stop_reason": "LLM_FAILED",
                "final_result": f"agent.run error: {e}",
                "cost_raw": _extract_token_usage(agent),
            }

        # browser-use's AgentHistoryList exposes summary helpers - extract
        # the final result string and any structured data the agent emitted
        # via the `done` action.
        final_result = ""
        extracted = None
        final_url = session.last_url
        pages: list[str] = []
        is_done = False

        try:
            if hasattr(history, "final_result"):
                fr = history.final_result()
                if asyncio.iscoroutine(fr):
                    fr = await fr
                final_result = str(fr) if fr else ""
            if hasattr(history, "extracted_content"):
                ec = history.extracted_content()
                if asyncio.iscoroutine(ec):
                    ec = await ec
                extracted = ec
            if hasattr(history, "is_done"):
                d = history.is_done()
                if asyncio.iscoroutine(d):
                    d = await d
                is_done = bool(d)
            if hasattr(history, "urls"):
                us = history.urls()
                if asyncio.iscoroutine(us):
                    us = await us
                if isinstance(us, list):
                    pages = [str(u) for u in us if u]
                    if pages:
                        final_url = pages[-1]
        except Exception:
            logger.debug("history summary extraction failed", exc_info=True)

        last_error = await _extract_last_history_error(history)
        stop_reason, final_result_str = _classify_stop_reason(
            is_done=is_done,
            actual_steps=session.step_index,
            max_steps=max_steps,
            final_result=final_result,
            last_error=last_error,
        )

        # Post-completion DETACHED hold: when a live-view viewer is watching
        # (or the user already took control mid-task), hand Chromium to a
        # detached task that keeps the page open and interactive - the
        # agent's result returns IMMEDIATELY (the chat reply / workflow no
        # longer waits on the hold). The detached task owns the final
        # screenshot + browser close + session unregister; it releases on
        # viewer disconnect (+grace), input inactivity, an explicit
        # RESUME/ABORT control, or the hard cap. Without a viewer the
        # browser tears down right here as before.
        _maybe_detach_hold(agent, session, redis, user_id)

        return {
            "stop_reason": stop_reason,
            "final_result": final_result_str,
            "extracted_data": extracted,
            "final_url": final_url,
            "pages_visited": pages,
            "cost_raw": _extract_token_usage(agent),
        }
    finally:
        # Capture the FINAL page BEFORE Chromium is killed - this is the
        # WS-independent "last page" the frontend panel falls back to when
        # the live CDP screencast never connected. Runs on every exit path
        # (success, _StopAgent, crash) so a frozen-mid-step run still leaves
        # a last frame. Best-effort: never blocks teardown.
        # When the detached hold owns the browser, IT does both at release
        # time instead - the capture then shows the page as the user left it.
        if not session.hold_detached:
            await _capture_and_store_final_screenshot(agent, session, redis)
            await _close_browser_session(agent)


# ── Helpers ───────────────────────────────────────────────────────────────

def _maybe_detach_hold(agent: Any, session: BrowserAgentSession,
                       redis: aioredis.Redis, user_id: str) -> bool:
    """Hand the completed task's browser to a detached post-completion hold
    when someone is there to hold it FOR (see `_should_detach_hold`).

    Registers the hold in the per-user + host-wide caps (the user's next
    session evicts their previous page; the oldest hold is evicted past
    `max_global_sessions`), keeps a STRONG reference to the task (asyncio
    holds only weak ones - a GC'd hold task would leak Chromium and the
    registry entry), and marks `session.hold_detached` so the runner's
    cleanup leaves teardown to the hold task. Returns whether it detached.
    """
    if not _should_detach_hold(session):
        return False
    session.hold_detached = True
    session.hold_release_event = asyncio.Event()
    _register_hold(session, user_id)
    hold_task = asyncio.get_running_loop().create_task(
        _post_completion_hold(agent, session, redis),
        name=f"browser-hold-{session.session_id}",
    )
    _hold_tasks.add(hold_task)
    hold_task.add_done_callback(_hold_tasks.discard)
    logger.info(
        "browser_agent detached HOLD started: session=%s viewers=%d takeover=%s",
        session.session_id, session.viewer_count, session.takeover_active,
    )
    return True


def _should_detach_hold(session: BrowserAgentSession) -> bool:
    """Whether the completed task's browser should be handed to a detached
    post-completion hold instead of being torn down inline.

    True when the feature is enabled, the session wasn't aborted, and there
    is someone to hold FOR: a connected live-view viewer, or a user who
    already took control mid-task (their WS may be between reconnects at
    this exact instant - the takeover flag keeps their page alive)."""
    return (settings.browser_agent_hold_enabled
            and not session.aborted
            and (session.takeover_active or session.viewer_count > 0))


async def _post_completion_hold(
    agent: Any,
    session: BrowserAgentSession,
    redis: aioredis.Redis,
) -> None:
    """DETACHED owner of the browser after the agent's task completes.

    Spawned by `_drive_browser_use` when a live-view viewer is connected (or
    the user already took control mid-task). The agent's result has ALREADY
    been returned to the caller - this task only governs how long the final
    page stays open and interactive, then does the teardown the non-held
    path does inline: final screenshot → browser close → session unregister
    → job-mapping cleanup.

    Never raises: every phase is best-effort so a hold failure can't leak a
    Chromium (the close+unregister run in the finally).
    """
    try:
        await _hold_until_released(session, redis)
    except Exception:
        logger.warning("browser_agent hold loop failed (non-fatal): session=%s",
                       session.session_id, exc_info=True)
    finally:
        _unregister_hold(session)
        # Capture AFTER the hold so the stored "last page" reflects what the
        # user left on screen, not what the agent finished on.
        await _capture_and_store_final_screenshot(agent, session, redis)
        await _close_browser_session(agent)
        await unregister_session(session.session_id)
        try:
            await redis.delete(f"agent:browser:job:{session.job_id}")
        except Exception:
            logger.debug("delete job→session mapping failed", exc_info=True)


async def _hold_until_released(
    session: BrowserAgentSession,
    redis: aioredis.Redis,
) -> None:
    """Block until the post-completion hold should release.

    Presence-based, never a fixed countdown that cuts active use:
      - releases when the LAST live-view viewer disconnects and stays gone
        for ``browser_agent_hold_viewer_gone_grace_seconds`` (panel closed;
        the grace covers a remount / tab switch),
      - releases on INACTIVITY (no user input for ``TAKEOVER_IDLE_TIMEOUT_S``
        - the cdp.py bridge refreshes ``session.last_activity`` on every
        input, so active driving keeps it open within the hard cap),
      - releases immediately on an explicit RESUME / ABORT / END control
        entry (the frontend posts takeover-resume on Resume / panel close),
      - hard cap ``browser_agent_hold_max_seconds`` regardless of activity
        (a stuck viewer socket can never squat Chromium).

    The page stays interactive throughout: the cdp.py WS proxy keeps
    forwarding the user's CDP ``Input.dispatch*`` to the same Chromium this
    hold keeps open.
    """
    hold_started = time.time()
    # Seed the idle clock so a viewer who never provides input is still
    # bounded by inactivity, not only the hard cap.
    if session.last_activity <= 0:
        session.last_activity = hold_started
    viewer_grace = max(1, settings.browser_agent_hold_viewer_gone_grace_seconds)
    hard_cap = max(5, settings.browser_agent_hold_max_seconds)
    logger.info(
        "browser_agent post-completion HOLD start: session=%s viewers=%d "
        "idle_timeout=%ds viewer_grace=%ds hard_cap=%ds",
        session.session_id, session.viewer_count,
        TAKEOVER_IDLE_TIMEOUT_S, viewer_grace, hard_cap,
    )
    while True:
        now = time.time()
        # Cap eviction: the user's next session (or the host-wide hold cap)
        # asked this hold to release.
        if session.hold_release_event is not None and session.hold_release_event.is_set():
            logger.info("browser_agent HOLD end (evicted): session=%s",
                        session.session_id)
            return
        # Hard backstop, independent of any activity signal.
        if now - hold_started >= hard_cap:
            logger.info("browser_agent HOLD end (hard cap %ds): session=%s",
                        hard_cap, session.session_id)
            return
        # Viewer presence: nobody watching → short grace → release. When no
        # viewer ever connected during the hold, anchor the grace on the
        # hold start (last_viewer_disconnect may still be 0.0).
        if session.viewer_count <= 0:
            gone_since = max(session.last_viewer_disconnect, hold_started)
            if now - gone_since >= viewer_grace:
                logger.info("browser_agent HOLD end (viewer gone %.0fs): session=%s",
                            now - gone_since, session.session_id)
                return
        # Inactivity: no user input for the idle window (viewer may still be
        # passively watching - a completed task with no interaction doesn't
        # justify keeping Chromium alive indefinitely).
        idle = now - session.last_activity
        if idle >= TAKEOVER_IDLE_TIMEOUT_S:
            logger.info("browser_agent HOLD end (idle %.0fs): session=%s",
                        idle, session.session_id)
            return
        # Explicit end (RESUME / ABORT / END). Bound the BLPOP so the
        # presence/idle/cap checks keep ticking when it's quiet.
        wait_s = max(1, min(TAKEOVER_HOLD_POLL_S, viewer_grace))
        cmd = await blpop_control(redis, session.run_id, session.node_id, timeout_s=wait_s)
        if cmd is not None:
            # A command tagged for a SIBLING session sharing this control
            # key (loop iteration / re-trigger while this hold is open) is
            # requeued at the tail for its owner - never swallowed here.
            if not await _cmd_is_for_session(session, cmd, redis):
                await asyncio.sleep(0.2)
                continue
            c = str(cmd.get("cmd") or "").upper()
            if c in (CMD_RESUME, CMD_ABORT, "END", "END_TAKEOVER"):
                logger.info("browser_agent HOLD end (cmd=%s): session=%s",
                            c, session.session_id)
                if c == CMD_ABORT:
                    session.aborted = True
                return
            # PAUSE / INTERVENE during a post-completion hold are no-ops.


async def _capture_and_store_final_screenshot(
    agent: Any,
    session: BrowserAgentSession,
    redis: aioredis.Redis,
) -> None:
    """Capture the FINAL page as a JPEG via CDP and stash it (base64) in
    Redis so the frontend live panel can show the last page even when the
    live CDP screencast WS never connected (no public /cdp route,
    internal-only cdp_ws_url, Cloudflare WS blocked).

    Keyed by (run_id, node_id) via :func:`store_final_screenshot` so the
    orchestrator's run-ownership-gated endpoint can serve it without
    trusting a client-supplied session id. Sensitive inputs are already
    DOM-masked by `guardrails.js`, so the captured frame carries the same
    redaction as the live screencast.

    Best-effort: never raises and is wallclock-bounded (each CDP await is
    wrapped in ``asyncio.wait_for`` so a hung Chromium can't strand teardown)
    - a capture failure must not change the run's stop_reason. Returns
    silently when the feature is disabled or no live CDP page is reachable
    (e.g. early LLM failure before Chromium booted)."""
    if not settings.browser_agent_final_screenshot_enabled:
        return
    browser_session = _safe_get(agent, "browser_session")
    if browser_session is None or not hasattr(browser_session, "get_or_create_cdp_session"):
        return
    try:
        cdp_session = await asyncio.wait_for(
            browser_session.get_or_create_cdp_session(),
            timeout=_FINAL_SHOT_CAPTURE_TIMEOUT_S,
        )
        cdp_client = _safe_get(cdp_session, "cdp_client")
        cdp_session_id = _safe_get(cdp_session, "session_id")
        if cdp_client is None or cdp_session_id is None:
            return
        quality = max(1, min(100, settings.browser_agent_final_screenshot_quality))
        result = await asyncio.wait_for(
            cdp_client.send.Page.captureScreenshot(
                params={"format": "jpeg", "quality": quality},
                session_id=cdp_session_id,
            ),
            timeout=_FINAL_SHOT_CAPTURE_TIMEOUT_S,
        )
        data_b64 = (result or {}).get("data")
        if not data_b64 or not isinstance(data_b64, str):
            return
        await store_final_screenshot(
            redis,
            session.run_id,
            session.node_id,
            data_b64,
            mime="image/jpeg",
            ttl_s=settings.browser_agent_final_screenshot_ttl_seconds,
        )
        logger.info(
            "browser_agent final screenshot stored: session=%s run=%s node=%s b64_len=%d",
            session.session_id, session.run_id, session.node_id, len(data_b64),
        )
    except Exception:
        logger.warning(
            "final screenshot capture failed (non-fatal): session=%s",
            session.session_id, exc_info=True,
        )


async def _close_browser_session(agent: Any) -> None:
    """Best-effort Chromium cleanup after browser-use returns or crashes."""
    try:
        browser_session = getattr(agent, "browser_session", None)
        if browser_session is None:
            return
        close_fn = getattr(browser_session, "kill", None) or getattr(browser_session, "close", None)
        if close_fn is None:
            return
        result = close_fn()
        if hasattr(result, "__await__"):
            await result
    except Exception:
        logger.debug("browser_session kill/close failed", exc_info=True)


async def _drain_control(
    session: BrowserAgentSession,
    redis: aioredis.Redis,
) -> None:
    """Drain ALL pending control commands without blocking.

    Multiple commands may have been LPUSHed between steps (e.g. INTERVENE
    + PAUSE). We process them in order.
    """
    foreign: list[dict[str, Any]] = []
    try:
        while True:
            cmd = await lpop_control(redis, session.run_id, session.node_id)
            if cmd is None:
                return
            sid = cmd.get("session_id")
            if sid and sid != session.session_id:
                if get_session_state(sid) is None:
                    # Owner is gone - dropping beats cycling it forever
                    # between live consumers (same rule as
                    # _cmd_is_for_session).
                    logger.info("dropping control cmd=%r for defunct session %s",
                                cmd.get("cmd"), sid)
                    continue
                # Sibling session's command (shared (run,node) control key)
                # - requeue AFTER the drain so this loop cannot re-pop it.
                foreign.append(cmd)
                continue
            await _apply_control(session, cmd)
    finally:
        for cmd in foreign:
            await requeue_control(redis, session.run_id, session.node_id, cmd)


async def _apply_control(
    session: BrowserAgentSession,
    cmd: dict[str, Any],
) -> None:
    """Apply a control command to the session state."""
    raw = (cmd.get("cmd") or "").upper()
    if raw == CMD_PAUSE:
        session.paused = True
        if session.abort_event:
            session.resume_event = asyncio.Event()
    elif raw == CMD_RESUME:
        session.paused = False
        injection = cmd.get("memory_injection")
        if injection:
            session.pending_hint = str(injection)
        if session.resume_event and not session.resume_event.is_set():
            session.resume_event.set()
    elif raw == CMD_ABORT:
        session.aborted = True
        if session.abort_event and not session.abort_event.is_set():
            session.abort_event.set()
    elif raw == CMD_INTERVENE:
        hint = cmd.get("hint")
        if hint:
            session.pending_hint = str(hint)
    else:
        logger.warning("unknown control cmd: %r (session=%s)", raw, session.session_id)


def _make_on_step_callback(session: BrowserAgentSession, redis: aioredis.Redis):
    """Return an async callback the runner invokes after each step.

    Captures session + redis in closure so `_drive_browser_use` can call
    `on_step(history_step)` without re-passing them.

    Also tracks the wallclock interval between consecutive step ends so
    the orchestrator's observability writer can record per-iteration
    `duration_ms`. browser-use 0.12.x does not expose a per-step timing
    on the history entry, so the inter-callback delta is the best signal
    available.

    Uses `time.monotonic()` rather than `time.time()` because wall-clock
    is subject to NTP step-back / leap-second / manual clock changes, any
    of which would silently corrupt a duration delta. Monotonic is the
    correct primitive for an internal timing metric.
    """
    last_step_end_ts: list[float] = [time.monotonic()]

    async def _cb(history_step: Any) -> None:
        action_raw = getattr(history_step, "action", None)
        url = getattr(history_step, "url", "") or ""
        eval_text = getattr(history_step, "eval", "") or ""
        memory = getattr(history_step, "memory", "") or ""
        next_goal = getattr(history_step, "next_goal", "") or ""
        screenshot_key = getattr(history_step, "screenshot_key", None)
        tokens_in = int(getattr(history_step, "tokens_in", 0) or 0)
        tokens_out = int(getattr(history_step, "tokens_out", 0) or 0)

        session.last_url = url or session.last_url
        session.last_action = str(action_raw) if action_raw else session.last_action
        session.last_eval = eval_text or session.last_eval
        session.tokens_in += tokens_in
        session.tokens_out += tokens_out
        session.llm_calls += 1

        action = _action_dict(action_raw)
        action_name, target = _split_action(action, fallback_url=url)

        now = time.monotonic()
        duration_ms = int(max(0.0, now - last_step_end_ts[0]) * 1000)
        last_step_end_ts[0] = now

        # Belt-and-braces redaction: if browser-use surfaced raw screenshot
        # bytes + bounding-box hints on the history step, run them through
        # the post-capture mask before the event is emitted. The redacted
        # bytes ride along on the payload as `redacted_screenshot_bytes`
        # (size only - the orchestrator's MinIO uploader replaces the
        # original frame referenced by `screenshot_key`).
        redacted_size: Optional[int] = None
        if settings.browser_agent_redact_screenshots:
            try:
                redacted = _redact_step_screenshot(history_step)
                if redacted is not None:
                    redacted_size = len(redacted)
            except Exception:  # pragma: no cover - defensive, must not break the loop
                logger.warning(
                    "screenshot redaction failed for session=%s step=%d",
                    session.session_id, session.step_index,
                    exc_info=True,
                )

        extra: Optional[dict[str, Any]] = None
        if redacted_size is not None:
            extra = {"redacted_screenshot_bytes_size": redacted_size}

        # Accumulate the step in `session.steps_log` so the final result
        # carries the trace back to the orchestrator's observability writer.
        # Shape aligned with what `BrowserAgentNode.recordObservability`
        # reads (every key here is read on the Java side; no dead fields):
        #   - action (string)        → tool_call.tool_name + args.action + meta.action
        #   - target (string)        → args.target + meta.target
        #   - action_args (dict)     → args.action_args + meta.action_args (full dump)
        #   - url (string)           → meta.url
        #   - eval / memory / next_goal / screenshot_key (string) → meta.*
        #   - duration_ms (long)     → iteration.duration_ms + tool_call.duration_ms
        #   - tokens_in / tokens_out (long) → iteration.prompt_tokens / completion_tokens
        #     (header cost block is summed separately in _build_result)
        session.steps_log.append({
            "step_index": session.step_index,
            "url": url,
            "eval": eval_text,
            "memory": memory,
            "next_goal": next_goal,
            "action": action_name,          # short kind for tool_name
            "action_args": action,          # full pydantic dump for replay
            "target": target,
            "screenshot_key": screenshot_key,
            "tokens_in": tokens_in,
            "tokens_out": tokens_out,
            "duration_ms": duration_ms,
        })

        await xadd_step(redis, session.run_id, session.node_id, make_step_event(
            type_="step",
            step_index=session.step_index,
            url=url,
            eval_text=eval_text,
            memory=memory,
            next_goal=next_goal,
            action=action,
            screenshot_key=screenshot_key,
            tokens_in=tokens_in,
            tokens_out=tokens_out,
            extra=extra,
        ))
    return _cb


def _split_action(action: Any, fallback_url: str = "") -> tuple[str, str]:
    """Pull a clean ``(action_name, target)`` pair out of a browser-use
    action dump.

    browser-use serializes a step's action as a single-key pydantic dict
    such as ``{"go_to_url": {"url": "https://..."}}``. The orchestrator's
    observability writer expects two strings:

      - ``action_name`` - used as ``tool_call.tool_name``
      - ``target``     - used as ``args.target`` (URL or selector)

    Falls back to ``fallback_url`` when the action carries no URL/selector
    of its own (e.g. a synthetic step shell). Always returns ``("", "")``
    for unknown shapes - the writer tolerates empty strings.
    """
    if not isinstance(action, dict) or not action:
        return ("", fallback_url or "")
    keys = list(action.keys())
    if len(keys) == 1:
        name = str(keys[0])
        body = action[name]
        target = ""
        if isinstance(body, dict):
            for tk in ("url", "selector", "xpath", "text", "index"):
                v = body.get(tk)
                if v is not None and v != "":
                    target = str(v)
                    break
        return (name, target or fallback_url or "")
    name = str(action.get("kind") or action.get("name") or "")
    return (name, fallback_url or "")


def _task_with_start_url(task: str, start_url: Any) -> str:
    """Prepend an explicit "open this URL first" step to ``task`` so browser-use
    navigates to ``start_url`` before doing the rest. Without it Chromium boots
    on ``about:blank`` and the agent reports "no page URL was provided" when the
    task itself doesn't name a URL. We steer via the task instead of browser-use
    ``initial_actions``, whose go_to_url path errors at runtime on the deployed
    version ("Runner error: 'go_to_url'"). Returns ``task`` unchanged when no
    start_url is given."""
    if not start_url:
        return task
    return f"First, open this exact URL: {start_url}\n\n{task}"


def _make_on_navigation_callback(session: BrowserAgentSession):
    """Return a navigation hook that SSRF-checks the target before each go-to.

    Returns (allow, reason). Used by `_install_navigation_filter` to mark
    the session aborted when a frame navigates to a blocked URL - the
    next on_step_start raises _StopAgent("DOMAIN_BLOCKED").

    Defined as a separate factory so tests can stub the predicate without
    touching `is_url_safe_for_navigation` (which is unit-tested separately).
    """
    def _cb(target_url: str) -> tuple[bool, str]:
        safe, reason = is_url_safe_for_navigation(target_url)
        if not safe:
            session.last_eval = f"navigation blocked: {reason}"
        return safe, reason
    return _cb


async def _install_navigation_filter(agent: Any, session: BrowserAgentSession) -> bool:
    """Wire `is_url_safe_for_navigation` into the live CDP session.

    Subscribes to `Page.frameNavigated` on the BrowserSession's CDP client.
    Each navigation event calls the same predicate `is_url_safe_for_navigation`
    used at the start_url gate. When the target is blocked, the handler:

      1. Sets `session.last_eval = "navigation blocked: <reason>"` so the
         next `on_step_start` raises `_StopAgent("DOMAIN_BLOCKED", ...)`
         and short-circuits the run.
      2. Best-effort issues a `Page.stopLoading` to halt the in-progress
         load before the LLM sees the blocked page content.

    This closes the post-redirect SSRF gap: a 302 from an allowed start_url
    to an internal IP (e.g. 169.254.169.254 IMDS) is now caught at the
    Chromium layer, NOT just at the orchestrator-side allowlist.

    Returns True if the listener landed, False otherwise. Never raises.
    """
    on_nav = _make_on_navigation_callback(session)

    browser_session = _safe_get(agent, "browser_session")
    if browser_session is None:
        return False
    if not hasattr(browser_session, "get_or_create_cdp_session"):
        return False

    try:
        cdp_session = await browser_session.get_or_create_cdp_session()
        cdp_client = _safe_get(cdp_session, "cdp_client")
        cdp_session_id = _safe_get(cdp_session, "session_id")
        if cdp_client is None or cdp_session_id is None:
            return False

        async def _on_frame_navigated(event: dict) -> None:
            frame = (event or {}).get("frame") or {}
            url = frame.get("url") or ""
            if not url or not url.startswith(("http://", "https://")):
                return
            # Only top-level frames update the address bar; sub-frame
            # (iframe) navigations would otherwise overwrite `last_url`
            # with an ad/widget URL that isn't what the user is on.
            # Top-level frames have no `parentId` per the CDP spec.
            if not frame.get("parentId"):
                # Mirror Chromium navigations into session.last_url so
                # the live-view address bar (polled by the frontend via
                # /agent/sessions/{id}/status) reflects the page the
                # agent is on RIGHT NOW, not the URL of whichever step
                # last completed. Without this, the address bar stays
                # empty until step 1's history callback fires (~5-30 s
                # after the session starts).
                session.last_url = url
            safe, reason = on_nav(url)
            if not safe:
                logger.warning(
                    "browser_agent navigation blocked: session=%s url=%s reason=%s",
                    session.session_id, url, reason,
                )
                # Best-effort halt the in-progress load.
                try:
                    await cdp_client.send.Page.stopLoading(session_id=cdp_session_id)
                except Exception:
                    logger.debug("Page.stopLoading failed", exc_info=True)
                # Mark aborted; next step boundary picks it up.
                session.aborted = True

        # CDP event subscription. browser-use's cdp-use client supports
        # `cdp_client.on('Page.frameNavigated', handler, session_id=...)`.
        register = _safe_get(cdp_client, "on") or _safe_get(cdp_client, "register_event_handler")
        if not callable(register):
            logger.info(
                "browser_agent navigation filter: cdp_client has no event-subscription "
                "binding - relying on start_url gate only (session=%s)",
                session.session_id,
            )
            return False
        try:
            res = register("Page.frameNavigated", _on_frame_navigated, session_id=cdp_session_id)
            if asyncio.iscoroutine(res):
                await res
        except TypeError:
            # Fallback signature without session_id kw.
            res = register("Page.frameNavigated", _on_frame_navigated)
            if asyncio.iscoroutine(res):
                await res

        # Enable the Page domain so the event actually fires.
        try:
            await cdp_client.send.Page.enable(session_id=cdp_session_id)
        except Exception:
            logger.debug("Page.enable failed (likely already enabled)", exc_info=True)

        logger.info(
            "browser_agent navigation filter installed via CDP "
            "Page.frameNavigated (session=%s)",
            session.session_id,
        )
        return True
    except Exception as e:
        logger.warning(
            "browser_agent navigation filter install failed: %s - "
            "post-redirect SSRF NOT enforced for session=%s "
            "(start_url gate is still active)",
            e, session.session_id,
        )
        return False


async def _install_guardrails(agent: Any) -> bool:
    """Inject `guardrails.js` so it runs on every new document.

    browser-use 0.12.x does NOT expose the Playwright `Page` directly off
    the `Agent` - it owns its own `BrowserSession` that drives Chrome over
    CDP. The CDP equivalent of Playwright's `page.add_init_script(...)` is
    `Page.addScriptToEvaluateOnNewDocument`, which we call via the CDP
    session browser-use surfaces through
    `agent.browser_session.get_or_create_cdp_session()`.

    Falls back through three strategies in order - the first one that
    works wins:

      1. CDP `Page.addScriptToEvaluateOnNewDocument` via the
         browser_session's CDP client (browser-use 0.12+ canonical path).
      2. Playwright-style `add_init_script` if the binding ever exposes a
         raw `Page` (older / forked browser-use builds, or a future
         API change).
      3. Log "guardrails.js prepared (N bytes) - TODO browser-use API
         binding" so production startup is still verifiable even when
         the wiring point is missing.

    Returns True if a real injection landed, False otherwise. Never
    raises - guardrails are defense-in-depth; the post-capture redactor
    is the second layer.
    """
    js = GUARDRAILS_JS

    # Path 1 - CDP via browser_session (browser-use 0.12.x canonical).
    browser_session = getattr(agent, "browser_session", None)
    if browser_session is not None and hasattr(browser_session, "get_or_create_cdp_session"):
        try:
            cdp_session = await browser_session.get_or_create_cdp_session()
            cdp_client = getattr(cdp_session, "cdp_client", None)
            session_id = getattr(cdp_session, "session_id", None)
            if cdp_client is not None and session_id is not None:
                await cdp_client.send.Page.addScriptToEvaluateOnNewDocument(
                    params={"source": js},
                    session_id=session_id,
                )
                logger.info(
                    "browser_agent guardrails injected via CDP "
                    "(Page.addScriptToEvaluateOnNewDocument, %d bytes)",
                    len(js.encode("utf-8")),
                )
                return True
        except Exception as e:
            logger.warning(
                "browser_agent guardrails CDP injection failed: %s - falling through",
                e, exc_info=True,
            )

    # Path 2 - Playwright-style add_init_script if a raw page is exposed.
    page = await _get_active_page(agent)
    if page is not None and hasattr(page, "add_init_script"):
        try:
            res = page.add_init_script(js)
            if asyncio.iscoroutine(res):
                await res
            logger.info(
                "browser_agent guardrails injected via Playwright add_init_script (%d bytes)",
                len(js.encode("utf-8")),
            )
            return True
        except Exception as e:
            logger.warning(
                "browser_agent guardrails Playwright injection failed: %s - falling through",
                e, exc_info=True,
            )

    # Path 3 - TODO marker. The script is loaded; the binding is not.
    logger.warning(
        "browser_agent guardrails.js prepared (%d bytes) - "
        "TODO browser-use API binding: no CDP session and no Playwright "
        "page exposed; DOM-level guardrails NOT installed for this session. "
        "Post-capture screenshot redaction still applies.",
        len(js.encode("utf-8")),
    )
    return False


def _safe_get(obj: Any, attr: str) -> Any:
    """Read an attribute, swallowing any exception (incl. AssertionError).

    browser-use's `BrowserSession.cdp_client` etc. are properties that
    *assert* on internal state - `getattr(obj, attr, None)` does NOT
    short-circuit those asserts because the attribute access itself
    triggers them. This helper catches everything: AttributeError when
    the attribute is genuinely missing, AssertionError when the
    underlying invariant isn't met yet (Chromium not connected), plus
    any other surprise the upstream lib may throw.
    """
    try:
        return getattr(obj, attr, None)
    except (AttributeError, AssertionError, Exception):
        return None


async def _extract_cdp_endpoint(agent: Any) -> Optional[str]:
    """Best-effort extraction of a Chromium DevTools WebSocket URL.

    Thin wrapper over :func:`_extract_cdp_endpoints` kept as the public
    name several call sites already use. Returns the page-level URL when
    promotion succeeded; falls back to the browser-level URL when no page
    target could be promoted (matches legacy 0.12.x behaviour where every
    probe returned ``page_url or direct``). Same return type as before;
    the multi-tab path lives in :func:`_extract_cdp_endpoints` which
    surfaces both.
    """
    browser_url, page_url = await _extract_cdp_endpoints(agent)
    return page_url or browser_url


async def _extract_cdp_endpoints(agent: Any) -> tuple[Optional[str], Optional[str]]:
    """Probe browser-use's BrowserSession for the BROWSER-level *and* the
    PAGE-level CDP WebSocket URLs.

    Returns ``(browser_url, page_url)`` where:
      * ``browser_url`` = ``ws://host:port/devtools/browser/<id>`` - the
        control-plane URL cdp.py uses to subscribe to ``Target.*`` events
        so the screencast follows the agent across tab switches
        (auto-detected popups, ``target=_blank``).
      * ``page_url`` = ``ws://host:port/devtools/page/<targetId>`` - the
        initial page-target the screencast attaches to. ``Page.*`` commands
        only operate on Page targets, so we still need this for the first
        ``Page.startScreencast``.

    Either entry can be None when probing fails; callers fall back to the
    other one (page-only legacy mode if browser_url is missing, control-
    plane-only if page_url is missing). The four strategies below mirror
    :func:`_extract_cdp_endpoint`'s historical contract.

    Strategies (in order, first hit wins):
      1. ``agent.browser_session.cdp_url`` (typically BROWSER-level)
      2. ``agent.browser_session.browser.wsEndpoint()`` (Playwright-shape)
      3. ``agent.browser_session.cdp_client.ws_url`` / ``.endpoint``
      4. ``http://127.0.0.1:$CHROME_DEBUGGING_PORT/json/version``
         → ``webSocketDebuggerUrl``

    Never raises - wraps all probes in catch-all so an upstream
    AssertionError ("Root CDP client not initialized" when Chromium
    hasn't booted yet) returns ``(None, None)`` instead of bubbling up.
    Callers should re-probe after the first agent step in that case.
    """
    browser_session = _safe_get(agent, "browser_session")
    if browser_session is None:
        return None, None

    async def _probe(raw_url: Optional[str]) -> Optional[tuple[str, Optional[str]]]:
        """Return (browser_url, page_url-or-None) for a candidate raw URL.

        ``raw_url`` from browser-use is typically already the BROWSER-level
        endpoint. We keep it as ``browser_url`` and run promotion to derive
        the matching page-level endpoint. If promotion fails (no page
        target listed in /json yet) we still return the browser URL so
        cdp.py at least has a control-plane connection.
        """
        if not isinstance(raw_url, str) or not raw_url.startswith(("ws://", "wss://")):
            return None
        page_url = await _promote_to_page_target(raw_url)
        # Heuristic: if the raw URL already looks page-level
        # (`/devtools/page/<id>`) just keep it as the page_url; we have
        # no way to derive the browser URL from a page URL without an
        # /json/version lookup, which we attempt below as a last resort.
        if "/devtools/page/" in raw_url:
            return None, raw_url
        return raw_url, page_url

    # Strategy 1: direct attribute exposed by browser-use
    direct = _safe_get(browser_session, "cdp_url")
    res = await _probe(direct)
    if res is not None:
        return res

    # Strategy 2: Playwright Browser.wsEndpoint()
    browser = _safe_get(browser_session, "browser")
    if browser is not None:
        ws_fn = _safe_get(browser, "wsEndpoint") or _safe_get(browser, "ws_endpoint")
        if callable(ws_fn):
            try:
                val = ws_fn()
                if asyncio.iscoroutine(val):
                    val = await val
                res = await _probe(val if isinstance(val, str) else None)
                if res is not None:
                    return res
            except Exception:
                logger.debug("wsEndpoint probe failed", exc_info=True)

    # Strategy 3: cdp-use client's connection URL
    cdp_client = _safe_get(browser_session, "cdp_client")
    if cdp_client is not None:
        for attr in ("ws_url", "endpoint", "url"):
            res = await _probe(_safe_get(cdp_client, attr))
            if res is not None:
                return res

    # Strategy 4: derive from /json/version when the DevTools port is open
    import os as _os
    port = _os.environ.get("CHROME_DEBUGGING_PORT")
    if port and port.isdigit():
        try:
            import httpx  # already a dependency
            async with httpx.AsyncClient(timeout=2.0) as client:
                resp = await client.get(f"http://127.0.0.1:{port}/json/version")
                data = resp.json()
                ws = data.get("webSocketDebuggerUrl")
                res = await _probe(ws if isinstance(ws, str) else None)
                if res is not None:
                    return res
        except Exception:
            logger.debug("CDP /json/version probe failed", exc_info=True)

    return None, None


async def _promote_to_page_target(browser_ws_url: str) -> Optional[str]:
    """Convert a browser-level CDP URL into a page-level CDP URL.

    Why: ``Page.startScreencast`` (and most ``Page.*`` / ``DOM.*`` / ``Input.*``
    commands) operate on a Page target, not on the Browser target. A
    connection to ``ws://host:port/devtools/browser/<id>`` accepts the
    command but emits no frames because no page is attached. The
    cdp.py proxy needs ``ws://host:port/devtools/page/<targetId>``.

    How: every Chromium DevTools server publishes a list of targets at
    ``http://host:port/json``. We pull it, filter to ``type='page'``
    entries (skipping extensions, service workers, iframes), sort to
    prefer non-blank URLs (a freshly-launched Chromium has one blank
    ``about:blank`` tab; once browser-use navigates, we want the real
    one), and return the first match's ``webSocketDebuggerUrl``.

    Returns None on any failure (unparseable URL, /json unreachable,
    no page targets) so the caller falls back to the browser-level URL.
    Better to give the proxy something usable (even if Page.* no-ops)
    than to leave the live-view in stub mode.
    """
    if not isinstance(browser_ws_url, str) or "://" not in browser_ws_url:
        return None
    try:
        # ws://host:port/path → http://host:port (devtools serves both schemes
        # on the same listener; /json is plain HTTP)
        from urllib.parse import urlparse
        parsed = urlparse(browser_ws_url)
        if not parsed.hostname or not parsed.port:
            return None
        # Fast-fail TCP probe before the httpx GET. On a routable-but-
        # firewalled host (e.g. Docker bridge with iptables-DROP) the
        # 2 s httpx timeout would burn on the critical path of every
        # on_step_start until cdp_captured flips. A 250 ms TCP connect
        # rejects faster.
        try:
            connect = asyncio.open_connection(parsed.hostname, parsed.port)
            reader, writer = await asyncio.wait_for(connect, timeout=0.25)
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
        except (asyncio.TimeoutError, OSError):
            logger.debug("CDP /json TCP probe failed for %s:%s - skipping promotion",
                         parsed.hostname, parsed.port)
            return None
        json_url = f"http://{parsed.hostname}:{parsed.port}/json"
        import httpx  # already a dependency
        async with httpx.AsyncClient(timeout=2.0) as client:
            resp = await client.get(json_url)
            entries = resp.json() if resp.status_code == 200 else []
        if not isinstance(entries, list) or not entries:
            return None
        # Filter to real page targets:
        #   - type='page' (excludes service_worker, iframe, shared_worker)
        #   - URL is http(s) or about:blank (excludes chrome-error://,
        #     chrome-extension://, devtools://, etc.)
        def _is_real_page(e: Any) -> bool:
            if not isinstance(e, dict) or e.get("type") != "page":
                return False
            if not isinstance(e.get("webSocketDebuggerUrl"), str):
                return False
            url = e.get("url") or ""
            return (
                url.startswith(("http://", "https://"))
                or url == "about:blank"
                or url == ""
            )
        page_entries = [e for e in entries if _is_real_page(e)]
        if not page_entries:
            return None
        # Prefer entries with a non-blank URL (the real navigated tab over
        # the freshly-spawned about:blank one). `or ""` guards against
        # entries with a `null` url field - present on some Chromium
        # workers - which would otherwise hit `None not in (...)` → True
        # and silently rank as a real navigated tab.
        page_entries.sort(key=lambda e: 0 if (e.get("url") or "")
                          not in ("about:blank", "") else 1)
        return page_entries[0]["webSocketDebuggerUrl"]
    except Exception:
        logger.debug("page-target promotion failed for %s", browser_ws_url, exc_info=True)
        return None


async def _get_active_page(agent: Any) -> Any:
    """Best-effort fetch of the active Playwright `Page` from a browser-use
    Agent. Returns None if no compatible binding is exposed.

    browser-use's public surface in 0.12.x does not include a Playwright
    Page - we only attempt this for forward-compatibility / monkey-patched
    test builds. Never raises.
    """
    browser_session = getattr(agent, "browser_session", None)
    if browser_session is None:
        return None
    # Common attribute names probed in order of likelihood.
    for attr in ("page", "playwright_page", "active_page", "_page"):
        page = getattr(browser_session, attr, None)
        if page is not None and hasattr(page, "add_init_script"):
            return page
    # Some forks expose a coroutine getter.
    getter = getattr(browser_session, "get_active_page", None)
    if callable(getter):
        try:
            page = getter()
            if asyncio.iscoroutine(page):
                page = await page
            if page is not None and hasattr(page, "add_init_script"):
                return page
        except Exception:
            return None
    return None


async def _collect_redaction_regions(page: Any) -> list[dict[str, Any]]:
    """Run the same selector list as guardrails.js against the live page
    and return bounding-box dicts (`x`/`y`/`width`/`height`/`reason`).

    Used by the post-capture redactor as a belt-and-braces second layer:
    if a frame was captured during the brief window between page load and
    guardrail install (or if a site's CSS reset wipes the overlay), the
    server-side mask still covers the same fields.

    Never raises - a failed `page.evaluate` is logged and treated as "no
    regions" so the loop survives.
    """
    if page is None or not hasattr(page, "evaluate"):
        return []
    try:
        regions = await page.evaluate(_REDACTION_REGIONS_JS)
    except Exception as e:
        logger.warning("collect_redaction_regions: page.evaluate failed: %s", e)
        return []
    if not isinstance(regions, list):
        return []
    # Normalize: only keep dicts with the four numeric keys we care about.
    out: list[dict[str, Any]] = []
    for r in regions:
        if not isinstance(r, dict):
            continue
        try:
            entry = {
                "x": int(r.get("x", 0)),
                "y": int(r.get("y", 0)),
                "width": int(r.get("width", 0)),
                "height": int(r.get("height", 0)),
            }
        except (TypeError, ValueError):
            continue
        if entry["width"] <= 0 or entry["height"] <= 0:
            continue
        if "reason" in r:
            entry["reason"] = str(r["reason"])
        out.append(entry)
    return out


def _redact_step_screenshot(history_step: Any) -> Optional[bytes]:
    """Run the post-capture redactor against `history_step`, if applicable.

    The history step carries:
      - `screenshot_bytes` (bytes) - raw frame before MinIO upload
      - `redaction_regions` (list[dict]) - bbox hints (typically populated
        upstream by `_collect_redaction_regions(page)` once the runner is
        wired to a live Playwright/CDP page)

    If either is missing, returns None (caller logs nothing - this is the
    expected path until browser-use exposes the page binding).

    Returns the redacted WebP bytes on success, or None when nothing was
    done. Never raises.
    """
    if history_step is None:
        return None
    raw = getattr(history_step, "screenshot_bytes", None)
    regions = getattr(history_step, "redaction_regions", None)
    if not raw or not regions:
        return None
    try:
        return redact_screenshot(raw, regions)
    except Exception as e:
        logger.warning("redact_step_screenshot: redact_screenshot raised: %s", e)
        return None


def _action_dict(action: Any) -> Optional[dict[str, Any]]:
    """Best-effort: turn a browser-use action object into a JSON-safe dict.

    browser-use exposes pydantic-style models or dataclasses; we extract
    `kind` plus a small set of well-known attributes. Unknown shapes are
    serialized as their repr.
    """
    if action is None:
        return None
    try:
        if hasattr(action, "model_dump"):
            return action.model_dump()
        if hasattr(action, "__dict__") and isinstance(action.__dict__, dict):
            return {k: _stringify(v) for k, v in action.__dict__.items()}
        if isinstance(action, dict):
            return {k: _stringify(v) for k, v in action.items()}
    except Exception:
        logger.debug("failed to dict-ify action", exc_info=True)
    return {"repr": repr(action)}


def _stringify(v: Any) -> Any:
    if isinstance(v, (str, int, float, bool)) or v is None:
        return v
    if isinstance(v, (list, tuple)):
        return [_stringify(x) for x in v]
    if isinstance(v, dict):
        return {k: _stringify(x) for k, x in v.items()}
    return repr(v)


def _ctx_id(parameters: dict[str, Any], key: str) -> Optional[str]:
    creds = parameters.get("credentials") or {}
    val = creds.get(key) if isinstance(creds, dict) else None
    return str(val) if val else None


def _build_result(
    session: BrowserAgentSession,
    started: float,
    *,
    stop_reason: str,
    final_result: str = "",
    extracted_data: Optional[dict[str, Any]] = None,
    final_url: str = "",
    pages_visited: Optional[list[str]] = None,
    cost_raw: Optional[dict[str, Any]] = None,
) -> dict[str, Any]:
    # Token capture from browser-use's TokenCost service when available
    # (cost_raw is populated by `_extract_token_usage(agent)` in
    # `_drive_browser_use`). Fall back to the per-step session counters
    # when cost_raw is None (early start_url SSRF reject, ImportError,
    # tests that stub `_drive_browser_use` without exercising browser-use).
    if cost_raw:
        tokens_in = int(cost_raw.get("tokens_in") or 0)
        tokens_out = int(cost_raw.get("tokens_out") or 0)
        cache_read = int(cost_raw.get("cache_read_tokens") or 0)
        cache_creation = int(cost_raw.get("cache_creation_tokens") or 0)
        image_input = int(cost_raw.get("image_input_tokens") or 0)
        llm_calls = int(cost_raw.get("llm_calls") or 0)
        by_model = cost_raw.get("by_model") or {}
    else:
        tokens_in = session.tokens_in
        tokens_out = session.tokens_out
        cache_read = 0
        cache_creation = 0
        image_input = 0
        llm_calls = session.llm_calls
        by_model = {}

    return {
        "final_result": final_result,
        "extracted_data": extracted_data,
        "stop_reason": stop_reason,
        "final_url": final_url or session.last_url,
        "pages_visited": pages_visited or [],
        # Per-step trace. The Redis stream is the live channel; this list
        # is what `BrowserAgentNode.recordObservability` reads to persist
        # `agent_execution_iterations` + `tool_calls` + `messages` rows.
        "steps": list(session.steps_log),
        "screenshots": [],  # screenshot bytes ride on MinIO, not the result blob
        # Token cost block. browser-use 0.12.x's TokenCost.usage_history is
        # the source of truth - empirically validated to populate across all
        # 7 exit paths (COMPLETED / MAX_STEPS / CANCELLED / DOMAIN_BLOCKED /
        # BUDGET_EXHAUSTED / TIMEOUT / LLM_FAILED). Cache-aware billing on
        # the Java side reads `cache_read_tokens` / `cache_creation_tokens`
        # via the matching DTO setters that already exist on
        # `AgentObservabilityRequest`.
        #
        # browser-use NORMALIZES to subset convention across all providers:
        # `prompt_tokens` here ALREADY includes the cached portion. Verified
        # in `browser_use/llm/anthropic/chat.py` which folds Anthropic's
        # native `cache_read_input_tokens` into `prompt_tokens` before
        # surfacing on `ChatInvokeUsage`. The Java pricing layer should
        # treat tokens_in as the inclusive total and apply per-provider
        # cache discounts on the cache_read portion (subtract or multiply
        # depending on the family) - never sum tokens_in + cache_read.
        "cost": {
            "tokens_in": tokens_in,
            "tokens_out": tokens_out,
            "cache_read_tokens": cache_read,
            "cache_creation_tokens": cache_creation,
            "image_input_tokens": image_input,
            "llm_calls": llm_calls,
            "browser_seconds": round(time.time() - started, 2),
            "cost_usd": 0.0,  # priced by the orchestrator-side AgentObservability
            "by_model": by_model,
        },
        "session_id": session.session_id,
        "node_type": "BROWSER_AGENT",
    }


def _extract_token_usage(agent: Any) -> dict[str, Any]:
    """Aggregate token usage from browser-use's TokenCost.usage_history.

    `usage_history` is a public, sync, append-only `list[TokenUsageEntry]`
    populated by `tracked_ainvoke` on every LLM call (primary +
    page_extraction_llm + judge_llm + message_compaction.compaction_llm +
    fallback_llm - all wrapped via `register_llm`). Empirically validated
    to cover all 7 exit paths (COMPLETED / MAX_STEPS / CANCELLED /
    DOMAIN_BLOCKED / BUDGET_EXHAUSTED / TIMEOUT / LLM_FAILED) since the
    list is mutated synchronously before `tracked_ainvoke` returns and is
    never cleared by `agent.run()`.

    Per-entry shape (browser_use 0.12.6):
      entry.model: str                      - e.g. "claude-sonnet-4-6"
      entry.timestamp: datetime
      entry.usage: ChatInvokeUsage
        .prompt_tokens: int                  - INCLUDES cached portion
        .prompt_cached_tokens: int | None    - cache reads (Anthropic/OpenAI/Gemini)
        .prompt_cache_creation_tokens: int | None  - Anthropic-only
        .prompt_image_tokens: int | None     - Google-only
        .completion_tokens: int
        .total_tokens: int

    browser-use normalizes provider responses to a SUBSET convention:
    `prompt_tokens` already includes the `prompt_cached_tokens` portion
    (verified in `browser_use/llm/anthropic/chat.py` which sums Anthropic's
    native disjoint `input_tokens + cache_read_input_tokens` before
    surfacing). The Java pricing layer must therefore treat `tokens_in`
    as the inclusive total - applying cache discount multipliers on the
    `cache_read` portion only - never sum the two. cache_creation is
    Anthropic-only and IS disjoint from prompt_tokens; we sum it
    ourselves here because `UsageSummary` does not aggregate it.

    Returns a dict with snake_case fields the Java `BrowserAgentNode`
    reads via `cost.cache_read_tokens` / `cache_creation_tokens` /
    `image_input_tokens` / `llm_calls` / `by_model`. Always returns a
    populated dict (zeros if no LLM calls were made or the service is
    missing).
    """
    out = {
        "tokens_in": 0,
        "tokens_out": 0,
        "cache_read_tokens": 0,
        "cache_creation_tokens": 0,
        "image_input_tokens": 0,
        "llm_calls": 0,
        "by_model": {},
    }

    tcs = _safe_get(agent, "token_cost_service")
    history = _safe_get(tcs, "usage_history") if tcs is not None else None

    by_model: dict[str, dict[str, int]] = {}
    for entry in (history or []):
        model = str(_safe_get(entry, "model") or "unknown")
        usage = _safe_get(entry, "usage")
        if usage is None:
            continue
        prompt = int(_safe_get(usage, "prompt_tokens") or 0)
        completion = int(_safe_get(usage, "completion_tokens") or 0)
        cached = int(_safe_get(usage, "prompt_cached_tokens") or 0)
        cache_creation = int(_safe_get(usage, "prompt_cache_creation_tokens") or 0)
        image = int(_safe_get(usage, "prompt_image_tokens") or 0)

        out["tokens_in"] += prompt
        out["tokens_out"] += completion
        out["cache_read_tokens"] += cached
        out["cache_creation_tokens"] += cache_creation
        out["image_input_tokens"] += image
        out["llm_calls"] += 1

        stats = by_model.setdefault(model, {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "cache_read_tokens": 0,
            "cache_creation_tokens": 0,
            "image_input_tokens": 0,
            "invocations": 0,
        })
        stats["prompt_tokens"] += prompt
        stats["completion_tokens"] += completion
        stats["cache_read_tokens"] += cached
        stats["cache_creation_tokens"] += cache_creation
        stats["image_input_tokens"] += image
        stats["invocations"] += 1

    out["by_model"] = by_model
    # Cloud-relay path: browser-use's TokenCost does not track the custom BridgeChatClient
    # (and calculate_cost=False disables its accounting), so usage_history is empty and the
    # tokens would be lost. Fold in the usage the BridgeChatClient accumulated from the CE shim
    # (sourced from the cloud relay) so the browser agent records the same tokens/cost a chat
    # agent does - "everything written".
    if out["tokens_in"] == 0 and out["tokens_out"] == 0:
        _apply_bridge_relay_usage(agent, out)
    return out


def _apply_bridge_relay_usage(agent: Any, out: dict[str, Any]) -> None:
    """Populate ``out`` from the BridgeChatClient's own usage accumulators (cloud-relay path).

    On ``provider_kind='bridge'`` the browser agent's LLM is a ``BridgeChatClient`` whose
    per-call usage (returned by the CE shim, sourced from the cloud relay) browser-use does not
    track. The client accumulates ``relay_prompt_tokens`` / ``relay_completion_tokens`` /
    ``relay_calls`` itself; we read them here so token/cost observability is complete on the
    relay path.
    """
    llm = _safe_get(agent, "llm")
    if llm is None:
        return
    prompt = int(_safe_get(llm, "relay_prompt_tokens") or 0)
    completion = int(_safe_get(llm, "relay_completion_tokens") or 0)
    calls = int(_safe_get(llm, "relay_calls") or 0)
    if prompt == 0 and completion == 0:
        return
    out["tokens_in"] = prompt
    out["tokens_out"] = completion
    out["llm_calls"] = calls or out["llm_calls"]
    # The client's model is already clean (BrowserAgentModule keeps it unqualified; the provider
    # travels in the X-LLM-Provider header), so the Java pricing layer resolves a rate on it.
    model = str(_safe_get(llm, "model") or "unknown")
    out["by_model"] = {
        model: {
            "prompt_tokens": prompt,
            "completion_tokens": completion,
            "cache_read_tokens": 0,
            "cache_creation_tokens": 0,
            "image_input_tokens": 0,
            "invocations": calls or 1,
        }
    }


async def _extract_last_history_error(history: Any) -> str | None:
    """Surface the last per-step error from browser-use's history.

    Version-tolerant: 0.12.x exposes `errors()` returning a list aligned to
    steps; older builds may not. Returns None when extraction fails or every
    entry is None - the caller falls back to a generic descriptor.

    Typed exception subclasses sometimes return an empty `str(e)` (e.g. plain
    `ConnectionError()`); we fall back to `repr(e)` so the agent always sees
    something actionable instead of an empty Last-error string.
    """
    try:
        if not hasattr(history, "errors"):
            return None
        errs = history.errors()
        if asyncio.iscoroutine(errs):
            errs = await errs
        if not isinstance(errs, list) or not errs:
            return None
        for e in reversed(errs):
            if not e:
                continue
            rendered = str(e)
            if not rendered:
                rendered = repr(e)
            return rendered
        return None
    except Exception:
        logger.debug("history.errors() extraction failed", exc_info=True)
        return None


def _classify_stop_reason(
    *,
    is_done: bool,
    actual_steps: int,
    max_steps: int,
    final_result: str,
    last_error: str | None,
) -> tuple[str, str]:
    """Return (stop_reason, final_result_str) from a CLEAN agent.run() return.

    browser-use's `agent.run(max_steps=N)` reaches the caller via THREE paths
    (any exception path is intercepted upstream and never reaches here):
      1. is_done=True                                          → COMPLETED
      2. is_done=False AND actual_steps >= max_steps           → MAX_STEPS
      3. is_done=False AND actual_steps  < max_steps           → browser-use
         bailed out internally (default: 5 consecutive failures → stops).
         The historical bug was lumping case (3) into MAX_STEPS, which
         surfaced "reached max_steps=50" to the LLM agent even when only 6
         actual steps ran. The real cause (e.g. Anthropic 400
         "credit_balance too low", domain-blocked, etc.) was buried in the
         runner logs only - the agent then dutifully bumped max_steps and
         retried, producing the same false signal.

    Defensive: max_steps==0 (or negative) would otherwise satisfy `>=` and
    mask any real failure as MAX_STEPS. The runner clamps via min(...) at
    the entry point so this should never happen in production, but the
    guard costs nothing and keeps the classifier honest.
    """
    if is_done:
        return "COMPLETED", final_result
    if max_steps > 0 and actual_steps >= max_steps:
        return "MAX_STEPS", final_result or f"reached max_steps={max_steps}"
    if last_error:
        return "LLM_FAILED", (
            f"Browser agent stopped early after {actual_steps}/{max_steps} steps "
            f"due to repeated errors. Last error: {last_error}"
        )
    if final_result:
        return "LLM_FAILED", final_result
    return "LLM_FAILED", (
        f"Browser agent stopped early after {actual_steps}/{max_steps} steps "
        f"(browser-use internal failure cascade - typically 5 consecutive LLM/browser "
        f"errors). Check the runner logs for the underlying cause."
    )


def _error_result(message: str) -> dict[str, Any]:
    return {
        "final_result": message,
        "extracted_data": None,
        "stop_reason": "LLM_FAILED",
        "final_url": "",
        "pages_visited": [],
        "steps": [],
        "screenshots": [],
        "cost": {
            "tokens_in": 0,
            "tokens_out": 0,
            "cache_read_tokens": 0,
            "cache_creation_tokens": 0,
            "image_input_tokens": 0,
            "llm_calls": 0,
            "browser_seconds": 0.0,
            "cost_usd": 0.0,
            "by_model": {},
        },
        "session_id": "",
        "node_type": "BROWSER_AGENT",
    }
