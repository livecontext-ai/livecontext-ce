"""Gateway secret authentication middleware.

All non-health endpoints require the X-Gateway-Secret header
to match the configured gateway_secret. This prevents unauthorized
access from the VLAN or any network that can reach port 8085.
"""

from fastapi import Request, HTTPException
from starlette.middleware.base import BaseHTTPMiddleware

from app.config import settings


# Paths that do NOT require authentication
PUBLIC_PATHS = {"/health", "/health/capacity", "/metrics", "/docs", "/openapi.json"}


class GatewayAuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        # Skip auth for health checks
        if request.url.path in PUBLIC_PATHS:
            return await call_next(request)

        # CDP WebSocket upgrades carry their own JWT in `?token=` and are
        # checked by the cdp.py router. The gateway secret is the wrong
        # auth model here (the frontend has no way to inject it), so we
        # bypass this middleware for /cdp/* paths and let the router
        # perform JWT verification.
        if request.url.path.startswith("/cdp/"):
            return await call_next(request)

        # Require gateway secret
        if not settings.gateway_secret:
            # No secret configured = open (backward compat during rollout)
            return await call_next(request)

        provided = request.headers.get("X-Gateway-Secret", "")
        if provided != settings.gateway_secret:
            raise HTTPException(status_code=403, detail="Invalid or missing X-Gateway-Secret")

        return await call_next(request)
