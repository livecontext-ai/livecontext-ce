package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionIterationEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only query service for agent metrics and observability data.
 */
@Service
@Transactional(readOnly = true)
public class AgentMetricsQueryService {

    private final AgentExecutionRepository executionRepository;
    private final AgentExecutionMessageRepository messageRepository;
    private final AgentExecutionToolCallRepository toolCallRepository;
    private final AgentExecutionIterationRepository iterationRepository;
    private final EntityManager entityManager;

    public AgentMetricsQueryService(
            AgentExecutionRepository executionRepository,
            AgentExecutionMessageRepository messageRepository,
            AgentExecutionToolCallRepository toolCallRepository,
            AgentExecutionIterationRepository iterationRepository,
            EntityManager entityManager) {
        this.executionRepository = executionRepository;
        this.messageRepository = messageRepository;
        this.toolCallRepository = toolCallRepository;
        this.iterationRepository = iterationRepository;
        this.entityManager = entityManager;
    }

    /**
     * Post-V263 strict-isolation execution history. {@code organizationId} MUST
     * be non-blank - the legacy 2-arg overload that defaulted to a personal-scope
     * IS NULL branch was deleted (round-8, 2026-05-20) because that branch
     * returned zero rows after V262 backfill and would NPE inside
     * {@code TenantResolver.requireOrgId} on V263 ingress paths.
     */
    public Page<AgentExecutionEntity> getAgentExecutions(UUID agentId, String tenantId,
                                                          String organizationId, Pageable pageable) {
        TenantResolver.requireOrgId(organizationId);
        return executionRepository.findByAgentEntityIdAndOrganizationIdStrict(
            agentId, organizationId, pageable);
    }

    /**
     * Get a single execution by ID - caller is responsible for scope-checking the
     * returned entity. Prefer {@link #getExecutionForScope} when an org/tenant
     * context is available.
     */
    public Optional<AgentExecutionEntity> getExecution(UUID executionId) {
        return executionRepository.findById(executionId);
    }

    /**
     * PR20 - strict-isolation single fetch. Returns the row only if it belongs to
     * the requested scope, never falling through to the other scope.
     */
    public Optional<AgentExecutionEntity> getExecutionForScope(UUID executionId, String tenantId,
                                                                String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return executionRepository.findByIdAndOrganizationIdStrict(executionId, organizationId);
    }

    /**
     * Paginated DESC by sequenceNumber - page 0 = newest batch. Long agent loops can persist
     * thousands of messages per execution; the now-removed un-paginated list overload would
     * OOM the JVM for those. Frontend reverses to ASC for chronological display.
     */
    public Page<AgentExecutionMessageEntity> getConversationPaged(UUID executionId, Pageable pageable) {
        return messageRepository.findByExecutionIdOrderBySequenceNumberDesc(executionId, pageable);
    }

    /**
     * Paginated DESC variant for tool calls - page 0 = newest batch. Tool-call payloads
     * (request/response bodies, file blobs, agent traces) routinely run MB-scale per row;
     * un-paginated fetch was the OOM shape called out in the Email Digest incident.
     */
    public Page<AgentExecutionToolCallEntity> getToolCallsPaged(UUID executionId, Pageable pageable) {
        return toolCallRepository.findByExecutionIdOrderBySequenceNumberDesc(executionId, pageable);
    }

    /** Paginated DESC iterations. */
    public Page<AgentExecutionIterationEntity> getIterationsPaged(UUID executionId, Pageable pageable) {
        return iterationRepository.findByExecutionIdOrderByIterationNumberDesc(executionId, pageable);
    }

