"""Server-side screenshot redaction.

The DOM guardrail (guardrails.js) places visual `🔒 redacted` overlays
over sensitive fields BEFORE the page renders. CDP screencast frames
captured AFTER the script has run already include the overlay, so by
the time pixels reach Python the sensitive zone is already opaque.

This module is the belt-and-braces second layer: even if a frame is
captured during the brief window between page load and guardrail
install (or if a site's CSS reset wipes the overlay), we run a
deterministic post-capture mask using bounding-box hints provided by
the runner.

Public API:

    redact_screenshot(image_bytes: bytes, regions: list[dict]) -> bytes

Each region dict: {"x": int, "y": int, "width": int, "height": int,
"reason": str (optional)}. Coordinates are in viewport pixel space.

Implementation: Pillow paste a black rect at each region; encode back
to WebP at the same quality the rest of the pipeline uses.
"""

from __future__ import annotations

import io
import logging
from typing import Iterable, Optional

from PIL import Image, ImageDraw

logger = logging.getLogger(__name__)

# Match the existing screenshot pipeline's WebP quality so redacted
# images don't look out-of-place next to non-redacted ones.
WEBP_QUALITY = 80
DEFAULT_MASK_FILL = (0, 0, 0, 255)  # opaque black RGBA


def redact_screenshot(
    image_bytes: bytes,
    regions: Iterable[dict],
    fill: tuple[int, int, int, int] = DEFAULT_MASK_FILL,
) -> bytes:
    """Apply opaque masks at the given pixel regions; return new WebP bytes.

    Out-of-bounds regions are clipped to the image rect. Empty regions list
    returns the input unchanged (fast path).

    Robust to malformed region dicts: missing or non-int keys are skipped
    with a warning rather than raising - a single bad region from an
    upstream caller must not lose the entire screenshot.
    """
    region_list = list(regions)
    if not region_list:
        return image_bytes

    try:
        img = Image.open(io.BytesIO(image_bytes)).convert("RGBA")
    except Exception as e:
        logger.error("redact: failed to open image (%s); returning original", e)
        return image_bytes

    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    masked = 0
    for region in region_list:
        rect = _coerce_rect(region, width, height)
        if rect is None:
            logger.warning("redact: bad region skipped: %r", region)
            continue
        draw.rectangle(rect, fill=fill)
        masked += 1

    if masked == 0:
        return image_bytes

    composite = Image.alpha_composite(img, overlay)
    out = io.BytesIO()
    # WebP needs RGB; alpha doesn't hurt, but quality flag is RGB-domain.
    composite.convert("RGB").save(out, format="WEBP", quality=WEBP_QUALITY)
    return out.getvalue()


def _coerce_rect(region: dict, max_w: int, max_h: int) -> Optional[tuple[int, int, int, int]]:
    """Return (x0, y0, x1, y1) clipped to [0, max_w/h] or None if invalid."""
    try:
        x = int(region.get("x", 0))
        y = int(region.get("y", 0))
        w = int(region.get("width", 0))
        h = int(region.get("height", 0))
    except (TypeError, ValueError):
        return None
    if w <= 0 or h <= 0:
        return None
    x0 = max(0, x)
    y0 = max(0, y)
    x1 = min(max_w, x + w)
    y1 = min(max_h, y + h)
    if x1 <= x0 or y1 <= y0:
        return None
    return (x0, y0, x1, y1)
