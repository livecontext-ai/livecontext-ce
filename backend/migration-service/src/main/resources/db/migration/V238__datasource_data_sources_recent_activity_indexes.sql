-- V238 - Partial-pair indexes for the /api/activities/recent aggregator
-- (fan-out branch #4: datasource-service data_sources). See V234 for design
-- rationale.
--
-- flyway:executeInTransaction=false
-- ---------------------------------------------------------------------------

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_data_sources_org_updated_at
    ON datasource.data_sources (organization_id, updated_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_data_sources_tenant_updated_at_personal
    ON datasource.data_sources (tenant_id, updated_at DESC)
    WHERE organization_id IS NULL;
