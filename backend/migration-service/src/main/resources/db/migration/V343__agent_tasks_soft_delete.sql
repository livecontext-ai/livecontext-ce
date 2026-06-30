-- Task board "Deleted" column = soft-delete + 30-day retention.
--
-- A task moved to the trash gets status='deleted' (a new logical kanban column),
-- deleted_at = the trash timestamp, and previous_status = the column it held so a
-- Restore returns it to its origin (a deleted-completed task must NOT re-enter the
-- work queue as pending). Because the board / agent inbox / backlog / review queries
-- all key off `status`, a 'deleted' task drops out of every agent work queue for free.
--
-- Retention: TaskRetentionPurger hard-deletes rows where status='deleted' AND
-- deleted_at < now() - 30 days. The partial index keeps that daily sweep cheap.
ALTER TABLE agent.agent_tasks
    ADD COLUMN IF NOT EXISTS deleted_at      TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS previous_status VARCHAR(20) NULL;

-- 'deleted' is a new allowed status (the trash column). Extend the status CHECK the
-- same way V76 added 'in_review' - drop + recreate with the full allowed set.
ALTER TABLE agent.agent_tasks
    DROP CONSTRAINT IF EXISTS agent_tasks_status_check;

ALTER TABLE agent.agent_tasks
    ADD CONSTRAINT agent_tasks_status_check
        CHECK (status IN ('pending','in_progress','in_review','completed','failed','cancelled','deleted'));

CREATE INDEX IF NOT EXISTS idx_agent_tasks_deleted_at
    ON agent.agent_tasks (deleted_at)
    WHERE status = 'deleted';
