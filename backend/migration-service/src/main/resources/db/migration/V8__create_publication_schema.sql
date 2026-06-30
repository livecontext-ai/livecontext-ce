-- ============================================================================
-- V8: Publication Schema (consolidated)
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS publication;
SET search_path TO publication;

CREATE TABLE IF NOT EXISTS workflow_publications (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    showcase_interface_id UUID,
    showcase_run_id VARCHAR(255),
    category_id UUID,
    category_slug VARCHAR(100),
    category_name VARCHAR(255),
    category_icon_slug VARCHAR(100),
    category_color VARCHAR(50),
    plan_snapshot JSONB NOT NULL,
    plan_version INTEGER,
    snapshot_version INTEGER,
    node_icons JSONB,
    credits_per_use INTEGER NOT NULL DEFAULT 0,
    publisher_id VARCHAR(255) NOT NULL,
    publisher_name VARCHAR(255),
    publisher_email VARCHAR(255),
    publisher_avatar_url TEXT,
    project_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    visibility VARCHAR(50) NOT NULL DEFAULT 'PUBLIC',
    display_mode VARCHAR(50) NOT NULL DEFAULT 'WORKFLOW',
    interface_count INTEGER NOT NULL DEFAULT 0,
    datasource_count INTEGER NOT NULL DEFAULT 0,
    use_count INTEGER NOT NULL DEFAULT 0,
    total_credits_earned INTEGER NOT NULL DEFAULT 0,
    average_rating DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    review_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT,
    published_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_publications_publisher ON workflow_publications(publisher_id);
CREATE INDEX IF NOT EXISTS idx_publications_status_visibility ON workflow_publications(status, visibility);

CREATE TABLE IF NOT EXISTS publication_receipts (
    tenant_id VARCHAR(255) NOT NULL,
    publication_id UUID NOT NULL,
    credits_paid INTEGER NOT NULL DEFAULT 0,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, publication_id)
);

CREATE TABLE IF NOT EXISTS publication_reviews (
    id UUID PRIMARY KEY,
    publication_id UUID NOT NULL,
    reviewer_id VARCHAR(255) NOT NULL,
    reviewer_name VARCHAR(255),
    reviewer_avatar_url TEXT,
    parent_id UUID,
    rating SMALLINT,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reviews_publication ON publication_reviews(publication_id);

CREATE TABLE IF NOT EXISTS publication_snapshot_versions (
    id UUID PRIMARY KEY,
    publication_id UUID NOT NULL,
    version INTEGER NOT NULL,
    plan_snapshot JSONB NOT NULL,
    label VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(publication_id, version)
);

CREATE TABLE IF NOT EXISTS shared_links (
    id UUID PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    resource_type VARCHAR(30) NOT NULL,
    resource_token VARCHAR(64) NOT NULL,
    resource_id UUID,
    tenant_id VARCHAR(128) NOT NULL,
    title VARCHAR(256),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    access_config JSONB DEFAULT '{}'::jsonb,
    metadata JSONB DEFAULT '{}'::jsonb,
    access_count BIGINT NOT NULL DEFAULT 0,
    last_accessed TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_shared_links_token ON shared_links(token);
CREATE INDEX IF NOT EXISTS idx_shared_links_tenant ON shared_links(tenant_id, created_at DESC);
