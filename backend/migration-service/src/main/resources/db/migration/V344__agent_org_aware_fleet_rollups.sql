-- ============================================================================
-- V344: Org-aware incremental rollup tables for the Agent Fleet batch stats
--       (GET /agents/stats). "Option D" - replace the four full-history
--       GROUP-BY scans in AgentMetricsQueryService.getAll* with reads from
--       these incrementally-maintained rollups.
--
-- ADDITIVE + NON-DESTRUCTIVE: brand-new tables. The legacy tenant-keyed
-- *_live tables (agent_tool_call_stats_by_agent_live, agent_sub_agent_call_stats_live)
-- are left UNTOUCHED so a rolling deploy's old pods keep writing their old shape
-- harmlessly and a code rollback stays safe (old image reads via the raw scan,
-- unaffected by these tables). PR29 abandoned the legacy tables for org-aware
-- reads precisely because they are PK'd on tenant_id with no organization_id;
-- these carry organization_id as the LEADING key column so the org-strict read
-- (WHERE organization_id = :orgId) is a PK-prefix scan.
--
-- Two of the four rollups did not exist in any form before: the per-RESOURCE
-- breakdown (resource id extracted from tool-call arguments) and the
-- per-AGENT-per-MODEL execution rollup.
-- ============================================================================

-- Per-(agent, tool) call stats. Mirrors agent_tool_call_stats_by_agent_live but
-- org-keyed and with an extra duration_sample_count so the read can reproduce
-- AVG(duration_ms)-ignoring-NULL exactly (total_duration_ms / duration_sample_count)
-- instead of being skewed by NULL durations coerced to 0.
CREATE TABLE IF NOT EXISTS agent.agent_tool_call_stats_by_agent_org_live (
    organization_id        TEXT         NOT NULL,
    tenant_id              TEXT         NOT NULL,
    agent_entity_id        UUID         NOT NULL,
    tool_name              VARCHAR(255) NOT NULL,
    total_calls            BIGINT       NOT NULL DEFAULT 0,
    success_count          BIGINT       NOT NULL DEFAULT 0,
    failure_count          BIGINT       NOT NULL DEFAULT 0,
    total_duration_ms      BIGINT       NOT NULL DEFAULT 0,
    duration_sample_count  BIGINT       NOT NULL DEFAULT 0,
    max_duration_ms        BIGINT       NOT NULL DEFAULT 0,
    last_used_at           TIMESTAMPTZ,
    repeat_call_count      BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, tenant_id, agent_entity_id, tool_name)
);

-- Per-(agent, resource-family tool, resource_id) call stats. resource_id is the
-- specific entity the agent operated on, extracted from the tool-call arguments
-- at WRITE time (the read used to extract it from JSONB at query time).
CREATE TABLE IF NOT EXISTS agent.agent_resource_call_stats_by_agent_org_live (
    organization_id  TEXT         NOT NULL,
    tenant_id        TEXT         NOT NULL,
    agent_entity_id  UUID         NOT NULL,
    tool_name        VARCHAR(255) NOT NULL,
    resource_id      TEXT         NOT NULL,
    total_calls      BIGINT       NOT NULL DEFAULT 0,
    success_count    BIGINT       NOT NULL DEFAULT 0,
    failure_count    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, tenant_id, agent_entity_id, tool_name, resource_id)
);

-- Per-(caller, callee) sub-agent call stats. Fed from the CALLEE execution's own
-- finalize (success/failure = resolved execution status), matching the live read
-- getAllSubAgentCallStats which counts callee execution rows by status - NOT from
-- the caller's 'agent' tool-call success (a different event with different grain).
CREATE TABLE IF NOT EXISTS agent.agent_sub_agent_call_stats_org_live (
    organization_id  TEXT   NOT NULL,
    tenant_id        TEXT   NOT NULL,
    caller_agent_id  UUID   NOT NULL,
    callee_agent_id  UUID   NOT NULL,
    total_calls      BIGINT NOT NULL DEFAULT 0,
    success_count    BIGINT NOT NULL DEFAULT 0,
    failure_count    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, tenant_id, caller_agent_id, callee_agent_id)
);

-- Per-(agent, model) execution stats. budget_exhausted_count is a STRICT SUBSET of
-- failure_count (incremented only when the row also counted as FAILED), matching
-- the read's COUNT(*) FILTER (status='FAILED' AND stop_reason='BUDGET_EXHAUSTED').
CREATE TABLE IF NOT EXISTS agent.agent_model_exec_stats_by_agent_org_live (
    organization_id         TEXT         NOT NULL,
    tenant_id               TEXT         NOT NULL,
    agent_entity_id         UUID         NOT NULL,
    model                   VARCHAR(255) NOT NULL,
    total_executions        BIGINT       NOT NULL DEFAULT 0,
    success_count           BIGINT       NOT NULL DEFAULT 0,
    failure_count           BIGINT       NOT NULL DEFAULT 0,
    budget_exhausted_count  BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, tenant_id, agent_entity_id, model)
);

-- GC indexes: agent deletion purges by (tenant_id, agent_entity_id), which is not a
-- PK-prefix (the PK leads with organization_id), so give the cleanup a usable index.
CREATE INDEX IF NOT EXISTS idx_tool_by_agent_org_live_gc
    ON agent.agent_tool_call_stats_by_agent_org_live (tenant_id, agent_entity_id);
CREATE INDEX IF NOT EXISTS idx_resource_by_agent_org_live_gc
    ON agent.agent_resource_call_stats_by_agent_org_live (tenant_id, agent_entity_id);
CREATE INDEX IF NOT EXISTS idx_model_by_agent_org_live_gc
    ON agent.agent_model_exec_stats_by_agent_org_live (tenant_id, agent_entity_id);
-- Sub-agent GC deletes by caller OR callee.
CREATE INDEX IF NOT EXISTS idx_subagent_org_live_caller_gc
    ON agent.agent_sub_agent_call_stats_org_live (tenant_id, caller_agent_id);
CREATE INDEX IF NOT EXISTS idx_subagent_org_live_callee_gc
    ON agent.agent_sub_agent_call_stats_org_live (tenant_id, callee_agent_id);
