package com.apimarketplace.publication.utils;

import java.util.*;

/**
 * Extracts NodeIcon-compatible prop objects from a workflow plan.
 * The output is stored in the node_icons JSONB column and passed directly
 * to the frontend NodeIcon component as spread props.
 */
public final class WorkflowIconExtractor {

    // Twin of orchestrator-service WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID.
    // Both maps MUST stay byte-identical (publication-service writes node_icons
    // on workflow clone/import paths; orchestrator-service writes them on save).
    // Drift is caught by WorkflowIconExtractorParityTest.
    public static final Map<String, String> TRIGGER_TYPE_TO_NODE_ID = Map.of(
        "manual", "manual-trigger",
        "webhook", "webhook-trigger",
        "schedule", "schedule-trigger",
        "datasource", "tables-trigger",
        "chat", "chat-trigger",
        "form", "form-trigger",
        "workflow", "workflows-trigger",
        "error", "error-trigger"
    );

    private static final Map<String, String> AI_PROVIDER_SLUG = Map.of(
        "openai", "openai",
        "anthropic", "anthropic",
        "google", "googlegemini",
        "mistral", "mistral",
        "deepseek", "deepseek"
    );

    private static final Map<String, String> AGENT_TYPE_TO_NODE_ID = Map.of(
        "agent", "ai-agent",
        "guardrail", "guardrail",
        "classify", "classify"
    );

    private WorkflowIconExtractor() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractNodeIcons(Map<String, Object> plan) {
        if (plan == null) return List.of();

        List<Map<String, Object>> icons = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Triggers
        Object triggersObj = plan.get("triggers");
        if (triggersObj instanceof List<?> triggers) {
            for (Object item : triggers) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> trigger = (Map<String, Object>) item;
                String type = (String) trigger.get("type");
                if (type == null) continue;
                String dedupKey = "trigger:" + type;
                if (seen.contains(dedupKey)) continue;
                seen.add(dedupKey);
                String nodeId = TRIGGER_TYPE_TO_NODE_ID.getOrDefault(type, type + "-trigger");
                icons.add(Map.of("nodeId", nodeId, "nodeKind", "entry"));
            }
        }

        // MCPs
        Object mcpsObj = plan.get("mcps");
        if (mcpsObj instanceof List<?> mcps) {
            for (Object item : mcps) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> mcp = (Map<String, Object>) item;
                String id = (String) mcp.get("id");
                if (id == null || id.isBlank()) continue;
                String explicitIconSlug = (String) mcp.get("iconSlug");
                String apiSlug = id.contains("/") ? id.substring(0, id.indexOf('/')) : id;
                String slug = (explicitIconSlug != null && !explicitIconSlug.isBlank())
                    ? explicitIconSlug : apiSlug;
                String dedupKey = "mcp:" + slug;
                if (seen.contains(dedupKey)) continue;
                seen.add(dedupKey);
                Map<String, Object> iconProps = new HashMap<>();
                iconProps.put("iconSlug", slug);
                iconProps.put("isMcp", true);
                icons.add(iconProps);
            }
        }

        // Cores
        Object coresObj = plan.get("cores");
        if (coresObj instanceof List<?> cores) {
            for (Object item : cores) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> core = (Map<String, Object>) item;
                String type = (String) core.get("type");
                if (type == null) continue;
                String dedupKey = "core:" + type;
                if (seen.contains(dedupKey)) continue;
                seen.add(dedupKey);
                icons.add(Map.of("nodeId", type, "nodeKind", type));
            }
        }

        // Agents
        Object agentsObj = plan.get("agents");
        if (agentsObj instanceof List<?> agents) {
            for (Object item : agents) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> agent = (Map<String, Object>) item;
                String provider = (String) agent.get("provider");
                String agentType = (String) agent.getOrDefault("type", "agent");
                String avatarUrl = (String) agent.get("agentAvatarUrl");
                String agentConfigId = (String) agent.get("agentConfigId");
                String dedupKey = (agentConfigId != null && !agentConfigId.isBlank())
                    ? "agent:" + agentConfigId
                    : "agent:" + agentType + ":" + provider;
                if (seen.contains(dedupKey)) continue;
                seen.add(dedupKey);
                String nodeId = AGENT_TYPE_TO_NODE_ID.getOrDefault(agentType, "ai-agent");
                Map<String, Object> iconProps = new HashMap<>();
                iconProps.put("nodeId", nodeId);
                if ("agent".equals(agentType) && avatarUrl != null && !avatarUrl.isBlank()) {
                    iconProps.put("avatarUrl", avatarUrl);
                } else if (provider != null) {
                    String iconSlug = AI_PROVIDER_SLUG.get(provider);
                    if (iconSlug != null) {
                        iconProps.put("iconSlug", iconSlug);
                    }
                }
                icons.add(iconProps);
            }
        }

        // Tables
        Object tablesObj = plan.get("tables");
        if (tablesObj instanceof List<?> tables) {
            for (Object item : tables) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> table = (Map<String, Object>) item;
                String type = (String) table.get("type");
                if (type == null) continue;
                String nodeId = type.startsWith("crud-") ? type.substring(5) : type;
                String dedupKey = "table:" + nodeId;
                if (seen.contains(dedupKey)) continue;
                seen.add(dedupKey);
                icons.add(Map.of("nodeId", nodeId, "crudOperation", nodeId));
            }
        }

        // Interfaces
        Object interfacesObj = plan.get("interfaces");
        if (interfacesObj instanceof List<?> interfaces && !interfaces.isEmpty()) {
            if (!seen.contains("interface")) {
                seen.add("interface");
                icons.add(Map.of("nodeId", "interface", "nodeKind", "interface"));
            }
        }

        return icons;
    }
}
