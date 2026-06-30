# Project Guidelines (AI coding agents)

Guidance for AI coding assistants (Claude Code, Codex, …) working on this repo.
All new code MUST be in English.

## Before writing any code

Search the repo for existing logic first (`Grep`/`Glob` on candidate names, then
read the service/module that should own the responsibility). Never duplicate a
service/util/hook that already exists; if something almost fits, extend it
cleanly rather than adding a parallel path or speculative abstraction.

Put code where the responsibility lives: no business logic in controllers, no
catalog logic in the orchestrator, no direct fetches in the frontend.

## Architecture

```
Frontend (Next.js :3000) → /api/proxy/* → Backend monolith (:8080)
```

The Community Edition bundles all backend services into one Spring Boot JAR
(`backend/monolith-service`, Maven profile `-Pce`), with embedded authentication
(`auth.mode=embedded`). PostgreSQL, Redis, and MinIO are the only infrastructure
dependencies (`docker-compose.yml` in the public CE export).

Some code paths are cloud-only (billing, marketplace-remote, SSO bootstrap) and
are profile-gated so they stay inert in CE - do not remove them, and do not
un-gate them.

### Database migrations

`backend/migration-service` is the ONLY module that runs Flyway. Never re-enable
Flyway in other services. New migrations go in
`backend/migration-service/src/main/resources/db/migration/`.

### Inter-service communication

Each service only queries its own DB schema - cross-schema SQL is forbidden.
Services talk through the HTTP client modules (`backend/*-client`), which run as
loopback calls inside the monolith.

## Frontend rules

- All HTTP goes through `apiClient` (`frontend/lib/api/api-client.ts`) or
  `orchestratorApi` - never raw `fetch()` to the backend, never manual auth headers.
- All user-facing text uses next-intl; every key must exist in EVERY locale file
  under `frontend/messages/` with a real translation (en is the reference).
- Use `nodeRegistry` for node-type checks - never hardcode `node.type === '...'`.
- Avoid raw `useEffect` fetches; use the existing hooks (`useResourceQuery`,
  `useStandardApi`, …).

## Node contracts

When adding or changing workflow-node fields, update
`shared/contracts/node-contracts.schema.json` and run:

```bash
npm run contracts:validate
npm run contracts:generate:ts
```

## Testing

Every backend change needs unit tests (`cd backend/<module> && mvn test`).
Every bug fix gets a regression test that fails pre-fix and passes post-fix.
Frontend: `cd frontend && npm test`.

## Checklist before commit

No duplication (search first) · follow existing patterns · English code ·
unit tests green · i18n parity across all locales · no business logic in
controllers · no direct backend calls from the frontend.
