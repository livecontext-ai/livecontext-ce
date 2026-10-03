-- ======================================================================
-- V561: data_sensitivity column for agent.agent_tasks
--       (CASA LC-066, channel "agent delegation")
--
-- A chat or agent that already holds Gmail / Google Drive content can delegate
-- work with agent(action='assign'): the task's title and instructions may carry
-- that content, and the task is later run by the assignee agent (and its
-- reviewer) on whatever model provider that agent uses. The task now records
-- the classification of the execution that wrote it ('NORMAL' | 'RESTRICTED'),
-- and the execution that works on a RESTRICTED task is tagged restricted, so
-- the provider allow-list is applied to it. Which integrations are restricted
-- is decided in ONE place, common-lib RestrictedDataPolicy.
--
-- Metadata-only: ADD COLUMN ... NOT NULL DEFAULT <constant> does not rewrite
-- the table on PostgreSQL 11+, so the ALTER holds its ACCESS EXCLUSIVE lock
-- for milliseconds. lock_timeout makes the migration FAIL FAST (retried by
-- the next deploy) instead of queueing behind a long transaction - same
-- pattern as V535 / V538. Existing rows read NORMAL: no backfill is possible
-- (the context a task was written in was never recorded).
-- ======================================================================

SET lock_timeout = '5s';
SET statement_timeout = '60s';

ALTER TABLE agent.agent_tasks
    ADD COLUMN IF NOT EXISTS data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

-- RESET, not just left to the session: plain SET (no LOCAL) is session-scoped and Flyway can run
-- the next migration on the SAME pooled connection (see V535's trailer).
RESET lock_timeout;
RESET statement_timeout;
