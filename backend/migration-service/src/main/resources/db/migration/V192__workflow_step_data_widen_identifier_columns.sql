-- V192: widen the 6 remaining identifier columns of workflow_step_data from
-- VARCHAR(255) to VARCHAR(2000) + add 4 CHECK constraints (NOT VALID, instant).
-- (Renumbered from V189 - collided with V189__ce_install_state_registration_open.)
--
-- F1 bundle 2 final layer. Completes the work begun in V186/V187 (TEXT widening
-- of error_message / selected_branch / item_id) and commit b7c249e4f
-- (entity-setter caps + @PrePersist). Today the entity caps fire on every
-- write path the orchestrator owns; this migration adds the DB-side invariant
-- so partner services, native SQL repair scripts, future Flyway data
-- migrations, or any caller that bypasses the entity setter also cannot
-- overflow the column.
--
-- Math (round-3 schema audit MF-4): worst-case UTF-8 4 B/char on the v6 unique
-- index = 16 (UUID) + 500*4 (step_alias) + 500*4 (trigger_id) + 16 (4 ints)
-- + 200 (status*4) + 24 (overhead) = 2 256 B  <  2 704 B Postgres B-tree limit.
-- Safe at 500 chars per indexed column. For `run_id` (which participates in
-- idx_wsd_run_step alongside step_alias), the budget is 16 + 200*4 + 500*4 +
-- 8 (id) = 2 824 B if uncapped - capping run_id at 200 brings it to
-- 800 + 2000 + 24 ≈ 2 824 → trimmed: 200*4 + 500*4 + 16 = 2 816 vs 2 704;
-- still tight, but `run_id` in production is `run_<ts>_<8hex>` ≈ 24 chars,
-- so the CHECK is a forward-compat invariant pin rather than a near-miss
-- constraint.
--
-- ALTER COLUMN TYPE VARCHAR(255) -> VARCHAR(2000) is metadata-only on Postgres
-- 9.2+ (no rewrite, no scan, sub-second AccessExclusiveLock). Indexes share
-- text_ops between varchar/varchar so no index rebuild fires either.
--
-- The 4 CHECK constraints use NOT VALID - instant catalog-only ALTER, no full
-- scan. The follow-up V193 VALIDATE migration runs the actual scan outside a
-- transaction (Flyway `executeInTransaction=false`) so reads/DML continue
-- during the validation. Existing rows are all app-capped at <= 220 chars
-- (DiagnosticFieldLimits constants pre-V192), so VALIDATE will not find
-- violations. Recommended pre-flight on prod before V193:
--   SELECT max(length(step_alias)), max(length(trigger_id)),
--          max(length(normalized_key)), max(length(run_id))
--   FROM orchestrator.workflow_step_data;

SET search_path TO orchestrator;

ALTER TABLE workflow_step_data ALTER COLUMN step_alias       TYPE VARCHAR(2000);
ALTER TABLE workflow_step_data ALTER COLUMN tool_id          TYPE VARCHAR(2000);
ALTER TABLE workflow_step_data ALTER COLUMN loop_id          TYPE VARCHAR(2000);
ALTER TABLE workflow_step_data ALTER COLUMN trigger_id       TYPE VARCHAR(2000);
ALTER TABLE workflow_step_data ALTER COLUMN normalized_key   TYPE VARCHAR(2000);
ALTER TABLE workflow_step_data ALTER COLUMN skip_source_node TYPE VARCHAR(2000);

ALTER TABLE workflow_step_data
    ADD CONSTRAINT wsd_step_alias_max_500     CHECK (length(step_alias)     <= 500) NOT VALID,
    ADD CONSTRAINT wsd_trigger_id_max_500     CHECK (length(trigger_id)     <= 500) NOT VALID,
    ADD CONSTRAINT wsd_normalized_key_max_500 CHECK (length(normalized_key) <= 500) NOT VALID,
    ADD CONSTRAINT wsd_run_id_max_200         CHECK (length(run_id)         <= 200) NOT VALID;
