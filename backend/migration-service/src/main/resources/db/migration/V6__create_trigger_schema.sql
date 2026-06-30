-- ============================================================================
-- V6: Trigger Schema (consolidated)
-- Note: "trigger" is a PostgreSQL reserved word, must be quoted.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS "trigger";

CREATE TABLE IF NOT EXISTS "trigger".scheduled_executions (
    id UUID PRIMARY KEY,
    workflow_id UUID,
    trigger_id VARCHAR(255),
    tenant_id TEXT NOT NULL,
    name VARCHAR(255),
    workflow_name VARCHAR(255),
    cron_expression VARCHAR(100) NOT NULL,
    timezone VARCHAR(50) NOT NULL DEFAULT 'UTC',
    max_executions INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_execution_at TIMESTAMPTZ NOT NULL,
    last_execution_at TIMESTAMPTZ,
    execution_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    description VARCHAR(1000),
    resource_index INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    source_node_id TEXT
);

CREATE TABLE IF NOT EXISTS "trigger".standalone_webhooks (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    token VARCHAR(64) NOT NULL UNIQUE,
    http_method VARCHAR(10) NOT NULL DEFAULT 'POST',
    auth_type VARCHAR(20) NOT NULL DEFAULT 'none',
    auth_config JSONB,
    workflow_id UUID,
    workflow_name VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resource_index INTEGER NOT NULL DEFAULT 0,
    source_node_id TEXT
);

CREATE TABLE IF NOT EXISTS "trigger".webhook_tokens (
    id BIGSERIAL PRIMARY KEY,
    workflow_id UUID NOT NULL,
    trigger_id TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS "trigger".webhook_call_logs (
    id BIGSERIAL PRIMARY KEY,
    webhook_id UUID NOT NULL,
    request_method VARCHAR(10),
    request_headers JSONB,
    request_payload JSONB,
    response_status VARCHAR(30) NOT NULL,
    workflows_triggered INTEGER DEFAULT 0,
    called_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS "trigger".standalone_chat_endpoints (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    token VARCHAR(64) NOT NULL UNIQUE,
    workflow_id UUID,
    workflow_name VARCHAR(255),
    welcome_message TEXT,
    model VARCHAR(100),
    provider VARCHAR(100),
    memory_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    resource_index INTEGER NOT NULL DEFAULT 0,
    source_node_id TEXT,
    trigger_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS "trigger".chat_endpoint_access_logs (
    id BIGSERIAL PRIMARY KEY,
    chat_endpoint_id UUID NOT NULL,
    session_id TEXT,
    conversation_id TEXT,
    action VARCHAR(30) NOT NULL,
    ip_address VARCHAR(45),
    accessed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS "trigger".standalone_form_endpoints (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    token VARCHAR(64) NOT NULL UNIQUE,
    workflow_id UUID,
    workflow_name VARCHAR(255),
    form_config JSONB,
    success_message TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    resource_index INTEGER NOT NULL DEFAULT 0,
    source_node_id TEXT,
    trigger_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS "trigger".form_submission_logs (
    id BIGSERIAL PRIMARY KEY,
    form_endpoint_id UUID NOT NULL,
    submission_data JSONB,
    response_status VARCHAR(30) NOT NULL,
    workflows_triggered INTEGER DEFAULT 0,
    ip_address VARCHAR(45),
    submitted_at TIMESTAMPTZ NOT NULL
);
