-- CASA LC-054 (follow-up to V530): every lc_live_ key gets an expiry.
--
-- 1. The legacy single key on auth.users (POST /api/auth/api-keys/regenerate) gets its own
--    expires_at, set by the service on every regenerate (365 days, same policy as named keys).
-- 2. Keys that already exist get one too, but never a surprise: created + 365 days, and in any
--    case not earlier than 90 days from this migration, so every connected MCP client keeps
--    working for at least 90 days and its owner can see the date in Settings > MCP Server first.
-- Fail fast instead of queueing behind a long transaction; RESET below so the session settings do
-- not leak into the next migration on this pooled connection (same pattern as V535/V538).
SET lock_timeout = '5s';
SET statement_timeout = '60s';

ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS api_key_expires_at TIMESTAMPTZ NULL;

UPDATE auth.users
   SET api_key_expires_at = GREATEST(COALESCE(api_key_created_at, now()) + INTERVAL '365 days',
                                     now() + INTERVAL '90 days')
 WHERE api_key_hash IS NOT NULL
   AND api_key_expires_at IS NULL;

UPDATE auth.api_keys
   SET expires_at = GREATEST(COALESCE(created_at, now()::timestamp) + INTERVAL '365 days',
                             now()::timestamp + INTERVAL '90 days')
 WHERE expires_at IS NULL
   AND revoked_at IS NULL;

RESET lock_timeout;
RESET statement_timeout;
