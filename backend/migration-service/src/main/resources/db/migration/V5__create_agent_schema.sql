-- ============================================================================
-- V5: Agent Schema (consolidated)
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS agent;
SET search_path TO agent;

CREATE TABLE IF NOT EXISTS agents (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    system_prompt TEXT,
    model_provider VARCHAR(50),
    model_name VARCHAR(100),
    temperature NUMERIC(3,2),
    max_tokens INTEGER,
    max_iterations INTEGER,
    execution_timeout INTEGER,
    tools_config JSONB,
    workflow_id UUID,
    data_source_id BIGINT,
    conversation_id UUID,
    config JSONB,
    avatar_url VARCHAR(500),
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resource_index INTEGER NOT NULL DEFAULT 0,
    total_executions INTEGER NOT NULL DEFAULT 0,
    total_tokens_used BIGINT NOT NULL DEFAULT 0,
    total_tool_calls INTEGER NOT NULL DEFAULT 0,
    success_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    last_execution_at TIMESTAMPTZ,
    project_id UUID,
    organization_id TEXT,
    source_publication_id UUID
);

CREATE TABLE IF NOT EXISTS skills (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    icon VARCHAR(100),
    instructions TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resource_index INTEGER NOT NULL DEFAULT 0,
    folder_id UUID,
    source_publication_id UUID,
    default_key VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS agent_skills (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL,
    skill_id UUID NOT NULL,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(agent_id, skill_id)
);

CREATE TABLE IF NOT EXISTS skill_folders (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name VARCHAR(255) NOT NULL,
    parent_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_executions (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    step_data_id BIGINT,
    workflow_id UUID,
    workflow_run_id UUID,
    run_id TEXT,
    node_id TEXT,
    agent_entity_id UUID,
    agent_type VARCHAR(20) NOT NULL DEFAULT 'agent',
    epoch INTEGER NOT NULL DEFAULT 0,
    spawn INTEGER NOT NULL DEFAULT 0,
    item_index INTEGER NOT NULL DEFAULT 0,
    loop_iteration INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    stop_reason VARCHAR(30),
    error_message TEXT,
    provider VARCHAR(50),
    model VARCHAR(100),
    temperature NUMERIC(3,2),
    max_tokens_config INTEGER,
    max_iterations_config INTEGER,
    agent_config_snapshot JSONB,
    iteration_count INTEGER NOT NULL DEFAULT 0,
    total_tool_calls INTEGER NOT NULL DEFAULT 0,
    successful_tool_calls INTEGER NOT NULL DEFAULT 0,
    failed_tool_calls INTEGER NOT NULL DEFAULT 0,
    message_count INTEGER NOT NULL DEFAULT 0,
    initial_history_size INTEGER NOT NULL DEFAULT 0,
    total_prompt_tokens INTEGER NOT NULL DEFAULT 0,
    total_completion_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens INTEGER NOT NULL DEFAULT 0,
    total_cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
    total_cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    total_cached_tokens INTEGER NOT NULL DEFAULT 0,
    total_reasoning_tokens INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    duration_ms BIGINT,
    tool_sequence TEXT,
    distinct_tools TEXT[],
    loop_detected BOOLEAN NOT NULL DEFAULT FALSE,
    loop_type VARCHAR(20),
    loop_tool_name VARCHAR(255),
    system_prompt TEXT,
    source VARCHAR(20) NOT NULL DEFAULT 'WORKFLOW',
    caller_agent_entity_id UUID,
    depth INTEGER NOT NULL DEFAULT 0,
    conversation_id TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_execution_iterations (
    id BIGSERIAL PRIMARY KEY,
    execution_id UUID NOT NULL,
    tenant_id TEXT NOT NULL,
    iteration_number INTEGER NOT NULL,
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    cache_creation_tokens INTEGER,
    cache_read_tokens INTEGER,
    cached_tokens INTEGER,
    reasoning_tokens INTEGER,
    duration_ms BIGINT,
    finish_reason VARCHAR(50),
    is_final BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_execution_messages (
    id BIGSERIAL PRIMARY KEY,
    execution_id UUID NOT NULL,
    tenant_id TEXT NOT NULL,
    sequence_number INTEGER NOT NULL,
    iteration_number INTEGER,
    role VARCHAR(10) NOT NULL,
    content TEXT,
    content_storage_id UUID,
    content_length INTEGER,
    tool_call_id VARCHAR(255),
    tool_name VARCHAR(255),
    tool_calls_requested JSONB,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_execution_tool_calls (
    id BIGSERIAL PRIMARY KEY,
    execution_id UUID NOT NULL,
    tenant_id TEXT NOT NULL,
    sequence_number INTEGER NOT NULL,
    iteration_number INTEGER NOT NULL,
    tool_call_id VARCHAR(255),
    tool_name VARCHAR(255) NOT NULL,
    parallel_index INTEGER,
    arguments JSONB,
    success BOOLEAN NOT NULL,
    content TEXT,
    content_storage_id UUID,
    content_length INTEGER,
    error_message TEXT,
    duration_ms BIGINT,
    metadata JSONB,
    estimated_input_tokens INTEGER,
    estimated_output_tokens INTEGER,
    is_repeat BOOLEAN NOT NULL DEFAULT FALSE,
    consecutive_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_webhook_tokens (
    id BIGSERIAL PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    token VARCHAR(64) NOT NULL UNIQUE,
    http_method VARCHAR(10) DEFAULT 'POST',
    auth_type VARCHAR(20) DEFAULT 'none',
    auth_config JSONB,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Stats tables (live, updated via native SQL UPSERT)
CREATE TABLE IF NOT EXISTS agent_tool_call_stats_live (
    tenant_id TEXT NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    total_calls BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    max_duration_ms BIGINT NOT NULL DEFAULT 0,
    execution_count BIGINT NOT NULL DEFAULT 0,
    repeat_call_count BIGINT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, tool_name)
);

CREATE TABLE IF NOT EXISTS agent_execution_daily_stats_live (
    tenant_id TEXT NOT NULL,
    execution_date DATE NOT NULL,
    provider VARCHAR(255) NOT NULL DEFAULT '',
    model VARCHAR(255) NOT NULL DEFAULT '',
    total_executions BIGINT NOT NULL DEFAULT 0,
    completed_count BIGINT NOT NULL DEFAULT 0,
    failed_count BIGINT NOT NULL DEFAULT 0,
    cancelled_count BIGINT NOT NULL DEFAULT 0,
    loop_detected_count BIGINT NOT NULL DEFAULT 0,
    total_tool_calls BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    total_iterations BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, execution_date, provider, model)
);

CREATE TABLE IF NOT EXISTS agent_tool_call_stats_by_agent_live (
    tenant_id TEXT NOT NULL,
    agent_entity_id UUID NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    total_calls BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    max_duration_ms BIGINT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ,
    repeat_call_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, agent_entity_id, tool_name)
);

CREATE TABLE IF NOT EXISTS agent_sub_agent_call_stats_live (
    tenant_id TEXT NOT NULL,
    caller_agent_id UUID NOT NULL,
    callee_agent_id UUID NOT NULL,
    total_calls BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, caller_agent_id, callee_agent_id)
);

-- Agent widget configs (embeddable chat widgets)
CREATE TABLE IF NOT EXISTS agent_widget_configs (
    id BIGSERIAL PRIMARY KEY,
    agent_id UUID NOT NULL UNIQUE REFERENCES agents(id) ON DELETE CASCADE,
    position VARCHAR(20) DEFAULT 'bottom-right',
    theme VARCHAR(10) DEFAULT 'auto',
    primary_color VARCHAR(20) DEFAULT '#000000',
    welcome_message TEXT DEFAULT 'Hello! How can I help you today?',
    bubble_text VARCHAR(100) DEFAULT 'Chat with us',
    show_avatar BOOLEAN DEFAULT TRUE,
    auto_open_delay INTEGER DEFAULT 0,
    widget_token VARCHAR(40) UNIQUE,
    allowed_origins TEXT,
    require_auth BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
