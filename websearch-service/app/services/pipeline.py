import asyncio
import time
import logging

from app.models.crawl import (
    CrawlOptions,
    CrawlRequest,
    CrawlPageResult,
    FetchBatchResponse,
)
from app.services import crawl_client
from app.services.crawl_filter import (
    validate_crawl_content,
    domain_reputation,
)

logger = logging.getLogger(__name__)

# Max markdown characters per crawled page in the response (to avoid huge JSON payloads)
# 50k covers ~95% of articles while protecting against massive pages (docs, Wikipedia)
MAX_MARKDOWN_CHARS = 50_000


async def crawl_single_page(url: str, crawl_options: CrawlOptions) -> CrawlPageResult:
    """Crawl a single URL and return the result."""
    result = await crawl_client.crawl(
        CrawlRequest(url=url, options=crawl_options)
    )

    # Fast path: page was blocked early in the browser (WAF/anti-bot)
    blocked = result.metadata.get("blocked_reason")
    if blocked:
        logger.warning("Page blocked (early detection) for %s: %s", url, blocked)
        domain_reputation.record_failure(url, blocked)
        return CrawlPageResult(
            url=result.url,
            markdown="",
            metadata={
                "crawl_failed": True,
                "failure_reason": blocked,
                "title": result.metadata.get("title", ""),
                "llm_hint": (
                    f"Page unreachable: {blocked}. "
                    "This source is blocked or protected. "
                    "Use other available sources or search for alternative URLs."
                ),
            },
            screenshots=[],
            screenshot_key=None,
            crawl_time_ms=result.crawl_time_ms,
        )

    # Truncate markdown to prevent oversized responses
    markdown = result.markdown
    if markdown and len(markdown) > MAX_MARKDOWN_CHARS:
        markdown = markdown[:MAX_MARKDOWN_CHARS] + "\n\n[... truncated]"

    # Layer 2: post-crawl content validation (safety net for cases not caught early)
    valid, reason = validate_crawl_content(markdown, result.metadata)
    if not valid:
        logger.warning("Post-crawl validation failed for %s: %s", url, reason)
        domain_reputation.record_failure(url, reason)

        return CrawlPageResult(
            url=result.url,
            markdown="",
            metadata={
                "crawl_failed": True,
                "failure_reason": reason,
                "title": result.metadata.get("title", ""),
                "llm_hint": (
                    f"Page unreachable: {reason}. "
                    "This source is blocked or protected. "
                    "Use other available sources or search for alternative URLs."
                ),
            },
            screenshots=[],
            screenshot_key=None,
            crawl_time_ms=result.crawl_time_ms,
        )

    domain_reputation.record_success(url)

    return CrawlPageResult(
        url=result.url,
        markdown=markdown,
        metadata=result.metadata,
        screenshots=[],
        screenshot_key=result.screenshot_key,
        crawl_time_ms=result.crawl_time_ms,
    )


async def crawl_multiple_pages(urls: list[str], crawl_options: CrawlOptions) -> FetchBatchResponse:
    """Crawl multiple URLs in parallel and return all results."""
    start = time.monotonic()

    tasks = [crawl_single_page(url, crawl_options) for url in urls]
    results = await asyncio.gather(*tasks, return_exceptions=True)

    pages = []
    for i, result in enumerate(results):
        if isinstance(result, Exception):
            logger.warning("Crawl failed for %s: %s", urls[i], result)
            pages.append(CrawlPageResult(
                url=urls[i],
                markdown="",
                metadata={
                    "crawl_failed": True,
                    "failure_reason": str(result),
                },
                crawl_time_ms=0,
            ))
        else:
            pages.append(result)

    return FetchBatchResponse(
        pages=pages,
        total_time_ms=int((time.monotonic() - start) * 1000),
    )
