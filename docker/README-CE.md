# LiveContext Community Edition - Docker Setup

CE ships as a single self-hosted monolith with embedded auth (no Keycloak). The cloud
SaaS edition runs a different topology (microservices + Keycloak) and is deployed via
GitHub Actions, not via this directory.

| Mode | File | Containers | Keycloak | Best for |
|------|------|-----------|----------|----------|
| **Monolith** | `docker-compose.yml` | 5 | No | Local dev, self-hosting |

---

## Prerequisites

- Docker Desktop 4.x+ (or Docker Engine 24+ with Compose v2)
- 4 GB RAM minimum (8 GB recommended)
- An LLM provider for agents: connect to LiveContext Cloud (recommended), or add your own OpenAI / Anthropic / Google key in the app

## Quick Start

```bash
# From the repo root. This PULLS the prebuilt images (no local build):
docker compose up -d

# Wait ~2-3 minutes for the backend to initialize (Flyway migrations + tool registration)
docker compose ps
# Wait until the "livecontext" service is "healthy" and "frontend" is up.

# Open http://localhost:3000 and create an account (the first user becomes the admin)
```

> **Build from source instead?** The compose pulls prebuilt images. To build them
> yourself, use the per-service Dockerfiles (`backend/monolith-service/Dockerfile` with the
> `ce` Maven profile, `frontend/Dockerfile`, `mcp/bridge/Dockerfile`).

> **Accessing from another machine (not localhost)?** The web UI bakes the API URL as
> `http://localhost:8080` at image build time, so out of the box CE expects to be
> reached at `localhost`. To serve it on a server/VM by IP or domain, build the
> frontend from source with `NEXT_PUBLIC_GATEWAY_WS_URL` set to your public backend URL
> (or front the stack with a reverse proxy that maps it). See the build-args table below.

## Architecture

```
Browser (:3000)
   │
   ├── Static assets / SSR ──► Frontend (Next.js, host :3000 → container :3000)
   │                              │  SSR proxy: /api/proxy/* ──► Backend (container :8080)
   │
   └── WebSocket ────────────► Backend monolith (host :8080 → container :8080)
                                    ├── PostgreSQL (pgvector, :5432)
                                    ├── Redis (:6379)
                                    ├── MinIO S3 (:9000)
                                    └── Bridge (CLI/MCP tools, :8093)
```

### Containers

| Container | Image | Host port | Purpose |
|-----------|-------|-----------|---------|
| `livecontext-db` | `pgvector/pgvector:pg16` | 5432 (internal) | Database with vector extension |
| `livecontext-redis` | `redis:7-alpine` | 6379 (internal) | Cache, pub/sub, streaming |
| `livecontext-minio` | `minio/minio` | 9000 (internal) | S3-compatible file storage |
| `livecontext-minio-init` | `minio/mc` | - | Creates `workflow-files` bucket, then exits |
| `livecontext-bridge` | `ghcr.io/livecontext-ai/livecontext-ce-bridge` | 8093 (internal) | CLI adapters + MCP tools |
| `livecontext-app` | `ghcr.io/livecontext-ai/livecontext-ce` | **8080** | All backend services in one JAR |
| `livecontext-frontend` | `ghcr.io/livecontext-ai/livecontext-ce-frontend` | **3000** | Next.js app (embedded auth) |

Only ports **3000** (frontend, the app) and **8080** (backend API) are exposed to the host.

## Configuration

### Environment Variables

Pass them inline or copy `docker/.env.ce.example` to `docker/.env.ce` and run Compose with
`--env-file docker/.env.ce`.

```bash
# docker/.env.ce

# LLM API keys - at least one required for agent execution
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
GOOGLE_API_KEY=AI...

# Database (defaults are fine for local dev)
DB_USERNAME=postgres
DB_PASSWORD=postgres

# MinIO (defaults are fine for local dev)
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin

# Security - leave blank for first-boot auto-generation
CREDENTIAL_ENCRYPTION_PASSWORD=
CREDENTIAL_ENCRYPTION_SALT=

# Ports (optional - change if conflicts). NOTE: changing BACKEND_PORT requires a
# frontend rebuild - the API URL is baked into the web bundle (see the build-args table).
BACKEND_PORT=8080
FRONTEND_PORT=3000
```

### What the Backend Handles

The monolith JAR bundles all microservices into one process with the `ce` Spring profile:

