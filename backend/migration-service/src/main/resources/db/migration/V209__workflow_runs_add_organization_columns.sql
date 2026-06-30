-- PR15 - promote workflow_runs org context from JSONB metadata stash to
-- first-class columns.
--
-- Context: PR0.5b (gateway active-org transport) introduced the convention of
-- stuffing X-Organization-ID and X-Organization-Role into
-- workflow_runs.metadata as `__orgId__` / `__orgRole__`. That worked as a
-- short-term plumbing but has three problems:
--
--   1. NOT indexable - querying "all runs for org X last month" requires a
--      JSONB scan over every metadata blob.
--   2. NOT first-class on the ExecutionContext record - the AgentNode
--      workaround at AgentNode:1747 reads metadata, then stuffs the org id
--      into a credentials map (`credentials.__orgId__`) for downstream agent
--      dispatch. Any other consumer needs the same workaround.
--   3. JSONB writes are subject to the JsonbPatchExecutor / advisory-lock
--      regime (CLAUDE.md "JSONB writes on workflow_runs.state_snapshot" rule)
--      but the metadata column is OUTSIDE that contract - easy to corrupt
--      with a careless setMetadata + save().
--
-- This migration:
--   1. ADD COLUMN organization_id VARCHAR(255) NULL.
--   2. ADD COLUMN organization_role VARCHAR(32) NULL.
--   3. Backfill from the existing metadata JSONB stash so historical runs
--      retain their workspace context.
--   4. Create an index on (organization_id, started_at DESC) for org-aware
--      run listings.
--
-- After PR15 ships:
--   - WorkflowRunEntity.getOrgId()/getOrgRole() read the column first,
--     fallback to metadata for transitional safety.
--   - WorkflowExecutionController stamps the new columns at run-creation
--     time directly (no longer via metadata.put("__orgId__", ...)).
--   - ExecutionContext.organizationId is sourced from the column at
--     V2StepByStepService boot of each context.
--   - AgentNode workaround can be deleted in a follow-up cleanup commit.
--
-- The metadata['__orgId__'] entry is kept on legacy rows for two release
-- cycles (audit-trail safety net); future migration can DELETE it after the
-- column has been fully adopted by all readers.
--
-- Lock posture:
--   - ALTER TABLE … ADD COLUMN NULL → ACCESS EXCLUSIVE but metadata-only on
--     PostgreSQL ≥11 (instant, no row rewrite).
--   - UPDATE … FROM jsonb extract → row locks scoped to backfillable rows
--     (organization_id IS NULL).
--   - CREATE INDEX CONCURRENTLY → SHARE UPDATE EXCLUSIVE, concurrent
--     reads/writes proceed during build.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + WHERE organization_id IS NULL +
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS. Safe to re-run.

-- flyway:executeInTransaction=false

ALTER TABLE orchestrator.workflow_runs
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE orchestrator.workflow_runs
    ADD COLUMN IF NOT EXISTS organization_role VARCHAR(32);

-- Backfill from the legacy metadata['__orgId__'] / metadata['__orgRole__'] stash.
-- jsonb ->> returns text; we cast to VARCHAR explicitly. NULL metadata or
-- missing keys produce NULL - no failure mode.
UPDATE orchestrator.workflow_runs r
   SET organization_id = (r.metadata ->> '__orgId__'),
       organization_role = (r.metadata ->> '__orgRole__')
 WHERE r.organization_id IS NULL
   AND r.metadata IS NOT NULL
   AND r.metadata ? '__orgId__';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_runs_org_id_started
    ON orchestrator.workflow_runs (organization_id, started_at DESC)
    WHERE organization_id IS NOT NULL;

COMMENT ON COLUMN orchestrator.workflow_runs.organization_id IS
    'Workspace the run executes in. NULL = personal scope. Sourced from '
    'X-Organization-ID at run-creation time by WorkflowExecutionController. '
    'Read by ExecutionContext.organizationId throughout the execution engine. '
    'Legacy metadata[''__orgId__''] kept as fallback for one release cycle.';

COMMENT ON COLUMN orchestrator.workflow_runs.organization_role IS
    'Caller role in the active org (OWNER / ADMIN / MEMBER / VIEWER) at run-'
    'creation time. Sourced from X-Organization-Role. Used by per-org access '
    'checks (OrgAccessGuard) downstream.';
