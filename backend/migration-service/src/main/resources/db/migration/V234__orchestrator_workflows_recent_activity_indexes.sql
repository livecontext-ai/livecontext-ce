-- V234 - Partial-pair indexes for the /api/activities/recent aggregator
-- (RecentActivityAggregatorService, fan-out branch #1: orchestrator-own DB
-- query for workflows + applications). One scope-strict index, both required
-- because app-host serves mixed-mode tenants (orgs + personal users on the same
-- instance). The two are NEVER read in the same query - the aggregator routes
-- on `orgId != null` to exactly one of them (mirrors ActiveAutomationsService
-- :149-155 strict-finder pattern, PR22/PR30 contract).
--
-- Kill-criteria: if `SELECT * FROM pg_stat_user_indexes WHERE indexrelname
-- IN (...) AND idx_scan < N` shows <5% scans after 30d, drop the unused
-- partial in V239+. Vacuum + WAL cost on hot CRUD paths otherwise.
--
-- Flyway directive: `executeInTransaction=false` is REQUIRED - CREATE INDEX
-- CONCURRENTLY cannot run inside a tx (mirrors V225/V208-V214 convention).
-- Post-deploy invariant: `SELECT * FROM pg_index WHERE NOT indisvalid` MUST
-- return zero rows for the 2 new indexes (partially-failed CONCURRENTLY
-- leaves indisvalid=false rows requiring manual `DROP INDEX` + re-run).
-- ---------------------------------------------------------------------------
-- flyway:executeInTransaction=false
-- ---------------------------------------------------------------------------

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflows_org_updated_at
    ON orchestrator.workflows (organization_id, updated_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflows_tenant_updated_at_personal
    ON orchestrator.workflows (tenant_id, updated_at DESC)
    WHERE organization_id IS NULL;
