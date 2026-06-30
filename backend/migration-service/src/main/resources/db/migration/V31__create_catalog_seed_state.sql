SET search_path TO catalog, public;

CREATE TABLE IF NOT EXISTS catalog.catalog_seed_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seed_id VARCHAR(100) NOT NULL UNIQUE,
    api_id UUID NOT NULL REFERENCES catalog.apis(id) ON DELETE CASCADE,
    file_checksum VARCHAR(64) NOT NULL,
    user_modified BOOLEAN NOT NULL DEFAULT FALSE,
    last_imported_at BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000),
    spec_version VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_catalog_seed_state_seed_id ON catalog.catalog_seed_state(seed_id);
