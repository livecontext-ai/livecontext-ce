-- Run level in the virtual Files folder tree (workflow -> run -> epoch -> spawn -> iteration).
--
-- The Phase-2b grouping climbed workflow -> epoch directly (V314 index
-- idx_storage_virtual_groups on organization_id, workflow_id, epoch, spawn, item_index).
-- Epoch numbers restart per run, so a workflow with >=2 runs now groups by run_id first
-- (StorageExplorerRepository.listVirtualGroups at Level.RUN) and then by epoch pinned to a
-- run (AND s.run_id = :run). This index puts run_id between workflow_id and epoch so both
-- the RUN grouping (organization_id, workflow_id, run_id) and the run-pinned epoch/spawn/
-- iteration queries are index-served. The V314 index is kept: it stays optimal for the
-- collapsed single-run path, which groups by epoch without a run_id filter.
CREATE INDEX IF NOT EXISTS idx_storage_virtual_run_groups
    ON storage.storage (organization_id, workflow_id, run_id, epoch, spawn, item_index)
    WHERE status = 'ACTIVE' AND is_folder = FALSE AND parent_folder_id IS NULL;
