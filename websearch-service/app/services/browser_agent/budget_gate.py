"""Per-user budget gate for browser-agent sessions (Redis-backed).

Two counters, both keyed on the user_id flowing from the orchestrator
(`parameters['credentials']['__userId__']`):

  agent:browser:user:{uid}:concurrent       LIST  TTL=3600s
      LPUSH session_id on entry, LREM on exit. LLEN > limit ⇒ BUDGET_EXHAUSTED.
      A LIST (not a counter) so a crashed runner that never got to LREM
      is auto-cleaned by TTL - vs an INCR/DECR pair that would leak.

  agent:browser:user:{uid}:steps:{YYYY-MM-DD}   STRING  TTL=86400s
      INCR on every browser-use step. Once the value exceeds
      `daily_steps_limit`, the runner halts mid-loop with BUDGET_EXHAUSTED.

Important: this guard is the runner-side check. The Java `BrowserAgentModule`
ALSO checks the concurrent counter before submitting (saving a FastAPI
round-trip). The two paths must use IDENTICAL key formats - see
`backend/.../tools/websearch/BrowserAgentBudgetGuard.java`.

Note re: bridge billing - see CLAUDE.md "Bridges - never short-circuit
billing". Bridge sessions ARE counted toward the per-user step quota
exactly like direct-API sessions: the user opted into a billed model
(internal credits via the bridge), and the steps cap is a *resource*
gate (Chromium time), not a *price* gate. Don't add a bridge bypass.
"""

from __future__ import annotations

import asyncio
import datetime as _dt
import logging
import time
from typing import Optional

import redis.asyncio as aioredis

from app.config import browser_agent_budget

logger = logging.getLogger(__name__)


CONCURRENT_KEY_FMT = "agent:browser:user:{user_id}:concurrent"
STEPS_KEY_FMT = "agent:browser:user:{user_id}:steps:{ymd}"
# FIFO wait queue for the per-user concurrent slot (workflow split/fork
# fires N agent_browse branches at once - they must QUEUE, not fail).
WAITQ_KEY_FMT = "agent:browser:user:{user_id}:waitq"
# Per-waiter heartbeat: refreshed each poll while waiting; a head whose
# heartbeat expired crashed mid-wait and is skipped by the next waiter.
WAITQ_HB_KEY_FMT = "agent:browser:waitq:hb:{ticket}"

CONCURRENT_TTL_S = 3600     # 1h orphan cleanup if runner crashes mid-flight.
STEPS_TTL_S = 86400         # 24h - counter naturally rolls over per UTC day.
WAITQ_TTL_S = 3600          # queue key orphan cleanup.
WAITQ_HB_TTL_S = 15         # heartbeat expiry - dead-waiter detection window.
WAITQ_POLL_S = 1.0          # slot re-check cadence while queued.


class BudgetExhaustedError(Exception):
    """Raised when a per-user budget check rejects the request.

    Carries a `reason` (machine-readable) and `message` (human-readable);
    the runner maps both into the final result via stop_reason='BUDGET_EXHAUSTED'.
    """

    REASON_CONCURRENT = "concurrent_session_limit"
    REASON_DAILY_STEPS = "daily_steps_limit"
    REASON_QUEUE_TIMEOUT = "concurrent_queue_timeout"
    REASON_HOST_CAPACITY = "host_capacity_timeout"

    def __init__(self, reason: str, message: str):
        super().__init__(message)
        self.reason = reason
        self.message = message


def _today_utc() -> str:
    """UTC YYYY-MM-DD - same date as a Redis TTL of 86400s rolling window."""
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%d")


def concurrent_key(user_id: str) -> str:
    return CONCURRENT_KEY_FMT.format(user_id=user_id)


def waitq_key(user_id: str) -> str:
    return WAITQ_KEY_FMT.format(user_id=user_id)


def waitq_hb_key(ticket: str) -> str:
    return WAITQ_HB_KEY_FMT.format(ticket=ticket)


def steps_key(user_id: str, ymd: Optional[str] = None) -> str:
    return STEPS_KEY_FMT.format(user_id=user_id, ymd=ymd or _today_utc())


