-- H2-compatible schema for Catalog Service integration tests
-- Simplified version of production PostgreSQL schema

-- Categories
CREATE TABLE IF NOT EXISTS api_categories (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    icon VARCHAR(255),
    color VARCHAR(7),
    sort_order INTEGER NOT NULL DEFAULT 0,
    slug VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- Subcategories
CREATE TABLE IF NOT EXISTS api_subcategories (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES api_categories(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    icon VARCHAR(255),
    color VARCHAR(7),
    sort_order INTEGER NOT NULL DEFAULT 0,
    slug VARCHAR(255),
    icon_url VARCHAR(1000),
    icon_slug VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    UNIQUE(category_id, name)
);

-- APIs
CREATE TABLE IF NOT EXISTS apis (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    created_by VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255),
    api_name VARCHAR(255) NOT NULL,
    api_slug VARCHAR(255),
    description TEXT NOT NULL,
    category_id UUID NOT NULL REFERENCES api_categories(id),
    subcategory_id UUID NOT NULL REFERENCES api_subcategories(id),
    base_url VARCHAR(1000) NOT NULL,
    healthcheck_endpoint VARCHAR(255),
    visibility VARCHAR(50) NOT NULL DEFAULT 'public',
    pricing_model VARCHAR(50) NOT NULL DEFAULT 'free',
    auth_type VARCHAR(50) DEFAULT 'none',
    auth_header_name VARCHAR(255),
    auth_header_value VARCHAR(1000),
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    is_public BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT false,
    is_local BOOLEAN NOT NULL DEFAULT false,
    icon_slug VARCHAR(100),
    icon_url VARCHAR(1000),
    platform_credential_name VARCHAR(255),
    api_version VARCHAR(50),
    documentation TEXT,
    rate_limits TEXT,
    source VARCHAR(50) DEFAULT 'import',
    deprecated_at TIMESTAMP WITH TIME ZONE,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    version VARCHAR(20) DEFAULT '1.0.0',
    UNIQUE(created_by, api_name)
);

-- API Tools
CREATE TABLE IF NOT EXISTS api_tools (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_id UUID NOT NULL REFERENCES apis(id) ON DELETE CASCADE,
    tool_slug VARCHAR(255),
    description TEXT NOT NULL,
    tool_name_id VARCHAR(255),
    method VARCHAR(10) NOT NULL,
    endpoint VARCHAR(1000) NOT NULL,
    protocol VARCHAR(32) NOT NULL DEFAULT 'HTTP',
    default_headers TEXT,
    runtime_metadata TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    test_status VARCHAR(50),
    execution_mode VARCHAR(32),
    execution_spec TEXT,
    output_schema TEXT,
    pagination TEXT,
    next_hint VARCHAR(1000),
    is_active BOOLEAN NOT NULL DEFAULT false,
    required_scopes TEXT,
    deprecated_at TIMESTAMP WITH TIME ZONE,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    version VARCHAR(20) DEFAULT '1.0.0'
);

-- API Tool Parameters
CREATE TABLE IF NOT EXISTS api_tool_parameters (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    parameter_type VARCHAR(20) NOT NULL,
    name VARCHAR(255) NOT NULL,
    data_type VARCHAR(255) NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT false,
    description TEXT,
    example_value VARCHAR(1000),
    default_value VARCHAR(1000),
    allowed_values TEXT,
    file_path VARCHAR(500),
    extras TEXT,
    is_hidden BOOLEAN DEFAULT false,
    created_at BIGINT NOT NULL
);

-- API Tool Monetization
CREATE TABLE IF NOT EXISTS api_tool_monetization (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    monetization_type VARCHAR(20) NOT NULL,
    plan_name VARCHAR(20),
    rate_limit_requests INTEGER,
    rate_limit_period VARCHAR(20),
    free_requests INTEGER,
    free_requests_type VARCHAR(20),
    mau_value INTEGER,
    price_per_mau DECIMAL(10,6),
    calls INTEGER DEFAULT 1,
    quota INTEGER,
    price DECIMAL(10,2),
    overusage_cost DECIMAL(10,6),
    hard_limit BOOLEAN DEFAULT true,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    version VARCHAR(20) DEFAULT '1.0.0',
    UNIQUE(api_tool_id, monetization_type, plan_name)
);

-- Tool Categories
CREATE TABLE IF NOT EXISTS tool_categories (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    icon VARCHAR(50),
    color VARCHAR(20),
    sort_order INTEGER DEFAULT 0,
    slug VARCHAR(255),
    icon_url VARCHAR(1000),
    is_active BOOLEAN NOT NULL DEFAULT false,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- Tool Names
CREATE TABLE IF NOT EXISTS tool_names (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    tool_category_id UUID NOT NULL REFERENCES tool_categories(id) ON DELETE CASCADE,
    subcategory_id UUID REFERENCES api_subcategories(id) ON DELETE SET NULL,
    method VARCHAR(10) NOT NULL DEFAULT 'GET',
    endpoint_pattern VARCHAR(500),
    run_scope VARCHAR(10) NOT NULL DEFAULT 'external',
    requires_user_credentials BOOLEAN NOT NULL DEFAULT false,
    slug VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT false,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    UNIQUE(name, tool_category_id)
);

-- Tool Responses
CREATE TABLE IF NOT EXISTS tool_responses (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    name VARCHAR(255),
    description TEXT,
    schema TEXT,
    example TEXT,
    example_jsonb TEXT,
    format VARCHAR(20) NOT NULL,
    status_code INTEGER DEFAULT 200,
    is_default BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    structure_skeleton TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255)
);

-- Tool Signals
CREATE TABLE IF NOT EXISTS tool_signals (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    tool_id UUID NOT NULL,
    signal_type VARCHAR(50) NOT NULL,
    signal_value TEXT,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- Tool Embeddings
CREATE TABLE IF NOT EXISTS tool_embeddings (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    tool_id UUID NOT NULL,
    embedding_text TEXT,
    embedding_vector TEXT,
    model VARCHAR(100),
    dimensions INTEGER,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- Tool Next Hints
CREATE TABLE IF NOT EXISTS tool_next_hint (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    tool_name_id UUID NOT NULL,
    next_tool_name_id UUID NOT NULL,
    weight DOUBLE PRECISION DEFAULT 1.0,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- API Tool SQL Config
CREATE TABLE IF NOT EXISTS api_tool_sql_configs (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    dialect VARCHAR(100),
    query_template TEXT NOT NULL,
    parameter_mapping TEXT,
    default_schema VARCHAR(255),
    default_table VARCHAR(255),
    result_mode VARCHAR(50),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    UNIQUE (api_tool_id)
);

-- Mapping Definitions
CREATE TABLE IF NOT EXISTS mapping_definitions (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    tool_id UUID NOT NULL,
    source_format VARCHAR(20) NOT NULL,
    target_format VARCHAR(20) NOT NULL DEFAULT 'json',
    mapping_spec TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- Mapping Versions
CREATE TABLE IF NOT EXISTS mapping_versions (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    mapping_definition_id UUID NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    mapping_spec TEXT,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    created_by VARCHAR(255)
);

-- Lexical Search Index
CREATE TABLE IF NOT EXISTS lexical_search_index (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    tool_id UUID NOT NULL,
    tool_name VARCHAR(255),
    tool_description TEXT,
    api_name VARCHAR(255),
    api_description TEXT,
    category VARCHAR(255),
    subcategory VARCHAR(255),
    provider VARCHAR(255),
    parameters TEXT,
    signals TEXT,
    enriched_text TEXT,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- Search Feedback
CREATE TABLE IF NOT EXISTS search_feedback (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    query TEXT NOT NULL,
    selected_tool_id UUID,
    session_id VARCHAR(255),
    user_id VARCHAR(255),
    rank_position INTEGER,
    search_type VARCHAR(50),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- Tool Credentials Metadata
CREATE TABLE IF NOT EXISTS tool_credentials_metadata (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_id UUID NOT NULL,
    credential_name VARCHAR(255) NOT NULL,
    credential_type VARCHAR(50) NOT NULL,
    description TEXT,
    is_required BOOLEAN DEFAULT true,
    icon_slug VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000
);

-- AMQP Config
CREATE TABLE IF NOT EXISTS api_tool_amqp_configs (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    exchange VARCHAR(255),
    routing_key VARCHAR(255),
    queue VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    UNIQUE (api_tool_id)
);

-- Kafka Config
CREATE TABLE IF NOT EXISTS api_tool_kafka_configs (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    topic VARCHAR(255),
    key_field VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    UNIQUE (api_tool_id)
);

-- MQTT Config
CREATE TABLE IF NOT EXISTS api_tool_mqtt_configs (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    topic VARCHAR(255),
    qos INTEGER DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    UNIQUE (api_tool_id)
);

-- Redis Config
CREATE TABLE IF NOT EXISTS api_tool_redis_configs (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    command VARCHAR(50),
    key_pattern VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000,
    UNIQUE (api_tool_id)
);
