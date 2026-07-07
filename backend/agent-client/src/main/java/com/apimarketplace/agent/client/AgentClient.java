package com.apimarketplace.agent.client;

import com.apimarketplace.agent.client.dto.*;
import com.apimarketplace.agent.client.dto.execution.*;
import com.apimarketplace.common.recentactivity.RecentActivityScopeResultDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP client for communicating with agent-service.
 * Follows the same pattern as DataSourceClient.
 * <p>
 * Public API methods route through the gateway-authenticated endpoints.
 * Internal API methods use /api/internal/agents/* for inter-service calls.
 */
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final RestTemplate restTemplate;
    private final RestTemplate executionRestTemplate;
    // Dedicated bounded-timeout template used ONLY by
    // {@link #getRecentActivity} - 2s connect / 3s read. Prevents the
    // recent-activity branch from parking an orchestrator aggregator thread
    // on a slow / hung agent-service (auditor B v5 fix; the default
    // {@code restTemplate} has open-ended JDK timeouts).
    private final RestTemplate recentActivityRestTemplate;
    private final String baseUrl;

    public AgentClient(String agentServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.executionRestTemplate = createExecutionRestTemplate();
        this.recentActivityRestTemplate = createRecentActivityRestTemplate();
        this.baseUrl = agentServiceUrl;
    }

    public AgentClient(RestTemplate restTemplate, String agentServiceUrl) {
        this.restTemplate = restTemplate;
        this.executionRestTemplate = createExecutionRestTemplate();
        this.recentActivityRestTemplate = createRecentActivityRestTemplate();
        this.baseUrl = agentServiceUrl;
    }

    /**
     * Read timeout of the blocking execution endpoints = the total wall-clock budget
     * this client grants a sync agent run. Must cover the executionTimeout/
     * inactivityTimeout contract maximum (7200s) plus the downstream bridge cap
     * (125 min): under the previous 65-min value a valid 2h budget could never
     * elapse on the sync HTTP path. If the caller still times out before the agent
     * finishes, the cancel key is set via Redis and agent-service detects it within
     * one iteration.
     */
    static final Duration EXECUTION_READ_TIMEOUT = Duration.ofMinutes(130);

    /**
     * Create a RestTemplate with long timeouts for LLM execution endpoints.
     */
    private static RestTemplate createExecutionRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(EXECUTION_READ_TIMEOUT);
        return new RestTemplate(factory);
    }

    /**
     * 2s connect / 3s read - tight read-path budget for the
     * {@code /api/internal/agents/recent-activity} fan-out branch.
     */
    private static RestTemplate createRecentActivityRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }

    // ========== CRUD operations (via internal endpoints, no HMAC needed) ==========

    /**
     * Get an agent by ID. Post-V261: resolves the caller's active workspace
     * from {@link com.apimarketplace.common.web.TenantResolver#currentRequestOrganizationId()}
     * (X-Organization-ID header → ThreadLocal). Passes the resolved org so
     * server-side strict isolation matches the row's organization_id.
     */
    public AgentDto getAgent(UUID id, String tenantId) {
        return getAgent(id, tenantId, com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Get an agent by ID in the caller's active organization scope.
     */
    public AgentDto getAgent(UUID id, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/" + id + "/get";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<AgentDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, AgentDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get agent id={} org={}: {}", id, organizationId, e.getMessage());
            return null;
        }
    }

    /**
     * Get all agents for a tenant.
     */
    public List<AgentDto> getAgentsByTenantId(String tenantId) {
        String url = baseUrl + "/api/internal/agents/all";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<AgentDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get agents for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Batch lookup of every active agent webhook token for a tenant - used by
     * orchestrator's dashboard "active automations" widget. Single round-trip,
     * no per-agent N+1.
     */
    public List<ActiveAgentWebhookTokenDto> getActiveAgentWebhookTokens(String tenantId) {
        String url = baseUrl + "/api/internal/agents/active-webhook-tokens";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<ActiveAgentWebhookTokenDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get active agent webhook tokens for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get agents with org-based access filtering.
     */
    public List<AgentDto> getAgents(String tenantId, String orgId, String orgRole) {
        String url = baseUrl + "/api/internal/agents/all";
        HttpHeaders headers = buildHeaders(tenantId);
        if (orgId != null) headers.set("X-Organization-ID", orgId);
        if (orgRole != null) headers.set("X-Organization-Role", orgRole);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<List<AgentDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get agents for tenant={}, org={}: {}", tenantId, orgId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Create an agent (backward-compat overload).
     */
    public AgentDto createAgent(Map<String, Object> request, String tenantId) {
        return createAgent(request, tenantId, null);
    }

    /**
     * Create an agent in an explicit organization scope.
     */
    public AgentDto createAgent(Map<String, Object> request, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/create";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<AgentDto> response = restTemplate.exchange(url, HttpMethod.POST, entity, AgentDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create agent: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update an agent (backward-compat overload).
     */
    public AgentDto updateAgent(UUID id, Map<String, Object> updates, String tenantId) {
        return updateAgent(id, updates, tenantId, null);
    }

    /**
     * Update an agent in an explicit organization scope.
     */
    public AgentDto updateAgent(UUID id, Map<String, Object> updates, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/" + id + "/update";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(updates, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<AgentDto> response = restTemplate.exchange(url, HttpMethod.PUT, entity, AgentDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update agent id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Delete an agent.
     */
    public void deleteAgent(UUID id, String tenantId) {
        deleteAgent(id, tenantId, null);
    }

    /**
     * Delete an agent in an explicit organization scope.
     */
    public void deleteAgent(UUID id, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/" + id + "/delete";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to delete agent id={}: {}", id, e.getMessage());
        }
    }

    // ========== Internal API (used by orchestrator for inter-service operations) ==========

    /**
     * Resolve agent config by ID (for AgentConfigResolver in execution path).
     */
    public AgentDto resolveAgentConfig(UUID agentConfigId, String tenantId) {
        return resolveAgentConfig(agentConfigId, tenantId, null);
    }

    /**
     * Resolve agent config by ID in an explicit workspace scope.
     */
    public AgentDto resolveAgentConfig(UUID agentConfigId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/by-config/" + agentConfigId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<AgentDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, AgentDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to resolve agent config id={}: {}", agentConfigId, e.getMessage());
            return null;
        }
    }

    /**
     * Record agent execution observability data (fire-and-forget).
     */
    public void recordObservability(AgentObservabilityRequest request) {
        String url = baseUrl + "/api/internal/agents/observability";
        HttpEntity<AgentObservabilityRequest> entity = new HttpEntity<>(
                request,
                buildHeaders(request.getTenantId(), request.getOrganizationId()));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to record observability (non-critical): {}", e.getMessage());
        }
    }

    /**
     * The direct-API execution target a billed {@code (provider, model)} pair is
     * linked to, from agent-service's model execution links (CLOUD only).
     * {@code executionModel} is already resolved (never null).
     */
    public record ExecutionLinkTarget(String executionProvider, String executionModel) {}

    /**
     * Resolve the execution link for a billed pair, restricted to DIRECT-API targets
     * (agent-service returns no route for bridge-target links). Only ALL-scoped
     * links can match - the calling consumer (browser agent) carries no activity
     * source. Empty when the pair is unlinked, the link targets a CLI bridge, the
     * feature is absent (CE: the internal endpoint 404s), or agent-service is
     * unreachable - the caller then keeps the billed pair, so a transient error can
     * never fail a run, only skip the reroute (logged).
     */
    public Optional<ExecutionLinkTarget> resolveExecutionLinkApiTarget(String billedProvider, String billedModel) {
        // Built as a java.net.URI: the String overload re-encodes (URI-template
        // semantics), which double-encodes '%'/spaces and throws on '{'/'}' in ids.
        java.net.URI url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/model-config/execution-links/resolve-api-target")
                .queryParam("billedProvider", billedProvider)
                .queryParam("billedModel", billedModel)
                .build()
                .encode()
                .toUri();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) {
                return Optional.empty(); // 204: unlinked or bridge-target
            }
            Object provider = body.get("executionProvider");
            Object model = body.get("executionModel");
            if (provider == null || model == null) {
                return Optional.empty();
            }
            return Optional.of(new ExecutionLinkTarget(String.valueOf(provider), String.valueOf(model)));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty(); // CE: execution-links controller not loaded
        } catch (Exception e) {
            log.warn("Failed to resolve execution link for {}/{} (run stays on the billed pair): {}",
                    billedProvider, billedModel, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * F2.1 - propagate a conversation STOP to any workflow runs spawned by the
     * agent loop in that conversation. Best-effort, fire-and-forget: if
     * agent-service is down or the call fails, the conversation STOP itself
     * still proceeds (only the workflow side won't be cancelled until the
     * orchestrator's own watchdog hits).
     *
     * @return number of distinct workflow runs flagged for cancellation, 0 on error/none
     */
    public int cancelWorkflowsForConversation(String conversationId) {
        return cancelWorkflowsForConversation(conversationId, null);
    }

    public int cancelWorkflowsForConversation(String conversationId, String organizationId) {
        if (conversationId == null || conversationId.isBlank()) return 0;
        String url = baseUrl + "/api/internal/agents/conversations/" + conversationId + "/cancel-workflows";
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(buildHeaders(null, organizationId)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) return 0;
            Object n = body.get("cancelledRuns");
            return n instanceof Number ? ((Number) n).intValue() : 0;
        } catch (Exception e) {
            log.warn("Failed to cascade conversation STOP to workflow runs (conv={}): {}",
                conversationId, e.getMessage());
            return 0;
        }
    }

    /**
     * F3.4 - cascade-cancel background tasks linked to this conversation. Best-effort.
     */
    public int cancelTasksForConversation(String conversationId, String tenantId) {
        return cancelTasksForConversation(conversationId, tenantId, null);
    }

    public int cancelTasksForConversation(String conversationId, String tenantId, String organizationId) {
        if (conversationId == null || conversationId.isBlank()) return 0;
        String url = baseUrl + "/api/internal/agents/conversations/" + conversationId + "/cancel-tasks";
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(buildHeaders(tenantId, organizationId)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) return 0;
            Object n = body.get("cancelledTasks");
            return n instanceof Number ? ((Number) n).intValue() : 0;
        } catch (Exception e) {
            log.warn("Failed to cascade conversation STOP to tasks (conv={}): {}",
                conversationId, e.getMessage());
            return 0;
        }
    }

    /**
     * Check and reset periodic credit budget for an agent if needed.
     */
    public void checkAndResetBudget(UUID agentId) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/budget/check-reset";
        try {
            restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(buildHeaders(null)), Map.class);
        } catch (Exception e) {
            log.warn("Failed to check/reset budget for agent {}: {}", agentId, e.getMessage());
        }
    }

    /**
     * Get skills assigned to an agent.
     */
    public List<AgentSkillDto> getSkillsForAgent(UUID agentId, String tenantId) {
        return getSkillsForAgent(agentId, tenantId, null);
    }

    /**
     * Get skills assigned to an agent in an explicit organization scope.
     */
    public List<AgentSkillDto> getSkillsForAgent(UUID agentId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/skills";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<AgentSkillDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get skills for agent={}: {}", agentId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Delete all agents associated with a workflow (backward-compat overload).
     */
    public void deleteAgentsByWorkflowId(UUID workflowId, String tenantId) {
        deleteAgentsByWorkflowId(workflowId, tenantId, null);
    }

    /**
     * Delete all agents associated with a workflow in an explicit organization scope.
     */
    public void deleteAgentsByWorkflowId(UUID workflowId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/by-workflow/" + workflowId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to delete agents for workflow={}: {}", workflowId, e.getMessage());
        }
    }

    /**
     * Count agents for a tenant.
     */
    public long countByTenantId(String tenantId) {
        String url = baseUrl + "/api/internal/agents/count";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Long> response = restTemplate.exchange(url, HttpMethod.GET, entity, Long.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.error("Failed to count agents for tenant={}: {}", tenantId, e.getMessage());
            return 0;
        }
    }

    /**
     * Get metrics summary for a workflow.
     */
    public AgentMetricsSummaryDto getMetricsForWorkflow(UUID workflowId, String tenantId, int page, int size) {
        String url = baseUrl + "/api/internal/agents/metrics/workflow/" + workflowId
                + "?page=" + page + "&size=" + size;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<AgentMetricsSummaryDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, AgentMetricsSummaryDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get metrics for workflow={}: {}", workflowId, e.getMessage());
            return new AgentMetricsSummaryDto(Collections.emptyList(), 0);
        }
    }

    /**
     * Bulk find agents by IDs.
     */
    public List<AgentDto> bulkFind(List<UUID> ids, String tenantId) {
        return bulkFind(ids, tenantId, null);
    }

    /**
     * Bulk find agents by IDs in an explicit organization scope.
     */
    public List<AgentDto> bulkFind(List<UUID> ids, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/bulk";
        HttpEntity<List<UUID>> entity = new HttpEntity<>(ids, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<AgentDto>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to bulk find agents: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get agent by conversation ID.
     */
    public AgentDto getAgentByConversationId(UUID conversationId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/by-conversation/" + conversationId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<AgentDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, AgentDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get agent by conversationId={}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    /**
     * Get agents by project ID.
     */
    public List<AgentDto> findByProjectId(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/by-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<AgentDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to find agents for project={}: {}", projectId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Count agents by project ID.
     */
    public long countByProjectId(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/by-project/" + projectId + "/count";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Long> response = restTemplate.exchange(url, HttpMethod.GET, entity, Long.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.error("Failed to count agents for project={}: {}", projectId, e.getMessage());
            return 0;
        }
    }

    /**
     * Assign an agent to a project.
     */
    public boolean assignToProject(UUID agentId, UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Boolean> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Boolean.class);
            return Boolean.TRUE.equals(response.getBody());
        } catch (Exception e) {
            log.error("Failed to assign agent={} to project={}: {}", agentId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Remove an agent from a project.
     */
    public boolean removeFromProject(UUID agentId, UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Boolean> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, Boolean.class);
            return Boolean.TRUE.equals(response.getBody());
        } catch (Exception e) {
            log.error("Failed to remove agent={} from project={}: {}", agentId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Unassign all agents from a project.
     */
    public void unassignAllFromProject(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/by-project/" + projectId + "/unassign-all";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to unassign agents from project={}: {}", projectId, e.getMessage());
        }
    }

    /**
     * Get fleet counters for a tenant (aggregated metrics).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFleetCounters(String tenantId) {
        String url = baseUrl + "/api/internal/agents/fleet-counters";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.error("Failed to get fleet counters for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Webhook + Schedule (used by AgentCrudModule in orchestrator) ==========

    /**
     * Get webhook config for an agent. Returns null if no webhook exists.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getWebhookConfig(UUID agentId, String tenantId) {
        return getWebhookConfig(agentId, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getWebhookConfig(UUID agentId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/agents/" + agentId + "/webhook?tenantId=" + tenantId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return resp.getBody();
        } catch (Exception e) {
            log.debug("No webhook for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * Get schedule config for an agent. Returns null if no schedule exists.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getScheduleConfig(UUID agentId, String tenantId) {
        return getScheduleConfig(agentId, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getScheduleConfig(UUID agentId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/agents/" + agentId + "/schedule?tenantId=" + tenantId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return resp.getBody();
        } catch (Exception e) {
            log.debug("No schedule for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * Create or update webhook for an agent. Returns webhook config with URL.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createOrUpdateWebhook(UUID agentId, Map<String, Object> config, String tenantId) {
        String url = baseUrl + "/api/agents/" + agentId + "/webhook";
        HttpHeaders headers = buildHeaders(tenantId);
        Object organizationId = config != null ? config.get("organizationId") : null;
        String orgId = normalizeOrganizationId(organizationId);
        if (orgId != null && !headers.containsKey("X-Organization-ID")) {
            headers.set("X-Organization-ID", orgId);
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(config, headers);
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return resp.getBody();
        } catch (Exception e) {
            log.error("Failed to create webhook for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * Create or update schedule for an agent. Returns schedule config.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createOrUpdateSchedule(UUID agentId, Map<String, Object> config, String tenantId) {
        String url = baseUrl + "/api/agents/" + agentId + "/schedule";
        HttpHeaders headers = buildHeaders(tenantId);
        Object organizationId = config != null ? config.get("organizationId") : null;
        String orgId = normalizeOrganizationId(organizationId);
        if (orgId != null && !headers.containsKey("X-Organization-ID")) {
            headers.set("X-Organization-ID", orgId);
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(config, headers);
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return resp.getBody();
        } catch (Exception e) {
            log.error("Failed to create schedule for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    // ========== Skill CRUD (used by SkillCrudModule in orchestrator) ==========

    /**
     * Create a skill (backward-compat overload).
     */
    public SkillDto createSkill(Map<String, Object> request, String tenantId) {
        return createSkill(request, tenantId, null);
    }

    /**
     * Create a skill in an explicit organization scope.
     */
    public SkillDto createSkill(Map<String, Object> request, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/skills";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<SkillDto> response = restTemplate.exchange(url, HttpMethod.POST, entity, SkillDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create skill: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get a skill by ID.
     */
    public SkillDto getSkill(UUID skillId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/skills/" + skillId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<SkillDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, SkillDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get skill id={}: {}", skillId, e.getMessage());
            return null;
        }
    }

    /**
     * List all skills for a tenant.
     */
    public List<SkillDto> listSkills(String tenantId) {
        String url = baseUrl + "/api/internal/agents/skills";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<SkillDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to list skills for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Update a skill (backward-compat overload).
     */
    public SkillDto updateSkill(UUID skillId, Map<String, Object> updates, String tenantId) {
        return updateSkill(skillId, updates, tenantId, null);
    }

    /**
     * Update a skill in an explicit organization scope.
     */
    public SkillDto updateSkill(UUID skillId, Map<String, Object> updates, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/skills/" + skillId;
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(updates, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<SkillDto> response = restTemplate.exchange(url, HttpMethod.PUT, entity, SkillDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update skill id={}: {}", skillId, e.getMessage());
            return null;
        }
    }

    /**
     * Move a skill to a folder.
     */
    public void moveSkill(UUID skillId, UUID folderId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/skills/" + skillId + "/move";
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("folderId", folderId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to move skill id={}: {}", skillId, e.getMessage());
        }
    }

    /**
     * Delete a skill.
     */
    public void deleteSkill(UUID skillId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/skills/" + skillId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to delete skill id={}: {}", skillId, e.getMessage());
        }
    }

    /**
     * Seed default skills for a tenant (idempotent).
     * Creates DB entities from DefaultSkillsProvider for any defaults not yet present.
     *
     * @return number of skills seeded (0 if all already exist)
     */
    @SuppressWarnings("unchecked")
    public int seedDefaultSkills(String tenantId) {
        String url = baseUrl + "/api/internal/agents/skills/seed-defaults";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (response.getBody() != null && response.getBody().get("seeded") instanceof Number n) {
                return n.intValue();
            }
            return 0;
        } catch (Exception e) {
            log.error("Failed to seed default skills for tenant={}: {}", tenantId, e.getMessage());
            return 0;
        }
    }

    /**
     * Reset a default skill to its original content.
     */
    public SkillDto resetDefaultSkill(UUID skillId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/skills/" + skillId + "/reset";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<SkillDto> response = restTemplate.exchange(url, HttpMethod.POST, entity, SkillDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to reset default skill id={}: {}", skillId, e.getMessage());
            return null;
        }
    }

    /**
     * Add skills to an agent (additive - does not remove existing).
     * Returns the number of skills actually added.
     */
    public int addAgentSkills(UUID agentId, List<Map<String, Object>> assignments, String tenantId) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/skills/add";
        HttpEntity<List<Map<String, Object>>> entity = new HttpEntity<>(assignments, buildHeaders(tenantId));
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(url, HttpMethod.POST, entity, Integer.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.error("Failed to add skills to agent={}: {}", agentId, e.getMessage());
            return 0;
        }
    }

    /**
     * Set skills for an agent (replaces existing assignments).
     */
    public void setAgentSkills(UUID agentId, List<Map<String, Object>> assignments, String tenantId) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/skills/set";
        HttpEntity<List<Map<String, Object>>> entity = new HttpEntity<>(assignments, buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to set skills for agent={}: {}", agentId, e.getMessage());
        }
    }

    // ========== Skill Folder CRUD (used by SkillFolderModule in orchestrator) ==========

    /**
     * Create a skill folder (backward-compat overload).
     */
    public SkillFolderDto createFolder(Map<String, Object> request, String tenantId) {
        return createFolder(request, tenantId, null);
    }

    /**
     * Create a skill folder in an explicit organization scope.
     */
    public SkillFolderDto createFolder(Map<String, Object> request, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/skill-folders";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<SkillFolderDto> response = restTemplate.exchange(url, HttpMethod.POST, entity, SkillFolderDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create folder: {}", e.getMessage());
            return null;
        }
    }

    /**
     * List all skill folders for a tenant.
     */
    public List<SkillFolderDto> listFolders(String tenantId) {
        String url = baseUrl + "/api/internal/agents/skill-folders";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<SkillFolderDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to list folders for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Rename a skill folder.
     */
    public SkillFolderDto renameFolder(UUID folderId, Map<String, Object> request, String tenantId) {
        String url = baseUrl + "/api/internal/agents/skill-folders/" + folderId + "/rename";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            ResponseEntity<SkillFolderDto> response = restTemplate.exchange(url, HttpMethod.PUT, entity, SkillFolderDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to rename folder id={}: {}", folderId, e.getMessage());
            return null;
        }
    }

    /**
     * Move a skill folder.
     */
    public SkillFolderDto moveFolder(UUID folderId, Map<String, Object> request, String tenantId) {
        String url = baseUrl + "/api/internal/agents/skill-folders/" + folderId + "/move";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            ResponseEntity<SkillFolderDto> response = restTemplate.exchange(url, HttpMethod.PUT, entity, SkillFolderDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to move folder id={}: {}", folderId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a skill folder.
     */
    public void deleteFolder(UUID folderId, String tenantId) {
        String url = baseUrl + "/api/internal/agents/skill-folders/" + folderId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to delete folder id={}: {}", folderId, e.getMessage());
        }
    }

    // ========== Publication Acquisition: Clone Agent ==========

    /**
     * Clone an agent from snapshot data during publication acquisition.
     * Creates agent + skills + links in one call.
     * Returns a map with "agentId" → new agent UUID.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cloneFromSnapshot(Map<String, Object> request) {
        String url = baseUrl + "/api/internal/agents/clone-from-snapshot";
        String tenantId = (String) request.get("tenantId");
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to clone agent from snapshot: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Remap resource IDs in an agent's toolsConfig after all resources have been cloned.
     */
    public void remapToolsConfig(UUID agentId, Map<String, Object> mappings) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/remap-tools-config";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(mappings, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to remap toolsConfig for agent {}: {}", agentId, e.getMessage());
        }
    }

    // ========== Chat Agent Observability (proxy for conversation-service) ==========

    /**
     * Record chat agent observability. Used by orchestrator's thin proxy controller
     * to forward conversation-service requests to agent-service.
     */
    public void recordChatObservability(String tenantId, Map<String, Object> request) {
        String url = baseUrl + "/api/internal/agent-observability/record";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to forward chat observability (non-critical): {}", e.getMessage());
        }
    }

    // ========== Sub-agent Observability (used by AgentExecuteModule) ==========

    /**
     * Record sub-agent observability via a simplified request.
     * Fire-and-forget: errors are logged but don't propagate.
     */
    public void recordSubAgentObservability(Map<String, Object> request, String tenantId) {
        String url = baseUrl + "/api/internal/agents/observability/sub-agent";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to record sub-agent observability (non-critical): {}", e.getMessage());
        }
    }

    // ========== Agent Execution (remote LLM dispatch) ==========

    /**
     * Execute a full agent (with tool calls and streaming) on agent-service.
     * Uses long-timeout RestTemplate since agent loops can run for minutes.
     *
     * @return execution response, or null on failure
     */
    public AgentExecutionResponseDto executeAgent(AgentExecutionRequestDto request) {
        String url = baseUrl + "/api/internal/agent/execute/agent";
        String organizationId = normalizeOrganizationId(
                request.credentials() != null ? request.credentials().get("__orgId__") : null);
        HttpEntity<AgentExecutionRequestDto> entity =
                new HttpEntity<>(request, buildHeaders(request.tenantId(), organizationId));
        try {
            ResponseEntity<AgentExecutionResponseDto> response = executionRestTemplate.exchange(
                url, HttpMethod.POST, entity, AgentExecutionResponseDto.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            // 4xx - typically BRIDGE_ACCESS_DENIED (403/429) from GlobalExceptionHandler.
            // Surface the typed reason as a FAILED response so the orchestrator's
            // user-facing message keeps the actionable text (e.g. "Bridge access
            // denied for claude-code: admin_only_requires_admin_role") instead of
            // collapsing to "Remote agent execution returned null".
            String reason = extractStructuredError(e);
            log.warn("Agent remote 4xx: status={} reason={}", e.getStatusCode().value(), reason);
            return failedExecutionResponse(request, reason);
        } catch (Exception e) {
            log.error("Failed to execute agent remotely: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Execute classification on agent-service (backward-compat overload).
     * Uses long-timeout RestTemplate since LLM calls can take time.
     * Relies on OrgContextHeaderForwarder for org propagation on sync HTTP threads.
     * Async/daemon callers MUST use the org-aware overload.
     *
     * @return classify response, or null on failure
     */
    public ClassifyResponseDto executeClassify(ClassifyRequestDto request) {
        return executeClassify(request, null);
    }

    /**
     * Execute classification with explicit organization scope.
     * Daemon callers (any {@code @Scheduled}/{@code @Async} thread where
     * {@code RequestContextHolder} is silent) MUST pass {@code organizationId}
     * explicitly. HTTP-path callers may pass null.
     *
     * @return classify response, or null on failure
     */
    public ClassifyResponseDto executeClassify(ClassifyRequestDto request, String organizationId) {
        String url = baseUrl + "/api/internal/agent/execute/classify";
        HttpEntity<ClassifyRequestDto> entity = new HttpEntity<>(request, buildHeaders(request.tenantId(), organizationId));
        try {
            ResponseEntity<ClassifyResponseDto> response = executionRestTemplate.exchange(
                url, HttpMethod.POST, entity, ClassifyResponseDto.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            String reason = extractStructuredError(e);
            log.warn("Classify remote 4xx: status={} reason={}", e.getStatusCode().value(), reason);
            return failedClassifyResponse(request, reason);
        } catch (Exception e) {
            log.error("Failed to execute classify remotely: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Execute guardrail validation on agent-service (backward-compat overload).
     * Uses long-timeout RestTemplate since LLM calls can take time.
     * Relies on OrgContextHeaderForwarder for org propagation on sync HTTP threads.
     * Async/daemon callers MUST use the org-aware overload.
     *
     * @return guardrail response, or null on failure
     */
    public GuardrailResponseDto executeGuardrail(GuardrailRequestDto request) {
        return executeGuardrail(request, null);
    }

    /**
     * Execute guardrail validation with explicit organization scope.
     * Daemon callers (any {@code @Scheduled}/{@code @Async} thread where
     * {@code RequestContextHolder} is silent) MUST pass {@code organizationId}
     * explicitly. HTTP-path callers may pass null.
     *
     * @return guardrail response, or null on failure
     */
    public GuardrailResponseDto executeGuardrail(GuardrailRequestDto request, String organizationId) {
        String url = baseUrl + "/api/internal/agent/execute/guardrail";
        HttpEntity<GuardrailRequestDto> entity = new HttpEntity<>(request, buildHeaders(request.tenantId(), organizationId));
        try {
            ResponseEntity<GuardrailResponseDto> response = executionRestTemplate.exchange(
                url, HttpMethod.POST, entity, GuardrailResponseDto.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            String reason = extractStructuredError(e);
            log.warn("Guardrail remote 4xx: status={} reason={}", e.getStatusCode().value(), reason);
            return failedGuardrailResponse(request, reason);
        } catch (Exception e) {
            log.error("Failed to execute guardrail remotely: {}", e.getMessage());
            return null;
        }
    }

    // ========== LLM Models ==========

    /**
     * Get available LLM models info from agent-service.
     * Used by conversation-service for the /api/v3/chat/models endpoint.
     */
    public Map<String, Object> getModelsInfo() {
        return getModelsInfo(null);
    }

    public Map<String, Object> getModelsInfo(String category, String tenantId) {
        return getModelsInfo(category, tenantId, null);
    }

    /**
     * Category-scoped variant. Pass one of {@code chat}, {@code browser_agent},
     * {@code image_generation} (or any other V156 category) to fetch the catalog
     * with the per-category sidecar applied - used by browser_agent help_models
     * so admins can re-rank or disable models for that role independently of
     * the main chat picker. Pass {@code null} for the legacy global view.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getModelsInfo(String category) {
        return getModelsInfo(category, null, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getModelsInfo(String category, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agent/models";
        if (category != null && !category.isBlank()) {
            url = url + "?category=" + java.net.URLEncoder.encode(
                    category, java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(buildHeaders(tenantId, organizationId)), Map.class);
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.error("Failed to get models info from agent-service (category={}): {}",
                    category, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Task Delegation (scheduled prompt + system prompt injection) ==========

    /**
     * Build the dynamic prompt to send to an agent when its schedule fires.
     * <p>
     * Agent-service reads the agent's task inbox + claimable backlog and returns either:
     * <ul>
     *   <li>a dynamic task-inbox prompt if there is pending work, or</li>
     *   <li>the supplied {@code fallback} (the legacy static schedule_prompt) if there are no tasks.</li>
     * </ul>
     * <p>
     * Safe for schedule execution: never throws - falls back to {@code fallback} on any error.
     */
    /** Back-compat overload - uses PR16 forwarder for org context (HTTP-request callers only). */
    public String buildScheduledPrompt(UUID agentId, String tenantId, String fallback) {
        return buildScheduledPrompt(agentId, tenantId, null, fallback);
    }

    /**
     * PR26 - explicit organizationId arg for DAEMON callers that have no
     * inbound HTTP request context (e.g. ScheduleExecutorService firing
     * from a cron tick). Without this overload, the PR16 forwarder couldn't
     * thread the org from RequestContextHolder (null on daemon threads),
     * and ScheduledTaskPromptBuilder would silently fall through to the
     * personal-strict path - bleeding org tasks into a personal-scope
     * prompt OR (the opposite) returning no work for an org-scheduled agent.
     */
    public String buildScheduledPrompt(UUID agentId, String tenantId, String organizationId, String fallback) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/scheduled-prompt";
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            if (fallback != null) body.put("fallback", fallback);
            HttpHeaders headers = buildHeaders(tenantId);
            // Explicit set wins over the PR16 forwarder (which would no-op anyway
            // on a daemon thread without RequestContextHolder).
            OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (response.getBody() != null && response.getBody().get("prompt") instanceof String s) {
                return s;
            }
            return fallback;
        } catch (Exception e) {
            log.warn("Failed to build scheduled prompt for agent {}: {} - using fallback", agentId, e.getMessage());
            return fallback;
        }
    }

    /**
     * Fetch a compact task summary ({@code pendingCount}, {@code completedOutboxCount}, {@code backlogCount},
     * {@code promptSection}) used to inject task context into an agent's system prompt.
     * Returns an empty map on failure - callers should skip injection when empty.
     */
    public Map<String, Object> getTaskSummaryForPrompt(UUID agentId, String tenantId) {
        return getTaskSummaryForPrompt(agentId, tenantId, null);
    }

    /**
     * Org-aware overload - Audit 2026-05-17 round-3. Daemon callers (any
     * {@code @Scheduled}/{@code @Async} thread where {@code RequestContextHolder}
     * is silent) MUST pass {@code organizationId} explicitly. HTTP path callers
     * may pass null - the PR16 forwarder copies the header off the inbound
     * request automatically.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTaskSummaryForPrompt(UUID agentId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/agents/" + agentId + "/task-summary";
        try {
            HttpHeaders headers = buildHeaders(tenantId);
            OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.debug("Failed to get task summary for agent {}: {}", agentId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Storage Usage ==========

    /**
     * Get storage usage for agents and skills categories.
     * Returns map with keys "AGENTS" and "SKILLS".
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAgentStorageUsage(String tenantId) {
        String url = baseUrl + "/api/internal/agents/storage/usage";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to get agent storage usage for tenant {}: {}", tenantId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Task CRUD (used by TaskNode in orchestrator-service) ==========

    /**
     * Create a task via agent-service internal API (backward-compat overload).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createTaskForWorkflow(String tenantId, Map<String, Object> request) {
        return createTaskForWorkflow(tenantId, request, null);
    }

    /**
     * Create a task in an explicit organization scope.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createTaskForWorkflow(String tenantId, Map<String, Object> request, String organizationId) {
        String url = baseUrl + "/api/internal/agents/tasks";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create task for tenant={}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    /**
     * Get a single task by ID via agent-service internal API.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTaskForWorkflow(String tenantId, UUID taskId) {
        String url = baseUrl + "/api/internal/agents/tasks/" + taskId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get task id={} for tenant={}: {}", taskId, tenantId, e.getMessage());
            return null;
        }
    }

    /**
     * Update a task via agent-service internal API (backward-compat overload).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateTaskForWorkflow(String tenantId, UUID taskId, Map<String, Object> request) {
        return updateTaskForWorkflow(tenantId, taskId, request, null);
    }

    /**
     * Update a task in an explicit organization scope.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateTaskForWorkflow(String tenantId, UUID taskId, Map<String, Object> request, String organizationId) {
        String url = baseUrl + "/api/internal/agents/tasks/" + taskId;
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update task id={} for tenant={}: {}", taskId, tenantId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete (cancel) a task via agent-service internal API.
     */
    public boolean deleteTaskForWorkflow(String tenantId, UUID taskId) {
        String url = baseUrl + "/api/internal/agents/tasks/" + taskId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete task id={} for tenant={}: {}", taskId, tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * List tasks with filters via agent-service internal API.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listTasksForWorkflow(String tenantId, Map<String, String> filters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/agents/tasks");
        filters.forEach(builder::queryParam);
        String url = builder.toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to list tasks for tenant={}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    // ========== Recent Activity (fan-out branch for /api/activities/recent) ==========

    /**
     * Fetch the top-N most recently-edited agents AND skills (single union
     * response) in the caller's active workspace, plus a peer-scope count
     * for the empty-state hint. Used by orchestrator's
     * {@code RecentActivityAggregatorService}.
     *
     * <p>Returns the union (agents + skills) in ONE RPC instead of two,
     * reducing the aggregator fan-out from 5 branches to 4.
     *
     * <p>Uses the dedicated {@link #recentActivityRestTemplate} (2s connect /
     * 3s read) and degrades to an empty result on any failure - the caller
     * additionally wraps with {@code CompletableFuture.orTimeout(3s)} for
     * partial-degradation across the fan-out.
     *
     * <p>{@code orgId} is passed EXPLICITLY (not via the ThreadLocal-driven
     * {@code forwardOrgContextHeaders}) because the aggregator runs on a
     * dedicated executor thread without inbound RequestContextHolder.
     */
    public RecentActivityScopeResultDto getRecentAgentResources(String tenantId, String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return new RecentActivityScopeResultDto(List.of(), 0);
        }
        String url = baseUrl + "/api/internal/agents/recent-activity";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-User-ID", tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, orgId);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<RecentActivityScopeResultDto> response = recentActivityRestTemplate.exchange(
                    url, HttpMethod.GET, entity, RecentActivityScopeResultDto.class);
            return response.getBody() != null
                    ? response.getBody()
                    : new RecentActivityScopeResultDto(List.of(), 0);
        } catch (Exception e) {
            log.warn("Failed to fetch recent activity (agents+skills) tenant={} org={}: {}",
                    tenantId, orgId, e.getMessage());
            return new RecentActivityScopeResultDto(List.of(), 0);
        }
    }

    // ========== Helpers ==========

    private HttpHeaders buildHeaders(String tenantId) {
        return buildHeaders(tenantId, null);
    }

    private HttpHeaders buildHeaders(String tenantId, String organizationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        if (organizationId != null && !organizationId.isBlank()) {
            headers.set("X-Organization-ID", organizationId.trim());
        }
        // PR16 round-2 - forward X-Organization-ID / X-Organization-Role from
        // the inbound request. Pre-PR16 only `getAgents(...)` set these via
        // explicit args; every OTHER agent-client method (executeAgent,
        // recordObservability, resolveAgentConfig, checkAndResetBudget,
        // cancelWorkflowsForConversation, cancelTasksForConversation, …)
        // silently dropped org context at the orchestrator → agent-service
        // hop. Auto-forwarding closes that gap for all request-bound calls.
        OrgContextHeaderForwarder.forward(headers);
        return headers;
    }

    private static String normalizeOrganizationId(Object organizationId) {
        if (organizationId == null) {
            return null;
        }
        String text = organizationId.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();

    /**
     * Reads the actionable text out of a 4xx response body produced by
     * agent-service's {@code GlobalExceptionHandler}. The handler returns:
     * {@code {"error":"BRIDGE_ACCESS_DENIED","reason":"<token>","provider":"<id>","message":"<sentence>"}}
     * - the user-facing sentence lives in {@code message}; {@code error} carries
     * only the discriminator code (e.g. {@code "BRIDGE_ACCESS_DENIED"}). Priority:
     * {@code message} → {@code reason} → {@code error} → status-derived fallback.
     */
    private static String extractStructuredError(HttpClientErrorException ex) {
        String body = ex.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                Map<String, Object> parsed = ERROR_BODY_MAPPER.readValue(body, new TypeReference<>() {});
                Object messageField = parsed.get("message");
                if (messageField instanceof String s && !s.isBlank()) {
                    return s;
                }
                Object reasonField = parsed.get("reason");
                if (reasonField instanceof String s && !s.isBlank()) {
                    return s;
                }
                Object errorField = parsed.get("error");
                if (errorField instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (Exception ignored) {
                // Non-JSON body - fall through to a status-derived fallback.
            }
        }
        return "Remote rejected with status " + ex.getStatusCode().value();
    }

    private static AgentExecutionResponseDto failedExecutionResponse(AgentExecutionRequestDto request, String error) {
        return new AgentExecutionResponseDto(
            false, null, null, List.of(), 0, Map.of(),
            error, 0L,
            request.provider(), request.model(),
            List.of(), "ERROR",
            Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null
        );
    }

    private static ClassifyResponseDto failedClassifyResponse(ClassifyRequestDto request, String error) {
        return new ClassifyResponseDto(
            false, null, 0, null,
            error, 0L,
            request.provider(), request.model(),
            0, 0, 0,
            null, null, null
        );
    }

    private static GuardrailResponseDto failedGuardrailResponse(GuardrailRequestDto request, String error) {
        return new GuardrailResponseDto(
            false, false, List.of(), Map.of(), null,
            error, 0L,
            request.provider(), request.model(),
            0, 0, 0,
            null, null, null
        );
    }

}
