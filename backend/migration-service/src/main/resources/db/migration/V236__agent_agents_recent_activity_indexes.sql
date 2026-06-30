-- V236 - Partial-pair indexes for the /api/activities/recent aggregator
-- (fan-out branch #3a: agent-service returns agents + skills as one union;
-- this migration covers the agents table). See V234 for design rationale.
--
-- flyway:executeInTransaction=false
-- ---------------------------------------------------------------------------

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agents_org_updated_at
    ON agent.agents (organization_id, updated_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agents_tenant_updated_at_personal
    ON agent.agents (tenant_id, updated_at DESC)
    WHERE organization_id IS NULL;
