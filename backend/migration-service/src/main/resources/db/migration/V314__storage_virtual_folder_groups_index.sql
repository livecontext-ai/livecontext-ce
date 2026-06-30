-- V314: supporting index for the VIRTUAL workflow folder tree (Phase 2b).
--
-- The Files browser computes virtual folders by GROUP-BY over the run-context columns of a workflow's
-- file rows that are NOT manually filed (parent_folder_id IS NULL): one folder per
-- workflow_id -> epoch -> spawn -> item_index. Every such aggregation (listVirtualGroups /
-- previewIdsForVirtualGroups / listVirtualLeafFiles in StorageExplorerRepository) filters the same
-- virtual-root partition: organization_id = ? AND status = 'ACTIVE' AND is_folder = FALSE AND
-- parent_folder_id IS NULL, then groups/scans by workflow_id, epoch, spawn, item_index.
--
-- This partial composite index covers that partition + the grouping columns so the aggregations are an
-- index scan instead of a filtered heap scan. Partial on the exact virtual-root predicate keeps it small
-- (manual-folder rows, filed rows and deleted rows are excluded). Additive - no data change.

CREATE INDEX IF NOT EXISTS idx_storage_virtual_groups
    ON storage.storage (organization_id, workflow_id, epoch, spawn, item_index)
    WHERE status = 'ACTIVE' AND is_folder = FALSE AND parent_folder_id IS NULL;
