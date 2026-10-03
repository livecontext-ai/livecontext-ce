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
from app.services.crawl_filter import (
    crawl_block_reason,
    is_callback_url_allowed,
    is_url_safe_for_navigation,
)

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
    # Every entry point (/crawl, /jobs/submit fetch, batch fetch) lands here,
    # so the entry URL is checked here once; redirects and subresources are
    # checked by the browser-wide request guard (browser_request_guard).
    safe, reason = await asyncio.to_thread(is_url_safe_for_navigation, request.url)
    if not safe:
        logger.warning("Crawl refused for %s: %s", request.url, reason)
        return CrawlResponse(
            url=request.url,
            markdown="",
            metadata={"title": "", "blocked_reason": crawl_block_reason(reason)},
            screenshots=[],
            screenshot_key=None,
            crawl_time_ms=0,
        )

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
    """POST screenshot key to orchestrator callback endpoint.

    The callback URL comes from the request, so it is only used when it
    targets a configured orchestrator origin (WEBSEARCH_CALLBACK_ALLOWED_ORIGINS).
    """
    allowed, reason = is_callback_url_allowed(callback_url, settings.callback_allowed_origins)
    if not allowed:
        logger.warning("[CALLBACK] Refused callback for %s: %s", url, reason)
        return
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(callback_url, follow_redirects=False, json={
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
