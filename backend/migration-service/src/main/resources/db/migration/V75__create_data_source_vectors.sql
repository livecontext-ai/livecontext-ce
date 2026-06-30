-- Vector storage table for RAG / similarity search.
-- Stores embeddings separately from the JSONB data in data_source_items.
-- Vectors are stored without a fixed dimension (the column accepts any size);
-- dimension validation is enforced at the application level via mappingSpec config.
-- HNSW indexes are created dynamically per datasource with explicit dimension casts.

-- Vector type is available directly since pgvector extension is in datasource schema
SET search_path TO datasource;

CREATE TABLE IF NOT EXISTS datasource.data_source_vectors (
    id              BIGSERIAL PRIMARY KEY,
    data_source_id  BIGINT NOT NULL,
    item_id         BIGINT NOT NULL REFERENCES datasource.data_source_items(id) ON DELETE CASCADE,
    column_name     VARCHAR(255) NOT NULL,
    embedding       vector NOT NULL,
    dimension       SMALLINT NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(item_id, column_name)
);

-- B-tree indexes for filtering (HNSW indexes are created dynamically per datasource)
CREATE INDEX idx_dsv_datasource ON datasource.data_source_vectors(data_source_id);
CREATE INDEX idx_dsv_tenant ON datasource.data_source_vectors(tenant_id);
CREATE INDEX idx_dsv_item ON datasource.data_source_vectors(item_id);
