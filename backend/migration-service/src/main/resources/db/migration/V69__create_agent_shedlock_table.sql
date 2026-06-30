-- Agent-service shedlock table for horizontal scaling.
-- Required by AgentTaskRecurrenceScheduler (@Scheduled task recurrence tick)
-- to prevent double-firing when multiple agent-service instances run.
-- Omitted from V66 when the scaling shedlock tables were introduced; added
-- here alongside the task delegation feature that first needs it.

CREATE TABLE IF NOT EXISTS agent.shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
