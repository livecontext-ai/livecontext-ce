-- V305: allow workspace files to be assigned to projects.
-- Project assignment is metadata on storage.storage, not on the object-storage key.

ALTER TABLE storage.storage
    ADD COLUMN IF NOT EXISTS project_id UUID;

CREATE INDEX IF NOT EXISTS idx_storage_project_org_active
    ON storage.storage (project_id, organization_id, created_at DESC)
    WHERE project_id IS NOT NULL AND status = 'ACTIVE' AND file_name IS NOT NULL;

COMMENT ON COLUMN storage.storage.project_id IS
    'Optional project assignment for real files in storage.storage. NULL means the file is not attached to a project.';
