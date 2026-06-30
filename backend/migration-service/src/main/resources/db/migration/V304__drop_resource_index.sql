-- V304 - Drop the per-tenant `resource_index` numbering feature (contract phase).
--
-- Context: `resource_index` (+ the `auth.tenant_resource_counters` counter table and
-- the auth `/claim-index` endpoint) gave each resource a per-tenant sequential number
-- starting at 1. Audit (see ResourceIndex removal) found it:
--   * never exposed to the LLM agent (no MCP response field, no lookup-by-number),
--   * never wired for the "consistent ordering" it was built for (nothing sorts by it),
--   * only ever a UI fallback label ("Table #N") shown when a resource had no name,
--   * broken in prod (45% of workflows at index 0 from acquire/clone paths that never
--     claimed; per-tenant counter never followed the org model → cross-member collisions).
--
-- The application code that read/wrote these columns has been removed in the prior
-- deploy (expand/contract: code first, this DROP second). This migration is the
-- cleanup that keeps prod consistent. No FK / UNIQUE / index references `resource_index`,
-- so the drop is clean - no orphan data.
--
-- Why code MUST ship before this DROP (rolling-deploy safety): the now-removed code
-- still living on old replicas references `resource_index` in raw SQL - datasource &
-- credential INSERTs name the column and their RowMappers call rs.getInt("resource_index").
-- If the column vanished mid-rollout those old replicas would error. Keeping the column
-- (NOT NULL DEFAULT 0) lets the NEW code, which omits it entirely, coexist with the
-- still-present column until every replica is updated; then this DROP runs safely.
-- (This is NOT a JPA ddl-auto=validate constraint: the JPA entities that mapped the
-- column run ddl-auto=none, and Credential is a JDBC-backed record, not an @Entity.)
--
-- Idempotent: DROP ... IF EXISTS on every object.

ALTER TABLE orchestrator.workflows               DROP COLUMN IF EXISTS resource_index;
ALTER TABLE agent.agents                          DROP COLUMN IF EXISTS resource_index;
ALTER TABLE agent.skills                          DROP COLUMN IF EXISTS resource_index;
ALTER TABLE "trigger".scheduled_executions        DROP COLUMN IF EXISTS resource_index;
ALTER TABLE "trigger".standalone_webhooks         DROP COLUMN IF EXISTS resource_index;
ALTER TABLE "trigger".standalone_chat_endpoints   DROP COLUMN IF EXISTS resource_index;
ALTER TABLE "trigger".standalone_form_endpoints   DROP COLUMN IF EXISTS resource_index;
ALTER TABLE interface.interfaces                  DROP COLUMN IF EXISTS resource_index;
ALTER TABLE datasource.data_sources               DROP COLUMN IF EXISTS resource_index;
ALTER TABLE auth.credentials                      DROP COLUMN IF EXISTS resource_index;

-- The counter table backing the now-deleted TenantResourceCounterService / claim-index.
DROP TABLE IF EXISTS auth.tenant_resource_counters;
