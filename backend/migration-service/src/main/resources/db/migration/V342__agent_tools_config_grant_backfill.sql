-- V342: Backfill the per-family GRANT sentinel on agent.agents.tools_config.
--
-- Context (the authoritative grant model). For the 5 internal resource families
-- {workflows, tables, interfaces, agents, applications} a per-family
-- `<family>Grant` ∈ {'none','all','custom'} is now the SINGLE source of truth for
-- whether the agent may touch that family - NO legacy list fallback:
--   * 'all'    → unrestricted
--   * 'custom' → scoped to the family's id list (the list is ONLY the custom payload)
--   * 'none'   → no access
-- The read side (AgentModuleResolver.isResourceAccessible, AgentConfigProvider.is*None)
-- treats an ABSENT grant as 'none' (deny) - never as the list, never as 'all' - so a
-- row that escapes this backfill can only LOSE access, never silently gain it.
-- AgentService.normalizeToolsConfig writes a grant on every create/update/clone/restore,
-- so post-deploy every NEW row is self-describing; this migration brings EXISTING rows
-- in line so snapshots, exports, and the frontend all agree (precedent: V163, which did
-- the same one-time backfill for the 5 lists).
--
-- BEHAVIOUR NO-OP for every existing agent: the derived grant reproduces exactly what
-- today's list rule already resolved to -
--   jsonb_array_length(coalesce(tools_config->'<family>','[]')) > 0  → 'custom'
--   else                                                             → 'none'
-- i.e. a non-empty list was "custom" (allowed, scoped) and an empty/absent list was
-- "none" (denied) under both the pre-change list rule and the new grant rule.
--
-- Idempotent: each grant is set ONLY when its key is ABSENT (NOT (... ? '<family>Grant')),
-- so a Flyway repair / re-run never overwrites an explicit grant (e.g. a hand-set 'all').

-- 1) tools_config IS NULL → fully self-describing, DENY-BY-DEFAULT object.
--    mode='all' (MCP catalogue default) + webSearch=true (product default), but all 5
--    family grants = 'none' (matches the authoritative "absent ⇒ none" rule). We do NOT
--    silently grant 'all' to a null-config agent. Lists seeded to [] so the row is
--    structurally identical to a V163-normalized row, just with grants added.
UPDATE agent.agents
SET tools_config = jsonb_build_object(
        'mode',             'all',
        'workflows',        '[]'::jsonb,
        'tables',           '[]'::jsonb,
        'interfaces',       '[]'::jsonb,
        'agents',           '[]'::jsonb,
        'applications',     '[]'::jsonb,
        'webSearch',        true,
        'workflowsGrant',   '"none"'::jsonb,
        'tablesGrant',      '"none"'::jsonb,
        'interfacesGrant',  '"none"'::jsonb,
        'agentsGrant',      '"none"'::jsonb,
        'applicationsGrant','"none"'::jsonb
    )
WHERE tools_config IS NULL;

-- 2) tools_config IS NOT NULL → derive each ABSENT grant from the current list.
--    Non-empty list ⇒ 'custom', empty/absent list ⇒ 'none'. coalesce handles a missing
--    list key (treated as []). Each statement only touches rows still missing that grant.
UPDATE agent.agents
SET tools_config = jsonb_set(
        tools_config, '{workflowsGrant}',
        to_jsonb(CASE WHEN jsonb_array_length(coalesce(tools_config->'workflows', '[]'::jsonb)) > 0
                      THEN 'custom' ELSE 'none' END),
        true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'workflowsGrant');

UPDATE agent.agents
SET tools_config = jsonb_set(
        tools_config, '{tablesGrant}',
        to_jsonb(CASE WHEN jsonb_array_length(coalesce(tools_config->'tables', '[]'::jsonb)) > 0
                      THEN 'custom' ELSE 'none' END),
        true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'tablesGrant');

UPDATE agent.agents
SET tools_config = jsonb_set(
        tools_config, '{interfacesGrant}',
        to_jsonb(CASE WHEN jsonb_array_length(coalesce(tools_config->'interfaces', '[]'::jsonb)) > 0
                      THEN 'custom' ELSE 'none' END),
        true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'interfacesGrant');

UPDATE agent.agents
SET tools_config = jsonb_set(
        tools_config, '{agentsGrant}',
        to_jsonb(CASE WHEN jsonb_array_length(coalesce(tools_config->'agents', '[]'::jsonb)) > 0
                      THEN 'custom' ELSE 'none' END),
        true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'agentsGrant');

UPDATE agent.agents
SET tools_config = jsonb_set(
        tools_config, '{applicationsGrant}',
        to_jsonb(CASE WHEN jsonb_array_length(coalesce(tools_config->'applications', '[]'::jsonb)) > 0
                      THEN 'custom' ELSE 'none' END),
        true)
WHERE tools_config IS NOT NULL AND NOT (tools_config ? 'applicationsGrant');

-- 3) Promote the App Factory builder. This is the ONE agent whose access the grant model
--    is meant to WIDEN: it CREATES a fresh workflow + interface + (sub-)agent + table +
--    application every run, so an empty pre-attached list must not deny it. Set all 5
--    builder families to grant='all' (user-confirmed: tables ALSO 'all') and ensure
--    workflowAccessMode='write' (it CREATES, not merely reads). accessMode is the SEPARATE
--    R/W axis (enforced at the tool layer); grant='all' just removes the "no access" wall.
--    Scoped to the single known builder id, so the deny-by-default posture of every other
--    agent is untouched. Idempotent (re-running just re-sets the same values).
UPDATE agent.agents
SET tools_config = jsonb_set(jsonb_set(jsonb_set(jsonb_set(jsonb_set(jsonb_set(
        coalesce(tools_config, '{}'::jsonb),
        '{workflowsGrant}',    '"all"'::jsonb, true),
        '{interfacesGrant}',   '"all"'::jsonb, true),
        '{applicationsGrant}', '"all"'::jsonb, true),
        '{agentsGrant}',       '"all"'::jsonb, true),
        '{tablesGrant}',       '"all"'::jsonb, true),
        '{workflowAccessMode}','"write"'::jsonb, true)
WHERE id = '72f2a86f-ccf1-4d81-a159-4b6f683b973c';
