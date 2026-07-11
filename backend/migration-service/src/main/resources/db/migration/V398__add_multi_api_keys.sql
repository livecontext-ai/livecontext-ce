-- Multiple named lc_live_ API keys per user, each with optional tool scopes.
--
-- Why: the single key on auth.users (api_key_hash/api_key_hint, V382) is
-- all-or-nothing: one plaintext, full access, rotating it breaks every client.
-- External MCP clients need PER-CLIENT keys (name them, revoke one without
-- touching the others) and LEAST-PRIVILEGE keys (restrict a key to a list of
-- MCP tool names). The legacy users-table key keeps working unchanged as a
-- full-access key; resolution checks it first, then this table.
--
-- scopes: comma-separated MCP tool names the key may call; NULL = full access
-- (an empty string is never stored - creation rejects an empty scope list).
-- key_hash: HMAC-SHA256 hex of the plaintext (same hashing as users.api_key_hash).
-- key_hint: "lc_live_..." + last 4 chars, shown in the UI list.
-- revoked_at: soft revoke - a non-null value stops resolution immediately
-- (the gateway cache is busted by providerId on revoke, same as rotation).
-- last_used_at: best-effort observability, updated at most once per 15 min.

CREATE TABLE auth.api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    key_hint VARCHAR(20) NOT NULL,
    scopes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    last_used_at TIMESTAMP NULL,
    revoked_at TIMESTAMP NULL,
    -- Structural guarantee that an empty string can never masquerade as
    -- NULL (= full access): a scoped key must carry a real scope list.
    CONSTRAINT api_keys_scopes_not_blank CHECK (scopes IS NULL OR length(scopes) > 0)
);

CREATE INDEX idx_api_keys_user_id ON auth.api_keys(user_id);
