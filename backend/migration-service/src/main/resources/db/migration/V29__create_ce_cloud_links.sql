-- CE Cloud Account Linking: stores OAuth refresh tokens for cloud marketplace access
CREATE TABLE IF NOT EXISTS publication.ce_cloud_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL UNIQUE,
    cloud_user_id VARCHAR(255) NOT NULL,
    cloud_username VARCHAR(255) NOT NULL,
    encrypted_refresh_token VARCHAR(4096) NOT NULL,
    cached_access_token VARCHAR(4096),
    token_expires_at TIMESTAMP,
    linked_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMP
);
