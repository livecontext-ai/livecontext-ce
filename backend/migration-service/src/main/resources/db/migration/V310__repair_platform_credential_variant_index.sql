-- V82 created platform credential uniqueness before multi-variant credentials
-- existed, so it keyed only (integration_name, tenant). V103 added variant
-- support, but existing databases still kept the older expression index and
-- rejected a second platform-wide variant such as bearer_token next to api_key.

DROP INDEX IF EXISTS auth.idx_platform_cred_integration_tenant;

CREATE UNIQUE INDEX idx_platform_cred_integration_tenant
    ON auth.platform_credentials(integration_name, COALESCE(tenant_id, '__PLATFORM__'), variant);
