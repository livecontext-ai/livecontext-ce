-- ======================================================================
-- V535: data classification column for Google restricted-scope data
--       (CASA LC-066, LC-011)
--
-- Adds data_sensitivity ('NORMAL' | 'RESTRICTED' | 'REDACTED') to every sink
-- that stores content derived from a catalog tool: workflow payloads and files
-- (storage.storage), chat tool results and messages (conversation.*), agent
-- observability executions and tool calls (agent.*). Which integrations are
-- restricted is decided in ONE place, common-lib RestrictedDataPolicy.
--
-- Metadata-only on purpose: ADD COLUMN ... NOT NULL DEFAULT <constant> does not
-- rewrite the table on PostgreSQL 11+, so each ALTER holds its ACCESS EXCLUSIVE
-- lock for milliseconds. lock_timeout makes the migration FAIL FAST (and be
-- retried by the next deploy) instead of queueing behind a long transaction
-- and stalling every reader of these tables behind it.
--
-- NOT in this migration, by design:
--   * indexes: V536, CREATE INDEX CONCURRENTLY outside a transaction;
--   * backfill of existing rows: done by an idempotent, batched job in each
--     owning service (RestrictedDataBackfill in storage-, conversation- and
--     agent-service), so no single transaction touches millions of rows.
-- ======================================================================

SET lock_timeout = '5s';
SET statement_timeout = '60s';

ALTER TABLE storage.storage
    ADD COLUMN IF NOT EXISTS data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE conversation.tool_results
    ADD COLUMN IF NOT EXISTS data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE conversation.messages
    ADD COLUMN IF NOT EXISTS data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE agent.agent_executions
    ADD COLUMN IF NOT EXISTS data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE agent.agent_execution_tool_calls
    ADD COLUMN IF NOT EXISTS data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

-- RESET, not just left to the session: plain SET (no LOCAL) is session-scoped, and Flyway can
-- run the very next migration (V536, CREATE INDEX CONCURRENTLY - routinely slower than 5s on a
-- production-sized table) on the SAME pooled connection. An un-reset 5s lock_timeout would then
-- abort that CONCURRENTLY build with "canceling statement due to lock timeout" - reproduced by
-- RestrictedDataClassificationV535MigrationTest against a real Postgres before this line existed.
RESET lock_timeout;
RESET statement_timeout;
