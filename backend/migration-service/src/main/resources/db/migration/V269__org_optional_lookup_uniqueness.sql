-- V269 -- Back DB uniqueness for org-scoped Optional<> repository lookups.
--
-- Context:
-- Recent V261+ strict-org repository methods moved several "single row" reads
-- from tenant scope to organization scope. The Java return type is Optional<>,
-- so the database must guarantee at most one matching active row. Without a DB
-- constraint, two org members can create duplicate rows that only collide later
-- as IncorrectResultSizeDataAccessException.
--
-- This migration is intentionally fail-loud for existing duplicates instead of
-- silently deactivating or deleting user resources. If the preflight raises,
-- inspect the listed rows, choose the keeper explicitly, then rerun.

DO $$
DECLARE
    problems TEXT;
BEGIN
    SELECT string_agg(
               format('%s organization_id=%s key=%s count=%s',
                      table_name, organization_id, lookup_key, row_count),
               E'\n')
      INTO problems
      FROM (
            SELECT 'trigger.standalone_webhooks' AS table_name,
                   organization_id,
                   source_node_id AS lookup_key,
                   count(*) AS row_count
              FROM "trigger".standalone_webhooks
             WHERE source_node_id IS NOT NULL
             GROUP BY organization_id, source_node_id
            HAVING count(*) > 1

            UNION ALL

            SELECT 'trigger.standalone_chat_endpoints' AS table_name,
                   organization_id,
                   source_node_id AS lookup_key,
                   count(*) AS row_count
              FROM "trigger".standalone_chat_endpoints
             WHERE source_node_id IS NOT NULL
             GROUP BY organization_id, source_node_id
            HAVING count(*) > 1

            UNION ALL

            SELECT 'trigger.standalone_form_endpoints' AS table_name,
                   organization_id,
                   source_node_id AS lookup_key,
                   count(*) AS row_count
              FROM "trigger".standalone_form_endpoints
             WHERE source_node_id IS NOT NULL
             GROUP BY organization_id, source_node_id
            HAVING count(*) > 1

            UNION ALL

            SELECT 'trigger.scheduled_executions' AS table_name,
                   organization_id,
                   source_node_id AS lookup_key,
                   count(*) AS row_count
              FROM "trigger".scheduled_executions
             WHERE source_node_id IS NOT NULL
             GROUP BY organization_id, source_node_id
            HAVING count(*) > 1
           ) d;

    IF problems IS NOT NULL THEN
        RAISE EXCEPTION E'V269 preflight failed: duplicate org-scoped trigger source_node_id rows:\n%', problems;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_standalone_webhooks_org_source_node
    ON "trigger".standalone_webhooks (organization_id, source_node_id)
    WHERE source_node_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_standalone_chat_endpoints_org_source_node
    ON "trigger".standalone_chat_endpoints (organization_id, source_node_id)
    WHERE source_node_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_standalone_form_endpoints_org_source_node
    ON "trigger".standalone_form_endpoints (organization_id, source_node_id)
    WHERE source_node_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_scheduled_executions_org_source_node
    ON "trigger".scheduled_executions (organization_id, source_node_id)
    WHERE source_node_id IS NOT NULL;

-- The V136 tenant-scoped indexes are now too strict: the same user can work in
-- multiple org workspaces. V263 made organization_id NOT NULL, so org scope is
-- the canonical uniqueness boundary.
DROP INDEX IF EXISTS "trigger".uq_standalone_webhooks_tenant_source_node;
DROP INDEX IF EXISTS "trigger".uq_standalone_chat_endpoints_tenant_source_node;
DROP INDEX IF EXISTS "trigger".uq_standalone_form_endpoints_tenant_source_node;
DROP INDEX IF EXISTS "trigger".uq_scheduled_executions_tenant_source_node;

DO $$
DECLARE
    problems TEXT;
BEGIN
    SELECT string_agg(
               format('agent.agents organization_id=%s name=%s count=%s',
                      organization_id, name, row_count),
               E'\n')
      INTO problems
      FROM (
            SELECT organization_id, name, count(*) AS row_count
              FROM agent.agents
             WHERE is_active IS TRUE
             GROUP BY organization_id, name
            HAVING count(*) > 1
            LIMIT 20
           ) d;

    IF problems IS NOT NULL THEN
        RAISE EXCEPTION E'V269 preflight failed: duplicate active agents per organization/name:\n%', problems;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agents_org_name_active
    ON agent.agents (organization_id, name)
    WHERE is_active IS TRUE;

DROP INDEX IF EXISTS agent.uq_agents_tenant_name_active;

DO $$
DECLARE
    problems TEXT;
BEGIN
    SELECT string_agg(
               format('conversation.conversations organization_id=%s workflow_id=%s count=%s',
                      organization_id, workflow_id, row_count),
               E'\n')
      INTO problems
      FROM (
            SELECT organization_id, workflow_id, count(*) AS row_count
              FROM conversation.conversations
             WHERE active IS TRUE
               AND workflow_id IS NOT NULL
             GROUP BY organization_id, workflow_id
            HAVING count(*) > 1
            LIMIT 20
           ) d;

    IF problems IS NOT NULL THEN
        RAISE EXCEPTION E'V269 preflight failed: duplicate active workflow conversations per organization/workflow:\n%', problems;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_conversations_org_workflow_active
    ON conversation.conversations (organization_id, workflow_id)
    WHERE active IS TRUE AND workflow_id IS NOT NULL;

DO $$
DECLARE
    problems TEXT;
BEGIN
    SELECT string_agg(
               format('publication.shared_links %s=%s count=%s',
                      lookup_name, lookup_key, row_count),
               E'\n')
      INTO problems
      FROM (
            SELECT 'resource_token' AS lookup_name,
                   resource_token AS lookup_key,
                   count(*) AS row_count
              FROM publication.shared_links
             WHERE is_active IS TRUE
             GROUP BY resource_token
            HAVING count(*) > 1

            UNION ALL

            SELECT 'resource_id' AS lookup_name,
                   resource_id::text AS lookup_key,
                   count(*) AS row_count
              FROM publication.shared_links
             WHERE is_active IS TRUE
               AND resource_id IS NOT NULL
             GROUP BY resource_id
            HAVING count(*) > 1
           ) d;

    IF problems IS NOT NULL THEN
        RAISE EXCEPTION E'V269 preflight failed: duplicate active shared links:\n%', problems;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_shared_links_active_resource_token
    ON publication.shared_links (resource_token)
    WHERE is_active IS TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_shared_links_active_resource_id
    ON publication.shared_links (resource_id)
    WHERE is_active IS TRUE AND resource_id IS NOT NULL;
