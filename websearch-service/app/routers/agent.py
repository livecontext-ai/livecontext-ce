"""Browser-agent control-plane endpoints (sync, fast).

The orchestrator's BrowserAgentModule hits these for the
`browse_status / browse_intervene / browse_abort / browse_screenshot`
sub-actions. Unlike `agent_browse` (async, Redis Streams), these are
direct request/response - they read or mutate the in-process session
state held by `app/services/browser_agent/session_state.py`.

Auth: gateway-secret middleware (same as `/jobs/submit`).
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from app.services.browser_agent import (
    get_session_state,
    list_active_sessions,
)
from app.services.browser_agent.redis_io import control_key
from app.services.redis_client import get_redis

logger = logging.getLogger(__name__)
router = APIRouter()


@router.get("/agent/sessions")
async def list_sessions():
    """List active browser-agent sessions on this worker."""
    sessions = list_active_sessions()
    return {
        "active": [
            {
                "session_id": s.session_id,
                "job_id": s.job_id,
                "run_id": s.run_id,
                "node_id": s.node_id,
                "step_index": s.step_index,
                "last_url": s.last_url,
                "paused": s.paused,
                "tokens_in": s.tokens_in,
                "tokens_out": s.tokens_out,
                "llm_calls": s.llm_calls,
            }
            for s in sessions
        ]
    }


@router.post("/agent/sessions/{session_id}/status")
@router.get("/agent/sessions/{session_id}/status")
async def session_status(session_id: str):
    """Return live status of a session - used by `browse_status`.

    Intentionally lightweight: no screenshots, no step history. The full
    trace is on the Redis stream; the orchestrator-side
    BrowserAgentModule asks ONLY for a quick health check here.
    """
    s = get_session_state(session_id)
    if s is None:
        raise HTTPException(404, f"session not found: {session_id}")
    return {
        "session_id": s.session_id,
        "job_id": s.job_id,
        "run_id": s.run_id,
        "node_id": s.node_id,
        "step_index": s.step_index,
        "last_url": s.last_url,
        "last_action": s.last_action,
        "last_eval": s.last_eval,
        "paused": s.paused,
        "aborted": s.aborted,
        "tokens_in": s.tokens_in,
        "tokens_out": s.tokens_out,
        "llm_calls": s.llm_calls,
    }


@router.get("/agent/runs/{run_id}/nodes/{node_id}/final-screenshot")
async def final_screenshot(run_id: str, node_id: str):
    """Return the final-page screenshot captured at session teardown, if any.

    The runner stores it (base64 JPEG) in Redis keyed by (run_id, node_id)
    when the browser-agent session ends - a WS-independent fallback so the
    frontend live panel can show the last page even when the live CDP
    screencast never connected. Keyed by (run_id, node_id), NOT session_id,
    so the orchestrator can serve it behind its run-ownership gate without
    trusting a client-supplied session id.

    Returns 404 until/unless a screenshot was captured: capture is
    best-effort and some runs (early LLM failure, no page ever loaded,
    feature disabled) produce none - the frontend keeps polling, then
    falls back to its "session ended" text.
    """
    from app.services.browser_agent.redis_io import get_final_screenshot
    redis = get_redis()
    shot = await get_final_screenshot(redis, run_id, node_id)
    if not shot or not shot.get("data_base64"):
        raise HTTPException(404, "no final screenshot for this run/node")
    return {
        "run_id": run_id,
        "node_id": node_id,
        "mime": shot.get("mime") or "image/jpeg",
        "data_base64": shot["data_base64"],
    }


@router.post("/agent/sessions/{session_id}/intervene")
async def session_intervene(session_id: str, payload: dict):
    """Inject a hint into the runner's memory before its next step.

    Accepts {"hint": "..."}. The runner reads this on its next pre-step
    control drain and prepends it to the next browser-use step's memory.
    """
    s = get_session_state(session_id)
    if s is None:
        raise HTTPException(404, f"session not found: {session_id}")

    hint = (payload or {}).get("hint")
    if not hint:
        raise HTTPException(400, "missing 'hint' in payload")

    redis = get_redis()
    import json
    key = control_key(s.run_id, s.node_id)
    await redis.lpush(key, json.dumps({"cmd": "INTERVENE", "hint": hint}))
    await redis.expire(key, 600)
    return {"accepted": True, "session_id": session_id}


@router.post("/agent/sessions/{session_id}/abort")
async def session_abort(session_id: str):
    """Request abort. The runner picks it up at the next step boundary."""
    s = get_session_state(session_id)
    if s is None:
        raise HTTPException(404, f"session not found: {session_id}")

    redis = get_redis()
    import json
    key = control_key(s.run_id, s.node_id)
    await redis.lpush(key, json.dumps({"cmd": "ABORT"}))
    await redis.expire(key, 600)
    return {"accepted": True, "session_id": session_id}


@router.post("/agent/sessions/{session_id}/resume")
async def session_resume(session_id: str):
    """Lift a PAUSE - release the agent's step loop so it continues.

    Mirror of ``/abort``: LPUSHes ``{cmd: "RESUME"}`` onto the same
    per-session control queue. The runner's ``on_step_start`` callback
    is currently blocked inside ``while session.paused:`` waiting on
    ``BLPOP``; the new entry resolves that BLPOP and the loop's
    ``_apply_control`` flips ``session.paused = False`` so the next
    iteration falls through and the agent runs its next step.

    This is the runner-level companion to the orchestrator-side
    ``/api/internal/browser-agent/runs/{rid}/nodes/{nid}/takeover-resume``
    endpoint, which resolves the workflow-level
    ``BROWSER_USER_TAKEOVER`` signal. Calling either alone leaves the
    other half paused; for a full takeover-resume the orchestrator
    chains both calls.
    """
    s = get_session_state(session_id)
    if s is None:
        raise HTTPException(404, f"session not found: {session_id}")

    redis = get_redis()
    import json
    key = control_key(s.run_id, s.node_id)
    await redis.lpush(key, json.dumps({"cmd": "RESUME"}))
    await redis.expire(key, 600)
    return {"accepted": True, "session_id": session_id}


@router.post("/agent/sessions/{session_id}/screenshot")
async def session_screenshot(session_id: str, payload: dict | None = None):
    """Return a single screenshot for the requested step (or last step).

    The full pipeline (CDP capture → MinIO upload → signed URL) lands in
    PR #7 with the Docker isolation. For now this returns the most recent
    screenshot_key the runner has stamped on the session (which the
    Python runner XADDs into the step events but doesn't yet cache locally).

    Until the pipeline is complete, the response carries the session's
    last_url + step_index plus a `pending` flag so the LLM agent knows
    NOT to retry - the user resolves it from the UI.
    """
    s = get_session_state(session_id)
    if s is None:
        raise HTTPException(404, f"session not found: {session_id}")

    return {
        "session_id": session_id,
        "step_index": s.step_index,
        "url": s.last_url,
        "screenshot_key": None,
        "signed_url": None,
        "pending": True,
        "reason": (
            "Screenshot retrieval not yet wired in this build. The runner "
            "XADDs screenshot_key per step to the live trace stream; the "
            "MinIO signed-URL path lands with the Docker subprocess wrapper. "
            "Read screenshot_key from the per-step stream events instead."
        ),
    }
