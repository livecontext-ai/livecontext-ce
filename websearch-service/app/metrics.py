"""Custom Prometheus metrics for WebSearch service."""

from prometheus_client import Gauge, Counter, Histogram

# ─── Active jobs ─────────────────────────────────────────────────────
active_jobs_gauge = Gauge(
    "websearch_active_jobs",
    "Number of currently active async jobs",
)

# ─── Search metrics ──────────────────────────────────────────────────
search_requests_total = Counter(
    "websearch_search_requests_total",
    "Total search requests",
    ["status"],  # success, error
)

search_duration_seconds = Histogram(
    "websearch_search_duration_seconds",
    "Search request duration in seconds",
    buckets=[0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0],
)

search_results_total = Counter(
    "websearch_search_results_total",
    "Total number of search results returned",
)

# ─── Crawl metrics ───────────────────────────────────────────────────
crawl_requests_total = Counter(
    "websearch_crawl_requests_total",
    "Total crawl requests",
    ["status"],  # success, error, skipped
)

crawl_duration_seconds = Histogram(
    "websearch_crawl_duration_seconds",
    "Crawl request duration in seconds",
    buckets=[1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0],
)

# ─── Circuit breaker ─────────────────────────────────────────────────
circuit_breaker_state = Gauge(
    "websearch_circuit_breaker_state",
    "Circuit breaker state: 0=CLOSED (healthy), 1=HALF_OPEN, 2=OPEN (broken)",
)

# ─── Capacity ────────────────────────────────────────────────────────
max_active_jobs_gauge = Gauge(
    "websearch_max_active_jobs",
    "Maximum allowed active jobs",
)
