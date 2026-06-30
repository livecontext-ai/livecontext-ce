-- V187: widen workflow_step_data.selected_branch and item_id from VARCHAR(255) to TEXT.
--
-- Bundle 1 follow-up to V186 (which widened error_message). These two columns
-- are exposed to the same silent-drop pattern as error_message was:
--   * selected_branch - populated from the raw Classify agent output at
--     StepDataPersistenceService.java:339
--     (`entity.setSelectedBranch(selectedCategory.toString())`). LLM can emit
--     a category label of arbitrary length.
--   * item_id - populated from Transform-node outputs (line 199). Values seen in
--     prod include full JWTs (~1.5 KB) and concatenated URLs (~2 KB).
--
-- Both columns are NOT indexed - no Postgres B-tree row-size constraint applies.
-- The widening is a pure metadata-only ALTER on Postgres (no table rewrite, no
-- data validation pass on existing rows), so it is safe on the live ~50 M-row
-- table. Brief AccessExclusiveLock during the catalog flip; sub-second.
--
-- Application-level cap (16 384 chars, reusing ErrorMessageLimits.MAX_LENGTH)
-- is added in the entity setters in the same commit. The cap protects the
-- highest-volume orchestrator table from unbounded row sizes; the full payload
-- is already persisted to S3 by StepPayloadService for the few cases where
-- the truncated DB-side preview is not enough.
--
-- Indexed columns (step_alias, trigger_id, normalized_key) and other VARCHAR(255)
-- identifier fields (tool_id, loop_id, skip_source_node) are deferred to a
-- separate bundle - they require additional B-tree size handling, a NOT VALID
-- + VALIDATE migration shape, and read-side query symmetry.

SET search_path TO orchestrator;

ALTER TABLE workflow_step_data ALTER COLUMN selected_branch TYPE TEXT;
ALTER TABLE workflow_step_data ALTER COLUMN item_id         TYPE TEXT;
