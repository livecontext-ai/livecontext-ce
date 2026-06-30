-- ============================================================================
-- Real-Postgres DDL for ApiCatalogBundleSqlIntegrationTest.
--
-- Mirrors the FINAL production state of the catalog tables the bundle SQL
-- touches, consolidated from the migration-service migrations:
--   V12  (consolidated catalog schema: apis, api_tools, api_tool_parameters,
--         tool_responses, credentials, tool_credentials, categories)
--   V52  (api_tools.execution_spec/output_schema/execution_mode)
--   V82  (apis.icon_url, apis.source)
--   V87  (apis.api_version/documentation/rate_limits, api_tools.pagination/next_hint)
--   V103 (credentials.variant + UNIQUE(credential_name, variant))
--   V107 (tool_credentials.variant + UNIQUE(api_tool_id, credential_name, variant))
--   V166 (api_tools.required_scopes)
--   V331 (deprecated_at on apis/api_tools/credentials)
--
-- Why not schema-h2.sql: H2 2.3.232 in MODE=PostgreSQL cannot parse
-- `ON CONFLICT (…) DO UPDATE` (the three core upserts) nor
-- `INSERT … RETURNING` (category creation) - verified empirically - so the
-- merge SQL can only be exercised against real Postgres (Testcontainers,
-- same pattern as TypedExecutionPostgresE2ETest). The full Flyway chain is
-- not run here (it fails on the pgvector V12/V74 chain, see that test), so
-- this file restates only the bundle-relevant DDL.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS catalog;

CREATE TABLE IF NOT EXISTS catalog.api_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    icon VARCHAR(255),
    color VARCHAR(7),
    is_active BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER NOT NULL DEFAULT 0,
    slug VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
);

CREATE TABLE IF NOT EXISTS catalog.api_subcategories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES catalog.api_categories(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    icon VARCHAR(255),
    color VARCHAR(7),
    is_active BOOLEAN NOT NULL DEFAULT false,
    sort_order INTEGER NOT NULL DEFAULT 0,
    slug VARCHAR(255),
    icon_url VARCHAR(1000),
    icon_slug VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    UNIQUE(category_id, name)
);

CREATE TABLE IF NOT EXISTS catalog.apis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255),
    api_name VARCHAR(255) NOT NULL,
    api_slug VARCHAR(255),
    description TEXT NOT NULL,
    category_id UUID NOT NULL REFERENCES catalog.api_categories(id),
    subcategory_id UUID NOT NULL REFERENCES catalog.api_subcategories(id),
    base_url VARCHAR(1000) NOT NULL,
    healthcheck_endpoint VARCHAR(255),
    visibility VARCHAR(50) NOT NULL DEFAULT 'public',
    pricing_model VARCHAR(50) NOT NULL DEFAULT 'free',
    auth_type VARCHAR(50) DEFAULT 'none',
    auth_header_name VARCHAR(255),
    auth_header_value VARCHAR(1000),
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    is_public BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_local BOOLEAN NOT NULL DEFAULT false,
    icon_slug VARCHAR(100),
    credential_mode VARCHAR(20) DEFAULT 'user_key',
    platform_credential_name VARCHAR(255),
    icon_url VARCHAR(512),
    source VARCHAR(50) NOT NULL DEFAULT 'import',
    api_version VARCHAR(50),
    documentation VARCHAR(1000),
    rate_limits JSONB,
    deprecated_at TIMESTAMPTZ,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    version VARCHAR(20) DEFAULT '1.0.0',
    UNIQUE(created_by, api_name)
);

CREATE TABLE IF NOT EXISTS catalog.api_tools (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_id UUID NOT NULL REFERENCES catalog.apis(id) ON DELETE CASCADE,
    tool_slug VARCHAR(255),
    description TEXT NOT NULL,
    tool_name_id VARCHAR(255),
    method VARCHAR(10) NOT NULL,
    endpoint VARCHAR(1000) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    test_status VARCHAR(50),
    is_active BOOLEAN NOT NULL DEFAULT true,
    default_headers TEXT,
    protocol VARCHAR(32) NOT NULL DEFAULT 'HTTP',
    runtime_metadata TEXT,
    execution_spec JSONB,
    output_schema JSONB,
    execution_mode VARCHAR(20),
    pagination JSONB,
    next_hint TEXT,
    required_scopes JSONB,
    deprecated_at TIMESTAMPTZ,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    version VARCHAR(20) DEFAULT '1.0.0',
    UNIQUE(api_id, tool_name_id)
);

CREATE TABLE IF NOT EXISTS catalog.api_tool_parameters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES catalog.api_tools(id) ON DELETE CASCADE,
    parameter_type VARCHAR(20) NOT NULL,
    name VARCHAR(255) NOT NULL,
    data_type VARCHAR(100) NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT false,
    description TEXT,
    example_value VARCHAR(1000),
    default_value VARCHAR(1000),
    allowed_values TEXT,
    file_path VARCHAR(500),
    extras JSONB DEFAULT '{}'::jsonb,
    is_hidden BOOLEAN NOT NULL DEFAULT false,
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS catalog.tool_responses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tool_id UUID NOT NULL REFERENCES catalog.api_tools(id) ON DELETE CASCADE,
    name VARCHAR(255),
    description TEXT,
    schema JSONB,
    example TEXT,
    example_jsonb JSONB,
    format VARCHAR(20) NOT NULL,
    status_code INTEGER DEFAULT 200,
    is_default BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    structure_skeleton JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    CONSTRAINT unique_tool_response_name UNIQUE (tool_id, name),
    CONSTRAINT valid_status_code CHECK (status_code >= 200 AND status_code < 300),
    CONSTRAINT valid_format CHECK (format IN ('json', 'html', 'csv', 'text', 'xml', 'binary'))
);

CREATE TABLE IF NOT EXISTS catalog.credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credential_name VARCHAR(200) NOT NULL,
    variant VARCHAR(50) NOT NULL DEFAULT 'primary',
    display_name VARCHAR(255),
    description TEXT,
    credential_type VARCHAR(100),
    auth_type VARCHAR(50),
    test_endpoint VARCHAR(1000),
    documentation_url VARCHAR(1000),
    icon_url VARCHAR(500),
    icon_slug VARCHAR(100),
    properties JSONB DEFAULT '{}'::jsonb,
    extends_ JSONB DEFAULT '{}'::jsonb,
    metadata JSONB DEFAULT '{}'::jsonb,
    deprecated_at TIMESTAMPTZ,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    CONSTRAINT credentials_credential_name_variant_key UNIQUE (credential_name, variant)
);

CREATE TABLE IF NOT EXISTS catalog.tool_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES catalog.api_tools(id) ON DELETE CASCADE,
    credential_id UUID REFERENCES catalog.credentials(id) ON DELETE SET NULL,
    credential_name VARCHAR(200) NOT NULL,
    variant VARCHAR(50),
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    usage VARCHAR(50),
    condition JSONB,
    metadata JSONB,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    CONSTRAINT tool_credentials_tool_name_variant_key UNIQUE (api_tool_id, credential_name, variant)
);
