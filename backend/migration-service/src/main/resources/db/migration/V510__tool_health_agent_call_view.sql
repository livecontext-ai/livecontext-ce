-- V510: a projection of agent.agent_execution_tool_calls for the Grafana "Catalog - Tool health"
-- row, so the dashboard's read-only role never needs SELECT on the base table.
--
-- WHY A VIEW RATHER THAN A COLUMN GRANT. The row's agent lane needs two fields out of the
-- `arguments` jsonb: the action, and the tool_id. A column grant is the whole column or nothing,
-- and that column holds the FULL serialized argument map of every agent tool call, for every
-- tenant (AgentObservabilityService stores it verbatim). Granting it would hand anyone with
-- Editor rights on that Grafana every tenant's tool parameters - exactly the reader the
-- accompanying role file is written to constrain. A view is the only way Postgres lets a grant
-- name a jsonb PATH.
--
-- The view is owned by the migration role and `security_invoker` is left at its default (off), so
-- a SELECT through it runs with the owner's rights: grafana_ro reads these eight columns and has
-- no privilege at all on agent.agent_execution_tool_calls. Removing the view's grant removes the
-- access; there is no second door.
--
-- NOT read-only by construction. Postgres keeps a single-table view like this auto-updatable
-- (information_schema reports is_updatable = YES, and a DELETE through it as the owner does delete
-- the base row). What stops the dashboard role writing is that it is granted SELECT and nothing
-- else - the same sentence the role file makes. Saying the view itself is a barrier would be a
-- defence that is not there.
--
-- Kept deliberately narrow. It is not a general-purpose observability view: every column here is
-- read by a panel in that row, and ToolHealthDashboardContractTest asserts that correspondence in
-- both directions, so widening it without a reader fails the build.

CREATE OR REPLACE VIEW agent.tool_call_health AS
SELECT c.tool_name                        AS tool_name,
       c.arguments ->> 'action'           AS action,
       -- Lower-cased here so an upper-case UUID is the same tool downstream, and so the panels
       -- never touch `arguments` themselves.
       lower(c.arguments ->> 'tool_id')   AS tool_key,
       c.success                          AS success,
       c.error_message                    AS error_message,
       c.tenant_id                        AS tenant_id,
       c.consecutive_count                AS consecutive_count,
       c.created_at                       AS created_at
  FROM agent.agent_execution_tool_calls c;

COMMENT ON VIEW agent.tool_call_health IS
    'Narrow projection of agent_execution_tool_calls for the Grafana tool-health row. Exists so '
    'the dashboard role can read the action and tool_id out of `arguments` without being granted '
    'the column itself, which carries every tenant''s full tool parameters.';
