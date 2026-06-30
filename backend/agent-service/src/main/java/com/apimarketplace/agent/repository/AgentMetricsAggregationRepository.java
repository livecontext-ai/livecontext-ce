package com.apimarketplace.agent.repository;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for incremental UPSERT operations on agent metrics live tables.
 * All methods are fire-and-forget safe: they catch and log exceptions without rethrowing.
 */
@Repository
public class AgentMetricsAggregationRepository {

    private static final Logger logger = LoggerFactory.getLogger(AgentMetricsAggregationRepository.class);

    private final EntityManager entityManager;

    public AgentMetricsAggregationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Upsert a single tool call into tenant-wide tool call stats.
     */
    public void upsertToolCallStats(String tenantId, String toolName, boolean success,
                                     long durationMs, boolean isRepeat, Instant now) {
        try {
            entityManager.createNativeQuery(
                "INSERT INTO agent_tool_call_stats_live " +
                "(tenant_id, tool_name, total_calls, success_count, failure_count, " +
                "total_duration_ms, max_duration_ms, execution_count, repeat_call_count, last_used_at) " +
                "VALUES (:tenantId, :toolName, 1, :success, :failure, :dur, :dur, 0, :repeat, :now) " +
                "ON CONFLICT (tenant_id, tool_name) DO UPDATE SET " +
                "total_calls = agent_tool_call_stats_live.total_calls + 1, " +
                "success_count = agent_tool_call_stats_live.success_count + :success, " +
                "failure_count = agent_tool_call_stats_live.failure_count + :failure, " +
                "total_duration_ms = agent_tool_call_stats_live.total_duration_ms + :dur, " +
                "max_duration_ms = GREATEST(agent_tool_call_stats_live.max_duration_ms, :dur), " +
                "repeat_call_count = agent_tool_call_stats_live.repeat_call_count + :repeat, " +
                "last_used_at = :now")
                .setParameter("tenantId", tenantId)
                .setParameter("toolName", toolName)
                .setParameter("success", success ? 1 : 0)
                .setParameter("failure", success ? 0 : 1)
                .setParameter("dur", durationMs)
                .setParameter("repeat", isRepeat ? 1 : 0)
                .setParameter("now", now)
                .executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to upsert tool call stats: tenant={}, tool={}, error={}", tenantId, toolName, e.getMessage());
        }
    }

