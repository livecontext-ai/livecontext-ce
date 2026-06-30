-- ============================================================================
-- V7: Interface Schema (consolidated)
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS interface;
SET search_path TO interface;

CREATE TABLE IF NOT EXISTS interfaces (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    html_template TEXT,
    css_template TEXT,
    js_template TEXT,
    workflow_run_id UUID,
    step_data_id BIGINT,
    target_table TEXT,
    data_source_id BIGINT,
    template_variables JSONB DEFAULT '[]'::jsonb,
    source_workflow_id UUID,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resource_index INTEGER NOT NULL DEFAULT 0,
    interface_type VARCHAR(255) NOT NULL DEFAULT 'html',
    data JSONB,
    agent_id TEXT,
    message_id TEXT,
    conversation_id TEXT,
    project_id UUID,
    organization_id TEXT,
    source_publication_id UUID
);

CREATE TABLE IF NOT EXISTS interface_run_snapshots (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    interface_id UUID NOT NULL,
    workflow_run_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    html_template TEXT NOT NULL,
    css_template TEXT,
    js_template TEXT,
    variable_mappings JSONB,
    action_mappings JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(interface_id, workflow_run_id)
);
