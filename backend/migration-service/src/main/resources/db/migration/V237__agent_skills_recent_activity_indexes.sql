-- V237 - Partial-pair indexes for the /api/activities/recent aggregator
-- (fan-out branch #3b: agent-service skills, returned union'd with agents).
-- See V234 for design rationale.
--
-- flyway:executeInTransaction=false
-- ---------------------------------------------------------------------------

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_skills_org_updated_at
    ON agent.skills (organization_id, updated_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_skills_tenant_updated_at_personal
    ON agent.skills (tenant_id, updated_at DESC)
    WHERE organization_id IS NULL;
