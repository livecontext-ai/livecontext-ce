-- ============================================================================
-- V251: Consolidation of catalog-service V0-V79 (DDL only, seed data in V252)
-- ============================================================================
-- Idempotent: every statement uses IF NOT EXISTS or DO blocks.
-- Production: no-op (tables already exist). Fresh install: creates everything.
-- This represents the FINAL DDL state of the catalog schema.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS catalog;
SET search_path TO catalog, public;

-- ============================================================================
-- Extensions
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- pgvector must be created by DBA, only create if available
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS vector;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'pgvector extension not available, skipping';
        END;
    END IF;
END $$;

-- Move pg_trgm to catalog schema (from V76)
DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_extension e
        JOIN pg_namespace n ON e.extnamespace = n.oid
        WHERE e.extname = 'pg_trgm' AND n.nspname != 'catalog'
    ) THEN
        ALTER EXTENSION pg_trgm SET SCHEMA catalog;
    END IF;
END $$;

-- ============================================================================
-- Tables
-- ============================================================================

-- 1. api_categories (V1 + V9 slug)
CREATE TABLE IF NOT EXISTS api_categories (
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

-- 2. api_subcategories (V1 + V9 slug + V34 icon_url + V49 icon_slug)
CREATE TABLE IF NOT EXISTS api_subcategories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES api_categories(id) ON DELETE CASCADE,
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

-- 3. apis (V1 + V32 is_local + V50 icon_slug + V65 credential_mode/platform_credential_name + V79 is_active DEFAULT true)
CREATE TABLE IF NOT EXISTS apis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by VARCHAR(255) NOT NULL,
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
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_local BOOLEAN NOT NULL DEFAULT false,
    icon_slug VARCHAR(100),
    credential_mode VARCHAR(20) DEFAULT 'user_key',
    platform_credential_name VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    version VARCHAR(20) DEFAULT '1.0.0',
    UNIQUE(created_by, api_name)
);

-- 4. api_tools (V1 + V35 default_headers + V36 protocol + V41 runtime_metadata + V79 is_active DEFAULT true, V39 dropped is_core_node)
CREATE TABLE IF NOT EXISTS api_tools (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_id UUID NOT NULL REFERENCES apis(id) ON DELETE CASCADE,
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
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    version VARCHAR(20) DEFAULT '1.0.0',
    UNIQUE(api_id, tool_name_id)
);

-- 5. api_tool_parameters (V1 + V33 extras + V40 data_type VARCHAR(100) + V48 is_hidden)
CREATE TABLE IF NOT EXISTS api_tool_parameters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
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

-- 6. api_tool_monetization (V1)
CREATE TABLE IF NOT EXISTS api_tool_monetization (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    version VARCHAR(20) DEFAULT '1.0.0',
    UNIQUE(api_tool_id, monetization_type, plan_name)
);

-- 7. tool_categories (V1 + V9 slug + V34 icon_url)
CREATE TABLE IF NOT EXISTS tool_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    icon VARCHAR(50),
    color VARCHAR(20),
    sort_order INTEGER DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT false,
    slug VARCHAR(255),
    icon_url VARCHAR(1000),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
);

-- 8. tool_names (V1 + V9 slug, no unique on slug after V74)
CREATE TABLE IF NOT EXISTS tool_names (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    tool_category_id UUID NOT NULL REFERENCES tool_categories(id) ON DELETE CASCADE,
    subcategory_id UUID REFERENCES api_subcategories(id) ON DELETE SET NULL,
    method VARCHAR(10) NOT NULL DEFAULT 'GET',
    endpoint_pattern VARCHAR(500),
    run_scope VARCHAR(10) NOT NULL DEFAULT 'external' CHECK (run_scope IN ('local','external','both')),
    requires_user_credentials BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT false,
    slug VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    UNIQUE(name, tool_category_id)
);

-- 9. tool_responses (V1 + V43 structure_skeleton)
CREATE TABLE IF NOT EXISTS tool_responses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
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

