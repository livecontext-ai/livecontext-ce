-- Add organization_id to auth.platform_credentials so BYOK "custom OAuth
-- connections" become workspace (org) scoped, mirroring V208 (PR19) which did
-- exactly this for auth.credentials.
--
-- Problem: platform_credentials is scoped only by tenant_id (the user) + the
-- V103 `variant` discriminator. A BYOK custom OAuth connection registered in one
-- workspace is therefore visible AND resolvable from every other workspace the
-- user belongs to. Connected user tokens (auth.credentials) already got org
-- scoping in V208; this closes the same gap for the BYOK rows that mint them.
--
-- Scope semantics (mirrors V208 on auth.credentials):
--   * tenant_id IS NULL                       -> platform-wide LiveContext app
--                                                (global, org-agnostic). Untouched.
--   * tenant_id set, organization_id IS NULL  -> personal BYOK: legacy rows and
--                                                the "visible in all my workspaces"
--                                                fallback (backward compatible).
--   * tenant_id set, organization_id NOT NULL -> workspace BYOK, visible to that
--                                                org's members only (strict).
--
-- Backfill: legacy tenant-owned rows (organization_id IS NULL, numeric tenant_id)
-- are tagged from the creator's CURRENT default-org membership. Same proxy and
-- predicate as V208: the creator's default org is the right "where did they think
-- they registered this" signal, and platform_credentials has no FK to any resource
-- to JOIN through. Platform-wide rows (tenant_id IS NULL) stay org-NULL: they are
-- global, not owned by any workspace.
--
-- Unique index: V310 keyed
--   (integration_name, COALESCE(tenant_id,'__PLATFORM__'), variant).
-- It must gain the org dimension, otherwise two different workspaces could never
-- each hold their own (integration, tenant, variant) BYOK row - which is the whole
-- point of this change. Re-expressed as
--   (integration_name, COALESCE(tenant_id,'__PLATFORM__'),
--    COALESCE(organization_id,'__PERSONAL__'), variant).
-- The backfill is collision-free: each tenant resolves to exactly ONE default org,
-- so (integration, tenant, variant) being unique pre-migration guarantees
-- (integration, tenant, org, variant) is unique post-backfill. Platform-wide rows
-- collapse to (integration, '__PLATFORM__', '__PERSONAL__', variant), identical in
-- cardinality to the old (integration, '__PLATFORM__', variant).
--
-- Lock posture / idempotency (V208 pattern):
--   * ADD COLUMN NULL -> metadata-only on PostgreSQL >= 11 (instant, no rewrite).
--   * The unique-index swap is on a small table (hundreds of rows); the brief lock
--     is negligible. ON CONFLICT DO NOTHING in setEnabledForVariant is target-less,
--     so it keeps working against the renamed-shape index.
--   * CREATE INDEX CONCURRENTLY for the read-path index so writes are not blocked.
--   * ADD COLUMN IF NOT EXISTS + WHERE organization_id IS NULL + IF NOT EXISTS make
--     this safe to re-run.

-- flyway:executeInTransaction=false

ALTER TABLE auth.platform_credentials
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

-- Backfill tenant-owned rows from the creator's default-org membership.
-- tenant_id carries the owner's user id for BYOK rows.
UPDATE auth.platform_credentials pc
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = pc.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE pc.organization_id IS NULL
   AND pc.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = pc.tenant_id::bigint AND om.is_default = true);

-- Swap the uniqueness index to include the org dimension.
DROP INDEX IF EXISTS auth.idx_platform_cred_integration_tenant;
CREATE UNIQUE INDEX IF NOT EXISTS idx_platform_cred_integration_tenant
    ON auth.platform_credentials(
        integration_name,
        COALESCE(tenant_id, '__PLATFORM__'),
        COALESCE(organization_id, '__PERSONAL__'),
        variant);

-- Read-path index for the org-scoped "my custom OAuth connections" listing.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_platform_cred_tenant_org
    ON auth.platform_credentials (tenant_id, organization_id)
    WHERE tenant_id IS NOT NULL;

COMMENT ON COLUMN auth.platform_credentials.organization_id IS
    'Org that owns this BYOK row. NULL = personal scope (visible to tenant_id in '
    'every workspace - legacy/fallback) OR, when tenant_id is also NULL, a '
    'platform-wide LiveContext app. When set with a tenant_id, the row is a '
    'workspace BYOK visible to that org only. New INSERTs are tagged at '
    'controller level from X-Active-Organization-ID. Resolution priority: '
    'workspace-org > personal(tenant, org NULL) > platform-wide. Mirrors V208 on '
    'auth.credentials.';