async def acquire_concurrent_slot(
    redis: aioredis.Redis,
    user_id: str,
    session_id: str,
) -> None:
    """Atomically register the new session in the per-user concurrent LIST.

    Reads the current LIST length, LPUSHes the session_id, then re-reads
    the length to detect over-the-limit. If the post-push length is
    greater than the configured limit, we LREM ourselves and raise.

    Caveat: this is "racy-but-bounded" - two parallel acquires can both
    see len==limit-1, both LPUSH (so len momentarily becomes limit+1),
    both LREM their own slot, and both raise. That's actually the
    desired safety: at most one session survives, the other gets a clean
    rejection. We accept the (rare) double-reject vs the alternative of
    a Lua script for marginal gain. Java-side has the same property.

    Raises BudgetExhaustedError on rejection. On success, the caller must
    eventually call `release_concurrent_slot(...)`.
    """
    if not user_id:
        # No user context (local dev / system flow) - skip the gate. The
        # orchestrator never sends `agent_browse` without a tenantId in
        # production, so this branch only fires in tests + diagnostics.
        return

    limit = browser_agent_budget.per_user_concurrent_limit
    if limit <= 0:
        return  # disabled

    key = concurrent_key(user_id)
    await redis.lpush(key, session_id)
    await redis.expire(key, CONCURRENT_TTL_S)
    current = await redis.llen(key)
    if current > limit:
        # Roll back this session's slot before raising so we don't leak it.
        await redis.lrem(key, 1, session_id)
        logger.info(
            "browser_agent budget gate: concurrent session limit hit "
            "user_id=%s limit=%d post_push_len=%d",
            user_id, limit, current,
        )
        raise BudgetExhaustedError(
            BudgetExhaustedError.REASON_CONCURRENT,
            "user already has an active browser-agent session",
        )


async def acquire_concurrent_slot_queued(
    redis: aioredis.Redis,
    user_id: str,
    session_id: str,
    max_wait_s: float,
) -> None:
    """Acquire the per-user concurrent slot, WAITING in FIFO order when full.

    This is what lets a workflow split/fork fire N browser branches for the
    same user without N-1 of them failing instantly with BUDGET_EXHAUSTED:
    the extra branches queue on ``agent:browser:user:{uid}:waitq`` and run
    one after another as slots free up (Redis-backed, so the order holds
    across orchestrator pods and websearch restarts).

    Mechanics:
      - Fast path: immediate :func:`acquire_concurrent_slot` (no queue key
        touched when a slot is free).
      - Queued path: RPUSH our session_id as a FIFO ticket; only the HEAD
        ticket attempts the acquire, everyone else sleeps ``WAITQ_POLL_S``.
        Each waiter refreshes a short-TTL heartbeat key; a head whose
        heartbeat expired (crashed runner) is removed by the next waiter so
        the queue can never wedge behind a ghost.
      - Bounded: gives up after ``max_wait_s`` with
        ``REASON_QUEUE_TIMEOUT`` (surfaced as stop_reason=BUDGET_EXHAUSTED,
        same contract as the immediate rejection).

    On success the caller must eventually call
    :func:`release_concurrent_slot`, exactly like the immediate variant.
    """
    if not user_id:
        return
    limit = browser_agent_budget.per_user_concurrent_limit
    if limit <= 0:
        return  # disabled
    # Fast path ONLY when nobody is already queued - otherwise a newcomer
    # arriving in the window after a release would jump the line and starve
    # the head (the docstring promises FIFO; keep it honest).
    queued_ahead = 0
    try:
        queued_ahead = int(await redis.llen(waitq_key(user_id)) or 0)
    except Exception:
        logger.debug("waitq llen failed - assuming empty", exc_info=True)
    if queued_ahead == 0:
        try:
            await acquire_concurrent_slot(redis, user_id, session_id)
            return
        except BudgetExhaustedError:
            if max_wait_s <= 0:
                raise
    elif max_wait_s <= 0:
        # Queue occupied and no wait budget: same contract as an immediate
        # over-limit rejection.
        raise BudgetExhaustedError(
            BudgetExhaustedError.REASON_CONCURRENT,
            "user already has an active browser-agent session",
        )

    qkey = waitq_key(user_id)
    hbkey = waitq_hb_key(session_id)
    await redis.rpush(qkey, session_id)
    await redis.expire(qkey, WAITQ_TTL_S)
    deadline = time.monotonic() + max_wait_s
    logger.info(
        "browser_agent budget gate: queueing for concurrent slot "
        "user_id=%s session=%s max_wait_s=%.0f", user_id, session_id, max_wait_s,
    )
    try:
        while True:
            await redis.set(hbkey, "1", ex=WAITQ_HB_TTL_S)
            head = await redis.lindex(qkey, 0)
            if isinstance(head, bytes):
                head = head.decode("utf-8", errors="ignore")
            if head is None or head == session_id:
                # We're first in line (or the queue key was lost to TTL -
                # behave as head rather than wedging). Try the slot.
                try:
                    await acquire_concurrent_slot(redis, user_id, session_id)
                    logger.info(
                        "browser_agent budget gate: queued slot ACQUIRED "
                        "user_id=%s session=%s", user_id, session_id,
                    )
                    return
                except BudgetExhaustedError:
                    pass  # slot still full - keep waiting at head
            else:
                # Not head: skip a dead head (crashed waiter whose heartbeat
                # expired) so the queue can't wedge behind a ghost ticket.
                head_hb = await redis.get(waitq_hb_key(head))
                if head_hb is None:
                    removed = await redis.lrem(qkey, 1, head)
                    if removed:
                        logger.warning(
                            "browser_agent budget gate: removed dead waitq head "
                            "user_id=%s dead_ticket=%s", user_id, head,
                        )
                    continue  # re-evaluate the new head immediately
            if time.monotonic() >= deadline:
                logger.info(
                    "browser_agent budget gate: queue wait timed out "
                    "user_id=%s session=%s after %.0fs", user_id, session_id, max_wait_s,
                )
                raise BudgetExhaustedError(
                    BudgetExhaustedError.REASON_QUEUE_TIMEOUT,
                    "timed out waiting for a free browser-agent slot "
                    f"(waited {int(max_wait_s)}s; another session is still running)",
                )
            await asyncio.sleep(WAITQ_POLL_S)
    finally:
        # Leave the queue on EVERY exit (acquired, timeout, crash) and drop
        # the heartbeat so successors never wait on our ghost.
        try:
            await redis.lrem(qkey, 1, session_id)
            await redis.delete(hbkey)
        except Exception:  # pragma: no cover - best effort
            logger.debug("waitq cleanup failed", exc_info=True)


