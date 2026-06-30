-- V193: validate the 4 CHECK constraints added in V192 as NOT VALID.
-- (Renumbered from V190 - kept adjacent to V192 after V189/V190 collision with parallel work.)
--
-- Companion to V192. The NOT VALID state in V192 was instant (catalog flip)
-- but does not validate existing rows - only NEW inserts are checked against
-- the constraint. Running VALIDATE here scans the full table once with a
-- SHARE UPDATE EXCLUSIVE lock (coexists with reads and most writes), and on
-- success flips the constraint to fully-validated. This pattern matches V155
-- and V168 (CREATE INDEX CONCURRENTLY) - the right shape for prod safety on
-- a ~50 M-row hot table.
--
-- Existing rows are all app-capped at <= 220 chars (DiagnosticFieldLimits
-- constants pre-V192; selectors and @PrePersist hook from commit b7c249e4f
-- have been live since that commit shipped). VALIDATE will therefore find
-- zero violations. If somehow a violation exists, VALIDATE fails and the
-- constraint stays in NOT VALID - the table still accepts NEW conforming
-- inserts while operators patch the offending rows. No rollback artifact
-- needed.
--
-- Recommended pre-flight on prod before deploying V193:
--   SELECT max(length(step_alias)), max(length(trigger_id)),
--          max(length(normalized_key)), max(length(run_id))
--   FROM orchestrator.workflow_step_data;
-- All four maxes must be at or below 500 (200 for run_id) for VALIDATE to
-- pass cleanly.
--
-- Deploy window: run off-peak. SHARE UPDATE EXCLUSIVE coexists with INSERT/
-- UPDATE/DELETE but blocks ALTER and VACUUM FULL. Validation IO is non-
-- trivial on a 50 M-row table (estimated 5-15 min depending on cache).

-- flyway:executeInTransaction=false

ALTER TABLE orchestrator.workflow_step_data VALIDATE CONSTRAINT wsd_step_alias_max_500;
ALTER TABLE orchestrator.workflow_step_data VALIDATE CONSTRAINT wsd_trigger_id_max_500;
ALTER TABLE orchestrator.workflow_step_data VALIDATE CONSTRAINT wsd_normalized_key_max_500;
ALTER TABLE orchestrator.workflow_step_data VALIDATE CONSTRAINT wsd_run_id_max_200;
