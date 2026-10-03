-- V536: partial indexes for the restricted-data controls (CASA LC-011, LC-066).
--
-- Runs OUTSIDE Flyway's transaction wrapper because CREATE INDEX CONCURRENTLY
-- cannot be issued inside a transaction (same pattern as V149). Lock posture:
-- SHARE UPDATE EXCLUSIVE, so reads and writes proceed during the build.
--
-- Every index is PARTIAL on the RESTRICTED rows only: they are tiny, and they are
-- exactly what the sweepers, the run-taint lookup and the conversation guard read.
--
-- On crash: PostgreSQL leaves the index INVALID. Recovery: DROP INDEX CONCURRENTLY
-- the invalid one and re-run (flyway repair first if needed). IF NOT EXISTS would
-- otherwise skip an INVALID index.

-- flyway:executeInTransaction=false

-- storage-service RestrictedStorageRetentionSweeper: expired restricted rows.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_storage_restricted_expiry
    ON storage.storage (expires_at)
    WHERE data_sensitivity = 'RESTRICTED';

-- StepPayloadService.isRunRestricted: run-level taint lookup.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_storage_restricted_run
    ON storage.storage (run_id)
    WHERE data_sensitivity = 'RESTRICTED';

-- RestrictedConversationContentPurger + RestrictedDataTransferGuard.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_tool_results_restricted_conv
    ON conversation.tool_results (conversation_id, created_at)
    WHERE data_sensitivity = 'RESTRICTED';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_messages_restricted_conv
    ON conversation.messages (conversation_id, created_at)
    WHERE data_sensitivity = 'RESTRICTED';

-- RestrictedObservabilityContentPurger.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_executions_restricted_created
    ON agent.agent_executions (created_at)
    WHERE data_sensitivity = 'RESTRICTED';
