package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.repository.AgentMetricsAggregationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinates incremental updates to agent metrics live tables.
 * Called within the same transaction as the observability recording.
 */
@Service
public class AgentMetricsAggregationService {

    private static final Logger logger = LoggerFactory.getLogger(AgentMetricsAggregationService.class);

    private final AgentMetricsAggregationRepository repository;

    public AgentMetricsAggregationService(AgentMetricsAggregationRepository repository) {
        this.repository = repository;
    }

    /**
     * Update all aggregation tables after a full agent execution recording.
     *
     * @param savedToolCalls the tool call entities already persisted (with isRepeat flags computed)
     */
    /**
     * Cancelled stop reasons -- counted separately from failures in daily stats.
     */
    private static final Set<String> CANCELLED_STOP_REASONS = Set.of(
        "CANCELLED", "STOPPED_BY_USER", "TIMEOUT"
    );

    public void updateAggregations(String tenantId, String organizationId, UUID agentEntityId, String provider, String model,
                                    boolean success, String stopReason, boolean loopDetected, long durationMs,
                                    long totalTokens, int totalToolCalls, int iterationCount,
                                    List<AgentExecutionToolCallEntity> savedToolCalls) {
        try {
            boolean isCancelled = !success && stopReason != null && CANCELLED_STOP_REASONS.contains(stopReason);
            // Org-aware rollups (option D) only make sense for org-strict reads; a blank org
            // would never be read back, so skip those upserts when org is absent.
            boolean orgPresent = organizationId != null && !organizationId.isBlank();
            // 1. Daily stats
            repository.upsertDailyStats(tenantId, LocalDate.now(ZoneOffset.UTC),
                provider, model,
                success ? 1 : 0,
                (!success && !isCancelled) ? 1 : 0,
                isCancelled ? 1 : 0,
                loopDetected,
                totalToolCalls, totalTokens, durationMs, iterationCount);

            // 1b. Org-aware per-agent-per-model execution rollup (option D). Reproduces
            // getAllModelStatsByAgent: `success` is already "status==COMPLETED" (the caller
            // derived it from exec.getStatus()), failed = "status==FAILED" (not success AND
            // not cancelled - TIMEOUT/MAX_ITERATIONS/LOOP map to COMPLETED, never reaching
            // here as failures), budget_exhausted = the FAILED ∩ BUDGET_EXHAUSTED subset.
            // NOTE on replay: this is a +1 upsert, so a re-delivered observability record would
            // double-count here (the scan PK-merges the execution header and counts it once).
            // Acceptable: recordFromRequest is at-most-once-by-design (REQUIRES_NEW, no retry
            // loop), and the legacy daily/sub-agent rollups share the same non-idempotency. If
            // strict replay-safety is ever needed, gate this on a first-insert check upstream.
            if (orgPresent && agentEntityId != null && model != null) {
                boolean failed = !success && !isCancelled;
                boolean budgetExhausted = failed && "BUDGET_EXHAUSTED".equals(stopReason);
                repository.upsertModelExecStatsByAgentOrg(organizationId, tenantId, agentEntityId, model,
                    success, failed, budgetExhausted);
            }

            // 2. Per-tool-call aggregations
            if (savedToolCalls != null && !savedToolCalls.isEmpty()) {
                Instant now = Instant.now();
                Set<String> distinctToolNames = new LinkedHashSet<>();

                for (AgentExecutionToolCallEntity tc : savedToolCalls) {
                    String toolName = tc.getToolName();
                    long tcDuration = tc.getDurationMs() != null ? tc.getDurationMs() : 0L;

                    // Tenant-wide tool stats
                    repository.upsertToolCallStats(tenantId, toolName, tc.isSuccess(),
                        tcDuration, tc.isRepeat(), now);

                    // Per-agent tool stats (legacy tenant-keyed)
                    if (agentEntityId != null) {
                        repository.upsertToolCallStatsByAgent(tenantId, agentEntityId, toolName,
                            tc.isSuccess(), tcDuration, tc.isRepeat(), now);
                    }

                    // Org-aware per-agent tool + per-resource rollups (option D). Pass the RAW
                    // nullable duration so duration_sample_count counts only real samples
                    // (the read's AVG ignores NULL durations). resourceId is extracted with the
                    // SAME family precedence the V345 backfill SQL CASE uses - keep in sync.
                    if (orgPresent && agentEntityId != null) {
                        repository.upsertToolCallStatsByAgentOrg(organizationId, tenantId, agentEntityId, toolName,
                            tc.isSuccess(), tc.getDurationMs(), tc.isRepeat(), now);
                        String resourceId = extractResourceId(toolName, tc.getArguments());
                        if (resourceId != null) {
                            repository.upsertResourceCallStatsByAgentOrg(organizationId, tenantId, agentEntityId,
                                toolName, resourceId, tc.isSuccess());
                        }
                    }

                    // Sub-agent call detection (legacy tenant-keyed, fed from the CALLER's
                    // tool-call success - left intact). The org-aware sub-agent rollup is fed
                    // separately from the CALLEE execution's finalize (AgentObservabilityService)
                    // by resolved status, matching getAllSubAgentCallStats.
                    if ("agent".equals(toolName) && agentEntityId != null) {
                        UUID calleeId = extractCalleeAgentId(tc);
                        if (calleeId != null) {
                            repository.upsertSubAgentCallStats(tenantId, agentEntityId, calleeId, tc.isSuccess());
                        }
                    }

                    distinctToolNames.add(toolName);
                }

                // 3. Increment execution_count once per distinct tool
                repository.incrementToolCallStatsExecutionCount(tenantId, distinctToolNames);
            }
        } catch (Exception e) {
            logger.warn("Failed to update aggregations: tenant={}, error={}", tenantId, e.getMessage());
        }
    }

