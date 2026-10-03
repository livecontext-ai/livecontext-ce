-- ======================================================================
-- V563: conversation.conversations.task_id, the per-task conversation of a
--       RESTRICTED delegated task (CASA LC-066, channel "agent delegation")
--
-- An agent has ONE conversation (the agent's main conversation). Until now the
-- assignee and reviewer turns of every delegated task ran in it, and a
-- RESTRICTED task's prompt (Gmail / Google Drive content) was stored there as
-- RESTRICTED, so the guard treated the agent's ONLY conversation as restricted
-- for good: every later task, schedule and chat of that agent was refused on a
-- provider outside the allow-list, or tagged on one inside it.
--
-- A RESTRICTED task's turns now run in a conversation of their own, owned by
-- the same tenant, workspace and agent, keyed by (agent_id, task_id). The
-- agent's main conversation stays NORMAL. Normal tasks keep using the main
-- conversation (task_id stays NULL), and the main-conversation lookup ignores
-- rows with a task_id. A task conversation is stored with memory_enabled =
-- FALSE, so the V212 one-primary-conversation-per-agent indexes never see it.
--
-- Nullable, no default: metadata-only ADD COLUMN, so the ALTER holds its
-- ACCESS EXCLUSIVE lock for milliseconds; lock_timeout makes it FAIL FAST
-- (retried by the next deploy), same pattern as V561 / V562. Pods of the
-- previous release never write the column.
--
-- conversation-service maps the column (ddl-auto=validate), so this migration
-- must be applied before its new pods start: the migration job runs first
-- under helm --atomic, as for V562.
--
-- The index runs OUTSIDE Flyway's transaction (CREATE INDEX CONCURRENTLY, same
-- pattern as V536 / V562). On crash PostgreSQL leaves the index INVALID:
-- DROP INDEX CONCURRENTLY the invalid one and re-run (flyway repair first if
-- needed); IF NOT EXISTS would otherwise skip an INVALID index.
-- ======================================================================

-- flyway:executeInTransaction=false

SET lock_timeout = '5s';
SET statement_timeout = '60s';

ALTER TABLE conversation.conversations
    ADD COLUMN IF NOT EXISTS task_id VARCHAR(255);

COMMENT ON COLUMN conversation.conversations.task_id IS
    'CASA LC-066: the delegated task whose RESTRICTED turns this conversation holds (NULL for every other conversation).';

-- RESET before the CONCURRENTLY build below: plain SET is session-scoped and a 5s lock_timeout
-- would abort an index build on a production-sized table (see V535's trailer).
RESET lock_timeout;
RESET statement_timeout;

-- conversation-service findOrCreateTaskConversation: one lookup per RESTRICTED task turn.
-- Partial: only task conversations carry a task_id, so the index stays tiny.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_conversations_task
    ON conversation.conversations (task_id, agent_id)
    WHERE task_id IS NOT NULL;
