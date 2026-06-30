-- =============================================================================
-- V333 - Realign data_source_items.tenant_id with the parent datasource owner
--
-- Invariant: data_source_items.tenant_id (and data_source_vectors.tenant_id)
-- always carries the OWNER's tenantId = data_sources.tenant_id. The UI write
-- path (DataSourceEnhancedService.resolveAccessibleTenantId) has stamped the
-- owner since the org-scope work, but the workflow/agent CRUD path
-- (CrudExecutorService) and the internal bulk-insert stamped the CALLER's
-- tenant (workflow executor / org teammate). Those rows are invisible to every
-- owner-tenant-scoped read: empty data-table grid, nested views, exports,
-- column ops silently skipping them - while the writer's own reads still saw
-- them (prod example: datasource 38 "processed_emails", owner tenant 1, rows
-- stamped tenant 5 by a Gmail workflow → grid empty, agent read returned 9).
--
-- The code paths now stamp the owner (CrudExecutorService.execute,
-- InternalDataSourceController.bulkInsertItems); this migration repairs the
-- drifted rows already in place. Org-scope JOIN reads (post-V261 contract,
-- parent ds.organization_id) are unaffected: they never filtered on the item
-- tenant. Storage-usage counters keyed by item tenant shift to the owner -
-- consistent with where UI-path writes already attribute them; the daily
-- StorageReconciliationService absorbs the counter delta.
--
-- Side effects, accepted:
--   * The items UPDATE fires V249's statement-level trg_bump_ds_updated_at_update
--     → every datasource holding drifted rows gets updated_at = NOW() once
--     (one-time Activity-tab reshuffle at deploy).
--   * Rollout window: migration-service runs before the datasource-service
--     pods roll, so a not-yet-rolled pod can still write a handful of
--     caller-stamped rows after V333 ran. IS DISTINCT FROM makes this
--     statement re-runnable; any residue can be repaired by re-executing it
--     manually (or it stays invisible exactly as pre-fix, bounded to minutes).
-- =============================================================================
SET search_path TO datasource;

UPDATE data_source_items i
SET tenant_id = ds.tenant_id
FROM data_sources ds
WHERE ds.id = i.data_source_id
  AND i.tenant_id IS DISTINCT FROM ds.tenant_id;

UPDATE data_source_vectors v
SET tenant_id = ds.tenant_id
FROM data_sources ds
WHERE ds.id = v.data_source_id
  AND v.tenant_id IS DISTINCT FROM ds.tenant_id;