    /**
     * Increment execution_count for each distinct tool name used in one execution.
     */
    public void incrementToolCallStatsExecutionCount(String tenantId, Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        try {
            for (String toolName : toolNames) {
                entityManager.createNativeQuery(
                    "UPDATE agent_tool_call_stats_live " +
                    "SET execution_count = execution_count + 1 " +
                    "WHERE tenant_id = :tenantId AND tool_name = :toolName")
                    .setParameter("tenantId", tenantId)
                    .setParameter("toolName", toolName)
                    .executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to increment execution count: tenant={}, error={}", tenantId, e.getMessage());
        }
    }

    /**
     * Upsert daily execution stats.
     */
    public void upsertDailyStats(String tenantId, LocalDate date, String provider, String model,
                                  int completed, int failed, int cancelled, boolean loopDetected,
                                  int toolCalls, long tokens, long durationMs, int iterations) {
        try {
            String safeProvider = provider != null ? provider : "";
            String safeModel = model != null ? model : "";
            entityManager.createNativeQuery(
                "INSERT INTO agent_execution_daily_stats_live " +
                "(tenant_id, execution_date, provider, model, total_executions, completed_count, " +
                "failed_count, cancelled_count, loop_detected_count, total_tool_calls, total_tokens, total_duration_ms, total_iterations) " +
                "VALUES (:tenantId, :date, :provider, :model, 1, :completed, :failed, :cancelled, :loop, :tools, :tokens, :dur, :iters) " +
                "ON CONFLICT (tenant_id, execution_date, provider, model) DO UPDATE SET " +
                "total_executions = agent_execution_daily_stats_live.total_executions + 1, " +
                "completed_count = agent_execution_daily_stats_live.completed_count + :completed, " +
                "failed_count = agent_execution_daily_stats_live.failed_count + :failed, " +
                "cancelled_count = agent_execution_daily_stats_live.cancelled_count + :cancelled, " +
                "loop_detected_count = agent_execution_daily_stats_live.loop_detected_count + :loop, " +
                "total_tool_calls = agent_execution_daily_stats_live.total_tool_calls + :tools, " +
                "total_tokens = agent_execution_daily_stats_live.total_tokens + :tokens, " +
                "total_duration_ms = agent_execution_daily_stats_live.total_duration_ms + :dur, " +
                "total_iterations = agent_execution_daily_stats_live.total_iterations + :iters")
                .setParameter("tenantId", tenantId)
                .setParameter("date", date)
                .setParameter("provider", safeProvider)
                .setParameter("model", safeModel)
                .setParameter("completed", completed)
                .setParameter("failed", failed)
                .setParameter("cancelled", cancelled)
                .setParameter("loop", loopDetected ? 1 : 0)
                .setParameter("tools", toolCalls)
                .setParameter("tokens", tokens)
                .setParameter("dur", durationMs)
                .setParameter("iters", iterations)
                .executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to upsert daily stats: tenant={}, date={}, error={}", tenantId, date, e.getMessage());
        }
    }

    /**
     * Upsert a single tool call into per-agent tool call stats.
     */
    public void upsertToolCallStatsByAgent(String tenantId, UUID agentEntityId, String toolName,
                                            boolean success, long durationMs, boolean isRepeat, Instant now) {
        try {
            entityManager.createNativeQuery(
                "INSERT INTO agent_tool_call_stats_by_agent_live " +
                "(tenant_id, agent_entity_id, tool_name, total_calls, success_count, failure_count, " +
                "total_duration_ms, max_duration_ms, last_used_at, repeat_call_count) " +
                "VALUES (:tenantId, :agentId, :toolName, 1, :success, :failure, :dur, :dur, :now, :repeat) " +
                "ON CONFLICT (tenant_id, agent_entity_id, tool_name) DO UPDATE SET " +
                "total_calls = agent_tool_call_stats_by_agent_live.total_calls + 1, " +
                "success_count = agent_tool_call_stats_by_agent_live.success_count + :success, " +
                "failure_count = agent_tool_call_stats_by_agent_live.failure_count + :failure, " +
                "total_duration_ms = agent_tool_call_stats_by_agent_live.total_duration_ms + :dur, " +
                "max_duration_ms = GREATEST(agent_tool_call_stats_by_agent_live.max_duration_ms, :dur), " +
                "last_used_at = :now, " +
                "repeat_call_count = agent_tool_call_stats_by_agent_live.repeat_call_count + :repeat")
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .setParameter("toolName", toolName)
                .setParameter("success", success ? 1 : 0)
                .setParameter("failure", success ? 0 : 1)
                .setParameter("dur", durationMs)
                .setParameter("now", now)
                .setParameter("repeat", isRepeat ? 1 : 0)
                .executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to upsert per-agent tool call stats: tenant={}, agent={}, tool={}, error={}",
                tenantId, agentEntityId, toolName, e.getMessage());
        }
    }

    // ==================== Cleanup (agent deletion) ====================

    /**
     * Delete all per-agent tool call stats for a deleted agent.
     */
    public void deleteToolCallStatsByAgent(String tenantId, UUID agentEntityId) {
        try {
            int deleted = entityManager.createNativeQuery(
                "DELETE FROM agent_tool_call_stats_by_agent_live " +
                "WHERE tenant_id = :tenantId AND agent_entity_id = :agentId")
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .executeUpdate();
            logger.info("Deleted {} tool call stats rows for agent {}", deleted, agentEntityId);
        } catch (Exception e) {
            logger.warn("Failed to delete tool call stats for agent {}: {}", agentEntityId, e.getMessage());
        }
    }

    /**
     * Delete all sub-agent call stats where the deleted agent is caller or callee.
     */
    public void deleteSubAgentCallStats(String tenantId, UUID agentEntityId) {
        try {
            int deleted = entityManager.createNativeQuery(
                "DELETE FROM agent_sub_agent_call_stats_live " +
                "WHERE tenant_id = :tenantId AND (caller_agent_id = :agentId OR callee_agent_id = :agentId)")
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .executeUpdate();
            logger.info("Deleted {} sub-agent call stats rows for agent {}", deleted, agentEntityId);
        } catch (Exception e) {
            logger.warn("Failed to delete sub-agent call stats for agent {}: {}", agentEntityId, e.getMessage());
        }
    }

