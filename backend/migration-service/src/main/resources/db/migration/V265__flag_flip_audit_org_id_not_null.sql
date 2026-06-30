-- V265 - Round-10 Phase 7 follow-up: close the flag_flip_audit carve-out.
-- (the project docs, round-10 audit fix 2026-05-20)
--
-- V263 deliberately kept `orchestrator.flag_flip_audit.organization_id`
-- NULLABLE because FlagFlipAuditEntity had no `organizationId` field -
-- flipping NOT NULL would have crashed every TenantFlagService.flip()
-- after deploy. Round-10 added the entity field + threaded orgId through
-- FlagFlipAuditWriter.recordFlip + TenantFlagService.flip, so the writer
-- now stamps the column. This migration backfills any residual NULLs
-- then flips the constraint.
--
-- Idempotent guards: type alignment + SET NOT NULL both gated on
-- information_schema introspection so a re-run is a no-op.
--
-- Pairs with the V264 UUID→VARCHAR(255) alignment pattern. The previous
-- carve-out (V264 omitted flag_flip_audit because the column data type
-- was irrelevant while the column stayed unused) is closed here: V265
-- aligns the type to VARCHAR(255) to match the String field on the
-- entity, then flips NOT NULL.

-- ============================================================================
-- Section 1 - Re-run backfill in case rows landed between V262 and V265
-- ============================================================================
-- Same pattern as V262 §2.11 (EXISTS guard protects against silent NULL
-- overwrites when a tenant has no default-org membership).

UPDATE orchestrator.flag_flip_audit l
   SET organization_id = (
         SELECT om.organization_id FROM auth.organization_member om
          WHERE om.user_id = l.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE l.organization_id IS NULL
   AND l.tenant_id IS NOT NULL
   AND l.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = l.tenant_id::bigint AND om.is_default = true);

-- ============================================================================
-- Section 2 - Preflight: any residual NULLs?
-- ============================================================================
-- Operator-friendly abort message - same shape as V263 Section 0.
DO $$
DECLARE
    cnt bigint;
BEGIN
    SELECT count(*) INTO cnt
      FROM orchestrator.flag_flip_audit
     WHERE organization_id IS NULL;
    IF cnt > 0 THEN
        RAISE EXCEPTION 'V265 preflight failed - % rows still have NULL organization_id on orchestrator.flag_flip_audit', cnt
            USING HINT = 'Orphan rows (tenant_id non-numeric or user without default org) - either DELETE them, or hand-assign to a tombstone org, before re-applying V265.';
    END IF;
END $$;

-- ============================================================================
-- Section 3 - Align type UUID → VARCHAR(255) (mirrors V264 pattern)
-- ============================================================================
-- The entity now declares `String organizationId`; Hibernate binds VARCHAR.
-- Postgres would implicit-cast VARCHAR→UUID at INSERT but the type is
-- intentionally aligned to VARCHAR(255) to match every other org_id column
-- in the schema and avoid surprises with malformed UUID strings.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'orchestrator'
           AND table_name = 'flag_flip_audit'
           AND column_name = 'organization_id'
           AND data_type = 'uuid'
    ) THEN
        ALTER TABLE orchestrator.flag_flip_audit
            ALTER COLUMN organization_id TYPE VARCHAR(255) USING organization_id::text;
    END IF;
END $$;

-- ============================================================================
-- Section 4 - Flip NOT NULL (irreversible, gated)
-- ============================================================================
-- Idempotent: skipped if the column is already NOT NULL (re-run safety).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'orchestrator'
           AND table_name = 'flag_flip_audit'
           AND column_name = 'organization_id'
           AND is_nullable = 'YES'
    ) THEN
        ALTER TABLE orchestrator.flag_flip_audit
            ALTER COLUMN organization_id SET NOT NULL;
    END IF;
END $$;