-- 10. api_tool_sql_configs (V36)
CREATE TABLE IF NOT EXISTS api_tool_sql_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    dialect VARCHAR(100),
    query_template TEXT NOT NULL,
    parameter_mapping TEXT,
    default_schema VARCHAR(255),
    default_table VARCHAR(255),
    result_mode VARCHAR(50),
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    UNIQUE (api_tool_id)
);

-- 11. api_tool_amqp_configs (V37)
CREATE TABLE IF NOT EXISTS api_tool_amqp_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    broker_url VARCHAR(1000),
    virtual_host VARCHAR(255),
    exchange_name VARCHAR(255),
    queue_name VARCHAR(255),
    routing_key VARCHAR(255),
    prefetch_count INTEGER,
    ack_mode VARCHAR(50),
    ssl_enabled BOOLEAN,
    options TEXT,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    UNIQUE (api_tool_id)
);

-- 12. api_tool_kafka_configs (V37)
CREATE TABLE IF NOT EXISTS api_tool_kafka_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    brokers TEXT,
    topic VARCHAR(255),
    consumer_group VARCHAR(255),
    security_protocol VARCHAR(100),
    sasl_mechanism VARCHAR(100),
    sasl_username VARCHAR(255),
    sasl_password VARCHAR(255),
    ssl_enabled BOOLEAN,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    UNIQUE (api_tool_id)
);

-- 13. api_tool_mqtt_configs (V37)
CREATE TABLE IF NOT EXISTS api_tool_mqtt_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    broker_url VARCHAR(1000),
    topics TEXT,
    qos INTEGER,
    retain BOOLEAN,
    client_id VARCHAR(255),
    username VARCHAR(255),
    password VARCHAR(255),
    use_tls BOOLEAN,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    UNIQUE (api_tool_id)
);

-- 14. api_tool_redis_configs (V37)
CREATE TABLE IF NOT EXISTS api_tool_redis_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    host VARCHAR(255),
    port INTEGER,
    db_index INTEGER,
    channels TEXT,
    use_tls BOOLEAN,
    username VARCHAR(255),
    password VARCHAR(255),
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    UNIQUE (api_tool_id)
);

-- 15. tool_signals (V20)
CREATE TABLE IF NOT EXISTS tool_signals (
    tool_id UUID PRIMARY KEY REFERENCES api_tools(id) ON DELETE CASCADE,
    action VARCHAR(40),
    resource VARCHAR(60),
    provider VARCHAR(100),
    method VARCHAR(10),
    requires_user_credentials BOOLEAN,
    run_scope VARCHAR(10),
    is_active BOOLEAN,
    popularity INT DEFAULT 0,
    success_rate NUMERIC(5,4) DEFAULT 0.0,
    latency_ms_p50 INT DEFAULT 0,
    updated_at BIGINT DEFAULT (EXTRACT(EPOCH FROM now())*1000) NOT NULL
);

-- 16. tool_embeddings (V28+V46 3072 dims) - conditional on vector type
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vector') THEN
        EXECUTE '
        CREATE TABLE IF NOT EXISTS tool_embeddings (
            tool_id UUID PRIMARY KEY REFERENCES api_tools(id) ON DELETE CASCADE,
            text_concat TEXT NOT NULL,
            embedding vector(3072),
            lang VARCHAR(10) DEFAULT ''fr'',
            updated_at BIGINT DEFAULT (EXTRACT(EPOCH FROM now())*1000) NOT NULL
        )';
    ELSE
        CREATE TABLE IF NOT EXISTS tool_embeddings (
            tool_id UUID PRIMARY KEY REFERENCES api_tools(id) ON DELETE CASCADE,
            text_concat TEXT NOT NULL,
            embedding TEXT,
            lang VARCHAR(10) DEFAULT 'fr',
            updated_at BIGINT DEFAULT (EXTRACT(EPOCH FROM now())*1000) NOT NULL
        );
    END IF;
END $$;

