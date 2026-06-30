"""Async job submission endpoint for the Java orchestrator.

The Java side POSTs to /jobs/submit (~50ms), gets back a job_id, then
BLPOP on Redis key ``fetch:result:{job_id}`` until the result is ready.
This frees the HTTP connection immediately and lets the Java thread
block on Redis at near-zero CPU cost.
"""

import asyncio
import json
import uuid
import logging

from fastapi import APIRouter, HTTPException

from app.config import settings
from app.models.job import JobSubmitRequest, JobSubmitResponse
from app.models.crawl import CrawlRequest, CrawlOptions
from app.models.search import SearchRequest
from app.services import crawl_client, searxng_client, pipeline
from app.services.browser_agent import run_browser_agent_session
from app.services.redis_client import get_redis, publish_job_result
from app.services.crawl_client import _track_task

logger = logging.getLogger(__name__)
router = APIRouter()

# ---------------------------------------------------------------------------
# Admission control: limit total active jobs across this worker
# ---------------------------------------------------------------------------
_active_jobs = 0
_active_jobs_lock = asyncio.Lock()


async def _increment_active() -> bool:
    global _active_jobs
    async with _active_jobs_lock:
        if _active_jobs >= settings.max_active_jobs:
            return False
        _active_jobs += 1
        return True


async def _decrement_active():
    global _active_jobs
    async with _active_jobs_lock:
        _active_jobs -= 1


def get_active_jobs() -> int:
    return _active_jobs


# ---------------------------------------------------------------------------
# Job submission endpoint
# ---------------------------------------------------------------------------
@router.post("/jobs/submit", response_model=JobSubmitResponse)
async def submit_job(request: JobSubmitRequest):
    if not await _increment_active():
        raise HTTPException(429, "Too many active jobs. Retry later.")

    job_id = str(uuid.uuid4())
    _track_task(_execute_job(job_id, request))
    return JobSubmitResponse(job_id=job_id)


async def _execute_job(job_id: str, request: JobSubmitRequest):
    """Execute job in background, push result to Redis."""
    # Make job_id reachable to dispatch handlers that push to a per-job
    # key themselves (e.g. agent_browse → agent:result:{job_id}).
    request.parameters["__job_id__"] = job_id
    try:
        result = await _dispatch(request)
        if hasattr(result, "model_dump_json"):
            result_json = result.model_dump_json()
        else:
            result_json = json.dumps(result)
        await publish_job_result(job_id, result_json)
    except Exception as e:
        logger.error("Job %s failed: %s", job_id, e, exc_info=True)
        await publish_job_result(job_id, json.dumps({"error": str(e)}))
    finally:
        await _decrement_active()


async def _dispatch(request: JobSubmitRequest):
    """Route to the appropriate service based on action."""
    p = request.parameters

    if request.action == "fetch":
        callback_url = p.get("callback_url")
        # Multi-URL batch fetch
        if "urls" in p and isinstance(p["urls"], list):
            return await pipeline.crawl_multiple_pages(
                urls=p["urls"],
                crawl_options=CrawlOptions(
                    screenshots=p.get("screenshots", True),
                    timeout_ms=p.get("timeout_ms", 120000),
                    callback_url=callback_url,
                ),
            )
        # Single URL fetch
        return await crawl_client.crawl(CrawlRequest(
            url=p["url"],
            options=CrawlOptions(
                screenshots=p.get("screenshots", True),
                callback_url=callback_url,
            ),
        ))
    elif request.action == "search":
        return await searxng_client.search(SearchRequest(
            query=p["query"],
            max_results=p.get("max_results", 8),
        ))
    elif request.action == "agent_browse":
        # Multi-step LLM-driven browser session. Runner pushes the final
        # result to `agent:result:{job_id}` itself; we still return the
        # same dict so `_execute_job` can also LPUSH `fetch:result:{job_id}`
        # - Java BLPOPs *whichever* matches its known key, and PR #4 uses
        # `agent:result:{job_id}` directly, so this dual-write is harmless
        # belt-and-braces during the rolling migration.
        redis = get_redis()
        # _execute_job propagates job_id; we read it from the closure.
        # (job_id is added by the caller in _execute_job - but _dispatch
        # doesn't see it directly. We pass it via parameters['__job_id__']
        # set by _execute_job below.)
        job_id = p.get("__job_id__") or "job_unknown"
        return await run_browser_agent_session(job_id, p, redis)
    else:
        raise ValueError(f"Unknown action: {request.action}")
