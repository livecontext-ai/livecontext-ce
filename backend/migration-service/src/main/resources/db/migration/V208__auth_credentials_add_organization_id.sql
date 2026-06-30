-- PR19 - add organization_id to auth.credentials for strict-isolation org scope.
--
-- Context: V202 (`32be3ca16`) backfilled organization_id on workflows/agents/
-- data_sources/interfaces/projects. PR18 V204 added it to storage.storage.
-- Credentials were deliberately deferred at first design (plan.md §4.1 said
-- "per-user (intentional, NOT pooled) - OK"), but the user explicitly redirected
-- the design on 2026-05-13: "c'est les credentials de l'org" - credentials
-- belong to the team in org workspace, with strict workspace isolation. PR19
-- closes this gap.
--
-- This migration:
--   1. ADD COLUMN organization_id VARCHAR(255) NULL  (cheap metadata-only DDL).
--   2. Backfill from each creator's CURRENT default-org membership (V202
--      pattern). For credentials, no analogous JOIN-via-resource exists -
--      `auth.credentials` has no FK to any other resource. The creator's
--      default org is the right proxy for "where the user thought they were
--      registering this credential" because credentials are typically minted
--      via OAuth from a single workspace at a time.
--   3. Create two read-path indexes (CONCURRENTLY so writes don't block):
--        - idx_auth_credentials_org_id           → org listing
--        - idx_auth_credentials_org_integration  → org + integration lookup
--
-- Skipped (deliberate):
--   - Non-numeric tenant_ids (legacy/UUID/garbage) - same predicate as V202.
--   - Users with no default-org membership (degenerate, lone tenants).
--   - Already-tagged rows (idempotent - WHERE organization_id IS NULL).
--
-- Strict-isolation semantics (PR19 contract):
--   - organization_id NULL → personal scope (visible only to tenant_id when no
--     X-Organization-ID is active).
--   - organization_id non-NULL → visible to ALL members of the org via the
--     org-scoped repository finders, NEVER mixed with personal-scope rows.
--   - Runtime credential resolution (CachedLlmCredentialResolver, agent-side
--     OAuth dispatch) stays tenant-keyed until PR20 threads ExecutionContext
--     .organizationId - until then, a workflow running in org context will
--     still pick the executor's tenant credentials. The user-facing CRUD/UI
--     surface (PR19) IS strict-isolation today.
--
-- Lock posture:
--   - ALTER TABLE … ADD COLUMN NULL → ACCESS EXCLUSIVE but metadata-only on
--     PostgreSQL ≥11 (instant, no row rewrite).
--   - UPDATE … WHERE organization_id IS NULL → row locks scoped to backfillable
--     rows; readers continue.
--   - CREATE INDEX CONCURRENTLY → SHARE UPDATE EXCLUSIVE, concurrent
--     reads/writes proceed during build.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + WHERE organization_id IS NULL +
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS. Safe to re-run.

-- flyway:executeInTransaction=false

ALTER TABLE auth.credentials
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

-- Backfill from creator's default-org membership (V202 pattern).
UPDATE auth.credentials c
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = c.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE c.organization_id IS NULL
   AND c.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = c.tenant_id::bigint AND om.is_default = true);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_auth_credentials_org_id
    ON auth.credentials (organization_id)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_auth_credentials_org_integration
    ON auth.credentials (organization_id, integration)
    WHERE organization_id IS NOT NULL;

COMMENT ON COLUMN auth.credentials.organization_id IS
    'Org that owns this credential. NULL = personal scope (visible only to '
    'tenant_id in personal workspace). When set, visible to ALL members of '
    'the org via strict-isolation finders. New INSERTs are tagged at '
    'controller-level from X-Organization-ID. V208 backfilled legacy NULL '
    'rows from each creator''s default-org membership. Runtime resolution '
    '(agent OAuth dispatch, LLM key lookup) stays tenant-keyed until PR20 '
    'threads ExecutionContext.organizationId.';
