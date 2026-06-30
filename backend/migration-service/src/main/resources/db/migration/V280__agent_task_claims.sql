-- V280 - Claim log linking executions ↔ tasks, replacing the incomplete
-- "backfill on claim" mechanism that left agent_executions.task_id NULL on
-- every schedule fire (15/15 rows in prod 2026-05-22).
--
-- Architecture: stable runId minted at dispatch (= agent_executions.id), claim
-- log keyed by that id. Survives the race where claim arrives before the
-- agent_executions row is INSERTed (no FK on execution_id by design - the
-- log is the audit, the FK on task_id is the integrity constraint).
--
-- agent_executions.task_id stays as a denormalised hot-path column for the
-- existing /tasks/{id}/executions endpoint; populated at end-of-run by
-- AgentObservabilityService reading the claim log when request.taskId is
-- not already set by the workflow path.

SET search_path TO agent;

-- 1. Claim log - append-only audit + correlation primitive.
CREATE TABLE agent_task_claims (
    id              BIGSERIAL    PRIMARY KEY,
    execution_id    UUID         NOT NULL,                        -- = runId; no FK (row may not exist yet)
    task_id         UUID         NOT NULL REFERENCES agent_tasks(id) ON DELETE CASCADE,
    event           VARCHAR(20)  NOT NULL,                        -- 'claimed' | 'released' | 'submitted'
    at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    agent_id        UUID         NOT NULL,
    organization_id TEXT         NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    CONSTRAINT chk_atc_event CHECK (event IN ('claimed', 'released', 'submitted'))
);

-- Hot lookup: "what tasks did this run touch, in order?"
CREATE INDEX idx_atc_execution ON agent_task_claims (execution_id, at);

-- Hot lookup: "what runs touched this task?" - drives the Task detail page
-- when the denormalised agent_executions.task_id misses (claim-after-completed
-- race). LIMIT-friendly DESC order.
CREATE INDEX idx_atc_task ON agent_task_claims (task_id, at DESC);

-- Org-scoped scans (V261 strict-isolation regime).
CREATE INDEX idx_atc_organization ON agent_task_claims (organization_id);

-- Existing executions may contain denormalized task_id values for tasks that
-- were hard-deleted before this FK existed. Preserve valid links and clear only
-- dangling references so the new ON DELETE SET NULL contract can be validated.
UPDATE agent_executions e
SET task_id = NULL
WHERE e.task_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM agent_tasks t
      WHERE t.id = e.task_id
  );

-- 2. Promote agent_executions.task_id (already nullable since V72) to a real
-- FK so a hard task delete cleanly nulls the denorm. Cascade-on-task-delete
-- on the join already handles the audit side; the FK here just protects the
-- denormalised column from dangling values.
ALTER TABLE agent_executions
    ADD CONSTRAINT fk_agent_executions_task_id
    FOREIGN KEY (task_id) REFERENCES agent_tasks(id)
    ON DELETE SET NULL
    NOT VALID;
ALTER TABLE agent_executions VALIDATE CONSTRAINT fk_agent_executions_task_id;