-- 17. lexical_search_index (V30 + V45 + V58 + V61 + V75 - final state after V75 drops individual tsv columns)
CREATE TABLE IF NOT EXISTS lexical_search_index (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL UNIQUE REFERENCES api_tools(id) ON DELETE CASCADE,
    provider VARCHAR(100) NOT NULL,
    resource VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    endpoint TEXT NOT NULL,
    params_required TEXT[],
    params_optional TEXT[],
    param_examples TEXT[],
    summary TEXT NOT NULL,
    keywords TEXT,
    keywords_primary TEXT[],
    keywords_synonyms TEXT[],
    keywords_params TEXT[],
    use_cases TEXT[],
    summary_extended TEXT,
    subcategory VARCHAR(100),
    category VARCHAR(100),
    tool_name VARCHAR(255),
    tsv_combined TSVECTOR,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 18. credentials (V33 + V66 icon_slug)
CREATE TABLE IF NOT EXISTS credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credential_name VARCHAR(200) NOT NULL UNIQUE,
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
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)
);

-- 19. tool_credentials (V33)
CREATE TABLE IF NOT EXISTS tool_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID NOT NULL REFERENCES api_tools(id) ON DELETE CASCADE,
    credential_id UUID REFERENCES credentials(id) ON DELETE SET NULL,
    credential_name VARCHAR(200) NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    usage VARCHAR(50),
    condition JSONB,
    metadata JSONB,
    created_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    updated_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)
);

-- 20. search_feedback (V47) - conditional vector column
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vector') THEN
        EXECUTE '
        CREATE TABLE IF NOT EXISTS search_feedback (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            query TEXT NOT NULL,
            query_embedding vector(3072),
            presented_tool_ids UUID[] NOT NULL,
            presented_scores DOUBLE PRECISION[],
            selected_tool_id UUID,
            selection_rank INTEGER,
            execution_success BOOLEAN,
            execution_error TEXT,
            extracted_provider VARCHAR(100),
            extracted_action VARCHAR(50),
            extracted_resource VARCHAR(100),
            session_id VARCHAR(100),
            tenant_id VARCHAR(100),
            search_time_ms INTEGER,
            reranking_time_ms INTEGER,
            search_type VARCHAR(50) DEFAULT ''hybrid'',
            reranking_enabled BOOLEAN DEFAULT false,
            auto_pick_triggered BOOLEAN DEFAULT false,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )';
    ELSE
        CREATE TABLE IF NOT EXISTS search_feedback (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            query TEXT NOT NULL,
            query_embedding TEXT,
            presented_tool_ids UUID[] NOT NULL,
            presented_scores DOUBLE PRECISION[],
            selected_tool_id UUID,
            selection_rank INTEGER,
            execution_success BOOLEAN,
            execution_error TEXT,
            extracted_provider VARCHAR(100),
            extracted_action VARCHAR(50),
            extracted_resource VARCHAR(100),
            session_id VARCHAR(100),
            tenant_id VARCHAR(100),
            search_time_ms INTEGER,
            reranking_time_ms INTEGER,
            search_type VARCHAR(50) DEFAULT 'hybrid',
            reranking_enabled BOOLEAN DEFAULT false,
            auto_pick_triggered BOOLEAN DEFAULT false,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
    END IF;
END $$;

-- 21. tool_next_hint (V64)
CREATE TABLE IF NOT EXISTS tool_next_hint (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_tool_id UUID REFERENCES api_tools(id) ON DELETE CASCADE,
    tool_name_id UUID REFERENCES tool_names(id) ON DELETE CASCADE,
    hint TEXT NOT NULL,
    next_tool_name VARCHAR(255),
    next_tool_id UUID REFERENCES api_tools(id) ON DELETE SET NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    condition_expression TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    CONSTRAINT check_tool_reference CHECK (api_tool_id IS NOT NULL OR tool_name_id IS NOT NULL)
);

-- ============================================================================
-- Functions
-- ============================================================================

-- generate_slug (V1)
CREATE OR REPLACE FUNCTION generate_slug(input_text TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN LOWER(REGEXP_REPLACE(REGEXP_REPLACE(input_text, '[^a-zA-Z0-9\s\-]', '', 'g'), '[\s_]+', '-', 'g'));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- update_lexical_tsvectors (V75 final version, uses 'english' tokenizer)
CREATE OR REPLACE FUNCTION catalog.update_lexical_tsvectors()
RETURNS TRIGGER AS $$
DECLARE
    provider_text TEXT;
    keywords_primary_text TEXT;
    keywords_synonyms_text TEXT;
    keywords_params_text TEXT;
    use_cases_text TEXT;
    summary_text TEXT;
    tool_name_text TEXT;
BEGIN
    provider_text := replace(coalesce(NEW.provider, ''), '/', ' ');
    tool_name_text := coalesce(NEW.tool_name, '');
    keywords_primary_text := coalesce(array_to_string(NEW.keywords_primary, ' '), '');
    keywords_synonyms_text := coalesce(array_to_string(NEW.keywords_synonyms, ' '), '');
    keywords_params_text := coalesce(array_to_string(NEW.keywords_params, ' '), '');
    use_cases_text := coalesce(array_to_string(NEW.use_cases, ' '), '');
    summary_text := coalesce(NEW.summary_extended, NEW.summary, '');

    NEW.tsv_combined :=
        setweight(to_tsvector('english', coalesce(NEW.subcategory, '')), 'A') ||
        setweight(to_tsvector('english', tool_name_text), 'A') ||
        setweight(to_tsvector('english', keywords_primary_text), 'A') ||
        setweight(to_tsvector('english', keywords_synonyms_text), 'A') ||
        setweight(to_tsvector('english', use_cases_text), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.resource, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.action, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.category, '')), 'B') ||
        setweight(to_tsvector('english', summary_text), 'C') ||
        setweight(to_tsvector('english', provider_text), 'C') ||
        setweight(to_tsvector('english', coalesce(NEW.endpoint, '')), 'D') ||
        setweight(to_tsvector('english', keywords_params_text), 'D');

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Triggers
-- ============================================================================