    /**
     * Delete all execution records (and their children: messages, tool calls, iterations)
     * for a deleted agent. Children are deleted first to respect implicit ordering.
     */
    public void deleteExecutionsByAgent(String tenantId, UUID agentEntityId) {
        try {
            // Delete children first (they reference execution_id)
            int messages = entityManager.createNativeQuery(
                "DELETE FROM agent_execution_messages WHERE execution_id IN " +
                "(SELECT id FROM agent_executions WHERE tenant_id = :tenantId AND agent_entity_id = :agentId)")
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .executeUpdate();

            int toolCalls = entityManager.createNativeQuery(
                "DELETE FROM agent_execution_tool_calls WHERE execution_id IN " +
                "(SELECT id FROM agent_executions WHERE tenant_id = :tenantId AND agent_entity_id = :agentId)")
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .executeUpdate();

            int iterations = entityManager.createNativeQuery(
                "DELETE FROM agent_execution_iterations WHERE execution_id IN " +
                "(SELECT id FROM agent_executions WHERE tenant_id = :tenantId AND agent_entity_id = :agentId)")
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .executeUpdate();

            int executions = entityManager.createNativeQuery(
                "DELETE FROM agent_executions WHERE tenant_id = :tenantId AND agent_entity_id = :agentId")
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .executeUpdate();

            logger.info("Deleted agent {} execution data: {} executions, {} messages, {} tool calls, {} iterations",
                agentEntityId, executions, messages, toolCalls, iterations);
        } catch (Exception e) {
            logger.warn("Failed to delete executions for agent {}: {}", agentEntityId, e.getMessage());
        }
    }

