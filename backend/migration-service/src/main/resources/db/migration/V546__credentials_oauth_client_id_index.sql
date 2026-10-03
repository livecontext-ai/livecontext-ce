-- V546: expression index for the cross-tenant OAuth sibling lookup (CASA LC-065).
--
-- CredentialRepository.findUsableOAuth2ByClientId decides whether deleting a credential may revoke
-- the provider grant, by looking for another live credential on the same OAuth client (the grant is
-- per provider account x client, across tenants). It filters on
-- COALESCE(credential_data->>'oauth_client_id', credential_data->>'client_id'); without this index
-- that is a scan of every OAuth2 row on each delete.
--
-- Runs outside Flyway's transaction because CREATE INDEX CONCURRENTLY cannot run inside one (same
-- pattern as V149). Lock posture: SHARE UPDATE EXCLUSIVE, reads and writes continue during the build.
-- On crash PG leaves the index INVALID: drop it, `flyway repair`, then re-run.

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_auth_credentials_oauth2_client_id
    ON auth.credentials ((COALESCE(credential_data->>'oauth_client_id', credential_data->>'client_id')))
    WHERE type = 'OAuth2';