DROP TRIGGER IF EXISTS trigger_update_lexical_tsvectors ON lexical_search_index;
CREATE TRIGGER trigger_update_lexical_tsvectors
    BEFORE INSERT OR UPDATE ON lexical_search_index
    FOR EACH ROW EXECUTE FUNCTION update_lexical_tsvectors();

-- ============================================================================
-- Indexes - api_categories
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_api_categories_name ON api_categories(name);
CREATE INDEX IF NOT EXISTS idx_api_categories_active ON api_categories(is_active);
CREATE INDEX IF NOT EXISTS idx_api_categories_sort_order ON api_categories(sort_order);
CREATE INDEX IF NOT EXISTS idx_api_categories_slug ON api_categories(slug);

-- ============================================================================
-- Indexes - api_subcategories
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_api_subcategories_category_id ON api_subcategories(category_id);
CREATE INDEX IF NOT EXISTS idx_api_subcategories_name ON api_subcategories(name);
CREATE INDEX IF NOT EXISTS idx_api_subcategories_active ON api_subcategories(is_active);
CREATE INDEX IF NOT EXISTS idx_api_subcategories_sort_order ON api_subcategories(sort_order);
CREATE INDEX IF NOT EXISTS idx_api_subcategories_slug ON api_subcategories(slug);

-- ============================================================================
-- Indexes - apis
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_apis_created_by ON apis(created_by);
CREATE INDEX IF NOT EXISTS idx_apis_category_id ON apis(category_id);
CREATE INDEX IF NOT EXISTS idx_apis_subcategory_id ON apis(subcategory_id);
CREATE INDEX IF NOT EXISTS idx_apis_status ON apis(status);
CREATE INDEX IF NOT EXISTS idx_apis_public ON apis(is_public);
CREATE INDEX IF NOT EXISTS idx_apis_pricing_model ON apis(pricing_model);
CREATE INDEX IF NOT EXISTS idx_apis_created_at ON apis(created_at);
CREATE INDEX IF NOT EXISTS idx_apis_api_slug ON apis(api_slug);
CREATE INDEX IF NOT EXISTS idx_apis_created_by_slug ON apis(created_by, api_slug);
CREATE INDEX IF NOT EXISTS idx_apis_is_active ON apis(is_active);
CREATE INDEX IF NOT EXISTS idx_apis_icon_slug ON apis(icon_slug);
CREATE INDEX IF NOT EXISTS idx_apis_credential_mode ON apis(credential_mode);

