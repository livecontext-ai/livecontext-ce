-- Drop the obsolete UNIQUE(integration_name, variant) constraint
-- (platform_credentials_integration_variant_key, created by V103) that predates
-- per-tenant / per-org BYOK custom OAuth connections.
--
-- Why it must go: that constraint enforced ONE row per (integration_name,
-- variant) GLOBALLY. Tenant BYOK rows all use variant='primary', so a second
-- tenant - or, after V362, a second WORKSPACE - registering the same integration
-- collided on it and the save failed with a duplicate-key error (HTTP 500 on
-- POST /platform-credentials/my). The whole point of V362 is to allow one BYOK
-- row per (integration, tenant, organization), which this constraint forbids.
--
-- Why it is safe: the correct uniqueness is already enforced by
-- idx_platform_cred_integration_tenant (V362):
--   (integration_name, COALESCE(tenant_id,'__PLATFORM__'),
--    COALESCE(organization_id,'__PERSONAL__'), variant)
-- That index also covers platform-wide rows (tenant_id NULL -> '__PLATFORM__'),
-- so the global one-per-(integration,variant) guarantee for platform rows is
-- preserved. The V103 constraint is therefore fully redundant. V310 intended to
-- supersede it but only rewrote idx_platform_cred_integration_tenant and left
-- this table constraint behind.
--
-- Dropping the constraint also drops its backing unique index of the same name.
-- The separate NON-unique idx_platform_credentials_integration_variant (a plain
-- lookup index from V103) is intentionally left intact.
--
-- Idempotent (IF EXISTS). Prod was hotfixed 2026-06-24 with the same DROP; this
-- migration reconciles the migration history and covers fresh installs / CE.

ALTER TABLE auth.platform_credentials
    DROP CONSTRAINT IF EXISTS platform_credentials_integration_variant_key;
