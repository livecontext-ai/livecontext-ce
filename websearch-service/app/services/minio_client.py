"""MinIO client for uploading screenshots.

Singleton lazy-init pattern - the client is created on first use
so the service starts even if MinIO is temporarily unavailable.
"""

import asyncio
import hashlib
import io
import logging
import uuid

from minio import Minio

from app.config import settings

logger = logging.getLogger(__name__)

_client: Minio | None = None
_bucket_verified: bool = False


def _get_client() -> Minio:
    """Lazy-init the MinIO client singleton."""
    global _client
    if _client is None:
        _client = Minio(
            settings.minio_endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=settings.minio_secure,
        )
        logger.info(
            "MinIO client initialized: endpoint=%s, bucket=%s",
            settings.minio_endpoint,
            settings.minio_bucket,
        )
    return _client


def _ensure_bucket() -> None:
    """Ensure the bucket exists (cached - only checks once)."""
    global _bucket_verified
    if _bucket_verified:
        return
    client = _get_client()
    bucket = settings.minio_bucket
    if not client.bucket_exists(bucket):
        client.make_bucket(bucket)
        logger.info("Created MinIO bucket: %s", bucket)
    _bucket_verified = True


async def init_minio():
    """Verify bucket at startup so first upload is fast.

    Non-fatal: if MinIO is unreachable the service still starts -
    bucket will be verified lazily on the first upload instead.
    """
    try:
        await asyncio.to_thread(_ensure_bucket)
        logger.info("MinIO bucket pre-verified at startup")
    except Exception as exc:
        logger.warning(
            "MinIO unavailable at startup (screenshots disabled until reconnect): %s",
            exc,
        )


def _build_key(url: str) -> str:
    """Build an S3 key for a screenshot: screenshots/{uuid8}_{url_hash12}.webp"""
    url_hash = hashlib.sha256(url.encode()).hexdigest()[:12]
    short_uuid = uuid.uuid4().hex[:8]
    return f"screenshots/{short_uuid}_{url_hash}.webp"


def _upload_sync(key: str, image_bytes: bytes) -> None:
    """Blocking upload - called via asyncio.to_thread."""
    _ensure_bucket()
    _get_client().put_object(
        settings.minio_bucket,
        key,
        io.BytesIO(image_bytes),
        length=len(image_bytes),
        content_type="image/webp",
    )


async def upload_screenshot(url: str, image_bytes: bytes) -> str:
    """Upload a screenshot to MinIO and return the S3 key.

    Uses asyncio.to_thread to avoid blocking the event loop.
    """
    key = _build_key(url)
    await asyncio.to_thread(_upload_sync, key, image_bytes)
    logger.info("Screenshot uploaded: key=%s, size=%d KB", key, len(image_bytes) // 1024)
    return key
