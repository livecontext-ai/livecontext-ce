-- ============================================================================
-- V350: Allow custom task statuses on agent_tasks
-- ============================================================================
-- V349 made board columns user-configurable (agent.task_statuses). A task's
-- status may now be any key defined on its board, not just the seven historical
-- literals, so the fixed-enum CHECK can no longer hold. Validation moves to the
-- application layer (a status must exist in task_statuses for the board, or be a
-- historical default) - mirroring how priority synonyms are validated in code.
--
-- Also widen status / previous_status from VARCHAR(20) to VARCHAR(40) to match
-- task_statuses.key (VARCHAR(40)); a custom key longer than 20 chars would
-- otherwise overflow the column.
-- ============================================================================

SET search_path TO agent;

ALTER TABLE agent.agent_tasks
    DROP CONSTRAINT IF EXISTS agent_tasks_status_check;

ALTER TABLE agent.agent_tasks
    ALTER COLUMN status TYPE VARCHAR(40);

ALTER TABLE agent.agent_tasks
    ALTER COLUMN previous_status TYPE VARCHAR(40);
