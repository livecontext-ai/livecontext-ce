-- ============================================================================
-- V1__create_orchestrator_schema.sql
-- Consolidated migration: creates the orchestrator schema and ALL tables
-- in their final state. Replaces the original V1 and absorbs all subsequent
-- ALTER/CREATE migrations so no incremental changes are needed.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS orchestrator;
SET search_path TO orchestrator;

-- 1. workflows
CREATE TABLE IF NOT EXISTS workflows (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    version VARCHAR(50) NOT NULL DEFAULT '1.0.0',
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    plan JSONB,
    data_inputs JSONB,
    execution_metadata JSONB,
    tags JSONB,
    metadata JSONB,
    schedule JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_executed_at TIMESTAMPTZ,
    last_executed_by VARCHAR(255),
    retention_days INTEGER DEFAULT 30,
    webhook_token VARCHAR(255) UNIQUE,
    webhook_created_at TIMESTAMPTZ,
    node_icons JSONB,
    project_id UUID,
    organization_id VARCHAR(255),
    workflow_type VARCHAR(20) NOT NULL DEFAULT 'WORKFLOW',
    base_plan JSONB,
    source_publication_id UUID,
    acquired_at TIMESTAMPTZ,
    resource_index INTEGER NOT NULL DEFAULT 0,
    pinned_version INTEGER
);
CREATE INDEX IF NOT EXISTS idx_workflows_tenant_id ON workflows(tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflows_status ON workflows(status);
CREATE INDEX IF NOT EXISTS idx_workflows_tenant_status ON workflows(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_workflows_created_at ON workflows(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflows_is_active ON workflows(is_active);
CREATE INDEX IF NOT EXISTS idx_workflows_tenant_active ON workflows(tenant_id, is_active, created_at DESC);

-- 2. workflow_runs
CREATE TABLE IF NOT EXISTS workflow_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    tenant_id VARCHAR(255) NOT NULL,
    run_id_public VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    execution_mode VARCHAR(32) NOT NULL DEFAULT 'AUTOMATIC',
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    duration_ms BIGINT,
    total_nodes INTEGER,
    trigger_payload JSONB,
    metadata JSONB,
    plan JSONB,
    state_snapshot TEXT,
    plan_version INTEGER,
    source VARCHAR(20),
    publication_id VARCHAR(50),
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_workflow_runs_workflow_started ON workflow_runs(workflow_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_runs_status ON workflow_runs(status);
CREATE INDEX IF NOT EXISTS idx_workflow_runs_tenant_created ON workflow_runs(tenant_id, created_at DESC);

-- 3. workflow_run_status
CREATE TABLE IF NOT EXISTS workflow_run_status (
    run_id UUID PRIMARY KEY REFERENCES workflow_runs(id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    tenant_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 4. workflow_step_data
CREATE TABLE IF NOT EXISTS workflow_step_data (
    id BIGSERIAL PRIMARY KEY,
    workflow_run_id UUID,
    run_id VARCHAR(255) NOT NULL,
    step_alias VARCHAR(255) NOT NULL,
    tool_id VARCHAR(255) NOT NULL,
    input_data JSONB,
    output_storage_id UUID,
    http_status INTEGER,
    status VARCHAR(32) NOT NULL,
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    error_message VARCHAR(1000),
    tenant_id VARCHAR(255) NOT NULL,
    epoch INTEGER DEFAULT 0,
    spawn INTEGER DEFAULT 0,
    iteration INTEGER DEFAULT 0,
    item_index INTEGER DEFAULT 0,
    metadata JSONB,
    node_type VARCHAR(20),
    condition_expression TEXT,
    condition_result BOOLEAN,
    selected_branch VARCHAR(255),
    loop_id VARCHAR(255),
    loop_iteration INTEGER,
    loop_exit_reason VARCHAR(50),
    merge_strategy VARCHAR(50),
    merge_received_branches JSONB,
    merge_skipped_branches JSONB,
    item_id VARCHAR(255),
    trigger_id VARCHAR(255),
    skip_reason TEXT,
    skip_source_node VARCHAR(255),
    normalized_key VARCHAR(255),
    item_number INTEGER,
    CONSTRAINT idx_workflow_step_data_unique_v5 UNIQUE (workflow_run_id, step_alias, iteration, item_index, epoch, spawn, status)
);
CREATE INDEX IF NOT EXISTS idx_wsd_run_id ON workflow_step_data(run_id);
CREATE INDEX IF NOT EXISTS idx_wsd_run_step ON workflow_step_data(run_id, step_alias);
CREATE INDEX IF NOT EXISTS idx_wsd_resolution ON workflow_step_data(run_id, epoch, spawn, iteration, item_index);
CREATE INDEX IF NOT EXISTS idx_wsd_tenant ON workflow_step_data(tenant_id);

-- 5. workflow_plan_versions
CREATE TABLE IF NOT EXISTS workflow_plan_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    plan JSONB NOT NULL,
    label VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255),
    CONSTRAINT uq_workflow_plan_version UNIQUE (workflow_id, version)
);

-- 6. workflow_categories
CREATE TABLE IF NOT EXISTS workflow_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    icon_slug VARCHAR(50),
    color VARCHAR(20),
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 7. projects
CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(200) NOT NULL,
    description TEXT,
    color VARCHAR(7) DEFAULT '#6366f1',
    icon VARCHAR(50) DEFAULT 'folder-kanban',
    owner_id VARCHAR(50) NOT NULL,
    organization_id VARCHAR(255),
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_projects_owner_slug UNIQUE (owner_id, slug)
);
CREATE INDEX IF NOT EXISTS idx_projects_owner_id ON projects(owner_id);

-- 8. activity_log
CREATE TABLE IF NOT EXISTS activity_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    entity_name VARCHAR(255),
    action_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_activity_log_tenant_created ON activity_log(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_activity_log_entity ON activity_log(entity_type, entity_id);

-- 9. node_type_documentation
CREATE TABLE IF NOT EXISTS node_type_documentation (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(50) NOT NULL UNIQUE,
    label VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    parameters JSONB,
    outputs JSONB,
    global_variables JSONB,
    examples JSONB,
    keywords JSONB,
    variable_prefix VARCHAR(20),
    edge_ports JSONB,
    concepts JSONB,
    comparison JSONB,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    language VARCHAR(10) DEFAULT 'en',
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    search_vector TSVECTOR
);
CREATE INDEX IF NOT EXISTS idx_node_type_search_vector ON node_type_documentation USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS idx_node_type_category ON node_type_documentation(category);

-- 10. workflow_pending_signals
CREATE TABLE IF NOT EXISTS workflow_pending_signals (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) NOT NULL,
    item_id VARCHAR(50) NOT NULL,
    node_id VARCHAR(200) NOT NULL,
    signal_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    CONSTRAINT uq_pending_signals UNIQUE (run_id, item_id, node_id, signal_type)
);
CREATE INDEX IF NOT EXISTS idx_pending_signals_run_id ON workflow_pending_signals(run_id);

-- 11. workflow_signal_waits
CREATE TABLE IF NOT EXISTS workflow_signal_waits (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) NOT NULL,
    item_id VARCHAR(50) NOT NULL DEFAULT '0',
    node_id VARCHAR(200) NOT NULL,
    dag_trigger_id VARCHAR(200),
    signal_type VARCHAR(30) NOT NULL,
    signal_config JSONB,
    epoch INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolution VARCHAR(30),
    resolution_data JSONB,
    split_item_data JSONB,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR(100),
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(100),
    blocking BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_signal_waits UNIQUE (run_id, node_id, item_id, epoch)
);
CREATE INDEX IF NOT EXISTS idx_sw_timer_poll ON workflow_signal_waits(status, signal_type, expires_at) WHERE status = 'PENDING' AND signal_type = 'WAIT_TIMER';
CREATE INDEX IF NOT EXISTS idx_sw_run ON workflow_signal_waits(run_id) WHERE status IN ('PENDING', 'CLAIMED');

-- 12. workflow_epochs
CREATE TABLE IF NOT EXISTS workflow_epochs (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    trigger_id VARCHAR(255) NOT NULL DEFAULT 'trigger:default',
    epoch INTEGER NOT NULL DEFAULT 0,
    entry_type VARCHAR(20) NOT NULL,
    entry_key VARCHAR(512) NOT NULL DEFAULT '_',
    status VARCHAR(50) NOT NULL DEFAULT '_',
    count INTEGER NOT NULL DEFAULT 0,
    epoch_state JSONB,
    is_active BOOLEAN DEFAULT TRUE,
    started_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_workflow_epochs UNIQUE (run_id, trigger_id, epoch, entry_type, entry_key, status)
);
CREATE INDEX IF NOT EXISTS idx_we_run_epoch ON workflow_epochs(run_id, epoch);
CREATE INDEX IF NOT EXISTS idx_we_header ON workflow_epochs(run_id, trigger_id, epoch) WHERE entry_type = 'EPOCH_HEADER';

-- 13. credentials
CREATE TABLE IF NOT EXISTS credentials (
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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_credentials_tenant_id ON credentials(tenant_id);

-- 14. platform_credentials
CREATE TABLE IF NOT EXISTS platform_credentials (
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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_platform_credentials_category_enabled ON platform_credentials(category, is_enabled) WHERE is_enabled = true;

-- 15. platform_credential_endpoints
CREATE TABLE IF NOT EXISTS platform_credential_endpoints (
    id BIGSERIAL PRIMARY KEY,
    platform_credential_id BIGINT NOT NULL REFERENCES platform_credentials(id) ON DELETE CASCADE,
    tool_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255),
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_platform_credential_endpoints UNIQUE (platform_credential_id, tool_id)
);

-- 16. shedlock
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
