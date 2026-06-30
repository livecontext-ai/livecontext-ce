-- V313: manual folders in the Files browser.
--
-- A folder is a lightweight row in storage.storage (is_folder = TRUE, the folder name lives in
-- file_name, no s3_key / no data payload). A real file is "filed" into a manual folder via
-- parent_folder_id. Both are pure metadata on the row, NOT on the object-storage key - the s3_key
-- stays immutable, so moving/renaming never re-uploads.
--
-- Workflow / epoch / spawn / iteration "folders" remain VIRTUAL: they are computed from the
-- run-context columns already on the row (workflow_id, epoch, spawn, item_index). Only folders a
-- user (or agent) creates by hand live as rows here. A file with parent_folder_id set has been
-- manually re-filed and therefore leaves its virtual workflow location.
--
-- No FK on parent_folder_id (consistent with the app-managed lifecycle elsewhere in this schema):
-- recursive delete / re-parent on folder removal is enforced in the service layer.

ALTER TABLE storage.storage
    ADD COLUMN IF NOT EXISTS is_folder BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS parent_folder_id UUID;

-- List a folder's direct children (and the root: parent_folder_id IS NULL), org-scoped + active.
CREATE INDEX IF NOT EXISTS idx_storage_folder_children
    ON storage.storage (organization_id, parent_folder_id, is_folder, created_at DESC)
    WHERE status = 'ACTIVE';

-- Fast enumeration of folder rows for an org (root folder listing, name-collision checks).
CREATE INDEX IF NOT EXISTS idx_storage_folders_org
    ON storage.storage (organization_id, parent_folder_id)
    WHERE is_folder = TRUE AND status = 'ACTIVE';

COMMENT ON COLUMN storage.storage.is_folder IS
    'TRUE = a user/agent-created manual folder row (file_name holds the folder name; no s3_key/data). FALSE = a normal storage row.';
COMMENT ON COLUMN storage.storage.parent_folder_id IS
    'Manual folder this row is filed under (references a storage.storage row with is_folder = TRUE). NULL = top level / not manually filed. Workflow/epoch grouping is virtual (run-context columns), not this.';
