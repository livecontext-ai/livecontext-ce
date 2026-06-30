-- PR18 - add organization_id to storage.storage.
--
-- Context: V202 (`32be3ca16`) backfilled organization_id on workflows/agents/
-- data_sources/interfaces/projects. Storage was deliberately deferred (V2 schema
-- comment) because the table is far larger and a blanket tenant→default-org
-- backfill could lock writes for minutes in prod.
--
-- This migration:
--   1. ADD COLUMN organization_id VARCHAR(255) NULL  (cheap metadata-only DDL).
--   2. Backfill ONLY rows where workflow_id is set, by JOINing
--      orchestrator.workflows.organization_id. This is the vast majority of
--      storage rows (run artifacts, step outputs) and the JOIN uses
--      idx_storage_tenant_workflow + workflows PK → bounded cost.
--   3. Create two read-path indexes (CONCURRENTLY so writes don't block):
--        - idx_storage_org_status_created  → org explorer
--        - idx_storage_org_workflow_active → org-aware run history
--
-- Skipped here (deliberate):
--   - User-uploaded rows without workflow_id (avatars, manual uploads). These
--     would need a tenant_id→default_org JOIN like V202, which on tens of
--     millions of rows is a maintenance-window operation. They stay
--     organization_id=NULL → remain visible only in personal scope. The owner
--     can transfer them via the per-resource UI move action (PR-future).
--   - tenant_storage_quota / _breakdown / _history. These get peer tables
--     (organization_storage_*) in V205, no data backfill.
--
-- Lock posture:
--   - ALTER TABLE … ADD COLUMN NULL → ACCESS EXCLUSIVE but metadata-only
--     (PostgreSQL ≥11): instant, no row rewrite.
--   - UPDATE …WHERE workflow_id IS NOT NULL AND organization_id IS NULL → row
--     locks scoped to backfillable rows; readers continue. On prod scale
--     consider running as separate batched ops UPDATE if first deploy is slow.
--   - CREATE INDEX CONCURRENTLY → SHARE UPDATE EXCLUSIVE, concurrent
--     reads/writes proceed during build.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + WHERE organization_id IS NULL +
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS. Safe to re-run.

-- flyway:executeInTransaction=false

ALTER TABLE storage.storage
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

-- Backfill from owning workflow (cheap JOIN, bounded cost via existing index).
-- Storage rows with workflow_id set inherit organization_id from the workflow.
UPDATE storage.storage s
   SET organization_id = w.organization_id
  FROM orchestrator.workflows w
 WHERE s.workflow_id IS NOT NULL
   AND s.workflow_id = w.id::text
   AND s.organization_id IS NULL
   AND w.organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_storage_org_status_created
    ON storage.storage (organization_id, status, created_at DESC)
    WHERE organization_id IS NOT NULL AND status = 'ACTIVE';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_storage_org_workflow_active
    ON storage.storage (organization_id, workflow_id, created_at DESC)
    WHERE organization_id IS NOT NULL AND status = 'ACTIVE';

COMMENT ON COLUMN storage.storage.organization_id IS
    'Org that owns this storage row. NULL = personal/legacy (visible only to '
    'tenant_id in personal scope). When set, visible to ALL members of the org '
    'via strict-isolation finders. New INSERTs are tagged at controller-level '
    'from X-Organization-ID. V204 backfilled rows derived from workflows; '
    'remaining tenant-only rows (uploads without workflow_id) need an ops '
    'batched UPDATE during a maintenance window.';
