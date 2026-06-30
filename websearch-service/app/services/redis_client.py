"""Async Redis client for job result delivery.

Results are pushed to per-job Redis lists via LPUSH. The Java orchestrator
consumes them with BLPOP, which blocks the thread at near-zero CPU cost
until the result arrives.
"""

import logging

import redis.asyncio as aioredis

from app.config import settings

logger = logging.getLogger(__name__)

_redis: aioredis.Redis | None = None


async def init_redis():
    global _redis
    _redis = aioredis.from_url(settings.redis_url, decode_responses=True)
    # Verify connectivity
    await _redis.ping()
    logger.info("Redis connected: %s", settings.redis_url)


async def close_redis():
    if _redis:
        await _redis.aclose()
        logger.info("Redis connection closed")


def get_redis() -> aioredis.Redis:
    if _redis is None:
        raise RuntimeError("Redis not initialized. Call init_redis() first.")
    return _redis


async def publish_job_result(job_id: str, result_json: str):
    """Push job result to Redis list (consumed by Java BLPOP)."""
    r = get_redis()
    key = f"fetch:result:{job_id}"
    await r.lpush(key, result_json)
    await r.expire(key, settings.job_result_ttl_s)


