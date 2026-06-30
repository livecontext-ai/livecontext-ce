import time

from fastapi import APIRouter

from app.models.search import SearchRequest, SearchResponse
from app.services import searxng_client
from app.metrics import search_requests_total, search_duration_seconds, search_results_total

router = APIRouter()


@router.post("/search", response_model=SearchResponse)
async def search(request: SearchRequest):
    start = time.monotonic()
    try:
        result = await searxng_client.search(request)
        search_requests_total.labels(status="success").inc()
        search_results_total.inc(len(result.results) if result.results else 0)
        return result
    except Exception:
        search_requests_total.labels(status="error").inc()
        raise
    finally:
        search_duration_seconds.observe(time.monotonic() - start)
