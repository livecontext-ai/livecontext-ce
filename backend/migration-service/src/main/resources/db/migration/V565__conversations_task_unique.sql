-- ======================================================================
-- V565: one active conversation per (workspace, agent, task) (CASA LC-066)
--
-- V563 gave a RESTRICTED delegated task's turns a conversation of their own,
-- found or created by conversation-service findOrCreateTaskConversation. That
-- is a find-then-insert with no constraint behind it (V563's index on
-- (task_id, agent_id) is not unique), so two turns of the same task starting
-- together (an assignee retry racing a reviewer retry, two pods) could each
-- create one, and later turns would split between them.
--
-- This makes the key unique for ACTIVE rows, the same scope the lookup reads
-- (organization_id, agent_id, task_id, active = true). A soft-deleted task
-- conversation (active = false) does not count, so the next turn may create a
-- fresh one. conversation-service catches the unique violation and returns the
-- row the other caller created.
--
-- Duplicates already created by the race are soft-deleted first (the lookup
-- always returned the OLDEST one, so the newer ones were never read again).
--
-- Runs OUTSIDE Flyway's transaction (CREATE INDEX CONCURRENTLY, same pattern
-- as V563). On crash PostgreSQL leaves the index INVALID: DROP INDEX
-- CONCURRENTLY the invalid one and re-run (flyway repair first if needed);
-- IF NOT EXISTS would otherwise skip an INVALID index. Idempotent: the
-- soft-delete only touches active duplicates, which a re-run no longer finds.
-- ======================================================================

-- flyway:executeInTransaction=false

UPDATE conversation.conversations c
SET active = FALSE
WHERE c.task_id IS NOT NULL
  AND c.active IS TRUE
  AND EXISTS (
      SELECT 1 FROM conversation.conversations o
      WHERE o.task_id = c.task_id
        AND o.agent_id = c.agent_id
        AND o.organization_id = c.organization_id
        AND o.active IS TRUE
        AND (o.created_at, o.id) < (c.created_at, c.id)
  );

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_conversations_task_per_agent
    ON conversation.conversations (organization_id, agent_id, task_id)
    WHERE task_id IS NOT NULL
      AND active IS TRUE;
