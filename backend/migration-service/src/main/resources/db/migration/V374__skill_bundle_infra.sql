-- Skill bundle distribution (cloud -> CE), sibling of the catalog/model bundle system.
-- Cloud is the single source of truth for admin-managed GLOBAL skills (is_global=true).
-- Cloud builds a signed Ed25519 bundle of every global skill; CE installs pull it on a
-- cron + on startup, verify the signature offline against the SHARED trust root
-- (catalog.bundle.trusted-keys), and UPSERT-apply it into agent.skills as read-only,
-- cloud-owned global rows. CE admins cannot edit a bundle-applied skill (source_bundle_key
-- IS NOT NULL); end users hide it for themselves via the existing per-user override layer.
SET search_path TO agent;

-- 1. Stable provenance key on each CE-applied bundle skill = the cloud skill's UUID.
--    NULL on cloud-authored rows (admin-created globals + personal/default skills), so the
--    read-only gate (source_bundle_key IS NOT NULL) only ever fires on CE-applied copies.
ALTER TABLE skills ADD COLUMN IF NOT EXISTS source_bundle_key VARCHAR(64);

-- Idempotent upsert key: at most one CE row per cloud skill. Partial so the millions of
-- NULL (non-bundle) rows are not indexed and existing uniqueness is untouched.
CREATE UNIQUE INDEX IF NOT EXISTS idx_skills_source_bundle_key
    ON skills (source_bundle_key)
    WHERE source_bundle_key IS NOT NULL;

-- 2. Built bundles (cloud side builds + activates; CE records the applied bundle here too,
--    using is_active + version match for apply idempotency). Mirrors catalog_bundles: at most
--    one is_active=true row.
CREATE TABLE IF NOT EXISTS skill_bundles (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT       NOT NULL UNIQUE,
    schema_version    INT          NOT NULL DEFAULT 1,
    checksum          CHAR(64)     NOT NULL,           -- SHA-256 hex of canonical payload
    signature         TEXT         NOT NULL,           -- base64 Ed25519 over the decoded payload bytes
    signing_key_id    VARCHAR(50)  NOT NULL,
    issuer            VARCHAR(100) NOT NULL,
    skill_count       INT          NOT NULL,
    raw_bytes_size    INT          NOT NULL,
    source_url        TEXT,
    imported_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activated_at      TIMESTAMPTZ,
    is_active         BOOLEAN      NOT NULL DEFAULT FALSE
);

-- At most one active bundle at a time (concurrent activate -> unique violation -> 409).
CREATE UNIQUE INDEX IF NOT EXISTS idx_skill_bundles_one_active
    ON skill_bundles ((1)) WHERE is_active = TRUE;

-- 3. CE-side sync bookkeeping. Single row (id=1). Tracks the FETCH side, which can fail
--    without ever producing an apply (network down, signature invalid, not linked, ...).
CREATE TABLE IF NOT EXISTS skill_bundle_sync_status (
    id                      SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_applied_version    BIGINT,
    last_applied_at         TIMESTAMPTZ,
    last_fetch_at           TIMESTAMPTZ,
    last_fetch_status       VARCHAR(32),   -- OK | NOT_LINKED | NO_ACTIVE | HTTP_ERROR | NETWORK_ERROR | SIGNATURE_INVALID | CHECKSUM_INVALID | TRUST_UNKNOWN | APPLY_FAILED
    last_fetch_error        TEXT,
    consecutive_failures    INT          NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO skill_bundle_sync_status (id) VALUES (1) ON CONFLICT DO NOTHING;

COMMENT ON TABLE skill_bundles
    IS 'Cloud-side signed snapshots of global skills (is_global=true). At most one active row.';
COMMENT ON TABLE skill_bundle_sync_status
    IS 'CE-side last-known-good/last-error tracking for cloud skill-bundle sync. Single row (id=1).';
COMMENT ON COLUMN skills.source_bundle_key
    IS 'Cloud skill UUID for a CE-applied global bundle skill. NOT NULL => read-only, cloud-managed.';
