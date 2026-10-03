-- ======================================================================
-- V538: data_sensitivity column for datasource.data_source_items
--       (CASA LC-066/LC-011 re-audit item 2)
--
-- Rows written into a user's table during a RESTRICTED run/conversation
-- (an insert/update whose orchestrator node executes inside a workflow run
-- already tagged RESTRICTED, e.g. saving a Gmail message into a CRM table)
-- previously carried no classification at all: find_rows/read_rows had no
-- tag to return, so the orchestrator/agent tool layer could not stamp
-- __dataSensitivity__ on the result and a disallowed LLM processor could be
-- handed the row's content with no gate. See RestrictedDataPolicy
-- (common-lib) for the single definition of what counts as restricted.
--
-- Metadata-only: ADD COLUMN ... NOT NULL DEFAULT <constant> does not rewrite
-- the table on PostgreSQL 11+, so the ALTER holds its ACCESS EXCLUSIVE lock
-- for milliseconds. lock_timeout makes the migration FAIL FAST (retried by
-- the next deploy) instead of queueing behind a long transaction and
-- stalling every reader of this table behind it - same pattern as V535.
--
-- Retention is deliberately NOT bounded here: user tables are user-directed
-- storage (the user chose to save this data into their own table), so unlike
-- storage.storage / conversation.* / agent.* there is no hard-delete sweep
-- for this column - it is stated explicitly on the privacy page instead
-- (frontend/app/legal/privacy/page.tsx, pinned by privacyPolicyClaims.test.ts).
-- ======================================================================

SET lock_timeout = '5s';
SET statement_timeout = '60s';

ALTER TABLE datasource.data_source_items
    ADD COLUMN IF NOT EXISTS data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

-- RESET, not just left to the session: plain SET (no LOCAL) is session-scoped and Flyway can run
-- the next migration on the SAME pooled connection - an un-reset 5s lock_timeout would then abort
-- an unrelated slower statement. Same fix as V535's trailer (see
-- RestrictedDataClassificationV535MigrationTest for the reproduction against a real Postgres).
RESET lock_timeout;
RESET statement_timeout;
