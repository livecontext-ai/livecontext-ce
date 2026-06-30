-- V103: Multi-variant credentials.
--
-- An API can now expose more than one authentication method (OAuth2 + PAT,
-- API key + custom header, ...). See scripts/api-migrations/SCHEMA.md,
-- section "Multi-variant auth - the array format".
--
-- Design: one row per (API, variant) in both the catalog and the admin-side
-- platform credentials tables.
--
--   * catalog.credentials       - admin-visible credential template; one row
--                                 per variant so credential.auth_type and the
--                                 properties JSON truly describe that variant.
--                                 Grouped in the UI by credential_name.
--
--   * auth.platform_credentials - actual platform-owned secrets. One row per
--                                 variant so is_enabled is per-variant (admin
--                                 can disable OAuth while keeping PAT live)
--                                 and secrets of different shapes don't share
--                                 a row.
--
-- catalog.tool_credentials.credential_id is a FK to catalog.credentials(id)
-- which is still unique per row, so no schema change there - each tool_credential
-- link naturally points to exactly one variant.
--
-- Backfill: variant = auth_type for every existing row. The post-condition is
-- (credential_name | integration_name, variant) UNIQUE, which reduces to the
-- old invariant for single-variant APIs.

-- ---------------------------------------------------------------------------
-- 1. catalog.credentials
-- ---------------------------------------------------------------------------

ALTER TABLE catalog.credentials
    ADD COLUMN IF NOT EXISTS variant VARCHAR(50);

UPDATE catalog.credentials
SET variant = COALESCE(NULLIF(auth_type, ''), 'primary')
WHERE variant IS NULL;

ALTER TABLE catalog.credentials
    ALTER COLUMN variant SET NOT NULL;

ALTER TABLE catalog.credentials
    ALTER COLUMN variant SET DEFAULT 'primary';

-- Replace the legacy UNIQUE(credential_name) with UNIQUE(credential_name, variant).
-- Postgres auto-names the old constraint credentials_credential_name_key; we look
-- it up by target columns rather than by name so the migration is reversible on
-- databases whose constraint was (re)created under a different name.
DO $$
DECLARE
    legacy_name TEXT;
BEGIN
    SELECT conname INTO legacy_name
    FROM pg_constraint
    WHERE conrelid = 'catalog.credentials'::regclass
      AND contype = 'u'
      AND conkey = ARRAY[(
          SELECT attnum FROM pg_attribute
          WHERE attrelid = 'catalog.credentials'::regclass AND attname = 'credential_name'
      )]::smallint[];

    IF legacy_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE catalog.credentials DROP CONSTRAINT %I', legacy_name);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'catalog.credentials'::regclass
          AND conname = 'credentials_credential_name_variant_key'
    ) THEN
        ALTER TABLE catalog.credentials
            ADD CONSTRAINT credentials_credential_name_variant_key
            UNIQUE (credential_name, variant);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_credentials_name_variant
    ON catalog.credentials (credential_name, variant);

-- ---------------------------------------------------------------------------
-- 2. auth.platform_credentials
-- ---------------------------------------------------------------------------

ALTER TABLE auth.platform_credentials
    ADD COLUMN IF NOT EXISTS variant VARCHAR(50);

UPDATE auth.platform_credentials
SET variant = COALESCE(NULLIF(auth_type, ''), 'primary')
WHERE variant IS NULL;

ALTER TABLE auth.platform_credentials
    ALTER COLUMN variant SET NOT NULL;

ALTER TABLE auth.platform_credentials
    ALTER COLUMN variant SET DEFAULT 'primary';

DO $$
DECLARE
    legacy_name TEXT;
BEGIN
    SELECT conname INTO legacy_name
    FROM pg_constraint
    WHERE conrelid = 'auth.platform_credentials'::regclass
      AND contype = 'u'
      AND conkey = ARRAY[(
          SELECT attnum FROM pg_attribute
          WHERE attrelid = 'auth.platform_credentials'::regclass AND attname = 'integration_name'
      )]::smallint[];

    IF legacy_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE auth.platform_credentials DROP CONSTRAINT %I', legacy_name);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'auth.platform_credentials'::regclass
          AND conname = 'platform_credentials_integration_variant_key'
    ) THEN
        ALTER TABLE auth.platform_credentials
            ADD CONSTRAINT platform_credentials_integration_variant_key
            UNIQUE (integration_name, variant);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_platform_credentials_integration_variant
    ON auth.platform_credentials (integration_name, variant);
