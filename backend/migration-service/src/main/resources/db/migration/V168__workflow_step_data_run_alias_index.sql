-- V168: Covering index for the latest-per-alias reconstructState query.
--
-- StateDataLoader.findLatestPerAliasLightweight runs (multi-epoch path):
--   SELECT DISTINCT ON (step_alias) ... FROM orchestrator.workflow_step_data
--   WHERE workflow_run_id = ? ORDER BY step_alias, id DESC
--
-- Without a matching index, PostgreSQL falls back on the V6 unique constraint
-- prefix (workflow_run_id, step_alias, ...) plus a Sort node - workable on a
-- single thread but multiplies CPU/IO under the burst concurrency that drives
-- async per-item delivery (e.g. a 37-item split with classify async).
--
-- Direct cause of OOM 2026-05-07 12:40 UTC was over-fetch: the previous query
-- materialised 17 180 PgResultSet rows × ~30 concurrent threads. The over-fetch
-- is fixed by DISTINCT ON in the query itself; this index is the matching
-- access path so the small result set is read without a separate sort step.
--
-- Lock posture: CONCURRENTLY → SHARE UPDATE EXCLUSIVE, concurrent reads/writes
-- proceed during the build (matches V149/V150/V155).
--
-- On crash: PostgreSQL marks the index INVALID. Recovery: drop and re-run V168
-- (use flyway:repair if needed).

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wsd_run_alias_id_desc
    ON orchestrator.workflow_step_data (workflow_run_id, step_alias, id DESC);
