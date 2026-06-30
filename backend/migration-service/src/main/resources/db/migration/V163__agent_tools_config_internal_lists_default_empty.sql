-- V163: Backfill the 5 internal resource list keys on agent.agents.tools_config
--
-- Security rule: for {workflows, tables, interfaces, agents, applications}, an
-- absent key MUST NEVER mean "all". Pre-V163 the runtime treated absent keys as
-- "no restriction" (AgentConfigProvider.is*None returned false for null), which
-- silently granted legacy agents full tenant access to every internal resource.
--
-- The Java side already enforces this: AgentService.normalizeToolsConfig backfills
-- on every create / update / clone / restoreFromSnapshot, the read-side flip in
-- AgentConfigProvider treats absent as denied, and SubAgentExecutionHandler /
-- AgentNode now write `[]` into allowed*Ids for absent keys. This migration brings
-- the persisted state of legacy rows in line with the new contract so downstream
-- snapshots, exports, and the frontend display all agree.
--
-- Behavior:
--   - tools_config IS NULL → set to a fully self-describing object with all 5
--     internal lists at [], `mode='all'` (MCP catalogue defaults), and
--     `webSearch=true` (product default). Seeding `mode` + `webSearch` makes
--     every persisted row self-describing - readers do not have to fall back
--     to runtime defaults, and DB inspection unambiguously shows intent.
--   - tools_config IS NOT NULL but missing one of the 5 keys → jsonb_set adds `[]`
--   - tools_config has the key (even with []) → untouched
--
-- Note: this migration is "eventual" - a row inserted after V163 by code that
-- bypasses AgentService.normalizeToolsConfig (none should exist; all create/
-- update/clone/restoreFromSnapshot/remap-tools-config paths route through it)
-- will NOT be backfilled. Each statement is idempotent on its own (WHERE clause
-- filters out already-fixed rows), so a Flyway repair / re-run is safe.
--
-- This is "strict" mode (audit recommendation 4 in the consolidated plan): any
-- agent that was relying on the absent-key loophole loses access and the user
-- must re-grant explicitly via the modal or `agent(action='update')`. We accept
-- the visible breakage over a silent over-permission. To audit blast radius
-- post-deploy, cross-check pre-migration counts against ledger:
--   SELECT count(*) FILTER (WHERE NOT (tools_config ? 'workflows')) AS w_missing,
--          count(*) FILTER (WHERE NOT (tools_config ? 'tables')) AS t_missing, ...
--   FROM agent.agents;

UPDATE agent.agents
SET tools_config = jsonb_build_object(
        'mode',         'all',
        'workflows',    '[]'::jsonb,
        'tables',       '[]'::jsonb,
        'interfaces',   '[]'::jsonb,
        'agents',       '[]'::jsonb,
        'applications', '[]'::jsonb,
        'webSearch',    true
    )
WHERE tools_config IS NULL;

UPDATE agent.agents
SET tools_config = jsonb_set(tools_config, '{workflows}', '[]'::jsonb, true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'workflows');

UPDATE agent.agents
SET tools_config = jsonb_set(tools_config, '{tables}', '[]'::jsonb, true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'tables');

UPDATE agent.agents
SET tools_config = jsonb_set(tools_config, '{interfaces}', '[]'::jsonb, true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'interfaces');

UPDATE agent.agents
SET tools_config = jsonb_set(tools_config, '{agents}', '[]'::jsonb, true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'agents');

UPDATE agent.agents
SET tools_config = jsonb_set(tools_config, '{applications}', '[]'::jsonb, true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'applications');
