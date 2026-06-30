-- ============================================================================
-- V345: One-shot backfill of the V344 org-aware fleet rollups from raw history
--       (agent.agent_executions / agent.agent_execution_tool_calls).
--
-- Each INSERT...SELECT is deliberately byte-identical in its FILTER / GROUP BY /
-- resource-id-extraction clauses to the corresponding live scan in
-- AgentMetricsQueryService.getAll*, so a post-backfill rollup read equals the
-- pre-option-D scan result. This IS the regression oracle: rollup-read == raw-scan.
--
-- Idempotent (ON CONFLICT DO NOTHING) so a re-run is a no-op. Set-based single
-- statements (no row loops); the one-time cost is bounded by current history size.
-- Backfill keys by (organization_id, tenant_id, ...) to match the V344 PK; the
-- reads re-aggregate across tenant (GROUP BY the non-tenant key) so this matches
-- the org-scoped scans which never group by tenant.
-- ============================================================================

-- Tool stats. duration_sample_count counts only non-NULL durations so the read's
-- AVG = total_duration_ms / NULLIF(duration_sample_count,0) reproduces the scan's
-- AVG(tc.duration_ms) which ignores NULLs.
INSERT INTO agent.agent_tool_call_stats_by_agent_org_live
    (organization_id, tenant_id, agent_entity_id, tool_name,
     total_calls, success_count, failure_count,
     total_duration_ms, duration_sample_count, max_duration_ms, last_used_at, repeat_call_count)
SELECT ae.organization_id, ae.tenant_id, ae.agent_entity_id, tc.tool_name,
       COUNT(*),
       COUNT(*) FILTER (WHERE tc.success),
       COUNT(*) FILTER (WHERE NOT tc.success),
       COALESCE(SUM(tc.duration_ms) FILTER (WHERE tc.duration_ms IS NOT NULL), 0),
       COUNT(*) FILTER (WHERE tc.duration_ms IS NOT NULL),
       COALESCE(MAX(tc.duration_ms), 0),
       MAX(tc.created_at),
       COUNT(*) FILTER (WHERE tc.is_repeat)
FROM agent.agent_execution_tool_calls tc
JOIN agent.agent_executions ae ON tc.execution_id = ae.id
WHERE ae.agent_entity_id IS NOT NULL AND ae.organization_id IS NOT NULL
GROUP BY ae.organization_id, ae.tenant_id, ae.agent_entity_id, tc.tool_name
ON CONFLICT DO NOTHING;

-- Per-resource stats. resource_id via the EXACT family COALESCE precedence the
-- read uses; rows whose extracted resource_id is NULL are dropped (no leaf).
INSERT INTO agent.agent_resource_call_stats_by_agent_org_live
    (organization_id, tenant_id, agent_entity_id, tool_name, resource_id,
     total_calls, success_count, failure_count)
SELECT ae.organization_id, ae.tenant_id, ae.agent_entity_id, tc.tool_name, r.rid,
       COUNT(*),
       COUNT(*) FILTER (WHERE tc.success),
       COUNT(*) FILTER (WHERE NOT tc.success)
FROM agent.agent_execution_tool_calls tc
JOIN agent.agent_executions ae ON tc.execution_id = ae.id
CROSS JOIN LATERAL (
    SELECT CASE tc.tool_name
        WHEN 'table'       THEN COALESCE(tc.arguments->>'table_id', tc.arguments->>'datasource_id', tc.arguments->>'id')
        WHEN 'interface'   THEN COALESCE(tc.arguments->>'interface_id', tc.arguments->>'id')
        WHEN 'workflow'    THEN COALESCE(tc.arguments->>'workflow_id', tc.arguments->>'id')
        WHEN 'application' THEN tc.arguments->>'application_id'
        WHEN 'skill'       THEN tc.arguments->>'skill_id'
    END AS rid
) r
WHERE ae.agent_entity_id IS NOT NULL AND ae.organization_id IS NOT NULL
  AND tc.tool_name IN ('table', 'interface', 'workflow', 'application', 'skill')
  AND r.rid IS NOT NULL
GROUP BY ae.organization_id, ae.tenant_id, ae.agent_entity_id, tc.tool_name, r.rid
ON CONFLICT DO NOTHING;

-- Sub-agent stats from the CALLEE execution rows by resolved status (matches the
-- live read, which FILTERs on agent_executions.status, NOT on tool-call success).
INSERT INTO agent.agent_sub_agent_call_stats_org_live
    (organization_id, tenant_id, caller_agent_id, callee_agent_id,
     total_calls, success_count, failure_count)
SELECT ae.organization_id, ae.tenant_id, ae.caller_agent_entity_id, ae.agent_entity_id,
       COUNT(*),
       COUNT(*) FILTER (WHERE ae.status = 'COMPLETED'),
       COUNT(*) FILTER (WHERE ae.status = 'FAILED')
FROM agent.agent_executions ae
WHERE ae.caller_agent_entity_id IS NOT NULL
  AND ae.agent_entity_id IS NOT NULL
  AND ae.organization_id IS NOT NULL
GROUP BY ae.organization_id, ae.tenant_id, ae.caller_agent_entity_id, ae.agent_entity_id
ON CONFLICT DO NOTHING;

-- Per-(agent, model) execution stats. budget_exhausted is the status='FAILED' AND
-- stop_reason='BUDGET_EXHAUSTED' subset, exactly as the read computes it.
INSERT INTO agent.agent_model_exec_stats_by_agent_org_live
    (organization_id, tenant_id, agent_entity_id, model,
     total_executions, success_count, failure_count, budget_exhausted_count)
SELECT ae.organization_id, ae.tenant_id, ae.agent_entity_id, ae.model,
       COUNT(*),
       COUNT(*) FILTER (WHERE ae.status = 'COMPLETED'),
       COUNT(*) FILTER (WHERE ae.status = 'FAILED'),
       COUNT(*) FILTER (WHERE ae.status = 'FAILED' AND ae.stop_reason = 'BUDGET_EXHAUSTED')
FROM agent.agent_executions ae
WHERE ae.agent_entity_id IS NOT NULL
  AND ae.model IS NOT NULL
  AND ae.organization_id IS NOT NULL
GROUP BY ae.organization_id, ae.tenant_id, ae.agent_entity_id, ae.model
ON CONFLICT DO NOTHING;
