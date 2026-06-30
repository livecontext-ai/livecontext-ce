-- Consolidation of storage-service V1 + catalog-service-import V001-V002
-- Tables in 'public' schema used by storage-service and catalog-import
-- Idempotent: every statement uses IF NOT EXISTS / CREATE OR REPLACE
-- Production: no-op (tables exist). Fresh install: creates everything.

SET search_path TO public;

-- ============================================================================
-- 1. stored_files (storage-service V1)
-- ============================================================================
CREATE TABLE IF NOT EXISTS stored_files (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    storage_provider VARCHAR(100),
    storage_key VARCHAR(500),
    is_public BOOLEAN DEFAULT FALSE,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_accessed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 2. file_metadata (storage-service V1)
-- ============================================================================
CREATE TABLE IF NOT EXISTS file_metadata (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL,
    metadata_key VARCHAR(100) NOT NULL,
    metadata_value TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (file_id) REFERENCES stored_files(id) ON DELETE CASCADE,
    UNIQUE(file_id, metadata_key)
);

-- ============================================================================
-- 3. user_storage_stats (storage-service V1)
-- ============================================================================
CREATE TABLE IF NOT EXISTS user_storage_stats (
    user_id BIGINT PRIMARY KEY,
    total_files BIGINT DEFAULT 0,
    total_size BIGINT DEFAULT 0,
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 4. tool_templates (catalog-import V001)
-- ============================================================================
CREATE TABLE IF NOT EXISTS tool_templates (
    id UUID PRIMARY KEY,
    provider TEXT NOT NULL,
    external_workflow_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    tool_id TEXT,
    name TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    spec JSONB NOT NULL,
    placeholders_schema JSONB,
    args_schema_mini JSONB,
    spec_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- 5. n8n_credentials (catalog-import V002)
-- ============================================================================
CREATE TABLE IF NOT EXISTS n8n_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credential_name TEXT NOT NULL UNIQUE,
    display_name TEXT,
    description TEXT,
    credential_type TEXT,
    properties JSONB,
    extends_ JSONB,
    documentation_url TEXT,
    icon_url TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- Indexes: stored_files
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_stored_files_user_id ON stored_files (user_id);
CREATE INDEX IF NOT EXISTS idx_stored_files_file_name ON stored_files (file_name);
CREATE INDEX IF NOT EXISTS idx_stored_files_content_type ON stored_files (content_type);
CREATE INDEX IF NOT EXISTS idx_stored_files_is_public ON stored_files (is_public);
CREATE INDEX IF NOT EXISTS idx_stored_files_created_at ON stored_files (created_at);
CREATE INDEX IF NOT EXISTS idx_stored_files_last_accessed_at ON stored_files (last_accessed_at);
CREATE INDEX IF NOT EXISTS idx_stored_files_storage_provider ON stored_files (storage_provider);

-- ============================================================================
-- Indexes: file_metadata
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_file_metadata_file_id ON file_metadata (file_id);
CREATE INDEX IF NOT EXISTS idx_file_metadata_key ON file_metadata (metadata_key);

-- ============================================================================
-- Indexes: user_storage_stats
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_user_storage_stats_last_updated ON user_storage_stats (last_updated);

-- ============================================================================
-- Indexes: tool_templates
-- ============================================================================
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'uq_tool_templates_provider_wf_hash') THEN
        CREATE UNIQUE INDEX uq_tool_templates_provider_wf_hash ON tool_templates (provider, external_workflow_id, spec_hash);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_tool_templates_tool_id ON tool_templates (tool_id);
CREATE INDEX IF NOT EXISTS idx_tool_templates_project_id ON tool_templates (project_id);
CREATE INDEX IF NOT EXISTS idx_tool_templates_created_at ON tool_templates (created_at DESC);

-- ============================================================================
-- Indexes: n8n_credentials
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_n8n_credentials_name ON n8n_credentials (credential_name);
CREATE INDEX IF NOT EXISTS idx_n8n_credentials_type ON n8n_credentials (credential_type);
CREATE INDEX IF NOT EXISTS idx_n8n_credentials_created_at ON n8n_credentials (created_at DESC);

-- ============================================================================
-- Constraints: stored_files (storage-service V1)
-- ============================================================================
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'chk_file_size_positive') THEN
        ALTER TABLE stored_files ADD CONSTRAINT chk_file_size_positive CHECK (file_size > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'chk_file_path_not_empty') THEN
        ALTER TABLE stored_files ADD CONSTRAINT chk_file_path_not_empty CHECK (file_path != '');
    END IF;
END $$;

-- ============================================================================
-- View: file_statistics (storage-service V1)
-- ============================================================================
CREATE OR REPLACE VIEW file_statistics AS
SELECT
    user_id,
    COUNT(*) as file_count,
    SUM(file_size) as total_size,
    AVG(file_size) as average_file_size,
    MIN(created_at) as first_file_date,
    MAX(created_at) as last_file_date
FROM stored_files
GROUP BY user_id;

-- ============================================================================
-- Function: update_user_storage_stats (storage-service V1)
-- ============================================================================
CREATE OR REPLACE FUNCTION update_user_storage_stats()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO user_storage_stats (user_id, total_files, total_size, last_updated)
        VALUES (NEW.user_id, 1, NEW.file_size, CURRENT_TIMESTAMP)
        ON CONFLICT (user_id) DO UPDATE SET
            total_files = user_storage_stats.total_files + 1,
            total_size = user_storage_stats.total_size + NEW.file_size,
            last_updated = CURRENT_TIMESTAMP;
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        UPDATE user_storage_stats SET
            total_size = total_size - OLD.file_size + NEW.file_size,
            last_updated = CURRENT_TIMESTAMP
        WHERE user_id = NEW.user_id;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE user_storage_stats SET
            total_files = total_files - 1,
            total_size = total_size - OLD.file_size,
            last_updated = CURRENT_TIMESTAMP
        WHERE user_id = OLD.user_id;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Triggers: stored_files → update_user_storage_stats (storage-service V1)
-- ============================================================================
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trigger_update_storage_stats_insert') THEN
        CREATE TRIGGER trigger_update_storage_stats_insert
            AFTER INSERT ON stored_files
            FOR EACH ROW EXECUTE FUNCTION update_user_storage_stats();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trigger_update_storage_stats_update') THEN
        CREATE TRIGGER trigger_update_storage_stats_update
            AFTER UPDATE ON stored_files
            FOR EACH ROW EXECUTE FUNCTION update_user_storage_stats();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trigger_update_storage_stats_delete') THEN
        CREATE TRIGGER trigger_update_storage_stats_delete
            AFTER DELETE ON stored_files
            FOR EACH ROW EXECUTE FUNCTION update_user_storage_stats();
    END IF;
END $$;
