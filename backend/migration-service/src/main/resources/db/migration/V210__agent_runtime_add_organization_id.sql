-- PR20 - promote agent runtime tables' org context to first-class columns.
--
-- Context: PR15 V209 added `organization_id` + `organization_role` to
-- `orchestrator.workflow_runs`, and PR16 wired X-Organization-ID propagation
-- across every *-client RestTemplate call. With those in place the runtime
-- execution path knows the active org all the way through ExecutionContext,
-- but the 5 agent runtime tables that the UI history surfaces read from are
-- still tenant_id-only:
--
--   agent.agent_executions             - one row per agent loop run
--   agent.agent_execution_iterations   - one row per LLM call within a run
--   agent.agent_execution_messages     - full message stream per run
--   agent.agent_execution_tool_calls   - every tool call per run
--   agent.agent_tasks                  - async agent tasks (P0 inbox)
--
-- Visible bug: a team-workspace user opens the agent execution-history panel
-- (`AgentExecutionHistoryPanel.tsx` → `GET /agents/{id}/executions`) and sees
-- zero rows even though the agent has executed under that workspace. Reason:
-- `AgentExecutionRepository.findByAgentEntityIdAndTenantIdOrderByStartedAtDesc`
-- filters on tenant_id only, and rows persisted before PR15 carry the
-- account-root tenant_id, not the workspace tenant_id. The fix is to add
-- `organization_id` and route reads through strict-isolation finders
-- (org-mode reads org-only, personal-mode reads org_id IS NULL).
--
-- This migration:
--   1. ADD COLUMN organization_id VARCHAR(255) NULL to all 5 tables.
--   2. Cross-schema backfill of agent_executions from
--      orchestrator.workflow_runs.organization_id (joining on
--      agent_executions.workflow_run_id = workflow_runs.id). The
--      migration-service runs with multi-schema Flyway permissions so the
--      cross-schema UPDATE is the legitimate one-shot path.
--   3. Cascade backfill from agent_executions to its 3 child tables via
--      execution_id FK.
--   4. agent_tasks has no workflow_run_id FK → all existing rows stay NULL
--      (= personal scope). This is the correct semantics: tasks created
--      before org workspaces existed belong to personal scope by definition.
--   5. Indexes for the org-scoped read paths.
--
-- Lock posture:
--   - ALTER TABLE … ADD COLUMN NULL → ACCESS EXCLUSIVE, metadata-only on
--     PostgreSQL ≥11 (instant, no row rewrite).
--   - UPDATE … FROM cross-schema → row locks on backfillable rows only.
--   - CREATE INDEX CONCURRENTLY → SHARE UPDATE EXCLUSIVE, concurrent
--     reads/writes proceed during build. Requires non-transactional flyway.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + WHERE organization_id IS NULL +
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS. Safe to re-run.

-- flyway:executeInTransaction=false

-- ---------------------------------------------------------------------------
-- 1. ADD COLUMN organization_id on all 5 agent runtime tables.
-- ---------------------------------------------------------------------------

ALTER TABLE agent.agent_executions
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE agent.agent_execution_iterations
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE agent.agent_execution_messages
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE agent.agent_execution_tool_calls
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE agent.agent_tasks
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

-- ---------------------------------------------------------------------------
-- 2. Backfill agent_executions from owning orchestrator.workflow_runs.
--    PR15 already populated workflow_runs.organization_id from the legacy
--    metadata['__orgId__'] stash, so we can lean on that single source of
--    truth here. Rows with workflow_run_id IS NULL (orphan executions, e.g.
--    standalone chat-driven agent runs not attached to a workflow) stay
--    organization_id IS NULL → treated as personal scope downstream.
-- ---------------------------------------------------------------------------

UPDATE agent.agent_executions ae
   SET organization_id = wr.organization_id
  FROM orchestrator.workflow_runs wr
 WHERE ae.organization_id IS NULL
   AND ae.workflow_run_id IS NOT NULL
   AND ae.workflow_run_id = wr.id
   AND wr.organization_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Cascade backfill child tables from parent agent_executions.
