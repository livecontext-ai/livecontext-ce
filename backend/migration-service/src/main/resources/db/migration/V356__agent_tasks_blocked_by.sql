-- ============================================================================
-- V354: Task dependencies (blocked-by)
-- ============================================================================
-- Adds blocked_by_ids: the ids of tasks that must finish before this one can
-- proceed. Stored inline as a JSONB array (like label_ids) so the board, which
-- already loads every card, can compute the "blocked" badge client-side (a task
-- is blocked while any blocker is still non-terminal) and derive the reverse
-- "blocks" edge without a join. A stale id (blocker purged) is harmless: the
-- resolver skips ids not among the loaded board tasks, and a purged task can no
-- longer be non-terminal, so it never shows as actively blocking.
-- ============================================================================

SET search_path TO agent;

ALTER TABLE agent.agent_tasks
    ADD COLUMN IF NOT EXISTS blocked_by_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_agent_tasks_blocked_by_ids
    ON agent.agent_tasks USING GIN (blocked_by_ids);