async def release_concurrent_slot(
    redis: aioredis.Redis,
    user_id: str,
    session_id: str,
) -> None:
    """Drop the session_id from the per-user concurrent LIST.

    Best-effort: a failed LREM is logged but never raised - the TTL on
    the LIST will eventually clean orphans. Called from the runner's
    `finally` block so it runs regardless of stop_reason.
    """
    if not user_id:
        return
    try:
        await redis.lrem(concurrent_key(user_id), 1, session_id)
    except Exception:  # pragma: no cover - best effort
        logger.warning(
            "browser_agent budget gate: failed to LREM concurrent slot "
            "user_id=%s session_id=%s", user_id, session_id, exc_info=True,
        )


async def increment_daily_steps(
    redis: aioredis.Redis,
    user_id: str,
) -> None:
    """INCR the per-user daily-steps counter; raise if it crosses the limit.

    Called BEFORE each browser-use step. The counter expires after 24h so
    it auto-rolls per UTC day even without explicit reset.

    Raises BudgetExhaustedError when the post-incr value exceeds the
    configured cap. The runner translates this into a final result with
    `stop_reason='BUDGET_EXHAUSTED'` and the partial trace already
    accumulated.
    """
    if not user_id:
        return
    limit = browser_agent_budget.per_user_daily_steps_limit
    if limit <= 0:
        return  # disabled

    key = steps_key(user_id)
    new_val = await redis.incr(key)
    # Set the TTL on the first INCR (when new_val == 1). Doing it every
    # time is harmless but wastes a round-trip; setting it conditionally
    # also covers the case where the counter was created by the Java-side
    # guard (which doesn't INCR - it only reads the GET value).
    if new_val == 1:
        await redis.expire(key, STEPS_TTL_S)
    if new_val > limit:
        logger.info(
            "browser_agent budget gate: daily steps limit hit "
            "user_id=%s limit=%d post_incr=%d",
            user_id, limit, new_val,
        )
        raise BudgetExhaustedError(
            BudgetExhaustedError.REASON_DAILY_STEPS,
            "daily steps quota exhausted",
        )
