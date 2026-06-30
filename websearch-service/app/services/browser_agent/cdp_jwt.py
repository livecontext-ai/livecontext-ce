"""Tiny HS256 JWT helper for CDP-WS authentication.

Purpose:
  - Java side issues a token alongside each browser-agent submit
  - Frontend uses it to upgrade ``wss://websearch-host/cdp/{sid}?token=...``
  - FastAPI verifies the token before bridging to the live Chromium

We DON'T want a heavyweight JWT library here:
  - PyJWT is not in `requirements.txt` and pulls cryptography → +10MB
  - HS256 is symmetric - Java HmacSHA256 and Python hmac.SHA256 produce
    byte-identical signatures from the same secret + payload
  - No claim issuer/audience matching is needed; the secret IS the trust

Public API:
  - issue_cdp_token(session_id, user_id, run_id, node_id, ttl_seconds=...) -> str
  - verify_cdp_token(token) -> dict   raises JWTError on bad sig / expired / claim mismatch
  - JWTError                          single exception type for all failures

The token format is the standard 3-part dot-separated base64url payload:

    base64url(header) + "." + base64url(payload) + "." + base64url(signature)

with header `{"alg":"HS256","typ":"JWT"}`. This way the Java side can use
its existing ``com.auth0.jwt.algorithms.Algorithm.HMAC256(secret)`` helper
(or any standards-compliant JWT lib) and the bytes match.

Required claims:
  - sid  session id  (matches the URL path param)
  - sub  user id     (X-User-ID at issue time)
  - rid  run id      (workflow run id)
  - nid  node id     (workflow node id)
  - exp  expiry      (unix seconds; 5min default TTL)
  - iat  issued-at   (unix seconds)

The verify side enforces ``exp`` and signature; ``sid`` is matched against
the URL path inside the router - keeping that check OUT of this module so
the helper stays pure and the unit tests can drive it deterministically.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any


DEFAULT_TTL_SECONDS = 300  # 5 minutes


class JWTError(ValueError):
    """Raised on any verification failure (bad sig, expired, malformed)."""


def _b64url_encode(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


def _b64url_decode(s: str) -> bytes:
    pad = (-len(s)) % 4
    return base64.urlsafe_b64decode(s + ("=" * pad))


def issue_cdp_token(
    *,
    session_id: str,
    user_id: str,
    run_id: str,
    node_id: str,
    secret: str,
    ttl_seconds: int = DEFAULT_TTL_SECONDS,
    now: int | None = None,
) -> str:
    """Issue a short-lived HS256 JWT for the CDP WebSocket upgrade.

    Empty/None ``secret`` is rejected with ``JWTError`` so a misconfig
    doesn't silently produce unsigned tokens.
    """
    if not secret:
        raise JWTError("CDP JWT secret is empty - cannot issue tokens")
    if not session_id or not run_id or not node_id:
        raise JWTError("session_id, run_id, node_id are required")

    issued_at = int(time.time()) if now is None else int(now)
    payload: dict[str, Any] = {
        "sid": session_id,
        "sub": user_id or "",
        "rid": run_id,
        "nid": node_id,
        "iat": issued_at,
        "exp": issued_at + max(1, int(ttl_seconds)),
    }
    header = {"alg": "HS256", "typ": "JWT"}
    h = _b64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    p = _b64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = (h + "." + p).encode("ascii")
    sig = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{h}.{p}.{_b64url_encode(sig)}"


def verify_cdp_token(token: str, *, secret: str, now: int | None = None) -> dict[str, Any]:
    """Verify signature + expiry. Returns the decoded payload on success.

    Does NOT validate that ``sid`` / ``rid`` / ``nid`` match anything in
    particular - the caller (router) does that against the URL path so this
    helper stays pure.

    Raises ``JWTError`` for: empty token, malformed format, header mismatch
    (alg != HS256), invalid signature, expired ``exp``.
    """
    if not secret:
        raise JWTError("CDP JWT secret is empty - refusing to verify")
    if not token or not isinstance(token, str):
        raise JWTError("empty token")

    parts = token.split(".")
    if len(parts) != 3:
        raise JWTError("malformed token (expected 3 segments)")
    h_b64, p_b64, sig_b64 = parts

    try:
        header = json.loads(_b64url_decode(h_b64).decode("utf-8"))
    except Exception as e:
        raise JWTError(f"malformed header: {e}") from e
    if not isinstance(header, dict) or header.get("alg") != "HS256":
        raise JWTError("unexpected algorithm - only HS256 is accepted")

    expected_sig = hmac.new(
        secret.encode("utf-8"),
        (h_b64 + "." + p_b64).encode("ascii"),
        hashlib.sha256,
    ).digest()
    try:
        actual_sig = _b64url_decode(sig_b64)
    except Exception as e:
        raise JWTError(f"malformed signature: {e}") from e
    if not hmac.compare_digest(expected_sig, actual_sig):
        raise JWTError("invalid signature")

    try:
        payload = json.loads(_b64url_decode(p_b64).decode("utf-8"))
    except Exception as e:
        raise JWTError(f"malformed payload: {e}") from e
    if not isinstance(payload, dict):
        raise JWTError("payload not a JSON object")

    exp = payload.get("exp")
    if not isinstance(exp, (int, float)):
        raise JWTError("missing or non-numeric exp")
    current = int(time.time()) if now is None else int(now)
    if current >= int(exp):
        raise JWTError("token expired")

    return payload
