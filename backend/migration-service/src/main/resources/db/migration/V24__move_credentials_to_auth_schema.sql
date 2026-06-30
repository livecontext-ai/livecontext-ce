-- V24: Move credential tables from orchestrator schema to auth schema
-- Phase 7: Credential extraction to auth-service

-- 1. Create credentials table in auth schema
CREATE TABLE IF NOT EXISTS auth.credentials (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    integration VARCHAR(255),
    type VARCHAR(50) NOT NULL,
    environment VARCHAR(50) NOT NULL DEFAULT 'Production',
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    description TEXT,
    credential_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    scopes TEXT[],
    tags TEXT[],
    owner VARCHAR(255),
    icon_url VARCHAR(500),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    resource_index INTEGER NOT NULL DEFAULT 0,
    last_used TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_auth_credentials_tenant_id ON auth.credentials(tenant_id);

-- 2. Create platform_credentials table in auth schema
CREATE TABLE IF NOT EXISTS auth.platform_credentials (
    id BIGSERIAL PRIMARY KEY,
    integration_name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    auth_type VARCHAR(50) NOT NULL DEFAULT 'oauth2',
    client_id VARCHAR(500),
    client_secret TEXT,
    api_key TEXT,
    username VARCHAR(255),
    password TEXT,
    auth_url VARCHAR(500),
    token_url VARCHAR(500),
    default_scopes TEXT,
    icon_slug VARCHAR(100),
    category VARCHAR(100),
    description TEXT,
    custom_fields JSONB DEFAULT '{}',
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_auth_platform_credentials_category_enabled
    ON auth.platform_credentials(category, is_enabled) WHERE is_enabled = true;

-- 3. Create platform_credential_endpoints table in auth schema
CREATE TABLE IF NOT EXISTS auth.platform_credential_endpoints (
    id BIGSERIAL PRIMARY KEY,
    platform_credential_id BIGINT NOT NULL REFERENCES auth.platform_credentials(id) ON DELETE CASCADE,
    tool_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255),
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_platform_credential_endpoints UNIQUE (platform_credential_id, tool_id)
);

-- 4. Copy data from orchestrator schema to auth schema
INSERT INTO auth.credentials (id, tenant_id, name, integration, type, environment, status, description,
    credential_data, scopes, tags, owner, icon_url, is_default, resource_index, last_used, created_at, updated_at)
SELECT id, tenant_id, name, integration, type, environment, status, description,
    credential_data, scopes, tags, owner, icon_url, is_default, resource_index, last_used, created_at, updated_at
FROM orchestrator.credentials
ON CONFLICT DO NOTHING;

INSERT INTO auth.platform_credentials (id, integration_name, display_name, auth_type,
    client_id, client_secret, api_key, username, password, auth_url, token_url, default_scopes,
    icon_slug, category, description, custom_fields, is_enabled, created_at, updated_at, created_by)
SELECT id, integration_name, display_name, auth_type,
    client_id, client_secret, api_key, username, password, auth_url, token_url, default_scopes,
    icon_slug, category, description, custom_fields, is_enabled, created_at, updated_at, created_by
FROM orchestrator.platform_credentials
ON CONFLICT DO NOTHING;

INSERT INTO auth.platform_credential_endpoints (id, platform_credential_id, tool_id, tool_name, is_enabled, created_at, updated_at)
SELECT id, platform_credential_id, tool_id, tool_name, is_enabled, created_at, updated_at
FROM orchestrator.platform_credential_endpoints
ON CONFLICT DO NOTHING;

-- 5. Reset sequences to be higher than copied data
SELECT setval('auth.credentials_id_seq', COALESCE((SELECT MAX(id) FROM auth.credentials), 0) + 1, false);
SELECT setval('auth.platform_credentials_id_seq', COALESCE((SELECT MAX(id) FROM auth.platform_credentials), 0) + 1, false);
SELECT setval('auth.platform_credential_endpoints_id_seq', COALESCE((SELECT MAX(id) FROM auth.platform_credential_endpoints), 0) + 1, false);

-- NOTE: orchestrator.credentials, orchestrator.platform_credentials, orchestrator.platform_credential_endpoints
-- tables are preserved for now (cleanup in a future migration after verifying auth-service works)