-- ============================================================================
-- Indexes - api_tools
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_api_tools_api_id ON api_tools(api_id);
CREATE INDEX IF NOT EXISTS idx_api_tools_status ON api_tools(status);
CREATE INDEX IF NOT EXISTS idx_api_tools_tool_name_id ON api_tools(tool_name_id);
CREATE INDEX IF NOT EXISTS idx_api_tools_tool_slug ON api_tools(tool_slug);
CREATE INDEX IF NOT EXISTS idx_api_tools_api_slug ON api_tools(api_id, tool_slug);
CREATE INDEX IF NOT EXISTS idx_api_tools_is_active ON api_tools(is_active);

-- ============================================================================
-- Indexes - api_tool_parameters
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_api_tool_parameters_tool_id ON api_tool_parameters(api_tool_id);
CREATE INDEX IF NOT EXISTS idx_api_tool_parameters_type ON api_tool_parameters(parameter_type);
CREATE INDEX IF NOT EXISTS idx_api_tool_parameters_is_hidden ON api_tool_parameters(is_hidden);

-- ============================================================================
-- Indexes - api_tool_monetization
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_api_tool_monetization_tool_id ON api_tool_monetization(api_tool_id);
CREATE INDEX IF NOT EXISTS idx_api_tool_monetization_type ON api_tool_monetization(monetization_type);
CREATE INDEX IF NOT EXISTS idx_api_tool_monetization_plan_name ON api_tool_monetization(plan_name);
CREATE INDEX IF NOT EXISTS idx_api_tool_monetization_tool_type_plan ON api_tool_monetization(api_tool_id, monetization_type, plan_name);

-- ============================================================================
-- Indexes - tool_categories
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_tool_categories_name ON tool_categories(name);
CREATE INDEX IF NOT EXISTS idx_tool_categories_active ON tool_categories(is_active);
CREATE INDEX IF NOT EXISTS idx_tool_categories_sort_order ON tool_categories(sort_order);
CREATE INDEX IF NOT EXISTS idx_tool_categories_slug ON tool_categories(slug);

-- ============================================================================
-- Indexes - tool_names
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_tool_names_name ON tool_names(name);
CREATE INDEX IF NOT EXISTS idx_tool_names_tool_category_id ON tool_names(tool_category_id);
CREATE INDEX IF NOT EXISTS idx_tool_names_subcategory_id ON tool_names(subcategory_id);
CREATE INDEX IF NOT EXISTS idx_tool_names_method ON tool_names(method);
CREATE INDEX IF NOT EXISTS idx_tool_names_run_scope ON tool_names(run_scope);
CREATE INDEX IF NOT EXISTS idx_tool_names_requires_user_credentials ON tool_names(requires_user_credentials);
CREATE INDEX IF NOT EXISTS idx_tool_names_active ON tool_names(is_active);
CREATE INDEX IF NOT EXISTS idx_tool_names_slug ON tool_names(slug);

-- ============================================================================
-- Indexes - tool_responses
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_tool_responses_tool_id ON tool_responses(tool_id);
CREATE INDEX IF NOT EXISTS idx_tool_responses_status_code ON tool_responses(status_code);
CREATE INDEX IF NOT EXISTS idx_tool_responses_is_default ON tool_responses(is_default);
CREATE INDEX IF NOT EXISTS idx_tool_responses_created_at ON tool_responses(created_at);
CREATE INDEX IF NOT EXISTS idx_tool_responses_is_active ON tool_responses(is_active);

