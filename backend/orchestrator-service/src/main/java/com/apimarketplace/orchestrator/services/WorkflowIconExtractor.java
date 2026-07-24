package com.apimarketplace.orchestrator.services;

import java.util.*;

/**
 * Extracts NodeIcon-compatible prop objects from a workflow plan.
 * The output is stored in the node_icons JSONB column and passed directly
 * to the frontend NodeIcon component as spread props.
 */
public final class WorkflowIconExtractor {

    // Source of truth for the 8 trigger kinds. Must stay in sync with the twin
    // in publication-service (WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID) -
    // pinned by WorkflowIconExtractorParityTest. Frontend mirror lives at
    // dashboard.service.ts KIND_TO_NODE_ICON_KEY (inverse direction) and
    // nodeVisuals.ts NODE_ICON_REGISTRY (uses these nodeId keys).
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

    /**
     * Sentinel the catalog substitutes when an API has no icon of its own:
     * every WorkflowInspectorService query selects COALESCE(a.icon_slug, 'mcp').
     * It is non-blank, so it has to be rejected explicitly - otherwise the real
     * apiSlug fallback below never fires and the marketplace card renders the
     * generic API glyph instead of the brand logo.
     */
    private static final String UNRESOLVED_ICON_SLUG = "mcp";

    private static boolean isResolvedIconSlug(String slug) {
        return slug != null && !slug.isBlank()
            && !UNRESOLVED_ICON_SLUG.equalsIgnoreCase(slug.trim());
    }

    private WorkflowIconExtractor() {}

    /**
     * Extract NodeIcon-compatible prop maps from a workflow plan.
     *
     * @param plan the raw workflow plan map
     * @return list of prop maps suitable for the frontend NodeIcon component
     */
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

                // Prefer explicit iconSlug from plan, fallback to apiSlug extracted from id
                String explicitIconSlug = (String) mcp.get("iconSlug");
                String apiSlug = id.contains("/") ? id.substring(0, id.indexOf('/')) : id;
                String slug = isResolvedIconSlug(explicitIconSlug) ? explicitIconSlug : apiSlug;

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

                // Agents with avatars are unique per entity; others dedup by type+provider
                String dedupKey = (agentConfigId != null && !agentConfigId.isBlank())
                    ? "agent:" + agentConfigId
                    : "agent:" + agentType + ":" + provider;
                if (seen.contains(dedupKey)) continue;
                seen.add(dedupKey);

                String nodeId = AGENT_TYPE_TO_NODE_ID.getOrDefault(agentType, "ai-agent");
                Map<String, Object> iconProps = new HashMap<>();
                iconProps.put("nodeId", nodeId);

                if ("agent".equals(agentType) && avatarUrl != null && !avatarUrl.isBlank()) {
                    // Agent nodes use their avatar, not provider icon
                    iconProps.put("avatarUrl", avatarUrl);
                } else {
                    // Guardrail and Classify use provider icon
                    String iconSlug = provider != null ? AI_PROVIDER_SLUG.get(provider) : null;
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
                // Strip "crud-" prefix so nodeId matches frontend registry (e.g. "crud-create-row" → "create-row")
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