- **Embedded auth** (email/password) - no Keycloak needed
- **Flyway migrations** - DB schema created automatically on first boot
- **All service endpoints** on a single port (orchestrator, agent, auth, catalog, etc.)
- **S3 storage** via MinIO for workflow file nodes
- **Redis** for event bus, cache, and streaming state
- **Unlimited credits** - consumption is tracked but balance is infinite

### What the Frontend Handles

The Next.js app builds with `NEXT_PUBLIC_APP_EDITION=ce` (and the legacy
`NEXT_PUBLIC_AUTH_MODE=embedded` for one-release backward compat), which:

- Uses the built-in login/register page (no Keycloak redirect)
- Proxies all API calls through `/api/proxy/*` to the backend container
- SSR pages are rendered server-side using the `http://livecontext:8080` internal Docker hostname
- Bypasses the marketing landing page - `/` (and `/{locale}`) redirect 308 to `/app/chat`
- Sets `robots.txt` to disallow all paths so the self-hosted instance never appears in public search results

## Build Details

### Backend Dockerfile (`backend/monolith-service/Dockerfile`)

Multi-stage Maven build:

1. **Build stage**: `maven:3.9-eclipse-temurin-21` - copies all module POMs, downloads dependencies (cached layer), then builds with `-Pce -DskipTests`
2. **Runtime stage**: `eclipse-temurin:21-jre-alpine` - copies only the fat JAR (`app.jar`), runs as non-root `livecontext` user

The `-Pce` Maven profile is critical: it makes all service modules produce regular JARs (not Spring Boot fat JARs), so the monolith can include them on its classpath. Only `monolith-service` gets repackaged as a Spring Boot executable JAR.

JVM settings: `-Xms512m -Xmx1024m -XX:+UseZGC -XX:+ZGenerational` (1.5 GB container limit).

### Frontend Dockerfile (`frontend/Dockerfile`)

Multi-stage Node.js build:

1. **Build stage**: `node:20-alpine` - installs deps, builds with `NEXT_PUBLIC_*` build args baked in
2. **Runtime stage**: `node:20-alpine` - copies standalone output + static assets + `messages/` (i18n locale files)

Key build args injected by docker-compose:

| Arg | Value | Purpose |
|-----|-------|---------|
| `NEXT_PUBLIC_APP_EDITION` | `ce` | Edition SSOT - drives landing bypass, robots.txt disallow, edition-aware UI |
| `NEXT_PUBLIC_AUTH_MODE` | `embedded` | Use built-in auth (not Keycloak). Kept as legacy shim for one release |
| `NEXT_PUBLIC_SPRING_BASE_URL` | `http://livecontext:8080` | Backend URL for SSR proxy (container-to-container) |
| `NEXT_PUBLIC_GATEWAY_WS_URL` | `http://localhost:8080` | WebSocket URL the **browser** uses to reach the backend. Baked at build time, so the prebuilt image defaults to `localhost:8080` (localhost-only). To serve CE on a server by IP/domain, rebuild the frontend with this set to your public backend URL. |

### next.config.mjs - `compress: false`

Next.js compression is disabled. This is required for Docker Desktop on Windows (WSL2 backend) - the WSL2 port proxy fails to forward large chunked/gzipped SSR responses. In production, use a reverse proxy (nginx/Caddy) for compression.

### application-ce.yml - Key Settings

| Setting | Value | Why |
|---------|-------|-----|
| `deployment.mode` | `monolith` | Disables gateway auth filter, uses monolith security |
| `auth.mode` | `embedded` | Enables JWT key pair manager + password auth |
| `spring.flyway.enabled` | `true` | Auto-creates all DB schemas on first boot |
| `spring.flyway.baseline-on-migrate` | `true` | Safe start on empty or existing DB |
| `hikari.connection-init-sql` | `SET search_path TO orchestrator,auth,...` | All schemas accessible without prefixes |
| `piston.embedded` | `true` | In-process code execution (no Piston container; CE image includes bash, Node.js, Python, and tsx) |
| `websearch.enabled` | `false` (env `WEBSEARCH_ENABLED`) | Browser agent off by default; the opt-in `browser-agent` profile sets it to `true` (see "Browser agent" below) |
| `credit.unlimited` | `true` | No billing, infinite credits |
| All `services.*-url` | `http://localhost:${PORT}` | Loopback - all services in same JVM |

## Browser agent (agent_browse) - opt-in

The browser agent (an LLM that drives a real Chromium to navigate, click, and
extract from web pages) is **off by default** because it needs a heavy
Chromium + browser-use container (~1 GB image, +2 GB shared memory). Turn it on
with the bundled env file, which sets both halves at once - the `browser-agent`
Docker profile (starts the `websearch` container, built on demand from the
bundled `websearch-service/` source) and `WEBSEARCH_ENABLED=true` (loads the
browser-agent module in the app):

