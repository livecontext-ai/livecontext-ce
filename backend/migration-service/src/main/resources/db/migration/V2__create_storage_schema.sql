-- ============================================================================
-- V2: Storage Schema (consolidated)
-- Creates storage schema with all tables in final state.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS storage;
SET search_path TO storage;

CREATE TABLE IF NOT EXISTS storage (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    data JSONB,
    data_binary BYTEA,
    data_text TEXT,
    storage_type VARCHAR(20) DEFAULT 'JSON' CHECK (storage_type IN ('JSON', 'BINARY', 'TEXT')),
    file_name VARCHAR(255),
    file_extension VARCHAR(10),
    mime_type VARCHAR(100),
    width INTEGER,
    height INTEGER,
    duration INTEGER,
    metadata JSONB,
    data_mapped JSONB,
    structure_skeleton JSONB,
    size_bytes INTEGER NOT NULL,
    checksum VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    accessed_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    run_id VARCHAR(100),
    step_key VARCHAR(100),
    item_index INTEGER DEFAULT 0,
    epoch INTEGER NOT NULL DEFAULT 0,
    workflow_id VARCHAR(100),
    source_type VARCHAR(50),
    s3_key VARCHAR(500),
    spawn INTEGER NOT NULL DEFAULT 0,
    source_publication_id UUID
);

CREATE INDEX IF NOT EXISTS idx_storage_tenant_id ON storage(tenant_id);
CREATE INDEX IF NOT EXISTS idx_storage_status ON storage(status);
CREATE INDEX IF NOT EXISTS idx_storage_tenant_status ON storage(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_storage_run_id ON storage(run_id) WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_storage_run_step_epoch ON storage(run_id, step_key, epoch) WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_storage_explorer_main ON storage(tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_storage_tenant_workflow ON storage(tenant_id, workflow_id, created_at DESC) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_storage_s3_key ON storage(s3_key) WHERE s3_key IS NOT NULL AND status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_storage_tenant_usage ON storage(tenant_id, status, size_bytes) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS tenant_storage_quota (
    tenant_id VARCHAR(255) PRIMARY KEY,
    max_bytes BIGINT NOT NULL DEFAULT 1073741824,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    soft_limit_bytes BIGINT NOT NULL DEFAULT 858993459,
    hard_limit_bytes BIGINT NOT NULL DEFAULT 1073741824,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS tenant_storage_breakdown (
    tenant_id VARCHAR(255) NOT NULL,
    category VARCHAR(50) NOT NULL,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    item_count INTEGER NOT NULL DEFAULT 0,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, category)
);

CREATE TABLE IF NOT EXISTS storage_usage_history (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    category VARCHAR(50) NOT NULL,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    item_count INTEGER NOT NULL DEFAULT 0,
    snapshot_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, category, snapshot_date)
);
CREATE INDEX IF NOT EXISTS idx_storage_history_tenant_date ON storage_usage_history(tenant_id, snapshot_date);

-- Storage helper functions
CREATE OR REPLACE FUNCTION storage.calculate_tenant_usage(p_tenant_id VARCHAR)
RETURNS BIGINT AS $$
BEGIN
    RETURN (SELECT COALESCE(SUM(size_bytes), 0) FROM storage.storage WHERE tenant_id = p_tenant_id AND status = 'ACTIVE');
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION storage.trigger_update_storage_type()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.data_binary IS NOT NULL THEN
        NEW.storage_type := 'BINARY';
    ELSIF NEW.data_text IS NOT NULL AND (NEW.mime_type LIKE 'text/%' OR NEW.mime_type LIKE 'application/xml%') THEN
        NEW.storage_type := 'TEXT';
    ELSE
        NEW.storage_type := 'JSON';
    END IF;
    NEW.size_bytes := COALESCE(octet_length(NEW.data::text), 0) + COALESCE(octet_length(NEW.data_binary), 0) + COALESCE(octet_length(NEW.data_text), 0);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'storage_type_trigger') THEN
        CREATE TRIGGER storage_type_trigger
            BEFORE INSERT OR UPDATE ON storage.storage
            FOR EACH ROW
            EXECUTE FUNCTION storage.trigger_update_storage_type();
    END IF;
END $$;
