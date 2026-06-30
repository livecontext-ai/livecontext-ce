-- V63: Add correlation_id column for async agent execution signal support.
-- The correlation_id links a signal wait to an async agent task result,
-- enabling workers to resolve the correct signal after execution completes.

ALTER TABLE orchestrator.workflow_signal_waits
    ADD COLUMN correlation_id VARCHAR(255);

CREATE INDEX idx_signal_waits_correlation_id
    ON orchestrator.workflow_signal_waits(correlation_id)
    WHERE correlation_id IS NOT NULL;
