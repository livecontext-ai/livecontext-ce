-- V382: widen auth.users.api_key_hint from VARCHAR(10) to VARCHAR(20).
--
-- ApiKeyService builds the hint as "lc_live_" + "..." + <last 4 chars> = 15 chars,
-- which never fit the original VARCHAR(10) from V3: every POST /api/auth/api-keys/regenerate
-- failed with a 500 (value too long). Latent since the API-keys page was hidden; surfaced
-- by the MCP server feature, whose settings page drives key generation.
ALTER TABLE auth.users ALTER COLUMN api_key_hint TYPE VARCHAR(20);
