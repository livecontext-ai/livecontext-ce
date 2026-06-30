package com.apimarketplace.agent.controller;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionIterationEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.AgentMetricsQueryService;
import com.apimarketplace.agent.service.FleetStatsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for agent observability metrics.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentMetricsController {

    private final AgentMetricsQueryService metricsQueryService;
    private final AgentRepository agentRepository;
    private final TenantResolver tenantResolver;
    private final FleetStatsService fleetStatsService;

    public AgentMetricsController(
            AgentMetricsQueryService metricsQueryService,
            AgentRepository agentRepository,
            TenantResolver tenantResolver,
            FleetStatsService fleetStatsService) {
        this.metricsQueryService = metricsQueryService;
        this.agentRepository = agentRepository;
        this.tenantResolver = tenantResolver;
        this.fleetStatsService = fleetStatsService;
    }

    /**
     * Get paginated execution history for an agent - PR20 strict-isolation.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/executions")
    public ResponseEntity<Page<AgentExecutionEntity>> getAgentExecutions(
            HttpServletRequest httpRequest,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        // PR20 - workspace scope routes to strict org / strict personal finder.
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        Page<AgentExecutionEntity> executions = metricsQueryService.getAgentExecutions(
            id, tenantId, organizationId, PageRequest.of(page, Math.min(size, 100)));

        return ResponseEntity.ok(executions);
    }

    /**
     * Get a single execution with all counters - PR20 strict-isolation scope check.
     */
    @GetMapping("/executions/{execId}")
    public ResponseEntity<AgentExecutionEntity> getExecution(
            HttpServletRequest httpRequest,
            @PathVariable UUID execId) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return metricsQueryService.getExecutionForScope(execId, tenantId, organizationId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get ordered conversation messages for an execution.
     * <p>
     * Paginated DESC by sequenceNumber (page 0 = newest batch). Default size 30 / max 100.
     * Frontend reverses the page to ASC for chronological display.
     * PR20 - parent execution scope-check ensures child rows match the requested workspace.
     */
    @GetMapping("/executions/{execId}/conversation")
    public ResponseEntity<Page<AgentExecutionMessageEntity>> getConversation(
            HttpServletRequest httpRequest,
            @PathVariable UUID execId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return metricsQueryService.getExecutionForScope(execId, tenantId, organizationId)
            .map(exec -> ResponseEntity.ok(metricsQueryService.getConversationPaged(
                execId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get tool calls for an execution.
     * <p>
     * Paginated DESC by sequenceNumber (page 0 = newest). Default size 30 / max 100.
     * Tool-call payloads carry MB-scale `content` blobs - unpaginated was the OOM shape
     * called out in the Email Digest incident brief.
     * PR20 - parent execution scope-check ensures child rows match the requested workspace.
     */
    @GetMapping("/executions/{execId}/tool-calls")
    public ResponseEntity<Page<AgentExecutionToolCallEntity>> getToolCalls(
            HttpServletRequest httpRequest,
            @PathVariable UUID execId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return metricsQueryService.getExecutionForScope(execId, tenantId, organizationId)
            .map(exec -> ResponseEntity.ok(metricsQueryService.getToolCallsPaged(
                execId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get iterations for an execution (paginated DESC, page 0 = newest). Default size 30 / max 100.
     * PR20 - parent execution scope-check ensures child rows match the requested workspace.
     */
    @GetMapping("/executions/{execId}/iterations")
    public ResponseEntity<Page<AgentExecutionIterationEntity>> getIterations(
            HttpServletRequest httpRequest,
            @PathVariable UUID execId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return metricsQueryService.getExecutionForScope(execId, tenantId, organizationId)
            .map(exec -> ResponseEntity.ok(metricsQueryService.getIterationsPaged(
                execId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get per-tool aggregate stats for a specific agent.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/tool-stats")
    public ResponseEntity<List<Map<String, Object>>> getAgentToolStats(
            HttpServletRequest httpRequest,
            @PathVariable UUID id) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return ResponseEntity.ok(metricsQueryService.getToolStatsByAgent(tenantId, organizationId, id));
    }

    /**
     * Get per-RESOURCE aggregate stats for a specific agent - breaks the
     * resource-family tools (table/interface/workflow/application/skill) down by
     * the individual resource id targeted, so each fleet leaf shows its own usage.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/resource-stats")
    public ResponseEntity<List<Map<String, Object>>> getAgentResourceStats(
            HttpServletRequest httpRequest,
            @PathVariable UUID id) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return ResponseEntity.ok(metricsQueryService.getResourceStatsByAgent(tenantId, organizationId, id));
    }

    /**
     * Get per-caller sub-agent call stats for a given agent.
     * Returns how many times this agent called each sub-agent.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/sub-agent-stats")
    public ResponseEntity<List<Map<String, Object>>> getSubAgentCallStats(
            HttpServletRequest httpRequest,
            @PathVariable UUID id) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return ResponseEntity.ok(metricsQueryService.getSubAgentCallStats(tenantId, organizationId, id));
    }

    /**
     * Get all caller->callee edges for ancestor graph building.
     */
    @GetMapping("/sub-agent-edges")
    public ResponseEntity<List<Map<String, Object>>> getSubAgentEdges(
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return ResponseEntity.ok(metricsQueryService.getAllSubAgentEdges(tenantId, organizationId));
    }

    /**
     * Sub-agent executions spawned by a conversation (newest first).
     * <p>
     * Sub-agents run under their own dedicated conversation, so they never
     * appear when an observability view filters on the spawned execution's own
     * conversationId. They DO carry {@code parent_conversation_id} = the
     * spawning conversation, which this endpoint resolves - letting a
     * conversation observability panel list the sub-agent executions and drill
     * into each via {@code /executions/{execId}/tool-calls}. Org-strict scope.
     */
    @GetMapping("/conversations/{conversationId}/sub-agent-executions")
    public ResponseEntity<List<AgentExecutionEntity>> getSubAgentExecutionsForConversation(
            HttpServletRequest httpRequest,
            @PathVariable("conversationId") String conversationId) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return ResponseEntity.ok(
            metricsQueryService.getSubAgentExecutionsForConversation(conversationId, organizationId));
    }

    /**
     * Fleet batch - ALL agents' tool / resource / sub-agent / model stats in ONE
     * call, each row keyed by {@code agentId}. Replaces the per-agent
     * {@code /{id}/tool-stats}, {@code /resource-stats}, {@code /sub-agent-stats},
     * {@code /model-stats} fan-out (4 requests per agent) the Agent Fleet canvas
     * otherwise makes - collapsing 4N requests into one for the whole fleet.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getFleetStats(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        // Cache-aside + parallel compute on miss (FleetStatsService). The four GROUP-BY
        // aggregations no longer run sequentially on the request thread.
        return ResponseEntity.ok(fleetStatsService.getFleetStats(tenantId, organizationId));
    }

    /**
     * Get per-model aggregate stats for a specific agent.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/model-stats")
    public ResponseEntity<List<Map<String, Object>>> getModelStats(
            HttpServletRequest httpRequest,
            @PathVariable UUID id) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return ResponseEntity.ok(metricsQueryService.getModelStatsByAgent(tenantId, organizationId, id));
    }

    /**
     * Get per-conversation aggregate stats for a specific agent.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/conversation-stats")
    public ResponseEntity<List<Map<String, Object>>> getConversationStats(
            HttpServletRequest httpRequest,
            @PathVariable UUID id) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return ResponseEntity.ok(metricsQueryService.getConversationStatsByAgent(tenantId, organizationId, id));
    }

    // ========== Agent Type Metrics (classify / guardrail) ==========

    /**
     * Get summary metrics for a specific agent type (classify or guardrail).
     */
    @GetMapping("/metrics/{agentType}-summary")
    public ResponseEntity<Map<String, Object>> getAgentTypeSummary(
            HttpServletRequest httpRequest,
            @PathVariable String agentType) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        return ResponseEntity.ok(metricsQueryService.getAgentTypeSummary(tenantId, organizationId, agentType));
    }

    /**
     * Get paginated executions for a specific agent type (classify or guardrail).
     */
    @GetMapping("/metrics/{agentType}-executions")
    public ResponseEntity<Page<AgentExecutionEntity>> getAgentTypeExecutions(
            HttpServletRequest httpRequest,
            @PathVariable String agentType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        return ResponseEntity.ok(metricsQueryService.getAgentTypeExecutions(
            tenantId, organizationId, agentType, PageRequest.of(page, Math.min(size, 100))));
    }

    // ========== General Chat Metrics ==========

    /**
     * Get summary metrics for general chat (no agent selected).
     */
    @GetMapping("/metrics/chat-summary")
    public ResponseEntity<Map<String, Object>> getChatSummary(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        return ResponseEntity.ok(metricsQueryService.getChatSummary(tenantId, organizationId));
    }

    /**
     * Get paginated general chat executions.
     */
    @GetMapping("/metrics/chat-executions")
    public ResponseEntity<Page<AgentExecutionEntity>> getChatExecutions(
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        return ResponseEntity.ok(metricsQueryService.getChatExecutions(
            tenantId, organizationId, PageRequest.of(page, Math.min(size, 100))));
    }

    /**
     * Get daily time-series stats for general chat.
     */
    @GetMapping("/metrics/chat-daily")
    public ResponseEntity<List<Map<String, Object>>> getChatDailyStats(
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "30") int days) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        return ResponseEntity.ok(metricsQueryService.getChatDailyStats(tenantId, organizationId, Math.min(days, 365)));
    }

    /**
     * Get per-tool stats for general chat executions.
     */
    @GetMapping("/metrics/chat-tools")
    public ResponseEntity<List<Map<String, Object>>> getChatToolStats(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        return ResponseEntity.ok(metricsQueryService.getChatToolStats(tenantId, organizationId));
    }

    /**
     * Get per-tool aggregate stats from materialized view.
     */
    @GetMapping("/metrics/tools")
    public ResponseEntity<List<Map<String, Object>>> getToolStats(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        return ResponseEntity.ok(metricsQueryService.getToolStats(tenantId, organizationId));
    }

    /**
     * Get daily time-series stats. Uses materialized view for all agents,
     * or direct query on agent_executions when filtered by agentId.
     */
    @GetMapping("/metrics/daily")
    public ResponseEntity<List<Map<String, Object>>> getDailyStats(
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) UUID agentId) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        if (agentId != null) {
            return ResponseEntity.ok(metricsQueryService.getDailyStatsByAgent(tenantId, organizationId, Math.min(days, 365), agentId));
        }
        return ResponseEntity.ok(metricsQueryService.getDailyStats(tenantId, organizationId, Math.min(days, 365)));
    }

    /**
     * Get fleet summary from agents table counters.
     *
     * <p>PR29 - scope-aware: an org workspace gets the org-wide rollup,
     * a personal workspace gets the personal-only rollup (org_id IS NULL).
     * No mixing.
     */
    @GetMapping("/metrics/fleet-summary")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getFleetSummary(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        TenantResolver.requireOrgId(organizationId);

        // Aggregate counters in SQL - avoids loading full entities (and @Lob fields)
        Object[] raw = agentRepository.getFleetCountersByOrganizationIdStrict(organizationId);
        // Spring Data may wrap the single-row result: unwrap if needed
        Object[] row = (raw.length == 1 && raw[0] instanceof Object[]) ? (Object[]) raw[0] : raw;
        long totalAgents     = ((Number) row[0]).longValue();
        long totalExecutions = ((Number) row[1]).longValue();
        long totalTokens     = ((Number) row[2]).longValue();
        long totalToolCalls  = ((Number) row[3]).longValue();
        long totalDuration   = ((Number) row[4]).longValue();
        long totalSuccess    = ((Number) row[5]).longValue();
        long totalFailure    = ((Number) row[6]).longValue();
        double totalCredits  = row.length > 7 ? ((Number) row[7]).doubleValue() : 0.0;

        // Cancelled + loop detected counts from agent_executions
        Map<String, Long> extraCounts = metricsQueryService.getFleetExtraCounts(tenantId, organizationId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAgents", totalAgents);
        summary.put("totalExecutions", totalExecutions);
        summary.put("totalTokensUsed", totalTokens);
        summary.put("totalToolCalls", totalToolCalls);
        summary.put("totalDurationMs", totalDuration);
        summary.put("successCount", totalSuccess);
        summary.put("failureCount", totalFailure);
        summary.put("totalCreditsConsumed", totalCredits);
        summary.put("totalCachedTokens", extraCounts.getOrDefault("totalCachedTokens", 0L));
        summary.put("cancelledCount", extraCounts.getOrDefault("cancelledCount", 0L));
        summary.put("loopDetectedCount", extraCounts.getOrDefault("loopDetectedCount", 0L));
        summary.put("avgDurationMs", totalExecutions > 0 ? totalDuration / totalExecutions : 0);
        summary.put("successRate", totalExecutions > 0
            ? Math.round(totalSuccess * 1000.0 / totalExecutions) / 10.0 : 0.0);

        return ResponseEntity.ok(summary);
    }
}
