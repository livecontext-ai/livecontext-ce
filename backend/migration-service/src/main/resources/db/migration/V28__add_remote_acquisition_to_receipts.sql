SET search_path TO publication;

ALTER TABLE publication_receipts
    ADD COLUMN IF NOT EXISTS remote_acquisition BOOLEAN NOT NULL DEFAULT FALSE;
