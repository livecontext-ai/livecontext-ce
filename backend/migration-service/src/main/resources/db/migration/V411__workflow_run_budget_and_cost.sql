-- Workflow-level budget (advanced setting) + per-run accumulated cost.
--
-- Cost is always stored in CREDITS internally (1 credit = $0.001). The CE
-- edition renders it as dollars, the cloud edition renders raw credits; that
-- split is a frontend display concern (see frontend/lib/format-cost.ts).
--
-- Agent executions are the ONLY thing that consumes credits inside a workflow
-- run, so the accumulated run cost is fed by agent-service observability
-- (AgentObservabilityService) notifying the orchestrator after each agent
-- execution settles its credits.

-- budget_credits: workflow/application budget. NULL = no budget set. Applies
-- to the run's total accumulated cost across all epochs: when the total reaches
-- the budget, no NEW epoch is allowed to start (the running epoch finishes).
ALTER TABLE orchestrator.workflows
    ADD COLUMN IF NOT EXISTS budget_credits NUMERIC(15,4);

-- cost_credits: total accumulated cost across ALL epochs of this run. Written
-- ONLY by RunCostService's native increment (bypasses Hibernate), never by an
-- entity flush - the JPA column is updatable=false so a stray save(run) can
-- never clobber the live DB value with a stale in-memory copy (same fence as
-- state_snapshot). Monotonic, so the increment is safe under concurrency.
ALTER TABLE orchestrator.workflow_runs
    ADD COLUMN IF NOT EXISTS cost_credits NUMERIC(15,4) NOT NULL DEFAULT 0;

-- cost_by_epoch: per-epoch breakdown {"1": 0.42, "2": 1.05}, incremented in
-- lockstep with cost_credits inside the same UPDATE. A plain scalar jsonb
-- column - deliberately NOT part of state_snapshot, so it never routes through
-- the gated snapshot patch path (JsonbPatchExecutor).
ALTER TABLE orchestrator.workflow_runs
    ADD COLUMN IF NOT EXISTS cost_by_epoch JSONB NOT NULL DEFAULT '{}'::jsonb;
