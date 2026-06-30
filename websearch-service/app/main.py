import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
import httpx

from app.routers import health, search, crawl, jobs, agent, cdp
from app.auth import GatewayAuthMiddleware
from app.services.http import init_clients, close_clients
from app.services.redis_client import init_redis, close_redis
from app.services.crawl_client import cancel_pending_tasks
from app.services.minio_client import init_minio
from app.services.browser_client import close_browsers
from app.services.redis_client import get_redis

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


async def _purge_stale_browser_concurrent_slots() -> None:
    """Wipe orphan entries from the per-user concurrent-session LISTs.

    A hard kill (websearch restart, SIGKILL, container OOM) bypasses the
    runner's `finally: release_concurrent_slot(...)` cleanup, leaving
    session_ids stuck in `agent:browser:user:{uid}:concurrent` for up to
    1 hour. New `agent_browse` calls from the same user then bounce off
    the per-user concurrency cap (default 1) with `BUDGET_EXHAUSTED`
    until the TTL expires.

    On boot the in-process session registry is empty, so by definition
    every list entry is stale - safe to wipe. Best-effort: a Redis
    hiccup must NOT prevent websearch from starting.
    """
    try:
        redis = get_redis()
        pattern = "agent:browser:user:*:concurrent"
        cleared = 0
        async for key in redis.scan_iter(match=pattern, count=100):
            await redis.delete(key)
            cleared += 1
        if cleared > 0:
            logger.info("Purged %d stale browser concurrent-session LIST(s)", cleared)
    except Exception:
        logger.warning("Failed to purge stale concurrent-session LISTs", exc_info=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle: initialize shared clients, shutdown gracefully."""
    await init_clients()
    await init_redis()
    await init_minio()
    await _purge_stale_browser_concurrent_slots()
    yield
    await cancel_pending_tasks()
    await close_browsers()
    await close_redis()
    await close_clients()


app = FastAPI(title="WebSearch Service", version="1.0.0", lifespan=lifespan)

# Prometheus metrics - must be before middleware to instrument all requests
from prometheus_fastapi_instrumentator import Instrumentator
from app.metrics import active_jobs_gauge, circuit_breaker_state, max_active_jobs_gauge

instrumentator = Instrumentator(
    should_group_status_codes=False,
    excluded_handlers=["/health", "/health/capacity", "/metrics"],
)
instrumentator.instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)

@app.middleware("http")
async def update_capacity_gauges(request, call_next):
    """Update capacity gauges before /metrics response so Prometheus gets fresh values."""
    if request.url.path == "/metrics":
        from app.routers.jobs import get_active_jobs
        from app.services.circuit_breaker import browser_breaker
        from app.config import settings
        active_jobs_gauge.set(get_active_jobs())
        cb = browser_breaker.state
        circuit_breaker_state.set({"CLOSED": 0, "HALF_OPEN": 1, "OPEN": 2}.get(cb, -1))
        max_active_jobs_gauge.set(settings.max_active_jobs)
    return await call_next(request)

app.add_middleware(GatewayAuthMiddleware)

app.include_router(health.router)
app.include_router(search.router)
app.include_router(crawl.router)
app.include_router(jobs.router)
app.include_router(agent.router)
app.include_router(cdp.router)


@app.exception_handler(httpx.ConnectError)
async def connection_error_handler(request: Request, exc: httpx.ConnectError):
    logger.error("Connection error: %s", exc)
    return JSONResponse(
        status_code=503,
        content={"error": "service_unavailable", "message": str(exc)},
    )


@app.exception_handler(httpx.HTTPStatusError)
async def http_status_error_handler(request: Request, exc: httpx.HTTPStatusError):
    logger.error("HTTP error from upstream: %s", exc)
    return JSONResponse(
        status_code=502,
        content={"error": "upstream_error", "message": str(exc)},
    )


@app.exception_handler(Exception)
async def general_exception_handler(request: Request, exc: Exception):
    from fastapi import HTTPException as _H
    if isinstance(exc, _H):
        return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
    logger.error("Unhandled error: %s", exc, exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"error": "internal_error", "message": str(exc)},
    )