    // ──────────────────────────────────────────────────────────────────────
    // PR29 - scope-aware aggregate readers.
    //
    // The pre-PR29 design relied on the {@code *_live} aggregator tables
    // (agent_tool_call_stats_live, agent_execution_daily_stats_live,
    // agent_tool_call_stats_by_agent_live, agent_sub_agent_call_stats_live).
    // Those tables are PK'd on tenant_id and have no organization_id column,
    // so they cannot answer an org-aware question without a schema flip.
    //
    // Rather than wait on a schema migration, PR29 drops the live-table
    // reliance for org-aware reads and queries {@code agent_executions} and
    // {@code agent_execution_tool_calls} directly. Both tables already carry
    // {@code organization_id} (V210 / PR20), and the aggregations involved
    // (COUNT / SUM / FILTER) are cheap when indexed on agent_entity_id /
    // started_at / organization_id (the org-partial indexes ship with V210).
    //
    // Routing contract (matches PR20 list/single-fetch finders):
    //   • {@code organizationId != null && !blank}  →  org-strict:
    //     {@code WHERE organization_id = :orgId}. Never falls back to
    //     personal rows even if the org returns zero data.
    //   • {@code organizationId == null || blank}   →  personal-strict:
    //     {@code WHERE tenant_id = :tenantId AND organization_id IS NULL}.
    //     Never sees org-tagged rows even if the user is a member.
    //
    // The {@code *_live} aggregator continues to be written by
    // {@code AgentMetricsAggregationService} (harmless write-only path now
    // from the read side - left for future workloads that legitimately want
    // a tenant-wide cross-org rollup, or for an eventual V218+ org-aware
    // aggregator).
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Per-tool aggregate stats - scope-aware version of {@link #getToolStats(String)}.
     * Aggregates directly from {@code agent_execution_tool_calls} joined to
     * {@code agent_executions} so the org_id filter is exact for both scopes.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getToolStats(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT tc.tool_name, " +
            "COUNT(*) AS total_calls, " +
            "COUNT(*) FILTER (WHERE tc.success) AS success_count, " +
            "COUNT(*) FILTER (WHERE NOT tc.success) AS failure_count, " +
            "CASE WHEN COUNT(*) > 0 THEN ROUND(COUNT(*) FILTER (WHERE tc.success) * 100.0 / COUNT(*), 2) ELSE 0 END AS success_rate_pct, " +
            "ROUND(AVG(tc.duration_ms)::NUMERIC, 0) AS avg_duration_ms, " +
            "MAX(tc.duration_ms) AS max_duration_ms, " +
            "COUNT(DISTINCT tc.execution_id) AS execution_count, " +
            "COUNT(*) FILTER (WHERE tc.is_repeat) AS repeat_call_count, " +
            "MAX(tc.created_at) AS last_used_at " +
            "FROM agent_execution_tool_calls tc " +
            "JOIN agent_executions ae ON tc.execution_id = ae.id " +
            "WHERE " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY tc.tool_name " +
            "ORDER BY COUNT(*) DESC");
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("toolName", row[0]);
            stat.put("totalCalls", toLong(row[1]));
            stat.put("successCount", toLong(row[2]));
            stat.put("failureCount", toLong(row[3]));
            stat.put("successRatePct", row[4] instanceof BigDecimal bd ? bd.doubleValue() : 0.0);
            stat.put("avgDurationMs", toLong(row[5]));
            stat.put("maxDurationMs", toLong(row[6]));
            stat.put("p95DurationMs", null);
            stat.put("executionCount", toLong(row[7]));
            stat.put("repeatCallCount", toLong(row[8]));
            stat.put("lastUsedAt", row[9] != null ? row[9].toString() : null);
            stats.add(stat);
        }
        return stats;
    }

    /**
     * Per-tool aggregate stats for a single agent - scope-aware version of
     * {@link #getToolStatsByAgent(String, UUID)}.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getToolStatsByAgent(String tenantId, String organizationId, UUID agentId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT tc.tool_name, " +
            "COUNT(*) AS total_calls, " +
            "COUNT(*) FILTER (WHERE tc.success) AS success_count, " +
            "COUNT(*) FILTER (WHERE NOT tc.success) AS failure_count, " +
            "CASE WHEN COUNT(*) > 0 THEN ROUND(COUNT(*) FILTER (WHERE tc.success) * 100.0 / COUNT(*), 2) ELSE 0 END AS success_rate_pct, " +
            "ROUND(AVG(tc.duration_ms)::NUMERIC, 0) AS avg_duration_ms, " +
            "MAX(tc.duration_ms) AS max_duration_ms, " +
            "MAX(tc.created_at) AS last_used_at, " +
            "COUNT(*) FILTER (WHERE tc.is_repeat) AS repeat_call_count " +
            "FROM agent_execution_tool_calls tc " +
            "JOIN agent_executions ae ON tc.execution_id = ae.id " +
            "WHERE ae.agent_entity_id = :agentId AND " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY tc.tool_name " +
            "ORDER BY COUNT(*) DESC");
        query.setParameter("agentId", agentId);
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("toolName", row[0]);
            stat.put("totalCalls", toLong(row[1]));
            stat.put("successCount", toLong(row[2]));
            stat.put("failureCount", toLong(row[3]));
            stat.put("successRatePct", row[4] instanceof BigDecimal bd ? bd.doubleValue() : 0.0);
            stat.put("avgDurationMs", toLong(row[5]));
            stat.put("maxDurationMs", toLong(row[6]));
            stat.put("lastUsedAt", row[7] != null ? row[7].toString() : null);
            stat.put("repeatCallCount", toLong(row[8]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * Per-RESOURCE aggregate stats for a single agent's resource-family tools.
     *
     * <p>{@link #getToolStatsByAgent} groups only by {@code tool_name}, so for the
     * resource-family tools ({@code table}/{@code interface}/{@code workflow}/
     * {@code application}/{@code skill}) every call collapses onto a single family
     * row (e.g. all 31 interfaces share one "interface" total). The fleet view needs
     * the count attributed to the SPECIFIC resource the agent operated on so each
     * leaf shows its own usage and the family total is the honest sum of its parts.
     *
     * <p>The targeted resource id is the raw argument the LLM passed - persisted
     * verbatim in {@code agent_execution_tool_calls.arguments} (jsonb). The arg key
     * is family-specific and matches the id the fleet keys its leaves by:
     * <ul>
     *   <li>{@code table}       → {@code table_id} (or its {@code datasource_id} alias) - numeric datasource id</li>
     *   <li>{@code interface}   → {@code interface_id} - interface UUID</li>
     *   <li>{@code workflow}    → {@code workflow_id} - workflow UUID</li>
     *   <li>{@code application} → {@code application_id} - application UUID</li>
     *   <li>{@code skill}       → {@code skill_id} - skill UUID</li>
     * </ul>
     * Calls with no specific resource (e.g. {@code action='list'}/{@code 'create'})
     * yield a null id and are dropped - they belong to no leaf. Returned rows carry
     * {@code resourceId} as text so the frontend can match it against the leaf id
     * (numeric table ids and UUIDs both round-trip through {@code ->>}).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getResourceStatsByAgent(String tenantId, String organizationId, UUID agentId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT tc.tool_name, " +
            // The id arg key is family-specific; table/interface/workflow also accept a
            // generic 'id' alias (e.g. workflow(action='execute', id=...) - the most
            // common workflow call), so COALESCE it in LAST so those rows still attribute
            // to the right leaf instead of collapsing to a dropped null id. skill/
            // application only ever take their explicit key (no 'id' alias).
            "CASE tc.tool_name " +
            "  WHEN 'table' THEN COALESCE(tc.arguments->>'table_id', tc.arguments->>'datasource_id', tc.arguments->>'id') " +
            "  WHEN 'interface' THEN COALESCE(tc.arguments->>'interface_id', tc.arguments->>'id') " +
            "  WHEN 'workflow' THEN COALESCE(tc.arguments->>'workflow_id', tc.arguments->>'id') " +
            "  WHEN 'application' THEN tc.arguments->>'application_id' " +
            "  WHEN 'skill' THEN tc.arguments->>'skill_id' " +
            "END AS resource_id, " +
            "COUNT(*) AS total_calls, " +
            "COUNT(*) FILTER (WHERE tc.success) AS success_count, " +
            "COUNT(*) FILTER (WHERE NOT tc.success) AS failure_count " +
            "FROM agent_execution_tool_calls tc " +
            "JOIN agent_executions ae ON tc.execution_id = ae.id " +
            "WHERE ae.agent_entity_id = :agentId AND " + scopeWhereFor("ae", orgScope) + " " +
            "AND tc.tool_name IN ('table', 'interface', 'workflow', 'application', 'skill') " +
            "GROUP BY tc.tool_name, resource_id " +
            "ORDER BY COUNT(*) DESC");
        query.setParameter("agentId", agentId);
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();

        for (Object[] row : results) {
            // Drop family-level calls that targeted no specific resource (null id):
            // they map to no leaf and would otherwise inflate nothing but waste payload.
            if (row[1] == null) continue;
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("toolName", row[0]);
            stat.put("resourceId", row[1].toString());
            stat.put("totalCalls", toLong(row[2]));
            stat.put("successCount", toLong(row[3]));
            stat.put("failureCount", toLong(row[4]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * Per-caller sub-agent call stats - scope-aware. Counts how many times the
     * given caller invoked each callee, restricted to the workspace scope.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSubAgentCallStats(String tenantId, String organizationId, UUID callerAgentId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT ae.agent_entity_id AS callee_agent_id, " +
            "COUNT(*) AS total_calls, " +
            "COUNT(*) FILTER (WHERE ae.status = 'COMPLETED') AS success_count, " +
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED') AS failure_count " +
            "FROM agent_executions ae " +
            "WHERE ae.caller_agent_entity_id = :callerId AND ae.agent_entity_id IS NOT NULL " +
            "AND " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY ae.agent_entity_id " +
            "ORDER BY COUNT(*) DESC");
        query.setParameter("callerId", callerAgentId);
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("calleeAgentId", row[0] != null ? row[0].toString() : null);
            stat.put("totalCalls", toLong(row[1]));
            stat.put("successCount", toLong(row[2]));
            stat.put("failureCount", toLong(row[3]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * All caller→callee edges in the workspace - scope-aware. Used by the
     * ancestor graph builder.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllSubAgentEdges(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT DISTINCT ae.caller_agent_entity_id, ae.agent_entity_id " +
            "FROM agent_executions ae " +
            "WHERE ae.caller_agent_entity_id IS NOT NULL AND ae.agent_entity_id IS NOT NULL " +
            "AND " + scopeWhereFor("ae", orgScope));
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> edges = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("callerId", row[0] != null ? row[0].toString() : null);
            edge.put("calleeId", row[1] != null ? row[1].toString() : null);
            edges.add(edge);
        }
        return edges;
    }

    // ============================================================
    // All-agents (fleet) batch variants - one scope-wide query each,
    // grouped by agent_entity_id so the Fleet canvas resolves every
    // agent's stats in a single call instead of one request per agent
    // (the per-agent {@code *ByAgent} methods above are kept for the
    // single-agent detail views). Each returned row carries {@code agentId}.
    // ============================================================

    /**
     * All agents' per-tool stats in one scope-wide query. Option D: reads the
     * incrementally-maintained org rollup {@code agent_tool_call_stats_by_agent_org_live}
     * instead of scanning the whole tool-call history. Re-aggregates across tenant_id
     * (the PK carries it; the scan grouped only by agent+tool under the org scope) so the
     * output is row-for-row identical. avg = total_duration_ms / duration_sample_count
     * reproduces the old AVG(duration_ms)-ignoring-NULL exactly.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllToolStatsByAgent(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT agent_entity_id, tool_name, " +
            "SUM(total_calls) AS total_calls, " +
            "SUM(success_count) AS success_count, " +
            "SUM(failure_count) AS failure_count, " +
            "CASE WHEN SUM(total_calls) > 0 THEN ROUND(SUM(success_count) * 100.0 / SUM(total_calls), 2) ELSE 0 END AS success_rate_pct, " +
            "CASE WHEN SUM(duration_sample_count) > 0 THEN ROUND(SUM(total_duration_ms)::NUMERIC / SUM(duration_sample_count), 0) ELSE 0 END AS avg_duration_ms, " +
            "MAX(max_duration_ms) AS max_duration_ms, " +
            "MAX(last_used_at) AS last_used_at, " +
            "SUM(repeat_call_count) AS repeat_call_count " +
            "FROM agent_tool_call_stats_by_agent_org_live " +
            "WHERE organization_id = :orgId " +
            "GROUP BY agent_entity_id, tool_name " +
            "ORDER BY agent_entity_id, SUM(total_calls) DESC");
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("agentId", row[0] != null ? row[0].toString() : null);
            stat.put("toolName", row[1]);
            stat.put("totalCalls", toLong(row[2]));
            stat.put("successCount", toLong(row[3]));
            stat.put("failureCount", toLong(row[4]));
            stat.put("successRatePct", row[5] instanceof BigDecimal bd ? bd.doubleValue() : 0.0);
            stat.put("avgDurationMs", toLong(row[6]));
            stat.put("maxDurationMs", toLong(row[7]));
            stat.put("lastUsedAt", row[8] != null ? row[8].toString() : null);
            stat.put("repeatCallCount", toLong(row[9]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * All agents' per-resource stats in one scope-wide query. Option D: reads the org
     * rollup {@code agent_resource_call_stats_by_agent_org_live} (resource_id extracted at
     * write time with the same family COALESCE precedence) instead of re-deriving it from
     * JSONB at query time. Re-aggregates across tenant_id for parity with the org-scoped scan.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllResourceStatsByAgent(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT agent_entity_id, tool_name, resource_id, " +
            "SUM(total_calls) AS total_calls, " +
            "SUM(success_count) AS success_count, " +
            "SUM(failure_count) AS failure_count " +
            "FROM agent_resource_call_stats_by_agent_org_live " +
            "WHERE organization_id = :orgId " +
            "GROUP BY agent_entity_id, tool_name, resource_id " +
            "ORDER BY agent_entity_id, SUM(total_calls) DESC");
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Object[] row : results) {
            // Drop family-level calls that targeted no specific resource (null id).
            if (row[2] == null) continue;
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("agentId", row[0] != null ? row[0].toString() : null);
            stat.put("toolName", row[1]);
            stat.put("resourceId", row[2].toString());
            stat.put("totalCalls", toLong(row[3]));
            stat.put("successCount", toLong(row[4]));
            stat.put("failureCount", toLong(row[5]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * All callers' sub-agent call stats in one scope-wide query. Option D: reads the org
     * rollup {@code agent_sub_agent_call_stats_org_live}, which is fed from each CALLEE
     * execution's resolved status (success=COMPLETED, failure=FAILED) - the same semantics
     * the old scan derived from agent_executions.status. Re-aggregates across tenant_id.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllSubAgentCallStats(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT caller_agent_id, callee_agent_id, " +
            "SUM(total_calls) AS total_calls, " +
            "SUM(success_count) AS success_count, " +
            "SUM(failure_count) AS failure_count " +
            "FROM agent_sub_agent_call_stats_org_live " +
            "WHERE organization_id = :orgId " +
            "GROUP BY caller_agent_id, callee_agent_id " +
            "ORDER BY caller_agent_id, SUM(total_calls) DESC");
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("agentId", row[0] != null ? row[0].toString() : null);
            stat.put("calleeAgentId", row[1] != null ? row[1].toString() : null);
            stat.put("totalCalls", toLong(row[2]));
            stat.put("successCount", toLong(row[3]));
            stat.put("failureCount", toLong(row[4]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * All agents' per-model stats in one scope-wide query. Option D: reads the org rollup
     * {@code agent_model_exec_stats_by_agent_org_live}, fed from each execution's resolved
     * status (success=COMPLETED, failure=FAILED, budget_exhausted = the FAILED∩BUDGET_EXHAUSTED
     * subset) - matching the old status-based scan. Re-aggregates across tenant_id.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllModelStatsByAgent(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT agent_entity_id, model, " +
            "SUM(total_executions) AS total_executions, " +
            "SUM(success_count) AS success_count, " +
            "SUM(failure_count) AS failure_count, " +
            "SUM(budget_exhausted_count) AS budget_exhausted_count " +
            "FROM agent_model_exec_stats_by_agent_org_live " +
            "WHERE organization_id = :orgId " +
            "GROUP BY agent_entity_id, model " +
            "ORDER BY agent_entity_id, SUM(total_executions) DESC");
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("agentId", row[0] != null ? row[0].toString() : null);
            stat.put("model", row[1] != null ? row[1].toString() : null);
            stat.put("totalExecutions", toLong(row[2]));
            stat.put("successCount", toLong(row[3]));
            stat.put("failureCount", toLong(row[4]));
            stat.put("budgetExhaustedCount", toLong(row[5]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * Per-model aggregate stats for a single agent - scope-aware.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getModelStatsByAgent(String tenantId, String organizationId, UUID agentId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT ae.model, " +
            "COUNT(*) AS total_executions, " +
            "COUNT(*) FILTER (WHERE ae.status = 'COMPLETED') AS success_count, " +
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED') AS failure_count, " +
            // Carve out BUDGET_EXHAUSTED (insufficient-credits gate) from the
            // generic failure_count so the Fleet model chip can render an amber
            // "throttled" indicator distinct from the red "failed" one. The
            // budget_exhausted_count is a SUBSET of failure_count (every row in
            // this count is also in failure_count) - frontend subtracts when
            // rendering so the two chips don't double-count.
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED' AND ae.stop_reason = 'BUDGET_EXHAUSTED') AS budget_exhausted_count " +
            "FROM agent_executions ae " +
            "WHERE ae.agent_entity_id = :agentId AND ae.model IS NOT NULL " +
            "AND " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY ae.model " +
            "ORDER BY COUNT(*) DESC");
        query.setParameter("agentId", agentId);
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("model", row[0] != null ? row[0].toString() : null);
            stat.put("totalExecutions", toLong(row[1]));
            stat.put("successCount", toLong(row[2]));
            stat.put("failureCount", toLong(row[3]));
            stat.put("budgetExhaustedCount", toLong(row[4]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * Per-conversation aggregate stats for a single agent - scope-aware.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getConversationStatsByAgent(String tenantId, String organizationId, UUID agentId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT ae.conversation_id, " +
            "COUNT(*) AS total_executions, " +
            "COUNT(*) FILTER (WHERE ae.status = 'COMPLETED') AS success_count, " +
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED') AS failure_count " +
            "FROM agent_executions ae " +
            "WHERE ae.agent_entity_id = :agentId AND ae.conversation_id IS NOT NULL " +
            "AND " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY ae.conversation_id " +
            "ORDER BY COUNT(*) DESC");
        query.setParameter("agentId", agentId);
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("conversationId", row[0] != null ? row[0].toString() : null);
            stat.put("totalExecutions", toLong(row[1]));
            stat.put("successCount", toLong(row[2]));
            stat.put("failureCount", toLong(row[3]));
            stats.add(stat);
        }
        return stats;
    }

    /**
     * Agent type summary card (classify / guardrail / browser_agent) - scope-aware.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAgentTypeSummary(String tenantId, String organizationId, String agentType) {
        if (!ALLOWED_AGENT_TYPES.contains(agentType)) {
            throw new IllegalArgumentException("Invalid agent type: " + agentType);
        }
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT COUNT(*) AS total_executions, " +
            "COUNT(*) FILTER (WHERE ae.status = 'COMPLETED') AS success_count, " +
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED' AND ae.stop_reason NOT IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS failure_count, " +
            "COUNT(*) FILTER (WHERE ae.stop_reason IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS cancelled_count, " +
            "COUNT(*) FILTER (WHERE ae.loop_detected) AS loop_detected_count, " +
            "COALESCE(SUM(ae.total_tokens), 0) AS total_tokens_used, " +
            "COALESCE(SUM(ae.total_tool_calls), 0) AS total_tool_calls, " +
            "COALESCE(SUM(ae.duration_ms), 0) AS total_duration_ms, " +
            "ROUND(AVG(ae.duration_ms)::NUMERIC, 0) AS avg_duration_ms, " +
            "MAX(ae.started_at) AS last_execution_at, " +
            "COALESCE(SUM(ae.credits_consumed), 0) AS total_credits_consumed, " +
            "COALESCE(SUM(ae.total_cached_tokens), 0) AS total_cached_tokens " +
            "FROM agent_executions ae " +
            "WHERE ae.agent_type = :agentType AND " + scopeWhereFor("ae", orgScope));
        query.setParameter("agentType", agentType);
        bindScopeParams(query, tenantId, organizationId, orgScope);

        Object[] row = (Object[]) query.getSingleResult();
        long totalExecutions = toLong(row[0]);
        long successCount = toLong(row[1]);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalExecutions", totalExecutions);
        summary.put("successCount", successCount);
        summary.put("failureCount", toLong(row[2]));
        summary.put("cancelledCount", toLong(row[3]));
        summary.put("loopDetectedCount", toLong(row[4]));
        summary.put("totalTokensUsed", toLong(row[5]));
        summary.put("totalToolCalls", toLong(row[6]));
        summary.put("totalDurationMs", toLong(row[7]));
        summary.put("avgDurationMs", toLong(row[8]));
        summary.put("totalCreditsConsumed", row.length > 10 && row[10] != null ? ((Number) row[10]).doubleValue() : 0.0);
        summary.put("totalCachedTokens", row.length > 11 && row[11] != null ? toLong(row[11]) : 0L);
        summary.put("successRate", totalExecutions > 0
            ? Math.round(successCount * 1000.0 / totalExecutions) / 10.0 : 0.0);
        summary.put("lastExecutionAt", row[9] != null ? row[9].toString() : null);
        return summary;
    }

    /**
     * Paginated agent-type executions list - scope-aware.
     */
    public Page<AgentExecutionEntity> getAgentTypeExecutions(String tenantId, String organizationId, String agentType, Pageable pageable) {
        if (!ALLOWED_AGENT_TYPES.contains(agentType)) {
            throw new IllegalArgumentException("Invalid agent type: " + agentType);
        }
        TenantResolver.requireOrgId(organizationId);
        return executionRepository.findByAgentTypeAndOrganizationIdStrictOrderByStartedAtDesc(
            agentType, organizationId, pageable);
    }

    /**
     * General chat summary (source='CHAT', agent_entity_id IS NULL) - scope-aware.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getChatSummary(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT COUNT(*) AS total_executions, " +
            "COUNT(*) FILTER (WHERE ae.status = 'COMPLETED') AS success_count, " +
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED' AND ae.stop_reason NOT IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS failure_count, " +
            "COUNT(*) FILTER (WHERE ae.stop_reason IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS cancelled_count, " +
            "COUNT(*) FILTER (WHERE ae.loop_detected) AS loop_detected_count, " +
            "COALESCE(SUM(ae.total_tokens), 0) AS total_tokens_used, " +
            "COALESCE(SUM(ae.total_tool_calls), 0) AS total_tool_calls, " +
            "COALESCE(SUM(ae.duration_ms), 0) AS total_duration_ms, " +
            "ROUND(AVG(ae.duration_ms)::NUMERIC, 0) AS avg_duration_ms, " +
            "MAX(ae.started_at) AS last_execution_at, " +
            "COALESCE(SUM(ae.credits_consumed), 0) AS total_credits_consumed, " +
            "COALESCE(SUM(ae.total_cached_tokens), 0) AS total_cached_tokens " +
            "FROM agent_executions ae " +
            "WHERE ae.source = 'CHAT' AND ae.agent_entity_id IS NULL " +
            "AND " + scopeWhereFor("ae", orgScope));
        bindScopeParams(query, tenantId, organizationId, orgScope);

        Object[] row = (Object[]) query.getSingleResult();
        long totalExecutions = toLong(row[0]);
        long successCount = toLong(row[1]);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalExecutions", totalExecutions);
        summary.put("successCount", successCount);
        summary.put("failureCount", toLong(row[2]));
        summary.put("cancelledCount", toLong(row[3]));
        summary.put("loopDetectedCount", toLong(row[4]));
        summary.put("totalTokensUsed", toLong(row[5]));
        summary.put("totalToolCalls", toLong(row[6]));
        summary.put("totalDurationMs", toLong(row[7]));
        summary.put("avgDurationMs", toLong(row[8]));
        summary.put("totalCreditsConsumed", row.length > 10 && row[10] != null ? ((Number) row[10]).doubleValue() : 0.0);
        summary.put("totalCachedTokens", row.length > 11 && row[11] != null ? toLong(row[11]) : 0L);
        summary.put("successRate", totalExecutions > 0
            ? Math.round(successCount * 1000.0 / totalExecutions) / 10.0 : 0.0);
        summary.put("lastExecutionAt", row[9] != null ? row[9].toString() : null);
        return summary;
    }

    /**
     * Paginated chat executions list - scope-aware.
     */
    public Page<AgentExecutionEntity> getChatExecutions(String tenantId, String organizationId, Pageable pageable) {
        TenantResolver.requireOrgId(organizationId);
        return executionRepository.findBySourceAndAgentEntityIdIsNullAndOrganizationIdStrictOrderByStartedAtDesc(
            "CHAT", organizationId, pageable);
    }

    /**
     * Daily time-series stats for general chat - scope-aware.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getChatDailyStats(String tenantId, String organizationId, int days) {
        boolean orgScope = isOrgScope(organizationId);
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        Query query = entityManager.createNativeQuery(
            "SELECT DATE(ae.started_at AT TIME ZONE 'UTC') AS execution_date, ae.provider, ae.model, " +
            "COUNT(*) AS total_executions, " +
            "COUNT(*) FILTER (WHERE ae.status = 'COMPLETED') AS completed_count, " +
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED' AND ae.stop_reason NOT IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS failed_count, " +
            "COUNT(*) FILTER (WHERE ae.stop_reason IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS cancelled_count, " +
            "COUNT(*) FILTER (WHERE ae.loop_detected) AS loop_detected_count, " +
            "COALESCE(SUM(ae.total_tool_calls), 0) AS total_tool_calls, " +
            "COALESCE(SUM(ae.total_tokens), 0) AS total_tokens, " +
            "ROUND(AVG(ae.duration_ms)::NUMERIC, 0) AS avg_duration_ms, " +
            "ROUND(AVG(ae.iteration_count)::NUMERIC, 1) AS avg_iterations, " +
            "COALESCE(SUM(ae.total_cached_tokens), 0) AS total_cached_tokens " +
            "FROM agent_executions ae " +
            "WHERE ae.source = 'CHAT' AND ae.agent_entity_id IS NULL " +
            "AND DATE(ae.started_at AT TIME ZONE 'UTC') >= :since " +
            "AND " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY DATE(ae.started_at AT TIME ZONE 'UTC'), ae.provider, ae.model " +
            "ORDER BY execution_date DESC");
        query.setParameter("since", Date.valueOf(since));
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        return mapDailyStatsResults(results);
    }

    /**
     * Per-tool stats for chat executions - scope-aware.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getChatToolStats(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT tc.tool_name, " +
            "COUNT(*) AS total_calls, " +
            "COUNT(*) FILTER (WHERE tc.success) AS success_count, " +
            "COUNT(*) FILTER (WHERE NOT tc.success) AS failure_count, " +
            "ROUND(AVG(tc.duration_ms)::NUMERIC, 0) AS avg_duration_ms, " +
            "MAX(tc.duration_ms) AS max_duration_ms, " +
            "COUNT(*) FILTER (WHERE tc.is_repeat) AS repeat_call_count, " +
            "MAX(tc.created_at) AS last_used_at " +
            "FROM agent_execution_tool_calls tc " +
            "JOIN agent_executions ae ON tc.execution_id = ae.id " +
            "WHERE ae.source = 'CHAT' AND ae.agent_entity_id IS NULL " +
            "AND " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY tc.tool_name " +
            "ORDER BY COUNT(*) DESC");
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>();

        for (Object[] r : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("toolName", r[0]);
            stat.put("totalCalls", toLong(r[1]));
            stat.put("successCount", toLong(r[2]));
            stat.put("failureCount", toLong(r[3]));
            stat.put("avgDurationMs", toLong(r[4]));
            stat.put("maxDurationMs", toLong(r[5]));
            stat.put("repeatCallCount", toLong(r[6]));
            stat.put("lastUsedAt", r[7] != null ? r[7].toString() : null);
            stats.add(stat);
        }
        return stats;
    }

    /**
     * Daily time-series stats across all agents - scope-aware.
     * Aggregates {@code agent_executions} directly (no live-table reliance).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDailyStats(String tenantId, String organizationId, int days) {
        boolean orgScope = isOrgScope(organizationId);
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        Query query = entityManager.createNativeQuery(
            "SELECT DATE(ae.started_at AT TIME ZONE 'UTC') AS execution_date, ae.provider, ae.model, " +
            "COUNT(*) AS total_executions, " +
            "COUNT(*) FILTER (WHERE ae.status = 'COMPLETED') AS completed_count, " +
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED' AND ae.stop_reason NOT IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS failed_count, " +
            "COUNT(*) FILTER (WHERE ae.stop_reason IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS cancelled_count, " +
            "COUNT(*) FILTER (WHERE ae.loop_detected) AS loop_detected_count, " +
            "COALESCE(SUM(ae.total_tool_calls), 0) AS total_tool_calls, " +
            "COALESCE(SUM(ae.total_tokens), 0) AS total_tokens, " +
            "ROUND(AVG(ae.duration_ms)::NUMERIC, 0) AS avg_duration_ms, " +
            "ROUND(AVG(ae.iteration_count)::NUMERIC, 1) AS avg_iterations, " +
            "COALESCE(SUM(ae.total_cached_tokens), 0) AS total_cached_tokens " +
            "FROM agent_executions ae " +
            "WHERE DATE(ae.started_at AT TIME ZONE 'UTC') >= :since " +
            "AND " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY DATE(ae.started_at AT TIME ZONE 'UTC'), ae.provider, ae.model " +
            "ORDER BY execution_date DESC");
        query.setParameter("since", Date.valueOf(since));
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        return mapDailyStatsResults(results);
    }

    /**
     * Daily time-series stats for a specific agent - scope-aware.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDailyStatsByAgent(String tenantId, String organizationId, int days, UUID agentId) {
        boolean orgScope = isOrgScope(organizationId);
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        Query query = entityManager.createNativeQuery(
            "SELECT DATE(ae.started_at AT TIME ZONE 'UTC') AS execution_date, ae.provider, ae.model, " +
            "COUNT(*) AS total_executions, " +
            "COUNT(*) FILTER (WHERE ae.status = 'COMPLETED') AS completed_count, " +
            "COUNT(*) FILTER (WHERE ae.status = 'FAILED' AND ae.stop_reason NOT IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS failed_count, " +
            "COUNT(*) FILTER (WHERE ae.stop_reason IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS cancelled_count, " +
            "COUNT(*) FILTER (WHERE ae.loop_detected) AS loop_detected_count, " +
            "COALESCE(SUM(ae.total_tool_calls), 0) AS total_tool_calls, " +
            "COALESCE(SUM(ae.total_tokens), 0) AS total_tokens, " +
            "ROUND(AVG(ae.duration_ms)::NUMERIC, 0) AS avg_duration_ms, " +
            "ROUND(AVG(ae.iteration_count)::NUMERIC, 1) AS avg_iterations, " +
            "COALESCE(SUM(ae.total_cached_tokens), 0) AS total_cached_tokens " +
            "FROM agent_executions ae " +
            "WHERE ae.agent_entity_id = :agentId " +
            "AND DATE(ae.started_at AT TIME ZONE 'UTC') >= :since " +
            "AND " + scopeWhereFor("ae", orgScope) + " " +
            "GROUP BY DATE(ae.started_at AT TIME ZONE 'UTC'), ae.provider, ae.model " +
            "ORDER BY execution_date DESC");
        query.setParameter("agentId", agentId);
        query.setParameter("since", Date.valueOf(since));
        bindScopeParams(query, tenantId, organizationId, orgScope);

        List<Object[]> results = query.getResultList();
        return mapDailyStatsResults(results);
    }

    private List<Map<String, Object>> mapDailyStatsResults(List<Object[]> results) {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("executionDate", row[0] != null ? row[0].toString() : null);
            stat.put("provider", row[1]);
            stat.put("model", row[2]);
            stat.put("totalExecutions", toLong(row[3]));
            stat.put("completedCount", toLong(row[4]));
            stat.put("failedCount", toLong(row[5]));
            stat.put("cancelledCount", toLong(row[6]));
            stat.put("loopDetectedCount", toLong(row[7]));
            stat.put("totalToolCalls", toLong(row[8]));
            stat.put("totalTokens", toLong(row[9]));
            stat.put("avgDurationMs", toLong(row[10]));
            stat.put("avgIterations", row[11] instanceof BigDecimal bd ? bd.doubleValue() : null);
            stat.put("cachedTokens", row.length > 12 ? toLong(row[12]) : 0L);
            stats.add(stat);
        }
        return stats;
    }

    /**
     * Post-V261 (2026-05-19) - every agent_executions row has a non-null
     * organization_id. Callers MUST pass a non-blank organizationId. The
     * legacy personal-strict (tenant-only) branch returned zero rows and was
     * removed. Returns {@code true} for the org-strict branch - the only
     * remaining valid scope.
     */
    private static boolean isOrgScope(String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return true;
    }

    /**
     * SQL fragment for the workspace scope predicate against a single alias.
     * Post-V261 (2026-05-19): {@code organization_id} is NOT NULL on every
     * agent_executions row, so the only legitimate scope is org-strict. The
     * legacy {@code tenant_id = :tenantId AND organization_id IS NULL} personal
     * branch returned zero rows after V260 backfill and was removed. Caller
     * MUST pass a non-blank {@code organizationId}; an unbound {@code orgScope}
     * is a programming error and throws.
     */
    private static String scopeWhereFor(String alias, boolean orgScope) {
        if (!orgScope) {
            throw new IllegalStateException(
                "scopeWhereFor requires orgScope=true after V261 - pass a non-blank organizationId");
        }
        return alias + ".organization_id = :orgId";
    }

    private static void bindScopeParams(Query query, String tenantId, String organizationId, boolean orgScope) {
        if (!orgScope) {
            throw new IllegalStateException(
                "bindScopeParams requires orgScope=true after V261 - pass a non-blank organizationId");
        }
        query.setParameter("orgId", organizationId);
    }


    /**
     * Get iterations for an execution, ordered by iteration number.
     */
    public List<AgentExecutionIterationEntity> getIterations(UUID executionId) {
        return iterationRepository.findByExecutionIdOrderByIterationNumber(executionId);
    }

    /**
     * Sub-agent executions SPAWNED by a given conversation, newest first.
     *
     * <p>A sub-agent runs under its OWN dedicated conversation (minted per
     * sub-agent), so a conversation-scoped observability view filtering on the
     * spawned execution's own {@code conversation_id} never sees it. These rows
     * instead carry {@code parent_conversation_id} = the conversation that
     * spawned them, which this reader resolves so the parent conversation's
     * observability panel can surface the sub-agent executions (and, via the
     * per-execution drill-downs, their tool calls).
     *
     * <p>Org-strict, matching every other observability read post-V261: the
     * caller MUST pass a non-blank organizationId (enforced via
     * {@code TenantResolver.requireOrgId}), so a caller in one workspace can
     * never see sub-agent executions tagged with a different organizationId.
     */
    public List<AgentExecutionEntity> getSubAgentExecutionsForConversation(
            String parentConversationId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return executionRepository
            .findByParentConversationIdAndOrganizationIdStrictOrderByStartedAtDesc(
                parentConversationId, organizationId);
    }

    // ========== General Chat Metrics (no agent selected) ==========


    // ========== Agent Type Metrics (classify / guardrail / browser_agent) ==========

    // Whitelist of {@code agent_executions.agent_type} values exposed by the
    // dashboard's per-type drill-downs. Adding a value here surfaces the
    // dedicated summary card + executions panel in
    // {@code AgentMetricsDashboard.tsx} (frontend already calls
    // {@code getAgentTypeSummary('<type>')} for each entry - the rejection
    // path on a non-allowed type just returns null).
    private static final java.util.Set<String> ALLOWED_AGENT_TYPES =
        java.util.Set.of("classify", "guardrail", "browser_agent");


    /**
     * Get paginated workflow-level agent executions. Batch A2 (2026-05-20) -
     * routes through the org-strict finder when an orgId is in the request
     * scope so an org workspace caller can't see executions tagged with a
     * different organizationId even if the workflow row is somehow visible
     * cross-scope.
     */
    public Page<AgentExecutionEntity> getWorkflowExecutions(UUID workflowId, String tenantId, Pageable pageable) {
        String orgScope = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        if (orgScope != null && !orgScope.isBlank()) {
            return executionRepository.findByWorkflowIdAndOrganizationIdStrictOrderByStartedAtDesc(workflowId, orgScope, pageable);
        }
        return executionRepository.findByWorkflowIdAndTenantIdOrderByStartedAtDesc(workflowId, tenantId, pageable);
    }


    /**
     * PR29 - scope-aware cancelled and loop-detected counts for the fleet summary,
     * plus the cache-read token rollup ({@code totalCachedTokens}). These are
     * sourced from {@code agent_executions} (the agents table has no cached-token
     * counter), folded into the fleet summary alongside the agents-table counters.
     * Counts only executions for agents that still exist (excludes orphaned data
     * - agent rows that were hard-deleted while executions survived).
     *
     * <p>Post-V263 (round-7, 2026-05-20): only the org-strict branch survives;
     * {@code isOrgScope}/{@code scopeWhereFor} throw if {@code organizationId}
     * is blank. The legacy personal-strict {@code organization_id IS NULL}
     * branch returned zero rows after V262 backfill and was removed.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> getFleetExtraCounts(String tenantId, String organizationId) {
        boolean orgScope = isOrgScope(organizationId);
        Query query = entityManager.createNativeQuery(
            "SELECT " +
            "COUNT(*) FILTER (WHERE ae.stop_reason IN ('CANCELLED', 'STOPPED_BY_USER', 'TIMEOUT')) AS cancelled_count, " +
            "COUNT(*) FILTER (WHERE ae.loop_detected) AS loop_detected_count, " +
            // Cache-read rollup for the FLEET (agent) side only: exclude chat
            // (agent_entity_id IS NULL) so the dashboard's overview, which adds
            // chatSummary.totalCachedTokens on top, never double-counts chat cache
            // (mirrors the agents-table token counter, which is per-agent).
            "COALESCE(SUM(ae.total_cached_tokens) FILTER (WHERE ae.agent_entity_id IS NOT NULL), 0) AS total_cached_tokens " +
            "FROM agent_executions ae " +
            "WHERE " + scopeWhereFor("ae", orgScope) + " " +
            "AND (ae.agent_entity_id IS NULL OR EXISTS (SELECT 1 FROM agents a WHERE a.id = ae.agent_entity_id))");
        bindScopeParams(query, tenantId, organizationId, orgScope);

        Object[] row = (Object[]) query.getSingleResult();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("cancelledCount", toLong(row[0]));
        counts.put("loopDetectedCount", toLong(row[1]));
        counts.put("totalCachedTokens", row.length > 2 ? toLong(row[2]) : 0L);
        return counts;
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
