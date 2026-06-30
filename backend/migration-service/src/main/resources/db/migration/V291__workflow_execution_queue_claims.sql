CREATE TABLE IF NOT EXISTS orchestrator.workflow_execution_queue_claims (
    request_id VARCHAR(128) PRIMARY KEY,
    run_id_public VARCHAR(255) NOT NULL,
    trigger_id VARCHAR(255),
    trigger_type VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    claimed_by VARCHAR(255),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    epoch INTEGER,
    result JSONB,
    message TEXT,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_workflow_execution_queue_claims_run
    ON orchestrator.workflow_execution_queue_claims (run_id_public, trigger_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_workflow_execution_queue_claims_status
    ON orchestrator.workflow_execution_queue_claims (status, updated_at);

CREATE INDEX IF NOT EXISTS idx_workflow_execution_queue_claims_completed
    ON orchestrator.workflow_execution_queue_claims (completed_at)
    WHERE completed_at IS NOT NULL;
