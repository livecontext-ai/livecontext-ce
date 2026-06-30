-- ============================================================================
-- V352: Task labels (tags)
-- ============================================================================
-- A per-board catalog of colored labels, plus a label_ids array on each task.
-- Storing the ids inline as JSONB keeps board reads join-free (the frontend
-- resolves id -> name/color from the catalog fetched once) and makes label
-- filtering a simple jsonb containment test. A stale id (label deleted) is
-- harmless: the resolver just skips an unknown id, and label deletion also
-- scrubs the id from every task (see AgentTaskRepository.removeLabelFromTasks).
--
-- Scope mirrors agent_tasks: (tenant_id, organization_id), NULL org = personal.
-- ============================================================================

SET search_path TO agent;

CREATE TABLE IF NOT EXISTS agent.task_labels (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        TEXT NOT NULL,
    organization_id  TEXT,
    name             VARCHAR(60) NOT NULL,
    color            VARCHAR(30),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One label name per board (case-insensitive); COALESCE folds the NULL personal
-- scope into a real value so personal-scope names dedupe as expected.
CREATE UNIQUE INDEX IF NOT EXISTS uq_task_labels_board_name
    ON agent.task_labels (tenant_id, COALESCE(organization_id, ''), lower(name));

CREATE INDEX IF NOT EXISTS idx_task_labels_board
    ON agent.task_labels (tenant_id, COALESCE(organization_id, ''));

-- Inline label ids on the task (array of label UUID strings).
ALTER TABLE agent.agent_tasks
    ADD COLUMN IF NOT EXISTS label_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Containment index so "tasks carrying label X" stays cheap (F6 filtering).
CREATE INDEX IF NOT EXISTS idx_agent_tasks_label_ids
    ON agent.agent_tasks USING GIN (label_ids);
