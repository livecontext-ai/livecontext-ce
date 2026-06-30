package com.apimarketplace.orchestrator.tools.common;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Extracts workflow runtime requirements for agent list payloads.
 */
public final class AgentResourceRequirements {

    public record RequiredIntegration(String name) {
        public RequiredIntegration {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("integration name is required");
            }
            name = name.toLowerCase(Locale.ROOT);
        }
    }

    public record RequiredSubWorkflow(UUID workflowId, String name) {
        public RequiredSubWorkflow {
            if (workflowId == null) {
                throw new IllegalArgumentException("workflowId is required");
            }
        }
    }

    private AgentResourceRequirements() {
    }

    public static List<RequiredIntegration> integrationsFromNodeIcons(List<Map<String, Object>> nodeIcons) {
        if (nodeIcons == null || nodeIcons.isEmpty()) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<RequiredIntegration> requirements = new ArrayList<>();
        for (Map<String, Object> entry : nodeIcons) {
            if (entry == null || !Boolean.TRUE.equals(entry.get("isMcp"))) {
                continue;
            }
            Object iconSlug = entry.get("iconSlug");
            if (!(iconSlug instanceof String slug) || slug.isBlank()) {
                continue;
            }
            String normalized = slug.toLowerCase(Locale.ROOT);
            if (seen.add(normalized)) {
                requirements.add(new RequiredIntegration(normalized));
            }
        }
        return requirements;
    }

    public static List<RequiredSubWorkflow> subWorkflowsFromPlan(WorkflowPlan plan) {
        if (plan == null || plan.getCores() == null || plan.getCores().isEmpty()) {
            return List.of();
        }

        List<RequiredSubWorkflow> requirements = new ArrayList<>();
        for (Core core : plan.getCores()) {
            if (!"sub_workflow".equalsIgnoreCase(core.type())) {
                continue;
            }
            Core.SubWorkflowConfig config = core.subWorkflowConfig();
            if (config == null || config.workflowId() == null || config.workflowId().isBlank()) {
                continue;
            }
            try {
                UUID workflowId = UUID.fromString(config.workflowId());
                String name = core.label() != null && !core.label().isBlank()
                        ? core.label()
                        : config.workflowId();
                requirements.add(new RequiredSubWorkflow(workflowId, name));
            } catch (IllegalArgumentException ignored) {
                // Invalid references cannot be resolved by the caller, so they are excluded.
            }
        }
        return requirements;
    }

    public static Map<String, Object> buildEnvelope(
            List<RequiredIntegration> integrations,
            List<RequiredSubWorkflow> subWorkflows,
            Set<String> configuredIntegrations,
            Set<UUID> existingSubWorkflowIds) {
        boolean hasIntegrations = integrations != null && !integrations.isEmpty();
        boolean hasSubWorkflows = subWorkflows != null && !subWorkflows.isEmpty();
        if (!hasIntegrations && !hasSubWorkflows) {
            return null;
        }

        Set<String> configured = normalizeIntegrationSet(configuredIntegrations);
        Set<UUID> existing = existingSubWorkflowIds != null ? existingSubWorkflowIds : Set.of();
        List<String> blockers = new ArrayList<>();
        Map<String, Object> envelope = new LinkedHashMap<>();

        if (hasIntegrations) {
            List<Map<String, Object>> items = new ArrayList<>(integrations.size());
            for (RequiredIntegration integration : integrations) {
                boolean isConfigured = configured.contains(integration.name());
                items.add(Map.of(
                        "name", integration.name(),
                        "configured", isConfigured
                ));
                if (!isConfigured) {
                    blockers.add("integration:" + integration.name() + " not configured");
                }
            }
            envelope.put("integrations", items);
        }

        if (hasSubWorkflows) {
            List<Map<String, Object>> items = new ArrayList<>(subWorkflows.size());
            for (RequiredSubWorkflow subWorkflow : subWorkflows) {
                boolean exists = existing.contains(subWorkflow.workflowId());
                items.add(Map.of(
                        "workflow_id", subWorkflow.workflowId().toString(),
                        "name", subWorkflow.name(),
                        "exists", exists
                ));
                if (!exists) {
                    blockers.add("sub_workflow:" + subWorkflow.name() + " not found in tenant");
                }
            }
            envelope.put("sub_workflows", items);
        }

        envelope.put("ready", blockers.isEmpty());
        if (!blockers.isEmpty()) {
            envelope.put("blockers", blockers);
        }
        return envelope;
    }

    private static Set<String> normalizeIntegrationSet(Set<String> integrations) {
        if (integrations == null || integrations.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String integration : integrations) {
            if (integration != null && !integration.isBlank()) {
                normalized.add(integration.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }
}
