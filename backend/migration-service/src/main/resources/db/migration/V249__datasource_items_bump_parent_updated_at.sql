-- V249 - Bump datasource.data_sources.updated_at on child data_source_items writes.
--
-- Context: V9 created `data_sources` with `updated_at TIMESTAMPTZ DEFAULT
-- CURRENT_TIMESTAMP`. The column is bumped only on schema/metadata edits
-- (DataSourceRepositories.update, project reassignment, column add/rename/drop
-- via DataSourceColumnRepository). Row CRUD on `data_source_items` (via
-- DataSourceEnhancedService.applyJsonPatch / addItem / deleteItem,
-- DataSourceBulkOperationRepository, CrudRepository, etc. - 11 sites across
-- 4 repositories) NEVER touched the parent's `updated_at`.
--
-- Visible symptom: the bell's Activity tab reads `data_sources.updated_at` via
-- `InternalDataSourceController.getRecentActivity` and orders by it (backed
-- by V238 partial indexes idx_data_sources_org_updated_at +
-- idx_data_sources_tenant_updated_at_personal). A workflow like "Gmail
-- Auto-Labeler" that inserts/updates rows in a table left the table stuck at
-- the bottom of Activity even though it was the most-recently-active
-- resource in the workspace.
--
-- Fix: 3 statement-level AFTER triggers on `data_source_items` (INSERT,
-- UPDATE, DELETE) sharing one function that bumps the parent's `updated_at`
-- to NOW(). Statement-level + DISTINCT subquery on the transition table
-- keeps bulk INSERT cost at 1 parent UPDATE per statement (e.g. a 1000-row
-- Gmail Auto-Labeler bulk insert = 1 trigger fire = 1 parent update, not
-- 1000).
--
-- Cascade-DELETE note: when `DELETE FROM data_sources WHERE id=X` cascades
-- to children (V9 line 33 `ON DELETE CASCADE`), PG processes cascade
-- children before the parent row is removed in the same tx. The DELETE
-- trigger fires statement-level and bumps `updated_at` on a row that's
-- about to be deleted in the same statement - a harmless wasted UPDATE
-- (no error, no deadlock, the row vanishes a moment later).
--
-- Bulk-clone caveat: `InternalDataSourceController.bulkInsertItems`
-- (publication acquire/clone path) loops single-row `repository.save()`.
-- For 10k-row clones, the trigger fires 10k times (each save is one
-- INSERT statement). Final `updated_at` is correct; a future optimization
-- could batch-INSERT in the bulk path. Out of V249 scope.
--
-- Idempotent: DROP TRIGGER IF EXISTS guards re-runs.

DROP TRIGGER IF EXISTS trg_bump_ds_updated_at_insert ON datasource.data_source_items;
DROP TRIGGER IF EXISTS trg_bump_ds_updated_at_update ON datasource.data_source_items;
DROP TRIGGER IF EXISTS trg_bump_ds_updated_at_delete ON datasource.data_source_items;

CREATE OR REPLACE FUNCTION datasource.bump_data_source_updated_at() RETURNS trigger AS $$
BEGIN
    UPDATE datasource.data_sources
       SET updated_at = NOW()
     WHERE id IN (SELECT DISTINCT data_source_id FROM affected_rows);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bump_ds_updated_at_insert
    AFTER INSERT ON datasource.data_source_items
    REFERENCING NEW TABLE AS affected_rows
    FOR EACH STATEMENT EXECUTE FUNCTION datasource.bump_data_source_updated_at();

CREATE TRIGGER trg_bump_ds_updated_at_update
    AFTER UPDATE ON datasource.data_source_items
    REFERENCING NEW TABLE AS affected_rows
    FOR EACH STATEMENT EXECUTE FUNCTION datasource.bump_data_source_updated_at();

CREATE TRIGGER trg_bump_ds_updated_at_delete
    AFTER DELETE ON datasource.data_source_items
    REFERENCING OLD TABLE AS affected_rows
    FOR EACH STATEMENT EXECUTE FUNCTION datasource.bump_data_source_updated_at();
