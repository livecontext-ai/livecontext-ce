-- V147: Widen storage_type CHECK to allow 'S3_FILE'.
--
-- Companion to V146. The legacy CHECK constraint
-- `storage_storage_type_check` restricted storage_type to
-- {JSON, BINARY, TEXT}. `saveS3FileIndex` has been setting
-- storage_type='S3_FILE' for years, but it survived because the
-- BEFORE INSERT trigger was unconditionally rewriting the value to
-- 'JSON' before the constraint check fired (and recomputing size_bytes
-- from the empty `data` JSON, which is the size_bytes=2 bug fixed by
-- V146). With V146 in place the trigger correctly preserves S3_FILE,
-- but the constraint then rejects every insert. This migration widens
-- the constraint to match the application's actual contract.

ALTER TABLE storage.storage DROP CONSTRAINT IF EXISTS storage_storage_type_check;
ALTER TABLE storage.storage ADD CONSTRAINT storage_storage_type_check
    CHECK (storage_type IN ('JSON', 'BINARY', 'TEXT', 'S3_FILE'));