```bash
# First run builds the Chromium image (a few minutes); later runs reuse it.
docker compose --env-file docker/.env.ce.browser-agent up -d
```

- **Model:** the agent node picks the model (default **gemini 3.1 flash**). The
  app injects the install's Google/Gemini key into each browse job, so set
  `GEMINI_API_KEY` (or add a Google credential in the app) for it to run.
- **Live view:** the side panel always shows the **final page** the agent saw
  (captured screenshot). The real-time screencast additionally needs
  `WEBSEARCH_CDP_JWT_SECRET` set to the same value on both the app and the
  `websearch` container.
- Set only one of the two and the feature is broken (a container the app never
  calls, or a module with no container) - always use the env file so they stay
  coupled.

## Common Commands

```bash
# Start everything (pulls the prebuilt images)
docker compose up -d

# Update to a newer release: the compose pins the image version, so pull the repo
# (which carries the new pinned compose), then restart
git pull
docker compose up -d

# View backend / frontend logs
docker compose logs -f livecontext
docker compose logs -f frontend

# Stop everything
docker compose down

# Stop and delete all data (fresh start)
docker compose down -v

# Check health status
docker compose ps
```

## Startup Order and Timing

The compose file uses `depends_on` with health checks to ensure correct startup:

```
postgres (healthy) ─┐
redis (healthy) ────┼──► livecontext (healthy, ~2 min) ──► frontend
minio (healthy) ────┘
                    └──► minio-init (creates bucket, exits)
```

1. **PostgreSQL** - ready in ~5s
2. **Redis** - ready in ~3s
3. **MinIO** - ready in ~10s, then `minio-init` creates the `workflow-files` bucket
4. **Backend** - starts after all 3 are healthy. Flyway migrations run (~30s on first boot), then tool registration (~10s). Health check: `start_period: 120s`
5. **Frontend** - starts after backend is healthy. Ready in ~5s

Total first boot: **~2-3 minutes**. Subsequent starts: **~30-60 seconds**.

## Troubleshooting

### Backend fails to start - Flyway errors

If you see `relation "..." already exists`, the DB volume has stale data from a previous run with a different migration state.

```bash
# Nuclear option: wipe everything and start fresh
docker compose down -v
docker compose up -d
```

### Backend fails - "Could not deserialize" tool registration error

If you see `Could not deserialize string to java type: java.util.List<java.lang.String>`, a migration left bad JSONB data in `node_type_documentation`. This is fixed by migration V30. If it persists, wipe volumes (`down -v`) and rebuild.

### Frontend loads but SSR pages hang (Windows only)

If `http://localhost:3000` shows a blank page or times out on Windows with Docker Desktop (WSL2 backend), the prebuilt image already ships with Next.js compression disabled (it conflicts with the WSL2 port proxy), so this should not happen. If it does, restart the frontend:
```bash
docker compose restart frontend
```

### Port conflicts

If ports 3000 or 8080 are already in use, change `FRONTEND_PORT` freely:

```bash
# Move the app to another port (safe with the prebuilt image)
FRONTEND_PORT=9870 \
  docker compose up -d
```

Then open `http://localhost:9870`. NOTE: changing `BACKEND_PORT` with the prebuilt
image breaks the browser WebSocket (the API URL `localhost:8080` is baked in). To move
the backend port you must rebuild the frontend image from `frontend/Dockerfile` with
`NEXT_PUBLIC_GATEWAY_WS_URL` pointing at the new port.

### Backend out of memory

The backend has a 1.5 GB memory limit. If you see OOM errors:

```bash
# In docker-compose.yml, increase the livecontext memory limit:
# deploy.resources.limits.memory: 2048M
```

### Check container health

```bash
# Quick status
docker compose ps

# Backend health endpoint
curl http://localhost:8080/actuator/health

# Backend registered tools (should be 16)
curl http://localhost:8080/api/agent-tools | python -m json.tool | head -5
```

## Resource Usage

| Container | Memory Limit | Typical Usage |
|-----------|-------------|---------------|
| PostgreSQL | 256 MB | ~50 MB idle |
| Redis | 96 MB | ~10 MB idle |
| MinIO | 256 MB | ~30 MB idle |
| Backend | 1536 MB | ~800 MB after startup |
| Frontend | 256 MB | ~100 MB after startup |
| **Total** | **~2.4 GB** | **~1 GB idle** |

