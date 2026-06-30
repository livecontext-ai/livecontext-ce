-- ============================================================================
-- V9: Datasource Schema (consolidated)
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS datasource;
SET search_path TO datasource;

CREATE TABLE IF NOT EXISTS data_sources (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    source_type VARCHAR(50) NOT NULL,
    source_config JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    column_order JSONB DEFAULT '[]'::jsonb,
    mapping_spec JSONB DEFAULT '{}'::jsonb,
    next_row_index INTEGER NOT NULL DEFAULT 0,
    source_workflow_id UUID,
    resource_index INTEGER NOT NULL DEFAULT 0,
    source_publication_id UUID,
    project_id UUID,
    organization_id VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ds_tenant_status_created ON data_sources(tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ds_tenant_name ON data_sources(tenant_id, name);

CREATE TABLE IF NOT EXISTS data_source_items (
    id BIGSERIAL PRIMARY KEY,
    data_source_id BIGINT NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    tenant_id VARCHAR(255) NOT NULL,
    data JSONB NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    row_index INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_dsi_data_gin ON data_source_items USING GIN (data jsonb_ops);
CREATE INDEX IF NOT EXISTS idx_dsi_tenant_ds ON data_source_items(tenant_id, data_source_id);
CREATE INDEX IF NOT EXISTS idx_dsi_ds_row_index ON data_source_items(data_source_id, row_index);
