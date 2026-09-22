# LiveContext Community Edition - Docker Setup

CE ships as a single self-hosted monolith with embedded auth (no Keycloak). The cloud
SaaS edition runs a different topology (microservices + Keycloak) and is deployed via
GitHub Actions, not via this directory.

| Mode | File | Containers | Keycloak | Best for |
|------|------|-----------|----------|----------|
| **Monolith** | `docker-compose.yml` | 6 running + 1 initialization job | No | Local dev, self-hosting |

---

## Prerequisites

- A machine supported by the release images. Prebuilt releases support x86-64; ARM64 must be explicitly included in that release. See [image architectures](../README.md#images).
- Docker Desktop 4.x+ (or Docker Engine 24+ with Compose v2)
- 4 GB RAM minimum (8 GB recommended)
- An LLM provider for agents: connect to LiveContext Cloud (recommended), or add your own OpenAI / Anthropic / Google key in the app

## Quick Start

```bash
# Clone once, then run from the repository root:
git clone https://github.com/livecontext-ai/livecontext-ce.git
cd livecontext-ce
cp docker/.env.ce.example .env
# Review .env before the first start, especially passwords on a server.
docker compose up -d

# Wait ~2-3 minutes for the backend to initialize (Flyway migrations + tool registration)
docker compose ps
# Wait until the "livecontext" service is "healthy" and "frontend" is up.

# Open http://localhost:3000 and create an account (the first user becomes the admin)
```

> **Build from source instead?** The compose pulls prebuilt images. To build them
> yourself, use the per-service Dockerfiles (`backend/monolith-service/Dockerfile` with the
> `ce` Maven profile, `frontend/Dockerfile`, `mcp/bridge/Dockerfile`).

For LAN or server access, publish both ports and configure `PUBLIC_BASE_URL` and
`GATEWAY_PUBLIC_URL` for email links and OAuth callbacks. No image rebuild is needed.
See [server and reverse-proxy setup](#server-and-reverse-proxy-setup).

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

Copy `docker/.env.ce.example` to `.env` in the repository root. Compose loads it automatically on every command. PowerShell also accepts `cp`. Keep `.env` private.

If you already use `docker/.env.ce`, continue passing `--env-file docker/.env.ce` on every command, including stop and update. Do not change database credentials or encryption keys on an existing installation without a planned migration and a backup.

```bash
# .env (repository root)

# Optional provider keys if you are not using a cloud connection
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

# SMTP - needed for password reset and invitation e-mails (see below)
# MAIL_HOST=smtp.your-provider.example
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=noreply@your-domain.example

# Ports (optional). Both are read at runtime; no frontend rebuild is required.
BACKEND_PORT=8080
FRONTEND_PORT=3000
```

### E-mail (SMTP)

Set this up before you need it. Most notifications only degrade the experience
when they never arrive, but **the password reset link is the way back into an
account**: with no relay configured, a user who forgets their password submits
the form, is told to check their inbox, and nothing ever arrives. The only trace
is one `ERROR` line in the container log saying the send failed and the user is
still locked out.

| Variable | Default | Notes |
|----------|---------|-------|
| `MAIL_HOST` | `localhost` | The relay. The default is the Mailpit dev convention, not a working relay. |
| `MAIL_PORT` | `1025` | `587` on a real relay (the submission port, with STARTTLS). |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | blank | Set **both or neither**. Setting both is all an authenticated relay needs: the client then sends `AUTH` on its own. Leave both blank for a relay that accepts unauthenticated submission. |
| `MAIL_FROM` | `noreply@livecontext.local` | Must be an address your relay accepts as sender, or it will refuse the message. |
| `MAIL_SMTP_STARTTLS` | `true` | Leave it on. It is opportunistic, so a local relay that does not offer TLS still works, while a real one gets an encrypted session. Turning it off against a **non-local** host makes the app **refuse to start**, on purpose: a reset link in cleartext is an account handed to anyone on the path. |
| private-CA relay | (not a variable) | If your relay's certificate is signed by a **private CA**, put that CA's PEM in a directory mounted at `/app/extra-ca` (`CE_EXTRA_CA_DIR`). The container imports it into a runtime truststore at startup and logs `[CE-TLS] Imported extra CA`. Trust the CA; do not turn TLS off. |
| `PUBLIC_BASE_URL` | `http://localhost:$FRONTEND_PORT` | Already used for OAuth redirects, and it is also the host in the reset link. If it is wrong, the e-mail arrives with a link nobody outside the server can open. Do not leave a trailing slash on it. |

There is deliberately no "e-mail is not configured" warning in the app. The host
defaults to a non-blank value, so nothing can tell a real relay from an unset
one without also refusing the feature to installs that run their own relay on
`localhost`, which is a common setup.

#### Upgrading to this release with an internal relay

This release turns STARTTLS **on** (it was off before, so every send was
plaintext). That is what makes the reset link work with a normal relay, and it
changes one case: if your `MAIL_HOST` advertises TLS with a certificate signed by
a **private CA**, or one whose name does not match, JavaMail now issues
`STARTTLS` and aborts rather than falling back, so **all** mail stops, including
verification codes and invitations. The only symptom is an `ERROR` line per send.

If that is you, mount the CA: put its PEM in a folder and add
`- ./extra-ca:/app/extra-ca:ro` to the `livecontext` service's volumes. The
startup log then shows `[CE-TLS] Imported extra CA #1` and mail resumes.
Setting `MAIL_SMTP_STARTTLS=false` also works, but only for a **local** relay:
against a non-local host it makes the app refuse to start, on purpose.

One thing that is **not** configurable here: SMTP `AUTH` is never forced on. It
does not need to be, because setting both credentials is what makes the client
authenticate; and forcing it on with credentials missing makes JavaMail refuse to
open the connection at all, which would break the credential-less default.

To check the wiring without waiting for a locked-out user, put Mailpit on the
stack's own network and read what arrives. Note `MAIL_HOST=mailpit`, not
`localhost`: the backend runs in a container, where `localhost` is that
container and nothing is listening on it.

```yaml
# docker-compose.override.yml, at the REPO ROOT next to docker-compose.yml.
# Compose only auto-loads an override that sits beside the base file, so one
# placed in docker/ is silently ignored and mailpit never starts.
services:
  mailpit:
    image: axllent/mailpit
    ports:
      - "8025:8025"
  livecontext:
    environment:
      MAIL_HOST: mailpit
      MAIL_PORT: 1025
```

Then use "Forgot password?" on the sign-in page and open http://localhost:8025.
If nothing arrives, `docker compose logs livecontext | grep -i "reset"` shows
the send failure, which is the only place a delivery problem is reported.

### What the Backend Handles

The monolith JAR bundles all microservices into one process with the `ce` Spring profile:

- **Embedded auth** (email/password) - no Keycloak needed
- **Flyway migrations** - DB schema created automatically on first boot
- **All service endpoints** on a single port (orchestrator, agent, auth, catalog, etc.)
- **S3 storage** via MinIO for workflow file nodes
- **Redis** for event bus, cache, and streaming state
- **Local usage tracking** - platform counters do not make upstream providers free; your own provider or connected cloud account may charge for calls.

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

The public Compose limits the backend container to 1.5 GB. Check the image entrypoint and Compose file for the JVM settings of the version you run.

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
| `NEXT_PUBLIC_GATEWAY_WS_URL` | `http://localhost:8080` | Build-time fallback for the browser-facing backend URL. Inlined into the client bundle, so it is only a last resort now: the running app prefers the RUNTIME values below. Leave it alone unless you build your own image. |
| `NEXT_PUBLIC_RECAPTCHA_SITE_KEY` | *(empty)* | reCAPTCHA v3 site key for the public `/contact` form. Deliberately empty in CE: the form renders disabled and points at email, because the matching secret would also have to be set and the site key cannot be supplied without rebuilding. |

**Runtime** env vars on the `frontend` service (no rebuild needed, this is how you serve CE
anywhere other than localhost):

| Variable | Default | Purpose |
|----------|---------|---------|
| `GATEWAY_PUBLIC_URL` | empty | Browser-facing backend origin. Empty means "derive it from the address the app was opened with, on `BACKEND_PORT`", which is what makes an install reachable by LAN IP or domain work unmodified. Set it when the backend is elsewhere, e.g. a reverse proxy on one origin: `https://livecontext.example.com`. |
| `BACKEND_PORT` | `8080` | Port the backend is published on, used for that derivation. Keep it equal to the port mapping on the `livecontext` service. |

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
| `credit.unlimited` | `true` | Local CE credit accounting; upstream provider/cloud charges still apply |
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
docker compose --env-file .env --env-file docker/.env.ce.browser-agent up -d
```

- **Model:** the agent node picks the model per AI provider
  (google/anthropic/openai/deepseek/mistral/...). When the install is
  **cloud-linked**, the browser agent relays its per-step LLM calls through your
  cloud connection and bills the cloud account, exactly like the chat / workflow
  agents and `web_search` (no local key needed). **Not linked?** Add that
  provider's API key in the app (Settings > AI providers), or set the matching
  env key (e.g. `GEMINI_API_KEY` for Google); otherwise the run fails with the
  provider's "No API key" error.
- **web_search:** the same `browser-agent` profile also starts a **SearXNG**
  metasearch sidecar, wired via `WEBSEARCH_SEARXNG_URL`, so `web_search` returns
  results. Its config (kept engines + JSON output) is mounted read-only from
  `searxng/settings.yml`; set a unique `server.secret_key` there for your install.
- **Live view:** the side panel always shows the **final page** the agent saw
  (captured screenshot). The real-time screencast additionally needs
  `WEBSEARCH_CDP_JWT_SECRET` set to the same value on both the app and the
  `websearch` container.
- Set only one of the two and the feature is broken (a container the app never
  calls, or a module with no container) - always use the env file so they stay
  coupled.

## Interface screenshots + PDF renderer - opt-in

Interface nodes can render a page to a **PNG screenshot** (`generateScreenshot`)
or a **PDF** (`generatePdf`). That needs a headless Playwright/Chromium sidecar,
which is **off by default** (~1 GB image). Turn it on with the bundled env file,
which starts the `screenshot-renderer` container (`renderer` Docker profile) and
points the app at it (`SCREENSHOT_RENDERER_URL=http://screenshot-renderer:8094`):

```bash
docker compose --env-file .env --env-file docker/.env.ce.renderer up -d
```

- **Best-effort when off:** with the renderer disabled the interface node still
  runs, it just emits no screenshot/PDF output - the rest of the workflow is
  unaffected.
- Set only one half and it stays off (a container the app never calls, or the URL
  with no container) - always use the env file so they stay coupled.

## Keeping optional features enabled

Prefer storing the settings in the root `.env`, then using ordinary `docker compose up -d`:

```dotenv
COMPOSE_PROFILES=renderer,browser-agent
SCREENSHOT_RENDERER_URL=http://screenshot-renderer:8094
WEBSEARCH_ENABLED=true
```

For only one extension, keep its profile and corresponding setting. See the root README for each case.
If you use the bundled env-file examples instead, repeat the same options on every start, update and stop.
The later file replaces the earlier `COMPOSE_PROFILES`; explicitly enable both profiles when combining them:

```bash
docker compose --env-file .env --env-file docker/.env.ce.renderer --env-file docker/.env.ce.browser-agent --profile renderer --profile browser-agent up -d
```

## Server and reverse-proxy setup

Use the prebuilt images; changing the public address does not require a rebuild.
For direct LAN access, make ports 3000 and 8080 reachable from your browser, and set:

```dotenv
PUBLIC_BASE_URL=http://192.168.1.50:3000
GATEWAY_PUBLIC_URL=http://192.168.1.50:8080
```

Replace the address and ports with yours. These values also control email links and credential OAuth callbacks.
Register `http://192.168.1.50:8080/api/credentials/oauth2/callback` with the credential provider when it permits LAN HTTP callbacks; many providers require an HTTPS domain.

For internet access, use HTTPS. A straightforward proxy setup is:

| Public hostname | Proxy target | Required support |
| --- | --- | --- |
| `https://app.example.com` | frontend port 3000 | HTTP, long-lived responses |
| `https://api.example.com` | backend port 8080 | HTTP and WebSocket upgrades |

Set `PUBLIC_BASE_URL=https://app.example.com` and `GATEWAY_PUBLIC_URL=https://api.example.com` in `.env`, without trailing slashes. Configure matching DNS and certificates, then run `docker compose up -d`. Register the credential callback `https://api.example.com/api/credentials/oauth2/callback` with the provider. Social sign-in has its own provider settings; a credential callback is not a social-login callback.

A single-origin proxy must explicitly route the backend API and WebSocket paths as well as the frontend. Merely setting `GATEWAY_PUBLIC_URL` does not configure the proxy. After setup, check login, a chat response, live execution updates, and any OAuth integration you use from another machine.

## Backup and recovery

Persistent application data is in Docker volumes, not just the clone or the launcher's configuration directory.
Before an upgrade, save the Compose file and version, `.env`, any proxy/custom overrides and the `catalog-seeds` directory privately.
The `.env` and encryption-key backup may contain secrets; restrict access to the backup.

For a consistent cold backup, stop the stack with `docker compose stop` using the same project and profile options used to start it. Then export these volumes with your Docker volume backup tool (Docker Desktop provides volume export/import):

| Logical volume | What it preserves |
| --- | --- |
| `livecontext_data` | PostgreSQL database |
| `livecontext_minio` | Stored files and generated assets |
| `livecontext_keys` | Authentication and credential-encryption keys |
| `livecontext_redis` | Redis state and queued work |
| `livecontext_logs` | Logs and audit history |

The actual volume names have a project prefix: use `docker volume ls` and `docker inspect livecontext-app livecontext-db livecontext-minio livecontext-redis` to identify the mounted volumes. The npm launcher uses project `livecontext`, while the repository defaults to `livecontext-ce`.
Start the stack again after all volume exports finish. Keep all volumes from the same stopped snapshot together.

Test recovery on an isolated host: restore the configuration and all backed-up volumes with the same project name and the same image versions, then start the stack and verify login, saved credentials and a stored file. A cold PostgreSQL volume backup requires a compatible PostgreSQL version. Restore before attempting a software upgrade. Do not restore over a running database or overwrite an existing installation during a test.
A database-only backup without the encryption keys or file storage is not a complete recovery plan.

## Update check and anonymous install count

Once a day (and once shortly after startup) your install asks
`https://livecontext.ai/api/ce/releases/latest` whether a newer release exists.
That is what puts the "Update available" badge on the Settings > Information
card. The app never updates itself: the badge only shows you the
`docker compose pull` commands.

**What that request carries, beyond the HTTP basics** (host, accept, connection and a
default `User-Agent` naming the Java runtime, as any HTTP client sends)**:**

```
GET /api/ce/releases/latest?current=0.2.13
X-LiveContext-Anon-Install-Id: 8f2c1a44-...   # random UUID, generated once at first boot
```

The install id is a random UUID generated once and kept in your own database
(`auth.ce_install`). It is derived from nothing: not your IP, not your hostname,
not your licence, not any user account. It exists so the number of live
self-hosted installs can be counted, so the cloud stores it too, alongside
exactly three things: the version above and the dates it was first and last
seen. That is the whole record. **No IP address is stored in it**, and it is
deliberately not the cloud-link install id, so the record itself carries no link
or account information. A build made from source reports itself as `dev` rather
than by its commit id. Records not seen for 180 days are deleted.

To be precise about what that does and does not promise: like any HTTP request
to any service, this one reaches our edge with your IP visible to the web server
and its access log, exactly as your browser does when you open livecontext.ai.
What the claim above is about is the fleet record itself, which is the only
thing derived from this feature and the only thing it keeps.

**Turning it off**, in the root `.env`:

```bash
# Keep the update check, stop identifying this install:
CE_VERSIONCHECK_SENDINSTALLID=false

# Or drop the request entirely (no update badge either):
CE_VERSIONCHECK_ENABLED=false
```

Both are read at startup, so restart the backend after changing them. With the
check off, nothing at all leaves your install on this path, and every feature
keeps working.

## What's new panel

After an upgrade, a one-time panel describes the newest change in the version
you are running, illustrated with an image or a short clip. It ships **inside
the image**: no feed is polled and no request leaves your install, so it works
the same on an air-gapped box as on a connected one. Each user sees it once,
older entries are never replayed, and it stays reachable afterwards from the
"What's new" entry in the profile menu.

To remove it entirely, in the root `.env`:

```bash
CHANGELOG_ENABLED=false
```

Read at startup, so restart the backend after changing it. With it off there is
no panel, no profile-menu entry and no unread dot, and nothing is recorded about
who has seen what.

One caveat if you maintain your own compose file rather than using the one in
this repo: Compose only passes a variable to the container if the service's
`environment:` block names it. The bundled compose forwards both of these; a
hand-written one that does not will make the setting look applied while changing
nothing. To confirm which state you are actually in, look at Settings >
Information: the paragraph about the install id is shown only when this install
is configured to send one. In the backend log, an install that sends one says so once
at startup ("This install reports an anonymous install id ..."), and one that does
not prints nothing. That line appears when the id first becomes readable, which is
normally within seconds of startup but is delayed if the database is not up yet.

## Common Commands

```bash
# Start everything (pulls the prebuilt images)
docker compose up -d

# Back up first (see Backup and recovery). Update the pinned image versions:
git pull --ff-only
docker compose pull
docker compose up -d

# View backend / frontend logs
docker compose logs -f livecontext
docker compose logs -f frontend

# Stop everything
docker compose down

# Check health status
docker compose ps
```

`docker compose down` keeps the data volumes. **`docker compose down -v` deletes them.** Never use `-v` as an upgrade or ordinary troubleshooting step.

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

### Cloud syncs fail with PKIX / certificate errors (TLS-intercepting proxy)

If the Bundles tab shows `PKIX path building failed` or `unable to find valid
certification path`, your network intercepts outbound HTTPS (corporate proxy or
antivirus) and re-signs certificates with a private root CA the containers do
not trust. Fix it at runtime, no rebuild needed:

1. Export your interception root-CA chain as a PEM file (ask IT, or export it
   from your OS certificate store).
2. Put it in a folder next to the compose file, e.g. `extra-ca/corp-root.pem`.
3. Mount the folder on the `livecontext` service and (for the bridge) point
   Node at the PEM:

```yaml
services:
  livecontext:
    volumes:
      - ./extra-ca:/app/extra-ca:ro   # entrypoint imports every .pem/.crt at startup
  bridge:
    volumes:
      - ./extra-ca:/app/extra-ca:ro
    environment:
      NODE_EXTRA_CA_CERTS: /app/extra-ca/corp-root.pem
```

4. `docker compose up -d livecontext bridge`. The app logs
   `[CE-TLS] Imported extra CA ...` on boot and cloud syncs work again.

### Backend fails to start - Flyway errors

Do not delete volumes to fix a migration error. Capture the error and running image version:

```bash
docker compose ps
docker compose logs --tail=200 livecontext
```

Check the release notes for upgrade requirements and confirm that the Compose version matches the intended release. Back up the existing data before repair. If the cause is unclear, open a support issue with the version and sanitized error, never credentials or a database dump.
Rolling an image back after a migration is not automatically safe; use a verified, complete pre-upgrade backup when recovery requires reverting the database.

### Backend fails with a deserialization error

Capture the failing field and migration error from the backend logs, then check the release notes. Preserve the database and follow a version-specific repair. Deleting volumes erases workflows, accounts and stored credentials and is not the default remedy.

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

Then open `http://localhost:9870`. Changing `BACKEND_PORT` is also safe with the prebuilt
image: the compose passes it to the `frontend` service, which serves it at runtime, and the
browser derives the backend origin from the address you opened the app with. Set both and
they stay consistent, no rebuild:

```bash
FRONTEND_PORT=9870 BACKEND_PORT=18080 docker compose up -d
```

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

# Backend registered tools (authentication may be required)
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

