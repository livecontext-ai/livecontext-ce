-- CASA LC-054: named lc_live_ API keys (auth.api_keys, V398) never expired.
--
-- expires_at: when the key stops resolving. NULL = no expiry, which is what every key created
-- BEFORE this migration keeps (existing MCP clients must not break on deploy). Keys created from
-- now on get one: 365 days by default, and at most 365 days (enforced in ApiKeyService.createKey).
-- Resolution (ApiKeyService.resolveByPlaintextKey, shared by the cloud gateway and the CE
-- monolith) refuses a key whose expires_at is in the past.
ALTER TABLE auth.api_keys ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP NULL;
