-- ============================================================================
-- V207: Partial index supporting WorkflowInertProbe
--
-- The hourly WorkflowInertProbe iterates "pinned + active" workflows to
-- detect those whose pinned plan declares a schedule trigger but has no
-- ACTIVE row in trigger.scheduled_executions. Without this partial index,
-- the probe does a full table scan on orchestrator.workflows every hour.
--
-- The predicate matches the probe's JPQL exactly so Postgres uses the index:
--   WHERE pinned_version IS NOT NULL AND is_active = true
--
-- See the project docs §D.2 for the probe spec.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_workflows_pinned_active
    ON orchestrator.workflows (tenant_id)
    WHERE pinned_version IS NOT NULL AND is_active = true;
