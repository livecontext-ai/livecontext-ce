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

import datetime as _dt
import logging
from typing import Optional

import redis.asyncio as aioredis

from app.config import browser_agent_budget

logger = logging.getLogger(__name__)


CONCURRENT_KEY_FMT = "agent:browser:user:{user_id}:concurrent"
STEPS_KEY_FMT = "agent:browser:user:{user_id}:steps:{ymd}"

CONCURRENT_TTL_S = 3600     # 1h orphan cleanup if runner crashes mid-flight.
STEPS_TTL_S = 86400         # 24h - counter naturally rolls over per UTC day.


class BudgetExhaustedError(Exception):
    """Raised when a per-user budget check rejects the request.

    Carries a `reason` (machine-readable) and `message` (human-readable);
    the runner maps both into the final result via stop_reason='BUDGET_EXHAUSTED'.
    """

    REASON_CONCURRENT = "concurrent_session_limit"
    REASON_DAILY_STEPS = "daily_steps_limit"

    def __init__(self, reason: str, message: str):
        super().__init__(message)
        self.reason = reason
        self.message = message


def _today_utc() -> str:
    """UTC YYYY-MM-DD - same date as a Redis TTL of 86400s rolling window."""
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%d")


def concurrent_key(user_id: str) -> str:
    return CONCURRENT_KEY_FMT.format(user_id=user_id)


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
