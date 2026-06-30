-- V146: Storage trigger - preserve size_bytes for S3-backed rows.
--
-- The legacy `storage.trigger_update_storage_type` BEFORE INSERT/UPDATE
-- recomputes `size_bytes = octet_length(data) + octet_length(data_binary)
-- + octet_length(data_text)`. This is correct for inline-stored rows
-- (JSON / BINARY / TEXT) but WRONG for `storage_type='S3_FILE'` rows
-- whose content lives in MinIO/S3 - the in-row `data` is `'{}'` so the
-- trigger always overwrites the caller-supplied size with `2` (the byte
-- length of the stub JSON).
--
-- Symptom before this fix: every row inserted by
-- `StorageService.saveS3FileIndex(...)` ended up with `size_bytes=2`
-- regardless of the actual S3 object size, breaking the Files-tab size
-- column, the Storage Explorer breakdowns, and quota accounting for
-- catalog-tool binary outputs.
--
-- Fix: when `storage_type='S3_FILE'` (set by the application before the
-- trigger runs), keep whatever the application supplied. For all other
-- types the recompute path is unchanged.

CREATE OR REPLACE FUNCTION storage.trigger_update_storage_type()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.data_binary IS NOT NULL THEN
        NEW.storage_type := 'BINARY';
    ELSIF NEW.data_text IS NOT NULL AND (NEW.mime_type LIKE 'text/%' OR NEW.mime_type LIKE 'application/xml%') THEN
        NEW.storage_type := 'TEXT';
    ELSIF NEW.storage_type = 'S3_FILE' THEN
        -- preserve S3_FILE classification (set by app)
        NULL;
    ELSE
        NEW.storage_type := 'JSON';
    END IF;

    -- For S3-backed rows, leave size_bytes alone - the actual content
    -- size cannot be derived from in-row columns (data='{}'). Otherwise
    -- recompute from the stored payload as before.
    IF NEW.storage_type <> 'S3_FILE' THEN
        NEW.size_bytes := COALESCE(octet_length(NEW.data::text), 0)
                       + COALESCE(octet_length(NEW.data_binary), 0)
                       + COALESCE(octet_length(NEW.data_text), 0);
    END IF;

    RETURN NEW;
END;
$function$;

-- Backfill: catalog-binary rows previously written with size_bytes=2 are
-- effectively orphan from a usage / display standpoint. We leave them as
-- audit data - the cleanup sweep (CatalogBinaryCleanupJob) will reap them
-- on TTL anyway. Future inserts will record correct sizes.
