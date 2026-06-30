-- CE-side catalog bundle sync status.
-- Single-row bookkeeping (PK=1) for the last fetch attempt. `catalog_bundles`
-- persists successful imports; this tracks the *fetch* side which can fail
-- without ever producing a bundle row (network down, signature invalid, …).
-- Makes the "/sync-status" admin endpoint cheap and keeps failure state
-- surviving restarts without extra log scraping.
SET search_path TO agent;

CREATE TABLE IF NOT EXISTS catalog_bundle_sync_status (
    id                      SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    -- Last successful apply: mirrors the active bundle's version for quick reads.
    last_applied_version    BIGINT,
    last_applied_at         TIMESTAMPTZ,
    -- Fetch-side tracking. last_fetch_error is null on success.
    last_fetch_at           TIMESTAMPTZ,
    last_fetch_status       VARCHAR(32),           -- OK | HTTP_ERROR | NETWORK_ERROR | SIGNATURE_INVALID | CHECKSUM_INVALID | TRUST_UNKNOWN | APPLY_FAILED
    last_fetch_error        TEXT,
    consecutive_failures    INT          NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Seed the single row so UPDATE semantics are guaranteed without upsert.
INSERT INTO catalog_bundle_sync_status (id) VALUES (1) ON CONFLICT DO NOTHING;

COMMENT ON TABLE catalog_bundle_sync_status
    IS 'CE-side last-known-good/last-error tracking for cloud bundle sync. Single row (id=1).';
