-- V217 - PR27.2 closeout: add organization_id to skills, skill_folders,
-- and agent_task_recurrences to complete the strict-isolation foundation
-- for the agent-service.
--
-- Context: PR23 V210 added organization_id to the 5 core agent runtime
-- tables (agent_executions, *_iterations, *_messages, *_tool_calls,
-- agent_tasks). The remaining agent-service tables - skills, skill_folders,
-- agent_task_recurrences - were left tenant-only because they are not runtime
-- data paths, but the PR24 scope audit flagged them as residual cross-scope
-- leak surfaces.
--
-- Backfill policy: NULL is intentional for pre-existing rows. They stay as
-- personal until manually re-tagged via UI re-creation. No automatic backfill
-- from agent.organization_id because skills are user-owned at their tenant
-- level, not derivative of any single agent.

ALTER TABLE agent.skills
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_skills_organization_id
    ON agent.skills (organization_id)
    WHERE organization_id IS NOT NULL;

COMMENT ON COLUMN agent.skills.organization_id IS
    'PR27.2 - workspace tag. NULL = personal scope. Matches the strict-isolation chain pattern: org-strict reads filter on org_id, personal-strict reads require organization_id IS NULL.';

ALTER TABLE agent.skill_folders
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_skill_folders_organization_id
    ON agent.skill_folders (organization_id)
    WHERE organization_id IS NOT NULL;

COMMENT ON COLUMN agent.skill_folders.organization_id IS
    'PR27.2 - workspace tag. See agent.skills.organization_id.';

ALTER TABLE agent.agent_task_recurrences
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_agent_task_recurrences_organization_id
    ON agent.agent_task_recurrences (organization_id)
    WHERE organization_id IS NOT NULL;

COMMENT ON COLUMN agent.agent_task_recurrences.organization_id IS
    'PR27.2 - workspace tag. Recurrence schedules fire tasks that inherit this org. See agent.skills.organization_id for the contract.';
