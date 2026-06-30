from fastapi import APIRouter

from app.config import settings
from app.services.circuit_breaker import browser_breaker
from app.metrics import active_jobs_gauge, circuit_breaker_state, max_active_jobs_gauge

router = APIRouter()

_CB_STATE_MAP = {"CLOSED": 0, "HALF_OPEN": 1, "OPEN": 2}


@router.get("/health")
async def health():
    return {"status": "ok", "service": "websearch-service"}


@router.get("/health/capacity")
async def health_capacity():
    """Report current capacity and circuit breaker state for monitoring."""
    from app.routers.jobs import get_active_jobs

    jobs = get_active_jobs()
    cb_state = browser_breaker.state

    # Update Prometheus gauges
    active_jobs_gauge.set(jobs)
    circuit_breaker_state.set(_CB_STATE_MAP.get(cb_state, -1))
    max_active_jobs_gauge.set(settings.max_active_jobs)

    return {
        "active_jobs": jobs,
        "max_jobs": settings.max_active_jobs,
        "max_crawls": settings.max_concurrent_crawls,
        "circuit_breaker": cb_state,
    }
