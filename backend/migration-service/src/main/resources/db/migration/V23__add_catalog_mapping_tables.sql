-- ============================================================================
-- V23: Add missing catalog mapping tables (mapping_definitions, mapping_versions)
-- ============================================================================
-- These tables are used by MappingDefinitionEntity and MappingVersionEntity
-- in catalog-service but were never added to the centralized migration.
-- Idempotent: uses IF NOT EXISTS.
-- ============================================================================

SET search_path TO catalog, public;

-- 1. mapping_definitions
CREATE TABLE IF NOT EXISTS mapping_definitions (
    id BIGSERIAL PRIMARY KEY,
    tool_id UUID NOT NULL,
    display_name VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    description TEXT
);

CREATE INDEX IF NOT EXISTS idx_mapping_definitions_tool_id ON mapping_definitions(tool_id);
CREATE INDEX IF NOT EXISTS idx_mapping_definitions_status ON mapping_definitions(status);

-- 2. mapping_versions
CREATE TABLE IF NOT EXISTS mapping_versions (
    id BIGSERIAL PRIMARY KEY,
    mapping_definition_id BIGINT NOT NULL REFERENCES mapping_definitions(id) ON DELETE CASCADE,
    version VARCHAR(50) NOT NULL,
    spec JSONB NOT NULL,
    is_latest BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    description TEXT
);

CREATE INDEX IF NOT EXISTS idx_mapping_versions_definition_id ON mapping_versions(mapping_definition_id);
CREATE INDEX IF NOT EXISTS idx_mapping_versions_is_latest ON mapping_versions(is_latest);
