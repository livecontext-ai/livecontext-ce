import time

from fastapi import APIRouter, HTTPException

from app.models.crawl import CrawlRequest, CrawlResponse
from app.services import crawl_client
from app.services.crawl_filter import should_skip_url
from app.metrics import crawl_requests_total, crawl_duration_seconds

router = APIRouter()


@router.post("/crawl", response_model=CrawlResponse)
async def crawl(request: CrawlRequest):
    skip, reason = should_skip_url(request.url)
    if skip:
        crawl_requests_total.labels(status="skipped").inc()
        raise HTTPException(status_code=422, detail=f"URL skipped by crawl filter: {reason}")
    start = time.monotonic()
    try:
        result = await crawl_client.crawl(request)
        crawl_requests_total.labels(status="success").inc()
        return result
    except Exception:
        crawl_requests_total.labels(status="error").inc()
        raise
    finally:
        crawl_duration_seconds.observe(time.monotonic() - start)
