-- Extend custom API registration to support all api-migrations JSON schema fields.
-- All new columns are nullable for backwards compatibility.

-- API-level metadata columns
ALTER TABLE catalog.apis
    ADD COLUMN IF NOT EXISTS api_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS documentation VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS rate_limits JSONB;

-- Tool-level columns for pagination and next_hint
ALTER TABLE catalog.api_tools
    ADD COLUMN IF NOT EXISTS pagination JSONB,
    ADD COLUMN IF NOT EXISTS next_hint TEXT;
