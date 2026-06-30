import time
import logging
from urllib.parse import urlparse

from app.config import settings
from app.models.search import SearchRequest, SearchResult, SearchResponse
from app.services.http import get_client

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Sponsored / ad result filtering
# ---------------------------------------------------------------------------
# URL patterns that indicate ad redirects or sponsored placements
_AD_URL_SUBSTRINGS = [
    # Google ads
    "googleadservices.com",
    "googlesyndication.com",
    "doubleclick.net",
    "ad.doubleclick",
    "adclick.",
    "adsensecustomsearchads",
    "googleads.",
    "/aclk?",
    # Bing/Microsoft ads
    "bingads.",
    "msads.",
    "ad.atdmt.com",
    # Affiliate networks
    "click.linksynergy.com",
    "go.redirectingat.com",
    "anrdoezrs.net",
    "awin1.com",
    "impact.com/click",
    "clickserve.",
    "tracking.com",
    "shareasale.com",
    "commission-junction.com",
    "cj.com/jump",
    "tradedoubler.com",
    "partner.com/click",
    "prf.hn/click",
    "avantlink.com",
]

# Domains that are primarily ad/affiliate aggregators
_AD_DOMAINS = {
    "googleadservices.com",
    "googlesyndication.com",
    "doubleclick.net",
    "adsensecustomsearchads.com",
    "clickserve.dartsearch.net",
}


# Domains that produce false-positive search results (e.g. "cours" = course vs price)
_NOISE_DOMAINS = {
    "openclassrooms.com",
    "udemy.com",
    "coursera.org",
    "edx.org",
    "khanacademy.org",
    "skillshare.com",
    "pluralsight.com",
    "codecademy.com",
    "leetcode.com",
}

# E-commerce / shopping domains - never useful for an AI agent research task
_SHOPPING_DOMAINS = {
    # Chinese marketplaces
    "aliexpress.com", "aliexpress.fr", "aliexpress.us",
    "alibaba.com",
    "temu.com",
    "wish.com",
    "dhgate.com",
    "banggood.com",
    "gearbest.com",
    "made-in-china.com",
    "lightinthebox.com",
    # Fast fashion
    "shein.com", "shein.fr",
    "romwe.com",
    "zaful.com",
    # Marketplaces / shopping aggregators
    "ebay.com", "ebay.fr", "ebay.de", "ebay.co.uk",
    "etsy.com",
    "rakuten.com", "rakuten.fr",
    "cdiscount.com",
    "fnac.com",
    "darty.com",
    "boulanger.com",
    "manomano.fr",
    "leroymerlin.fr",
    "amazon.com", "amazon.fr", "amazon.de", "amazon.co.uk", "amazon.ca",
    "amazon.es", "amazon.it",
    # US/UK retail
    "walmart.com",
    "target.com",
    "bestbuy.com", "bestbuy.ca",
    "costco.com",
    "homedepot.com",
    "lowes.com",
    "macys.com",
    "nordstrom.com",
    "newegg.com",
    "overstock.com",
    "wayfair.com",
    "argos.co.uk",
    "currys.co.uk",
    "johnlewis.com",
    # Price comparison / deals
    "idealo.fr", "idealo.de", "idealo.com",
    "kelkoo.com", "kelkoo.fr",
    "shopzilla.com",
    "priceminister.com",
    "dealabs.com",
    "slickdeals.net",
    "groupon.com", "groupon.fr",
    # Classifieds
    "leboncoin.fr",
    "craigslist.org",
    "gumtree.com",
    "marktplaats.nl",
}


def _is_noise_result(url: str) -> bool:
    """Check if a search result is an ad, sponsored link, or known noise domain."""
    url_lower = url.lower()
    for pattern in _AD_URL_SUBSTRINGS:
        if pattern in url_lower:
            return True
    try:
        hostname = urlparse(url).hostname or ""
        if hostname in _AD_DOMAINS:
            return True
        # Check noise domains (including subdomains like www.openclassrooms.com)
        for nd in _NOISE_DOMAINS:
            if hostname == nd or hostname.endswith("." + nd):
                return True
        # Check shopping/e-commerce domains
        for sd in _SHOPPING_DOMAINS:
            if hostname == sd or hostname.endswith("." + sd):
                return True
    except Exception:
        pass
    return False


# ---------------------------------------------------------------------------
# Deduplication: keep highest-score result per domain
# ---------------------------------------------------------------------------
def _deduplicate(results: list[dict]) -> list[dict]:
    """Remove duplicate URLs and keep only the best result per domain."""
    seen_urls: set[str] = set()
    out: list[dict] = []
    for item in results:
        url = item.get("url", "")
        if url in seen_urls:
            continue
        seen_urls.add(url)
        out.append(item)
    return out


async def search(request: SearchRequest) -> SearchResponse:
    """Execute a search query against SearXNG and return structured results."""
    start = time.monotonic()

    params = {
        "q": request.query,
        "format": "json",
        "categories": ",".join(request.categories),
        "pageno": 1,
    }
    # Only send language if explicitly set (not "auto")
    # "auto" = let SearXNG detect from query, returning multilingual results
    if request.language and request.language != "auto":
        params["language"] = request.language
    if request.time_range:
        params["time_range"] = request.time_range

    client = get_client()
    resp = await client.get(f"{settings.searxng_url}/search", params=params, timeout=15.0)
    resp.raise_for_status()
    data = resp.json()

    raw_results = data.get("results", [])

    # Filter ads, deduplicate, then slice to max_results
    filtered = [r for r in raw_results if not _is_noise_result(r.get("url", ""))]
    if len(filtered) < len(raw_results):
        logger.info("Filtered %d ad/sponsored results", len(raw_results) - len(filtered))
    filtered = _deduplicate(filtered)

    results = []
    for item in filtered[: request.max_results]:
        results.append(
            SearchResult(
                url=item.get("url", ""),
                title=item.get("title", ""),
                snippet=item.get("content", ""),
                source=item.get("engine", None),
                score=item.get("score", None),
                published_date=item.get("publishedDate") or item.get("published_date"),
            )
        )

    elapsed_ms = int((time.monotonic() - start) * 1000)
    return SearchResponse(
        query=request.query,
        results=results,
        total_results=len(results),
        search_time_ms=elapsed_ms,
    )
