"""Shared httpx client with connection pooling.

A single AsyncClient is reused across all requests to SearXNG,
avoiding the overhead of creating/destroying TCP connections on every call.

Lifecycle is managed via FastAPI lifespan events (startup/shutdown).
"""

import httpx
import logging

logger = logging.getLogger(__name__)

# Global shared client - initialized at app startup, closed at shutdown
_client: httpx.AsyncClient | None = None


def get_client() -> httpx.AsyncClient:
    """Return the shared httpx client for SearXNG requests."""
    if _client is None:
        raise RuntimeError("HTTP client not initialized. Call init_clients() first.")
    return _client


async def init_clients() -> None:
    """Initialize shared HTTP client. Called once at app startup."""
    global _client

    # Main client: used for SearXNG search requests
    _client = httpx.AsyncClient(
        timeout=httpx.Timeout(
            connect=10.0,
            read=150.0,      # Default; streaming overrides per-request
            write=10.0,
            pool=30.0,
        ),
        limits=httpx.Limits(
            max_connections=50,
            max_keepalive_connections=20,
            keepalive_expiry=30.0,
        ),
    )

    logger.info("HTTP client initialized (50 connections)")


async def close_clients() -> None:
    """Close shared HTTP client. Called once at app shutdown."""
    global _client

    if _client:
        await _client.aclose()
        _client = None

    logger.info("HTTP client closed")
