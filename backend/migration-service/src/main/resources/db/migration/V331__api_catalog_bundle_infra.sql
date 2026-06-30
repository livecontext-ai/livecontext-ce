-- =============================================================================
-- V331 - API catalog bundle infrastructure (cloud → CE distribution)
--
-- Mirrors the LLM model-bundle infra (V109/V114, schema agent) for the API
-- catalog (schema catalog): the cloud snapshots+signs the catalog (apis,
-- api_tools, parameters, responses, credential templates); CE installs fetch,
-- verify (Ed25519), and UPSERT-apply by UUID. Strategy locked in
-- the project docs:
--   * UPSERT in-place by UUID (never TRUNCATE) - execution path
--     (ToolContextService.loadToolContext) always finds its row.
--   * Soft-delete: rows absent from an applied bundle get deprecated_at=NOW()
--     (hidden from builder/search; execution by UUID still resolves). Only
--     rows with source IN ('import','bundle') are eligible - user-created
--     custom APIs are NEVER deprecated by a bundle.
--   * Bundles never touch auth.* (platform_credentials links by name survive).
-- =============================================================================
SET search_path TO catalog;

-- ── 1. Soft-delete marker on bundle-managed rows ──
ALTER TABLE apis        ADD COLUMN IF NOT EXISTS deprecated_at TIMESTAMPTZ;
ALTER TABLE api_tools   ADD COLUMN IF NOT EXISTS deprecated_at TIMESTAMPTZ;
ALTER TABLE credentials ADD COLUMN IF NOT EXISTS deprecated_at TIMESTAMPTZ;

COMMENT ON COLUMN apis.deprecated_at IS
    'Soft-delete by bundle apply: hidden from list/search, still executable by UUID. NULL = live.';
COMMENT ON COLUMN api_tools.deprecated_at IS
    'Soft-delete by bundle apply: hidden from list/search, still executable by UUID. NULL = live.';
COMMENT ON COLUMN credentials.deprecated_at IS
    'Soft-delete by bundle apply: hidden from templates list. NULL = live.';

-- ── 2. Bundle snapshots (cloud: built+signed here; CE: record of applied) ──
CREATE TABLE IF NOT EXISTS api_catalog_bundles (
    id              BIGSERIAL    PRIMARY KEY,
    version         BIGINT       NOT NULL UNIQUE,
    schema_version  INT          NOT NULL DEFAULT 1,
    checksum        CHAR(64)     NOT NULL,            -- SHA-256 hex of canonical payload
    signature       TEXT         NOT NULL,            -- Ed25519 base64
    signing_key_id  VARCHAR(50)  NOT NULL,
    issuer          VARCHAR(100) NOT NULL,
    api_count       INT          NOT NULL,
    tool_count      INT          NOT NULL,
    raw_bytes_size  INT          NOT NULL,
    -- Cloud keeps the signed payload so /latest serves directly from DB.
    -- CE leaves it NULL after apply (payload already merged into catalog.*).
    payload_gz      BYTEA,
    source_url      TEXT,
    imported_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activated_at    TIMESTAMPTZ,
    is_active       BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_api_catalog_bundles_one_active
    ON api_catalog_bundles ((1)) WHERE is_active = TRUE;

COMMENT ON TABLE api_catalog_bundles
    IS 'Immutable signed snapshots of the API catalog (cloud → CE distribution). Max one active.';

-- ── 3. CE-side sync bookkeeping (single row, mirrors agent.catalog_bundle_sync_status) ──
CREATE TABLE IF NOT EXISTS api_catalog_bundle_sync_status (
    id                   SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_applied_version BIGINT,
    last_applied_at      TIMESTAMPTZ,
    last_fetch_at        TIMESTAMPTZ,
    last_fetch_status    VARCHAR(32),  -- OK | HTTP_ERROR | NETWORK_ERROR | SIGNATURE_INVALID | CHECKSUM_INVALID | TRUST_UNKNOWN | APPLY_PARTIAL | APPLY_FAILED | DISABLED
    last_fetch_error     TEXT,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO api_catalog_bundle_sync_status (id) VALUES (1) ON CONFLICT DO NOTHING;

COMMENT ON TABLE api_catalog_bundle_sync_status
    IS 'CE-side last-known-good/last-error tracking for API catalog bundle sync. Single row (id=1).';