    /**
     * Resource id targeted by a resource-family tool call, extracted from the persisted
     * tool-call arguments with the EXACT family-specific COALESCE precedence the read
     * (getAllResourceStatsByAgent) and the V345 backfill SQL CASE use. Returns {@code null}
     * for non-family tools or when no id key is present (those calls map to no leaf and are
     * not rolled up). NOTE: this precedence lives in THREE places now - here, the
     * AgentMetricsQueryService read CASE, and the V345 backfill CASE - keep them in sync
     * (a change requires a re-backfill migration). Mirrors the 3-way-alignment discipline.
     */
    static String extractResourceId(String toolName, Map<String, Object> args) {
        if (toolName == null || args == null) {
            return null;
        }
        return switch (toolName) {
            case "table"       -> firstNonNullArg(args, "table_id", "datasource_id", "id");
            case "interface"   -> firstNonNullArg(args, "interface_id", "id");
            case "workflow"    -> firstNonNullArg(args, "workflow_id", "id");
            case "application" -> firstNonNullArg(args, "application_id");
            case "skill"       -> firstNonNullArg(args, "skill_id");
            default            -> null;
        };
    }

    /** First present (non-null) arg value as text, mirroring SQL COALESCE over {@code ->>}. */
    private static String firstNonNullArg(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * Update aggregations for a chat-originated execution (no individual tool call detail).
     */
    public void updateAggregationsFromChat(String tenantId, UUID agentEntityId, String provider, String model,
                                            boolean success, String stopReason, boolean loopDetected, long durationMs,
                                            long totalTokens, int totalToolCalls, int iterationCount) {
        try {
            boolean isCancelled = !success && stopReason != null && CANCELLED_STOP_REASONS.contains(stopReason);
            repository.upsertDailyStats(tenantId, LocalDate.now(ZoneOffset.UTC),
                provider, model,
                success ? 1 : 0,
                (!success && !isCancelled) ? 1 : 0,
                isCancelled ? 1 : 0,
                loopDetected,
                totalToolCalls, totalTokens, durationMs, iterationCount);
        } catch (Exception e) {
            logger.warn("Failed to update chat aggregations: tenant={}, error={}", tenantId, e.getMessage());
        }
    }

    /**
     * Update aggregations for lightweight executions (classify, guardrail).
     */
    public void updateAggregationsForLightweight(String tenantId, String provider, String model,
                                                  boolean success, long durationMs, long tokensUsed) {
        try {
            repository.upsertDailyStats(tenantId, LocalDate.now(ZoneOffset.UTC),
                provider, model,
                success ? 1 : 0,
                success ? 0 : 1,
                0,
                false,
                0, tokensUsed, durationMs, 1);
        } catch (Exception e) {
            logger.warn("Failed to update lightweight aggregations: tenant={}, error={}", tenantId, e.getMessage());
        }
    }

    /**
     * Record the CALLEE side of a sub-agent delegation into the org-aware sub-agent rollup
     * (option D). Called once per callee execution finalize with its RESOLVED status:
     * completed = status COMPLETED, failed = status FAILED (a CANCELLED / STOPPED_BY_USER /
     * TIMEOUT callee counts toward neither), reproducing getAllSubAgentCallStats which
     * FILTERs callee execution rows by status. No-op unless org + both agent ids are present
     * (a top-level execution has no caller and is skipped).
     */
    public void recordSubAgentCallFromCallee(String organizationId, String tenantId,
                                             UUID callerAgentId, UUID calleeAgentId, String resolvedStatus) {
        if (organizationId == null || organizationId.isBlank()
                || callerAgentId == null || calleeAgentId == null) {
            return;
        }
        try {
            boolean completed = "COMPLETED".equals(resolvedStatus);
            boolean failed = "FAILED".equals(resolvedStatus);
            repository.upsertSubAgentCallStatsOrg(organizationId, tenantId, callerAgentId, calleeAgentId, completed, failed);
        } catch (Exception e) {
            logger.warn("Failed to record sub-agent-org call: org={}, caller={}, callee={}, error={}",
                organizationId, callerAgentId, calleeAgentId, e.getMessage());
        }
    }

    private UUID extractCalleeAgentId(AgentExecutionToolCallEntity tc) {
        Map<String, Object> args = tc.getArguments();
        if (args == null) {
            return null;
        }
        Object agentIdValue = args.get("agent_id");
        if (agentIdValue == null) {
            return null;
        }
        try {
            return UUID.fromString(agentIdValue.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
