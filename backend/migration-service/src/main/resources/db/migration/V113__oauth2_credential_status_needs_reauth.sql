-- V113: Extend auth.credentials.status vocabulary with 'needs_reauth'.
--
-- The status column is a plain VARCHAR(50) (no Postgres ENUM, no CHECK),
-- so this migration is documentation-only for existing rows but adds a
-- partial index that the OAuth2 refresh scheduler sweep predicate
-- (CredentialRepository.findOAuth2CredentialsExpiringBefore) relies on
-- to skip credentials that are terminal or permanently errored.
--
-- Status vocabulary after this migration:
--   active         - normal, refresh pipeline considers it.
--   expiring       - access_token near expiry; ready for proactive refresh.
--   error          - TERMINAL_CONFIG: admin must fix template/secret.
--   needs_reauth   - TERMINAL_USER: refresh_token revoked/expired by provider
--                    (RFC 6749 invalid_grant / unauthorized_client). User
--                    must re-OAuth. Tokens are scrubbed by OAuth2Service on
--                    terminal release - do NOT treat as transiently failed.
--
-- No backfill: existing 'error' rows are left untouched. The classifier
-- only writes 'needs_reauth' on new terminal-user events.

SET lock_timeout = '10s';
SET statement_timeout = '120s';
SET search_path TO auth;

COMMENT ON COLUMN auth.credentials.status IS
    'Credential lifecycle: active | expiring | error (admin config) | needs_reauth (user re-OAuth required).';

-- Partial index to speed up the sweep predicate which filters
--   status NOT IN ('error','needs_reauth')
-- Also narrows the OAuth2-expiring scan to the actively-refreshable subset.
CREATE INDEX IF NOT EXISTS idx_auth_credentials_active_status
    ON auth.credentials(status)
    WHERE status IN ('active', 'expiring');
