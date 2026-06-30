package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves an agentConfigId into a complete Agent record by loading
 * the agent config from agent-service via AgentClient and merging
 * entity config with workflow-level overrides (prompt).
 *
 * <p>When agentConfigId is null, the plan agent is returned unchanged
 * (legacy inline config mode).
 */
@Service
public class AgentConfigResolver {

    private static final Logger logger = LoggerFactory.getLogger(AgentConfigResolver.class);

    private final AgentClient agentClient;

    public AgentConfigResolver(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    /**
     * Aggregate result of {@link #resolve(Agent, String)}: the merged workflow
     * {@link Agent} record plus the {@link AgentRuntimeOverrides} sidecar carrying
     * the three runtime fields (executionTimeout, loopIdenticalStop, loopConsecutiveStop)
     * that don't fit on the {@code Agent} record. Both come from the SAME
     * {@code agentClient.resolveAgentConfig(...)} call so callers get all the
     * persisted-entity values without paying multiple HTTP round-trips.
     */
    public record ResolveResult(Agent agent, AgentRuntimeOverrides overrides) {}

    /**
     * Resolve agent entity config into a workflow Agent record.
     * Merges entity config with workflow-level overrides (prompt).
     *
     * @param planAgent the Agent from the workflow plan
     * @param tenantId  the tenant ID for security verification
     * @return resolved Agent + runtime overrides; both populated from the same
     *         agent-service fetch. When {@code agentConfigId} is null or the
     *         entity cannot be loaded, returns the plan agent unchanged with
     *         {@link AgentRuntimeOverrides#EMPTY} (loop falls back to platform defaults).
     */
    public ResolveResult resolve(Agent planAgent, String tenantId) {
        return resolve(planAgent, tenantId, null);
    }

    /**
     * Resolve agent entity config into a workflow Agent record in an explicit workspace scope.
     */
    public ResolveResult resolve(Agent planAgent, String tenantId, String organizationId) {
        if (planAgent.agentConfigId() == null) {
            return new ResolveResult(planAgent, AgentRuntimeOverrides.EMPTY);
        }

        UUID entityId;
        try {
            entityId = UUID.fromString(planAgent.agentConfigId());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid agentConfigId '{}', falling back to inline config", planAgent.agentConfigId());
            return new ResolveResult(planAgent, AgentRuntimeOverrides.EMPTY);
        }

        // Fetch agent config from agent-service (tenant/org verification done server-side)
        AgentDto dto = agentClient.resolveAgentConfig(entityId, tenantId, organizationId);
        if (dto == null) {
            logger.warn("Agent entity not found: {}, falling back to inline config", entityId);
            return new ResolveResult(planAgent, AgentRuntimeOverrides.EMPTY);
        }

        logger.info("Resolved agent entity '{}' ({}) for workflow agent '{}'",
            dto.getName(), entityId, planAgent.label());

        // Merge: entity provides config, planAgent provides workflow overrides
        Agent merged = new Agent(
            planAgent.id(),
            planAgent.type(),
            planAgent.label(),
            planAgent.agentConfigId(),
            planAgent.withMemory(),
            dto.getModelProvider(),                              // From entity
            dto.getModelName(),                                  // From entity
            dto.getSystemPrompt(),                               // From entity
            planAgent.prompt(),                                  // From workflow (override)
            dto.getTemperature() != null ? dto.getTemperature().doubleValue() : null,
            dto.getMaxTokens(),                                  // From entity
            dto.getMaxIterations(),                              // From entity
            null, // maxTools - entity uses toolsConfig instead
            resolveToolsFromDto(dto),                            // From entity toolsConfig
            planAgent.parentLoopId(),
            planAgent.params(),
            planAgent.classifyCategories(),
            planAgent.classifyParams(),
            planAgent.guardrailRules(),
            planAgent.guardrailParams(),
            null // graphNodeId - not available in resolved config
        );

        AgentRuntimeOverrides overrides = new AgentRuntimeOverrides(
            dto.getExecutionTimeout(),
            dto.getLoopIdenticalStop(),
            dto.getLoopConsecutiveStop(),
            dto.getReasoningEffort(),
            dto.getInactivityTimeout());

        return new ResolveResult(merged, overrides);
    }

    /**
     * Get the raw toolsConfig JSONB map for a given agentConfigId.
     * Used by AgentNode to determine which resource modules to enable.
     *
     * @param agentConfigId the agent entity UUID string
     * @param tenantId      the tenant ID for security verification
     * @return the toolsConfig map, or null if not found/not applicable
     */
    public Map<String, Object> getToolsConfig(String agentConfigId, String tenantId) {
        return getToolsConfig(agentConfigId, tenantId, null);
    }

    public Map<String, Object> getToolsConfig(String agentConfigId, String tenantId, String organizationId) {
        if (agentConfigId == null) return null;

        UUID entityId;
        try {
            entityId = UUID.fromString(agentConfigId);
        } catch (IllegalArgumentException e) {
            return null;
        }

        AgentDto dto = agentClient.resolveAgentConfig(entityId, tenantId, organizationId);
        if (dto == null) return null;

        return dto.getToolsConfig();
    }

    /**
     * Get a summary of the agent entity for snapshot enrichment.
     * Returns name, description, and avatarUrl from the AgentEntity.
     *
     * @param agentConfigId the agent entity UUID string
     * @param tenantId      the tenant ID for security verification
     * @return summary map with name, description, avatarUrl; or null if not found
     */
    public Map<String, Object> getAgentEntitySummary(String agentConfigId, String tenantId) {
        return getAgentEntitySummary(agentConfigId, tenantId, null);
    }

    public Map<String, Object> getAgentEntitySummary(String agentConfigId, String tenantId, String organizationId) {
        if (agentConfigId == null) return null;

        UUID entityId;
        try {
            entityId = UUID.fromString(agentConfigId);
        } catch (IllegalArgumentException e) {
            return null;
        }

        AgentDto dto = agentClient.resolveAgentConfig(entityId, tenantId, organizationId);
        if (dto == null) return null;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", dto.getName());
        summary.put("description", dto.getDescription());
        summary.put("avatarUrl", dto.getAvatarUrl());
        return summary;
    }

    /**
     * Extract tool references from DTO's toolsConfig field.
     * Returns empty list if no tools configured (agent will auto-discover).
     */
    private List<String> resolveToolsFromDto(AgentDto dto) {
        Map<String, Object> toolsConfig = dto.getToolsConfig();
        if (toolsConfig == null) return List.of();
        Object tools = toolsConfig.get("tools");
        if (tools instanceof List<?> toolsList) {
            return toolsList.stream()
                .filter(item -> item instanceof String)
                .map(item -> (String) item)
                .toList();
        }
        return List.of();
    }
}
