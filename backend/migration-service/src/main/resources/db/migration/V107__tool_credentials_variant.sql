-- V107: Propagate the `variant` column from V103 to catalog.tool_credentials.
--
-- V103 introduced (credential_name, variant) uniqueness on catalog.credentials and
-- auth.platform_credentials, but forgot catalog.tool_credentials - which still
-- held UNIQUE (api_tool_id, credential_name) from V84. That constraint blocks
-- multi-variant APIs: the 2nd variant insert for any tool fails with
--   duplicate key value violates unique constraint "uq_tool_credentials_tool_cred_name"
-- and the importer leaves the API with partial credential wiring.
--
-- Fix: add a `variant` column (backfilled from catalog.credentials.variant via
-- credential_id FK, falling back to 'primary') and swap the unique index to
-- (api_tool_id, credential_name, variant).

ALTER TABLE catalog.tool_credentials
    ADD COLUMN IF NOT EXISTS variant VARCHAR(50);

-- Backfill from the credential row this link points at; rows whose FK was
-- ON DELETE SET NULL to NULL fall back to 'primary'.
UPDATE catalog.tool_credentials tc
SET variant = COALESCE(c.variant, 'primary')
FROM catalog.credentials c
WHERE tc.credential_id = c.id
  AND tc.variant IS NULL;

UPDATE catalog.tool_credentials
SET variant = 'primary'
WHERE variant IS NULL;

ALTER TABLE catalog.tool_credentials
    ALTER COLUMN variant SET NOT NULL;

ALTER TABLE catalog.tool_credentials
    ALTER COLUMN variant SET DEFAULT 'primary';

-- Swap the unique constraint: drop V84's (api_tool_id, credential_name) and
-- replace it with (api_tool_id, credential_name, variant). Keep the name stable
-- for ON CONFLICT clauses that reference it.
ALTER TABLE catalog.tool_credentials
    DROP CONSTRAINT IF EXISTS uq_tool_credentials_tool_cred_name;

ALTER TABLE catalog.tool_credentials
    ADD CONSTRAINT uq_tool_credentials_tool_cred_name_variant
    UNIQUE (api_tool_id, credential_name, variant);

CREATE INDEX IF NOT EXISTS idx_tool_credentials_variant
    ON catalog.tool_credentials (credential_name, variant);
