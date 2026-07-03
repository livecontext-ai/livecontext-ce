"""Redis I/O helpers for browser-agent step streaming and control-plane.

Channels:
  agent:run:{run_id}:node:{node_id}:steps   - Stream (XADD step events)
  agent:run:{run_id}:node:{node_id}:control - LIST (LPUSH from orchestrator,
                                              BLPOP/LPOP by runner)
  agent:result:{job_id}                     - LIST (LPUSH final result by
                                              runner, BLPOP'd by Java)

All keys auto-expire (1h for the stream, 10min for the control LIST).
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Optional

import redis.asyncio as aioredis

logger = logging.getLogger(__name__)

STEPS_KEY_FMT = "agent:run:{run_id}:node:{node_id}:steps"
CONTROL_KEY_FMT = "agent:run:{run_id}:node:{node_id}:control"
RESULT_KEY_FMT = "agent:browser:result:{job_id}"  # distinct from agent:result: (AgentQueueWorkerService)
FINAL_SHOT_KEY_FMT = "agent:browser:final_shot:{run_id}:{node_id}"

STEPS_TTL_S = 3600           # 1h after last write - frontend may re-stream
CONTROL_TTL_S = 600          # 10min - orphan cleanup if runner dies mid-flight
RESULT_TTL_S = 600           # 10min - Java BLPOPs immediately, TTL is paranoia
FINAL_SHOT_TTL_S = 600       # 10min - WS-independent fallback the frontend polls
STEPS_MAXLEN = 200           # cap stream length to avoid Redis bloat


def steps_key(run_id: str, node_id: str) -> str:
    return STEPS_KEY_FMT.format(run_id=run_id, node_id=node_id)


def control_key(run_id: str, node_id: str) -> str:
    return CONTROL_KEY_FMT.format(run_id=run_id, node_id=node_id)


def result_key(job_id: str) -> str:
    return RESULT_KEY_FMT.format(job_id=job_id)


def final_shot_key(run_id: str, node_id: str) -> str:
    return FINAL_SHOT_KEY_FMT.format(run_id=run_id, node_id=node_id)


async def store_final_screenshot(
    redis: aioredis.Redis,
    run_id: str,
    node_id: str,
    data_base64: str,
    mime: str = "image/jpeg",
    ttl_s: int = FINAL_SHOT_TTL_S,
) -> None:
    """Persist the final-page screenshot (base64) for the frontend's
    WS-independent live-view fallback.

    Stored as a small JSON blob ({mime, data_base64}) under
    `agent:browser:final_shot:{run_id}:{node_id}` with a short TTL. Keyed
    by (run_id, node_id) - NOT session_id - so the orchestrator's
    run-ownership-gated `final-screenshot` endpoint can serve it without
    trusting a client-supplied session id.
    """
    key = final_shot_key(run_id, node_id)
    await redis.set(key, json.dumps({"mime": mime, "data_base64": data_base64}), ex=ttl_s)


async def get_final_screenshot(
    redis: aioredis.Redis,
    run_id: str,
    node_id: str,
) -> Optional[dict[str, Any]]:
    """Read the stored final-page screenshot, or None if absent/expired.

    Tolerates both `decode_responses=True` (str) and raw-bytes Redis
    clients so it works regardless of how `get_redis()` is configured.
    """
    raw = await redis.get(final_shot_key(run_id, node_id))
    if raw is None:
        return None
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8", errors="ignore")
    try:
        obj = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as e:
        logger.warning("final_shot payload not JSON, ignored: %s", e)
        return None
    return obj if isinstance(obj, dict) else None


async def xadd_step(
    redis: aioredis.Redis,
    run_id: str,
    node_id: str,
    payload: dict[str, Any],
) -> str:
    """Append a step event to the per-session stream.

    Returns the stream entry ID (Redis-assigned, e.g. "1714000000000-0").
    """
    key = steps_key(run_id, node_id)
    # XADD requires flat string→string fields. We serialize the dict as a
    # single 'json' field; readers do json.loads(entry['json']).
    entry_id = await redis.xadd(
        key,
        {"json": json.dumps(payload)},
        maxlen=STEPS_MAXLEN,
        approximate=True,
    )
    await redis.expire(key, STEPS_TTL_S)
    return entry_id


async def lpop_control(
    redis: aioredis.Redis,
    run_id: str,
    node_id: str,
) -> Optional[dict[str, Any]]:
    """Non-blocking pop of the next control command, if any.

    Returns the parsed payload or None if no command is pending. Called
    by the runner before each browser-use step.
    """
    key = control_key(run_id, node_id)
    raw = await redis.lpop(key)
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError) as e:
        logger.warning("control payload not JSON, ignored: %s (raw=%r)", e, raw)
        return None


async def blpop_control(
    redis: aioredis.Redis,
    run_id: str,
    node_id: str,
    timeout_s: int = 5,
) -> Optional[dict[str, Any]]:
    """Blocking pop of the next control command (used while paused).

    Returns the parsed payload or None on timeout.
    """
    key = control_key(run_id, node_id)
    res = await redis.blpop(key, timeout=timeout_s)
    if res is None:
        return None
    _, raw = res
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError) as e:
        logger.warning("control payload not JSON, ignored: %s (raw=%r)", e, raw)
        return None


async def requeue_control(
    redis: aioredis.Redis,
    run_id: str,
    node_id: str,
    cmd: dict[str, Any],
) -> None:
    """Put a popped control command BACK at the TAIL of the queue.

    Used when a consumer pops a command tagged with a DIFFERENT session_id
    (two sessions can share the same (run_id, node_id) control key: loop
    iterations, re-triggers, a previous session's post-completion hold).
    Tail placement lets the rightful owner's own BLPOP pick it up without
    this consumer spinning on it at the head. Best-effort: a failed requeue
    is logged; PAUSE/RESUME are state flips, so a rare loss is recoverable
    by the user re-clicking.
    """
    key = control_key(run_id, node_id)
    try:
        await redis.rpush(key, json.dumps(cmd))
        await redis.expire(key, 600)
    except Exception:
        logger.warning("control requeue failed (run=%s node=%s cmd=%r)",
                       run_id, node_id, cmd.get("cmd"), exc_info=True)


async def push_result(
    redis: aioredis.Redis,
    job_id: str,
    result: dict[str, Any],
) -> None:
    """Push the final job result to the per-job LIST (Java BLPOPs this)."""
    key = result_key(job_id)
    await redis.lpush(key, json.dumps(result))
    await redis.expire(key, RESULT_TTL_S)


def make_step_event(
    *,
    type_: str,
    step_index: int,
    url: str = "",
    eval_text: str = "",
    memory: str = "",
    next_goal: str = "",
    action: Optional[dict[str, Any]] = None,
    screenshot_key: Optional[str] = None,
    tokens_in: int = 0,
    tokens_out: int = 0,
    cost_usd: float = 0.0,
    extra: Optional[dict[str, Any]] = None,
) -> dict[str, Any]:
    """Construct a step event payload (used by both runner and tests).

    `type_` ∈ {"step", "final", "error", "takeover_request", "cdp_ready"}.

    The {@code "cdp_ready"} type is the live-view bootstrap event: emitted
    once per session, on the first browser-use step, as soon as the
    runner has captured the upstream CDP endpoint URL. Carries
    {@code session_id} and {@code cdp_ws_url} in {@code extra} so the
    Java orchestrator's stream consumer can mint a CDP JWT token and
    fan-out an early "agent_browse session is live" event to the chat
    frontend BEFORE the blocking tool call returns.
    """
    payload: dict[str, Any] = {
        "type": type_,
        "step_index": step_index,
        "ts": int(time.time() * 1000),
    }
    if url:
        payload["url"] = url
    if eval_text:
        payload["eval"] = eval_text
    if memory:
        payload["memory"] = memory
    if next_goal:
        payload["next_goal"] = next_goal
    if action is not None:
        payload["action"] = action
    if screenshot_key:
        payload["screenshot_key"] = screenshot_key
    if tokens_in or tokens_out:
        payload["tokens"] = {"in": tokens_in, "out": tokens_out}
    if cost_usd:
        payload["cost_usd"] = round(cost_usd, 6)
    if extra:
        payload.update(extra)
    return payload