-- ============================================================================
-- Indexes - tool_signals
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_tool_signals_provider ON tool_signals(provider);
CREATE INDEX IF NOT EXISTS idx_tool_signals_action ON tool_signals(action);
CREATE INDEX IF NOT EXISTS idx_tool_signals_resource ON tool_signals(resource);
CREATE INDEX IF NOT EXISTS idx_tool_signals_is_active ON tool_signals(is_active);
CREATE INDEX IF NOT EXISTS idx_tool_signals_popularity ON tool_signals(popularity);

-- ============================================================================
-- Indexes - tool_embeddings (conditional HNSW + text GIN)
-- ============================================================================

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vector') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_tool_embeddings_vec_hnsw
            ON tool_embeddings
            USING hnsw ((embedding::halfvec(3072)) halfvec_cosine_ops)
            WITH (m = 16, ef_construction = 64)';
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_tool_embeddings_text_concat ON tool_embeddings USING gin (to_tsvector('simple', text_concat));

-- ============================================================================
-- Indexes - lexical_search_index
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_lexical_combined ON lexical_search_index USING gin(tsv_combined);
CREATE INDEX IF NOT EXISTS idx_lexical_provider_btree ON lexical_search_index(provider);
CREATE INDEX IF NOT EXISTS idx_lexical_resource_btree ON lexical_search_index(resource);
CREATE INDEX IF NOT EXISTS idx_lexical_action_btree ON lexical_search_index(action);
CREATE INDEX IF NOT EXISTS idx_lexical_api_tool_id ON lexical_search_index(api_tool_id);
CREATE INDEX IF NOT EXISTS idx_lexical_provider_trgm ON lexical_search_index USING gin (provider gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_lexical_resource_trgm ON lexical_search_index USING gin (resource gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_lexical_action_trgm ON lexical_search_index USING gin (action gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_lexical_subcategory_trgm ON lexical_search_index USING gin (subcategory gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_lexical_pra_btree ON lexical_search_index (provider, resource, action);
CREATE INDEX IF NOT EXISTS idx_lexical_keywords_primary_gin ON lexical_search_index USING gin (keywords_primary);
CREATE INDEX IF NOT EXISTS idx_lexical_keywords_synonyms_gin ON lexical_search_index USING gin (keywords_synonyms);
CREATE INDEX IF NOT EXISTS idx_lexical_subcategory_btree ON lexical_search_index(subcategory);
CREATE INDEX IF NOT EXISTS idx_lexical_category_btree ON lexical_search_index(category);
CREATE INDEX IF NOT EXISTS idx_lexical_tool_name_btree ON lexical_search_index(tool_name);

-- ============================================================================
-- Indexes - credentials
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_credentials_name ON credentials(credential_name);
CREATE INDEX IF NOT EXISTS idx_credentials_auth_type ON credentials(auth_type);
CREATE INDEX IF NOT EXISTS idx_credentials_icon_slug ON credentials(icon_slug);
CREATE INDEX IF NOT EXISTS idx_credentials_credential_name_trgm ON credentials USING gin (credential_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_credentials_display_name_trgm ON credentials USING gin (display_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_credentials_description_trgm ON credentials USING gin (description gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_credentials_icon_slug_trgm ON credentials USING gin (icon_slug gin_trgm_ops);

-- ============================================================================
-- Indexes - tool_credentials
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_tool_credentials_tool_id ON tool_credentials(api_tool_id);
CREATE INDEX IF NOT EXISTS idx_tool_credentials_credential_name ON tool_credentials(credential_name);

-- ============================================================================
-- Indexes - search_feedback
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_search_feedback_created_at ON search_feedback(created_at);
CREATE INDEX IF NOT EXISTS idx_search_feedback_selected_tool ON search_feedback(selected_tool_id);
CREATE INDEX IF NOT EXISTS idx_search_feedback_selection_rank ON search_feedback(selection_rank);
CREATE INDEX IF NOT EXISTS idx_search_feedback_tenant ON search_feedback(tenant_id);
CREATE INDEX IF NOT EXISTS idx_search_feedback_provider ON search_feedback(extracted_provider);

-- HNSW index for search_feedback query_embedding (conditional on vector type)
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vector') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_search_feedback_embedding_hnsw
            ON search_feedback
            USING hnsw ((query_embedding::halfvec(3072)) halfvec_cosine_ops)
            WITH (m = 16, ef_construction = 64)';
    END IF;
END $$;

-- ============================================================================
-- Indexes - tool_next_hint
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_tool_next_hint_api_tool_id ON tool_next_hint(api_tool_id);
CREATE INDEX IF NOT EXISTS idx_tool_next_hint_tool_name_id ON tool_next_hint(tool_name_id);
CREATE INDEX IF NOT EXISTS idx_tool_next_hint_next_tool_id ON tool_next_hint(next_tool_id);
CREATE INDEX IF NOT EXISTS idx_tool_next_hint_priority ON tool_next_hint(priority);
CREATE INDEX IF NOT EXISTS idx_tool_next_hint_active ON tool_next_hint(is_active);

-- ============================================================================
-- Constraints (V9, V19, V73 - idempotent using DO blocks)
-- ============================================================================

-- V9: slug unique constraints
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_tool_categories_slug') THEN
        ALTER TABLE tool_categories ADD CONSTRAINT uk_tool_categories_slug UNIQUE (slug);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_api_categories_slug') THEN
        ALTER TABLE api_categories ADD CONSTRAINT uk_api_categories_slug UNIQUE (slug);
    END IF;
END $$;

-- V9: slug validation constraints (CHECK constraints)
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'check_api_slug_not_empty') THEN
        ALTER TABLE apis ADD CONSTRAINT check_api_slug_not_empty CHECK (api_slug IS NULL OR LENGTH(TRIM(api_slug)) > 0);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'check_tool_slug_not_empty') THEN
        ALTER TABLE api_tools ADD CONSTRAINT check_tool_slug_not_empty CHECK (tool_slug IS NULL OR LENGTH(TRIM(tool_slug)) > 0);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'check_tool_categories_slug_not_empty') THEN
        ALTER TABLE tool_categories ADD CONSTRAINT check_tool_categories_slug_not_empty CHECK (slug IS NULL OR LENGTH(TRIM(slug)) > 0);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'check_tool_names_slug_not_empty') THEN
        ALTER TABLE tool_names ADD CONSTRAINT check_tool_names_slug_not_empty CHECK (slug IS NULL OR LENGTH(TRIM(slug)) > 0);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'check_api_categories_slug_not_empty') THEN
        ALTER TABLE api_categories ADD CONSTRAINT check_api_categories_slug_not_empty CHECK (slug IS NULL OR LENGTH(TRIM(slug)) > 0);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'check_api_subcategories_slug_not_empty') THEN
        ALTER TABLE api_subcategories ADD CONSTRAINT check_api_subcategories_slug_not_empty CHECK (slug IS NULL OR LENGTH(TRIM(slug)) > 0);
    END IF;
END $$;

-- V73: additional unique constraints
-- Note: api_categories(name) uniqueness is already enforced - skip if already present
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'api_categories_name_key') THEN
        ALTER TABLE api_categories ADD CONSTRAINT api_categories_name_key UNIQUE (name);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'api_subcategories_slug_key') THEN
        ALTER TABLE api_subcategories ADD CONSTRAINT api_subcategories_slug_key UNIQUE (slug);
    END IF;
END $$;

-- V9: uk_api_subcategories_slug (may overlap with api_subcategories_slug_key from V73)
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_api_subcategories_slug') THEN
        -- Only add if api_subcategories_slug_key also doesn't cover this
        -- In practice these are the same constraint; skip if V73 version exists
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'api_subcategories_slug_key') THEN
            ALTER TABLE api_subcategories ADD CONSTRAINT uk_api_subcategories_slug UNIQUE (slug);
        END IF;
    END IF;
END $$;

-- NOTE: V74 dropped uk_tool_names_slug and tool_names_name_category_key.
-- Therefore we do NOT add those constraints here.

-- ============================================================================
-- End of V251 consolidation
-- ============================================================================
