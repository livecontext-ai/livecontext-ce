from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    searxng_url: str = "http://127.0.0.1:8086"
    default_max_results: int = 8
    default_crawl_timeout_ms: int = 30000

    # Concurrency limits
    max_concurrent_crawls: int = 10     # Max parallel crawl jobs across all requests
    max_browser_tabs: int = 5            # Max parallel browser tabs (nodriver)

    # Redis for job result delivery (consumed by Java BLPOP)
    redis_url: str = "redis://127.0.0.1:6379/0"
    max_active_jobs: int = 50
    job_result_ttl_s: int = 300  # 5 min TTL on Redis results

    # MinIO configuration for screenshot storage
    minio_endpoint: str = "127.0.0.1:9000"
    minio_access_key: str = "minioadmin"
    minio_secret_key: str = "minioadmin"
    minio_bucket: str = "workflow-files"
    minio_secure: bool = False

    # Rotating proxy (optional - disabled if host is empty)
    proxy_host: str = ""
    proxy_port: int = 0
    proxy_username: str = ""
    proxy_password: str = ""

    @property
    def proxy_url(self) -> str | None:
        """Build full proxy URL with auth, or None if not configured."""
        if not self.proxy_host:
            return None
        auth = ""
        if self.proxy_username:
            auth = f"{self.proxy_username}:{self.proxy_password}@"
        return f"http://{auth}{self.proxy_host}:{self.proxy_port}"

    # Gateway authentication (shared secret with Java gateway)
    gateway_secret: str = ""

    # ── Browser-agent (browser-use) safety toggles ─────────────────────────
    # When enabled, the runner injects `guardrails.js` into every new
    # document so password / credit-card fields cannot be typed into and
    # are visually masked. Disable only for diagnostic runs - production
    # MUST keep this on.
    browser_agent_guardrails_enabled: bool = True

    # When enabled, the runner runs server-side `redact_screenshot(...)`
    # over each step's screenshot bytes using bounding boxes collected
    # from the live page (matches the same selector list as guardrails.js).
    # Belt-and-braces second layer behind the DOM overlay; safe to leave
    # on. Disabling is intended only for tests that need to assert on raw
    # pixels.
    browser_agent_redact_screenshots: bool = True

    # When enabled, the runner shells out to `docker run
    # livecontext/browser-agent:1` per session instead of running
    # browser-use in-process. Set to True once the Docker image is built
    # and the `browser-agent-net` network is provisioned. Default False
    # keeps the in-process path as the safe fallback during rollout.
    browser_agent_use_docker: bool = False

    # Shared secret used to sign the short-lived JWT the frontend uses to
    # upgrade `wss://websearch-host/cdp/{sid}?token=...`. MUST be set in
    # production. Empty default keeps tests hermetic; the CDP router
    # rejects connections with an explicit 503 when the secret is empty.
    cdp_jwt_secret: str = ""

    # CDP token TTL (seconds). The Java side issues a fresh token alongside
    # each browser-agent submit; the frontend immediately upgrades. 5 min
    # is enough for clock skew + handshake even on slow connections.
    cdp_jwt_ttl_seconds: int = 300

    # ── Final-page screenshot fallback ─────────────────────────────────────
    # The live CDP screencast WS often can't reach the browser in prod (no
    # public /cdp route, internal-only cdp_ws_url, Cloudflare WS blocked),
    # so the panel has no way to show the page. As a robust, infra-free
    # fallback the runner captures the FINAL page as a JPEG via CDP
    # `Page.captureScreenshot` at session teardown and stores it (base64)
    # in Redis under `agent:browser:final_shot:{run_id}:{node_id}`. The
    # orchestrator serves it behind the run-ownership gate; the frontend
    # polls + renders it as a static <img>. Disable only for diagnostics.
    browser_agent_final_screenshot_enabled: bool = True

    # JPEG quality (1-100) for the final-page capture. 80 keeps the base64
    # payload well under ~200 KB for a 1920-wide viewport while staying
    # legible. Lower it if the Redis payload size becomes a concern.
    browser_agent_final_screenshot_quality: int = 80

    # TTL (seconds) for the stored final screenshot. Matches the steps
    # stream / control-list lifetime so a panel reopened shortly after the
    # run still finds it; long enough for the frontend poll to land.
    browser_agent_final_screenshot_ttl_seconds: int = 600

    # extra="ignore" - env may contain BROWSER_AGENT_* (separate sub-Settings)
    # and process-wide vars (GOOGLE_API_KEY, …). Don't forbid them here.
    model_config = {"env_prefix": "WEBSEARCH_", "env_file": ".env", "extra": "ignore"}


# ── Browser-agent per-user budget gates (Redis-backed, no env prefix) ──────
# Free-tier resource caps enforced BEFORE the runner spins up Chromium.
# Keys live under `agent:browser:user:{user_id}:*` to avoid colliding
# with the orchestrator-side credit budget under `agent:budget:` and the
# job result LISTs under `agent:browser:result:`. Java-side guard
# (`BrowserAgentBudgetGuard`) checks the same keys before submit so the
# round-trip is short-circuited as early as possible.
#
# Defaults: at most 1 in-flight session per user (a session pins a Chromium
# process + an LLM context - small parallelism risks OOM); 200 agent
# steps per UTC day per user. Both are env-driven so ops can tune without
# code changes; we use bare env vars (NO `WEBSEARCH_` prefix) so the
# names match the spec verbatim.
class _BrowserAgentBudgetSettings(BaseSettings):
    per_user_concurrent_limit: int = 1
    per_user_daily_steps_limit: int = 200

    # `extra="ignore"` is REQUIRED: pydantic-settings 2.13+ forbids extra
    # inputs by default, but this sub-Settings reads the same `.env` file
    # as the main `Settings` (which has its own `WEBSEARCH_` prefix), so
    # every WEBSEARCH_* / GOOGLE_API_KEY / ... var would otherwise raise
    # ValidationError at startup. We only care about the BROWSER_AGENT_*
    # subset; ignore the rest.
    model_config = {
        "env_prefix": "BROWSER_AGENT_",
        "env_file": ".env",
        "extra": "ignore",
    }


browser_agent_budget = _BrowserAgentBudgetSettings()


settings = Settings()
