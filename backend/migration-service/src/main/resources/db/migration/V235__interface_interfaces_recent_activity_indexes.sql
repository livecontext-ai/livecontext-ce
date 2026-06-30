-- V235 - Partial-pair indexes for the /api/activities/recent aggregator
-- (fan-out branch #2: InternalInterfaceController.getRecentActivity in
-- interface-service). See V234 for the design rationale + kill-criteria.
--
-- flyway:executeInTransaction=false
-- ---------------------------------------------------------------------------

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interfaces_org_updated_at
    ON interface.interfaces (organization_id, updated_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_interfaces_tenant_updated_at_personal
    ON interface.interfaces (tenant_id, updated_at DESC)
    WHERE organization_id IS NULL;
