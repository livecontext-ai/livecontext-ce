-- Add agent scheduled execution support to existing scheduled_executions table.
-- agent_entity_id is an alternative to workflow_id: a schedule targets either a workflow or an agent.
-- schedule_prompt is the user message sent to the agent at each scheduled execution.

ALTER TABLE trigger.scheduled_executions
    ADD COLUMN agent_entity_id UUID,
    ADD COLUMN schedule_prompt TEXT,
    ADD COLUMN with_memory BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_scheduled_executions_agent_entity_id
    ON trigger.scheduled_executions (agent_entity_id)
    WHERE agent_entity_id IS NOT NULL;