--    execution_id is the FK shared across all 3 child tables; we propagate
--    the parent's org id wherever it is non-NULL. NULL parents (legacy or
--    personal-scope runs) leave children NULL.
-- ---------------------------------------------------------------------------

UPDATE agent.agent_execution_iterations c
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE c.organization_id IS NULL
   AND c.execution_id = e.id
   AND e.organization_id IS NOT NULL;

UPDATE agent.agent_execution_messages c
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE c.organization_id IS NULL
   AND c.execution_id = e.id
   AND e.organization_id IS NOT NULL;

UPDATE agent.agent_execution_tool_calls c
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE c.organization_id IS NULL
   AND c.execution_id = e.id
   AND e.organization_id IS NOT NULL;

-- agent.agent_tasks → no workflow_run_id, no backfill source. All existing
-- rows stay NULL (personal scope). New rows will be stamped by AgentTaskService
-- from ExecutionContext.organizationId / X-Organization-ID at creation time.

-- ---------------------------------------------------------------------------
-- 4. Indexes for org-scoped read paths.
--    The hot read path is "list executions for a given agent in a given org,
--    most-recent first" - composite (agent_entity_id, organization_id,
--    started_at DESC) lets the planner serve the team-workspace history
--    panel without a sort. Partial WHERE NOT NULL keeps the index small for
--    accounts that never opted into org workspaces.
--
--    The standalone (organization_id, started_at DESC) index supports the
--    cross-agent org-wide audit listing that PR24 observability adds later.
-- ---------------------------------------------------------------------------

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_executions_agent_org_started
    ON agent.agent_executions (agent_entity_id, organization_id, started_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_executions_org_started
    ON agent.agent_executions (organization_id, started_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_tasks_tenant_org_created
    ON agent.agent_tasks (tenant_id, organization_id, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_tasks_assigned_org
    ON agent.agent_tasks (assigned_to_agent_id, organization_id, status)
    WHERE assigned_to_agent_id IS NOT NULL;

-- Child tables (iterations/messages/tool_calls) read via execution_id FK +
-- parent-scope check in the service layer. The existing index on execution_id
-- is already sufficient - no per-child org index needed.

-- ---------------------------------------------------------------------------
-- 5. Column comments document the strict-isolation contract.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN agent.agent_executions.organization_id IS
    'Workspace the execution ran in. NULL = personal scope. Sourced from '
    'X-Organization-ID at AgentObservabilityService write time (or directly '
    'from ExecutionContext.organizationId when the run is workflow-driven). '
    'Reads via AgentExecutionRepository use strict-isolation finders: org '
    'workspace sees org_id = :orgId only; personal workspace sees '
    'org_id IS NULL only. NO mixing per CLAUDE.md strict-isolation contract.';

COMMENT ON COLUMN agent.agent_execution_iterations.organization_id IS
    'Mirrors parent agent_executions.organization_id, copied on insert by '
    'AgentObservabilityService.saveIterationsFromRequest. Lets the iteration '
    'list endpoint scope-check without an extra join.';

COMMENT ON COLUMN agent.agent_execution_messages.organization_id IS
    'Mirrors parent agent_executions.organization_id. Copied on insert by '
    'AgentObservabilityService.saveMessagesFromRequest.';

COMMENT ON COLUMN agent.agent_execution_tool_calls.organization_id IS
    'Mirrors parent agent_executions.organization_id. Copied on insert by '
    'AgentObservabilityService.saveToolCallsFromRequest.';

COMMENT ON COLUMN agent.agent_tasks.organization_id IS
    'Workspace the task belongs to. NULL = personal scope. Sourced from '
    'X-Organization-ID at AgentTaskService.assignTask / create time. Strict '
    'isolation enforced by AgentTaskRepository finders.';
