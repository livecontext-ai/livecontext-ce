-- PR22 - promote trigger.* tables' org context to first-class columns.
--
-- Context: PR15 V209 stamped organization_id on orchestrator.workflow_runs;
-- PR18 V204 / PR19 V208 / PR20 V210 / PR21 V211 did the same on storage,
-- credentials, agent runtime, and conversations. PR22 closes the same bug
-- class on triggers: a team-workspace user opens the workflow's trigger
-- panel and sees their personal webhook from before they switched
-- workspaces, because the trigger repositories filter on tenant_id only.
--
-- Tables that get organization_id:
--   • trigger.scheduled_executions      - cron/schedule triggers
--   • trigger.standalone_webhooks       - webhook triggers
--   • trigger.standalone_chat_endpoints - chat-page triggers
--   • trigger.standalone_form_endpoints - form-page triggers
--
-- Out of scope for the column:
--   • trigger.webhook_tokens - keyed by (workflow_id, trigger_id) and FK-
--     bound to standalone_webhooks; scope is inherited via the parent
--     webhook row. The fire path reads the parent on token lookup, so
--     adding org_id here is redundant.
--   • trigger.webhook_call_logs / chat_endpoint_access_logs / form_submission_logs
--     - audit-only side tables; org_id can be added later if log-export
--     surfaces need per-workspace filtering.
--
-- Fire-path semantics (critical):
--   • Anonymous public webhook fire (POST /webhook/{token}) does NOT carry
--     X-Organization-ID - the token IS the auth. The orchestrator
--     WebhookDispatchService looks up the trigger by token (now returning
--     organization_id on the entity) and STAMPS it onto the created
--     workflow_run (V209 organization_id column).
--   • Scheduled fire daemon (findDueExecutions) returns due rows with
--     organization_id; the dispatcher propagates it to the run-creation
--     call. Same propagation for form/chat fires.
--
-- This migration:
--   1. ADD COLUMN organization_id VARCHAR(255) NULL on the 4 trigger tables.
--   2. NO backfill - pre-PR22 trigger rows belong to personal scope by
--      definition (no orgs existed at creation time).
--   3. Partial indexes on (organization_id, tenant_id, created_at DESC)
--      WHERE organization_id IS NOT NULL for hot org-scoped reads. Existing
--      tenant indexes continue to serve the personal-scope path.
--
-- Lock posture:
--   - ALTER TABLE … ADD COLUMN NULL → ACCESS EXCLUSIVE but metadata-only on
--     PostgreSQL ≥11 (instant, no row rewrite).
--   - CREATE INDEX CONCURRENTLY → SHARE UPDATE EXCLUSIVE, concurrent reads/
--     writes proceed during build. Requires flyway:executeInTransaction=false.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + CREATE INDEX CONCURRENTLY IF NOT
-- EXISTS. Safe to re-run.

-- flyway:executeInTransaction=false

-- ---------------------------------------------------------------------------
-- 1. ADD COLUMN organization_id on the 4 trigger tables.
-- ---------------------------------------------------------------------------

ALTER TABLE trigger.scheduled_executions
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE trigger.standalone_webhooks
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE trigger.standalone_chat_endpoints
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE trigger.standalone_form_endpoints
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

-- ---------------------------------------------------------------------------
-- 2. Partial indexes for the org-scoped read paths. The existing tenant-
--    only indexes (idx_*_tenant_id_created_at etc.) continue to serve the
--    personal-scope listing, which adds AND organization_id IS NULL at the
--    JPQL layer. Partial WHERE keeps each index small.
-- ---------------------------------------------------------------------------

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_scheduled_executions_org_created
    ON trigger.scheduled_executions (organization_id, created_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_standalone_webhooks_org_created
    ON trigger.standalone_webhooks (organization_id, created_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_standalone_chat_endpoints_org_created
    ON trigger.standalone_chat_endpoints (organization_id, created_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_standalone_form_endpoints_org_created
    ON trigger.standalone_form_endpoints (organization_id, created_at DESC)
    WHERE organization_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Column comments document the strict-isolation contract.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN trigger.scheduled_executions.organization_id IS
    'Workspace the schedule belongs to. NULL = personal scope. Sourced from '
    'X-Organization-ID at create time (StandaloneScheduleService). The fire '
    'daemon (findDueExecutions) returns this column so the dispatcher can '
    'stamp it onto the created orchestrator.workflow_runs.organization_id '
    '(PR15 V209). Strict-isolation reads via the new finder pairs.';

COMMENT ON COLUMN trigger.standalone_webhooks.organization_id IS
    'Workspace the webhook belongs to. NULL = personal scope. Sourced from '
    'X-Organization-ID at create time. The public fire path (POST /webhook/'
    '{token}) does NOT carry the header (anonymous); WebhookDispatchService '
    'reads this column on token lookup and stamps it onto the created '
    'workflow_run. NO mixing per the strict-isolation contract.';

COMMENT ON COLUMN trigger.standalone_chat_endpoints.organization_id IS
    'Workspace the chat endpoint belongs to. NULL = personal scope. Same '
    'fire-path semantic as standalone_webhooks: anonymous public endpoint, '
    'stored org_id propagates to the created workflow_run.';

COMMENT ON COLUMN trigger.standalone_form_endpoints.organization_id IS
    'Workspace the form endpoint belongs to. NULL = personal scope. Same '
    'fire-path semantic as standalone_webhooks.';
