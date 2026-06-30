-- V239 - Audit 2026-05-17 round-6.
-- Round-4 added countPendingReviewsByOrganizationIdStrict in AgentTaskRepository:
--   SELECT COUNT(t) FROM agent_tasks t
--    WHERE t.organization_id = :orgId
--      AND t.reviewer_agent_id = :agentId
--      AND t.status = 'in_review'
-- Existing indexes (V67, V73, V210) cover (created_by_agent_id, status),
-- (assigned_to_agent_id, organization_id, status), and the org-only partial.
-- There is NO composite covering (reviewer_agent_id, organization_id, status),
-- so at >100k tasks per org the planner falls back to bitmap-AND with seq-scan.
--
-- This index closes the hot-path gap on the system-prompt task summary
-- (per-agent, fires on every sub-agent prompt injection).

CREATE INDEX IF NOT EXISTS idx_agent_tasks_reviewer_org_status
    ON agent.agent_tasks (reviewer_agent_id, organization_id, status)
    WHERE reviewer_agent_id IS NOT NULL;
