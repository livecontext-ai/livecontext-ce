"""Crawl client - stealth browser + innerText extraction.

Architecture:
  1. browser_client.crawl_page() → nodriver navigates, extracts innerText + viewport screenshot
  2. Upload screenshot to MinIO (async - does not block text return)
  3. Notify orchestrator via callback when screenshot is ready
"""

import asyncio
import time
import logging

import httpx

from app.config import settings
from app.models.crawl import CrawlRequest, CrawlResponse
from app.services.browser_client import crawl_page

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Concurrency: crawl semaphore (lazy-init to respect per-worker event loop)
# ---------------------------------------------------------------------------
_crawl_semaphore: asyncio.Semaphore | None = None


def _get_crawl_semaphore() -> asyncio.Semaphore:
    global _crawl_semaphore
    if _crawl_semaphore is None:
        _crawl_semaphore = asyncio.Semaphore(settings.max_concurrent_crawls)
    return _crawl_semaphore


# ---------------------------------------------------------------------------
# Background task tracking (for graceful shutdown)
# ---------------------------------------------------------------------------
_background_tasks: set[asyncio.Task] = set()


def _track_task(coro) -> asyncio.Task:
    """Create a tracked asyncio task that auto-removes on completion."""
    task = asyncio.create_task(coro)
    _background_tasks.add(task)
    task.add_done_callback(_background_tasks.discard)
    return task


async def cancel_pending_tasks():
    """Cancel all pending background tasks (called at shutdown)."""
    for task in _background_tasks:
        task.cancel()
    if _background_tasks:
        await asyncio.gather(*_background_tasks, return_exceptions=True)


# ---------------------------------------------------------------------------
# Main crawl function
# ---------------------------------------------------------------------------
async def crawl(request: CrawlRequest) -> CrawlResponse:
    """Crawl a single URL using stealth browser + innerText extraction.

    Screenshot upload + callback are fired as a background task so that
    the text result is returned to the caller (Redis BLPOP) immediately.
    """
    async with _get_crawl_semaphore():
        start = time.monotonic()
        logger.info("[TIMING] crawl() start for %s", request.url)

        # Single navigation: get innerText + viewport screenshot
        browsed = await crawl_page(
            url=request.url,
            take_screenshot=request.options.screenshots,
            timeout_ms=request.options.timeout_ms,
        )
        logger.info("[TIMING] crawl_page() done for %s: %dms", request.url, int((time.monotonic() - start) * 1000))

        # Early exit: page blocked by WAF/anti-bot (detected in browser_client)
        if browsed.blocked_reason:
            return CrawlResponse(
                url=request.url,
                markdown="",
                metadata={
                    "title": browsed.title,
                    "blocked_reason": browsed.blocked_reason,
                },
                screenshots=[],
                screenshot_key=None,
                crawl_time_ms=int((time.monotonic() - start) * 1000),
            )

        markdown = browsed.html

        # Fire screenshot upload as background task (non-blocking)
        if browsed.screenshot_bytes:
            _track_task(_async_screenshot_upload(
                url=request.url,
                screenshot_bytes=browsed.screenshot_bytes,
                callback_url=request.options.callback_url,
            ))

        total = int((time.monotonic() - start) * 1000)
        logger.info("[TIMING] crawl() TOTAL for %s: %dms (screenshot upload in background)", request.url, total)
        return CrawlResponse(
            url=request.url,
            markdown=markdown,
            metadata={
                "title": browsed.title,
                "word_count": len(markdown.split()) if markdown else 0,
            },
            screenshots=[],
            screenshot_key=None,  # will arrive via callback
            crawl_time_ms=total,
        )


async def _async_screenshot_upload(url: str, screenshot_bytes: bytes, callback_url: str | None):
    """Upload screenshot to MinIO and notify orchestrator via callback."""
    try:
        from app.services.minio_client import upload_screenshot
        t_start = time.monotonic()
        screenshot_key = await upload_screenshot(url, screenshot_bytes)
        logger.info("[TIMING] Background MinIO upload for %s: %dms (%d KB), key=%s",
                     url, int((time.monotonic() - t_start) * 1000),
                     len(screenshot_bytes) // 1024, screenshot_key)

        if screenshot_key and callback_url:
            await _notify_screenshot_ready(callback_url, url, screenshot_key)
    except Exception as e:
        logger.warning("Background screenshot upload failed for %s: %r", url, e)


async def _notify_screenshot_ready(callback_url: str, url: str, screenshot_key: str):
    """POST screenshot key to orchestrator callback endpoint."""
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(callback_url, json={
                "url": url,
                "screenshot_key": screenshot_key,
                "screenshot_index": 0,
                "is_final": True,
            })
            if resp.status_code == 200:
                logger.info("[CALLBACK] Screenshot notified for %s: key=%s", url, screenshot_key)
            else:
                logger.warning("[CALLBACK] Screenshot callback returned %d for %s", resp.status_code, url)
    except Exception as e:
        logger.warning("[CALLBACK] Failed to notify screenshot for %s: %r", url, e)