    /**
     * Upsert sub-agent call stats (caller → callee).
     */
    public void upsertSubAgentCallStats(String tenantId, UUID callerAgentId, UUID calleeAgentId, boolean success) {
        try {
            entityManager.createNativeQuery(
                "INSERT INTO agent_sub_agent_call_stats_live " +
                "(tenant_id, caller_agent_id, callee_agent_id, total_calls, success_count, failure_count) " +
                "VALUES (:tenantId, :caller, :callee, 1, :success, :failure) " +
                "ON CONFLICT (tenant_id, caller_agent_id, callee_agent_id) DO UPDATE SET " +
                "total_calls = agent_sub_agent_call_stats_live.total_calls + 1, " +
                "success_count = agent_sub_agent_call_stats_live.success_count + :success, " +
                "failure_count = agent_sub_agent_call_stats_live.failure_count + :failure")
                .setParameter("tenantId", tenantId)
                .setParameter("caller", callerAgentId)
                .setParameter("callee", calleeAgentId)
                .setParameter("success", success ? 1 : 0)
                .setParameter("failure", success ? 0 : 1)
                .executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to upsert sub-agent call stats: tenant={}, caller={}, callee={}, error={}",
                tenantId, callerAgentId, calleeAgentId, e.getMessage());
        }
    }

    // ====================================================================
    // Org-aware fleet rollups (option D - V344). Incrementally maintained
    // siblings of the legacy tenant-keyed tables above, carrying
    // organization_id so GET /agents/stats can read them org-strict instead
    // of scanning the whole execution history. Same fire-and-forget contract:
    // catch + log, never rethrow into the observability transaction.
    // ====================================================================

    /**
     * Upsert one tool call into the org-aware per-agent tool rollup.
     * {@code durationMs} is NULLABLE: a null does NOT contribute to
     * duration_sample_count, so the read's AVG = total_duration_ms /
     * duration_sample_count reproduces AVG(duration_ms)-ignoring-NULL exactly.
     */
    public void upsertToolCallStatsByAgentOrg(String organizationId, String tenantId, UUID agentEntityId,
                                              String toolName, boolean success, Long durationMs,
                                              boolean isRepeat, Instant now) {
        try {
            long durVal = durationMs != null ? durationMs : 0L;
            int durSample = durationMs != null ? 1 : 0;
            entityManager.createNativeQuery(
                "INSERT INTO agent_tool_call_stats_by_agent_org_live " +
                "(organization_id, tenant_id, agent_entity_id, tool_name, total_calls, success_count, failure_count, " +
                "total_duration_ms, duration_sample_count, max_duration_ms, last_used_at, repeat_call_count) " +
                "VALUES (:org, :tenantId, :agentId, :toolName, 1, :success, :failure, :dur, :durSample, :dur, :now, :repeat) " +
                "ON CONFLICT (organization_id, tenant_id, agent_entity_id, tool_name) DO UPDATE SET " +
                "total_calls = agent_tool_call_stats_by_agent_org_live.total_calls + 1, " +
                "success_count = agent_tool_call_stats_by_agent_org_live.success_count + :success, " +
                "failure_count = agent_tool_call_stats_by_agent_org_live.failure_count + :failure, " +
                "total_duration_ms = agent_tool_call_stats_by_agent_org_live.total_duration_ms + :dur, " +
                "duration_sample_count = agent_tool_call_stats_by_agent_org_live.duration_sample_count + :durSample, " +
                "max_duration_ms = GREATEST(agent_tool_call_stats_by_agent_org_live.max_duration_ms, :dur), " +
                "last_used_at = :now, " +
                "repeat_call_count = agent_tool_call_stats_by_agent_org_live.repeat_call_count + :repeat")
                .setParameter("org", organizationId)
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .setParameter("toolName", toolName)
                .setParameter("success", success ? 1 : 0)
                .setParameter("failure", success ? 0 : 1)
                .setParameter("dur", durVal)
                .setParameter("durSample", durSample)
                .setParameter("now", now)
                .setParameter("repeat", isRepeat ? 1 : 0)
                .executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to upsert per-agent-org tool stats: org={}, tenant={}, agent={}, tool={}, error={}",
                organizationId, tenantId, agentEntityId, toolName, e.getMessage());
        }
    }

    /** Upsert one resource-family tool call into the org-aware per-resource rollup. */
    public void upsertResourceCallStatsByAgentOrg(String organizationId, String tenantId, UUID agentEntityId,
                                                  String toolName, String resourceId, boolean success) {
        try {
            entityManager.createNativeQuery(
                "INSERT INTO agent_resource_call_stats_by_agent_org_live " +
                "(organization_id, tenant_id, agent_entity_id, tool_name, resource_id, total_calls, success_count, failure_count) " +
                "VALUES (:org, :tenantId, :agentId, :toolName, :resourceId, 1, :success, :failure) " +
                "ON CONFLICT (organization_id, tenant_id, agent_entity_id, tool_name, resource_id) DO UPDATE SET " +
                "total_calls = agent_resource_call_stats_by_agent_org_live.total_calls + 1, " +
                "success_count = agent_resource_call_stats_by_agent_org_live.success_count + :success, " +
                "failure_count = agent_resource_call_stats_by_agent_org_live.failure_count + :failure")
                .setParameter("org", organizationId)
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .setParameter("toolName", toolName)
                .setParameter("resourceId", resourceId)
                .setParameter("success", success ? 1 : 0)
                .setParameter("failure", success ? 0 : 1)
                .executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to upsert per-agent-org resource stats: org={}, agent={}, tool={}, resource={}, error={}",
                organizationId, agentEntityId, toolName, resourceId, e.getMessage());
        }
    }

    /**
     * Upsert one CALLEE execution into the org-aware sub-agent rollup. Fed from the
     * callee's resolved status: {@code completed}/{@code failed} are the
     * status='COMPLETED'/'FAILED' indicators (a CANCELLED/TIMEOUT callee counts toward
     * neither), matching getAllSubAgentCallStats which FILTERs callee execution status.
     */
    public void upsertSubAgentCallStatsOrg(String organizationId, String tenantId, UUID callerAgentId,
                                           UUID calleeAgentId, boolean completed, boolean failed) {
        try {
            entityManager.createNativeQuery(
                "INSERT INTO agent_sub_agent_call_stats_org_live " +
                "(organization_id, tenant_id, caller_agent_id, callee_agent_id, total_calls, success_count, failure_count) " +
                "VALUES (:org, :tenantId, :caller, :callee, 1, :success, :failure) " +
                "ON CONFLICT (organization_id, tenant_id, caller_agent_id, callee_agent_id) DO UPDATE SET " +
                "total_calls = agent_sub_agent_call_stats_org_live.total_calls + 1, " +
                "success_count = agent_sub_agent_call_stats_org_live.success_count + :success, " +
                "failure_count = agent_sub_agent_call_stats_org_live.failure_count + :failure")
                .setParameter("org", organizationId)
                .setParameter("tenantId", tenantId)
                .setParameter("caller", callerAgentId)
                .setParameter("callee", calleeAgentId)
                .setParameter("success", completed ? 1 : 0)
                .setParameter("failure", failed ? 1 : 0)
                .executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to upsert sub-agent-org stats: org={}, caller={}, callee={}, error={}",
                organizationId, callerAgentId, calleeAgentId, e.getMessage());
        }
    }

    /**
     * Upsert one execution into the org-aware per-agent-per-model rollup. Fed from the
     * RESOLVED status string (not the raw success flag): {@code completed}/{@code failed}
     * are status='COMPLETED'/'FAILED'; {@code budgetExhausted} is a STRICT SUBSET of
     * {@code failed} (status='FAILED' AND stop_reason='BUDGET_EXHAUSTED').
     */
    public void upsertModelExecStatsByAgentOrg(String organizationId, String tenantId, UUID agentEntityId,
                                               String model, boolean completed, boolean failed, boolean budgetExhausted) {
        try {
            entityManager.createNativeQuery(
                "INSERT INTO agent_model_exec_stats_by_agent_org_live " +
                "(organization_id, tenant_id, agent_entity_id, model, total_executions, success_count, failure_count, budget_exhausted_count) " +
                "VALUES (:org, :tenantId, :agentId, :model, 1, :success, :failure, :budget) " +
                "ON CONFLICT (organization_id, tenant_id, agent_entity_id, model) DO UPDATE SET " +
                "total_executions = agent_model_exec_stats_by_agent_org_live.total_executions + 1, " +
                "success_count = agent_model_exec_stats_by_agent_org_live.success_count + :success, " +
                "failure_count = agent_model_exec_stats_by_agent_org_live.failure_count + :failure, " +
                "budget_exhausted_count = agent_model_exec_stats_by_agent_org_live.budget_exhausted_count + :budget")
                .setParameter("org", organizationId)
                .setParameter("tenantId", tenantId)
                .setParameter("agentId", agentEntityId)
                .setParameter("model", model)
                .setParameter("success", completed ? 1 : 0)
                .setParameter("failure", failed ? 1 : 0)
                .setParameter("budget", budgetExhausted ? 1 : 0)
                .executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to upsert per-agent-org model stats: org={}, agent={}, model={}, error={}",
                organizationId, agentEntityId, model, e.getMessage());
        }
    }

    // ---- Org-rollup cleanup on agent deletion (by tenant+agent; org omitted so a
    // ---- delete without org context still fully purges, matching the legacy deletes). ----

    /** Delete an agent's org-aware tool rollup rows. */
    public void deleteToolCallStatsByAgentOrg(String tenantId, UUID agentEntityId) {
        try {
            entityManager.createNativeQuery(
                "DELETE FROM agent_tool_call_stats_by_agent_org_live WHERE tenant_id = :tenantId AND agent_entity_id = :agentId")
                .setParameter("tenantId", tenantId).setParameter("agentId", agentEntityId).executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to delete per-agent-org tool stats for agent {}: {}", agentEntityId, e.getMessage());
        }
    }

    /** Delete an agent's org-aware resource rollup rows. */
    public void deleteResourceCallStatsByAgentOrg(String tenantId, UUID agentEntityId) {
        try {
            entityManager.createNativeQuery(
                "DELETE FROM agent_resource_call_stats_by_agent_org_live WHERE tenant_id = :tenantId AND agent_entity_id = :agentId")
                .setParameter("tenantId", tenantId).setParameter("agentId", agentEntityId).executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to delete per-agent-org resource stats for agent {}: {}", agentEntityId, e.getMessage());
        }
    }

    /** Delete an agent's org-aware model rollup rows. */
    public void deleteModelExecStatsByAgentOrg(String tenantId, UUID agentEntityId) {
        try {
            entityManager.createNativeQuery(
                "DELETE FROM agent_model_exec_stats_by_agent_org_live WHERE tenant_id = :tenantId AND agent_entity_id = :agentId")
                .setParameter("tenantId", tenantId).setParameter("agentId", agentEntityId).executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to delete per-agent-org model stats for agent {}: {}", agentEntityId, e.getMessage());
        }
    }

    /** Delete an agent's org-aware sub-agent rollup rows (as caller OR callee). */
    public void deleteSubAgentCallStatsOrg(String tenantId, UUID agentEntityId) {
        try {
            entityManager.createNativeQuery(
                "DELETE FROM agent_sub_agent_call_stats_org_live WHERE tenant_id = :tenantId " +
                "AND (caller_agent_id = :agentId OR callee_agent_id = :agentId)")
                .setParameter("tenantId", tenantId).setParameter("agentId", agentEntityId).executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to delete sub-agent-org stats for agent {}: {}", agentEntityId, e.getMessage());
        }
    }
}
