-- V155: Composite index on workflow_step_data for split-aggregate count queries.
--
-- Used by StepCompletionOrchestrator.recordSplitAggregateIfMissing - issues two
-- COUNT queries per barrier seal:
--   SELECT COUNT(*) FROM orchestrator.workflow_step_data
--   WHERE run_id = ? AND normalized_key = ? AND epoch = ? AND status = ?
--
-- The aggregate write fires once per split barrier seal AND on AgentRecoveryService
-- startup sweep. Without this index, each call would do a sequential scan over
-- workflow_step_data (a heavily-written table - every node completion writes a row).
--
-- Lock posture: SHARE UPDATE EXCLUSIVE on workflow_step_data - concurrent
-- reads/writes proceed during the build, matching V149/V150 convention.
--
-- On crash: PG marks the index INVALID. Recovery: drop it and re-run V155 (use
-- flyway:repair if needed).

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wsd_aggregate
    ON orchestrator.workflow_step_data (run_id, normalized_key, epoch, status);
