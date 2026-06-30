package com.apimarketplace.orchestrator.utils;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized label normalization utility.
 *
 * This class provides a single source of truth for normalizing labels across the entire application.
 * It ensures consistent normalization for all node types.
 *
 * === PREFIX SYSTEM (7 categories) ===
 *
 * | Prefix      | Category  | Applies To                                              |
 * |-------------|-----------|--------------------------------------------------------|
 * | trigger:    | Entry     | All triggers (webhook, chat, schedule, etc.)            |
 * | mcp:        | MCP       | Tools (MCP tool calls)                                  |
 * | table:      | Table     | CRUD operations (database tables)                       |
 * | agent:      | AI        | Agent, Guardrail, Classify                              |
 * | core:       | Core      | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, User Approval |
 * | note:       | Note      | Notes                                                   |
 * | interface:  | Interface | Interfaces                                              |
 *
 * Normalization rules:
 * 1. Transliterate accented characters to ASCII (e→e, a→a, u→u, c→c, etc.)
 * 2. Convert to lowercase
 * 3. Replace ALL non-alphanumeric characters with underscores
 * 4. Collapse multiple consecutive underscores into one
 * 5. Remove leading/trailing underscores
 *
 * Examples:
 * - "My Label" -> "my_label"
 * - "If / else" -> "if_else"
 * - "While Loop" -> "while_loop"
 * - "Step-123" -> "step_123"
 * - "Entree IDs" -> "entree_ids"
 */
public final class LabelNormalizer {

    private LabelNormalizer() {
        // Utility class, prevent instantiation
    }

    /**
     * Normalizes a label to a slug format compatible with frontend and backend.
     *
     * This is the canonical normalization method used throughout the application.
     * All labels should use this method.
     *
     * @param label The label to normalize (can be null or blank)
     * @return The normalized label, or null if input is null or blank
     */
    public static String normalizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }

        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // Transliterate accented characters to ASCII
        String ascii = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");

        // Convert to lowercase
        String lower = ascii.toLowerCase(Locale.ROOT);

        // Replace all non-alphanumeric characters with underscores
        String underscored = lower.replaceAll("[^a-z0-9]", "_");

        // Collapse multiple underscores into one
        String collapsed = underscored.replaceAll("_+", "_");

        // Remove leading/trailing underscores
        String normalized = collapsed.replaceAll("^_|_$", "");

        return normalized.isEmpty() ? null : normalized;
    }

    // ========================================================================
    // KEY CONSTRUCTION METHODS - 7 categories
    // ========================================================================

    /**
     * Creates a normalized trigger key from a label.
     * Used for ALL trigger types (webhook, chat, schedule, form, datasource, manual, workflow).
     *
     * Example: "My Webhook" -> "trigger:my_webhook"
     */
    public static String triggerKey(String label) {
        String normalized = normalizeLabel(label);
        return normalized != null ? "trigger:" + normalized : null;
    }

    /**
     * Creates a normalized trigger key with fallback.
     */
    public static String triggerKey(String label, String fallbackId) {
        String normalized = normalizeLabel(label);
        if (normalized != null) {
            return "trigger:" + normalized;
        }
        String normalizedId = normalizeLabel(fallbackId);
        return normalizedId != null ? "trigger:" + normalizedId : null;
    }

    /**
     * Creates a normalized MCP key from a label.
     * Used for MCP tool calls.
     *
     * Example: "API Call" -> "mcp:api_call"
     */
    public static String mcpKey(String label) {
        String normalized = normalizeLabel(label);
        return normalized != null ? "mcp:" + normalized : null;
    }

    /**
     * Creates a normalized MCP key with fallback.
     */
    public static String mcpKey(String label, String fallbackId) {
        String normalized = normalizeLabel(label);
        if (normalized != null) {
            return "mcp:" + normalized;
        }
        String normalizedId = normalizeLabel(fallbackId);
        return normalizedId != null ? "mcp:" + normalizedId : null;
    }

    /**
     * Creates a normalized table key from a label.
     * Used for CRUD operations (database tables).
     *
     * Example: "Users Table" -> "table:users_table"
     */
    public static String tableKey(String label) {
        String normalized = normalizeLabel(label);
        return normalized != null ? "table:" + normalized : null;
    }

    /**
     * Creates a normalized table key with fallback.
     */
    public static String tableKey(String label, String fallbackId) {
        String normalized = normalizeLabel(label);
        if (normalized != null) {
            return "table:" + normalized;
        }
        String normalizedId = normalizeLabel(fallbackId);
        return normalizedId != null ? "table:" + normalizedId : null;
    }

    /**
     * Creates a normalized agent key from a label.
     * Used for ALL AI reasoning nodes (agent, guardrail, classify).
     *
     * Example: "My Analyzer" -> "agent:my_analyzer"
     */
    public static String agentKey(String label) {
        String normalized = normalizeLabel(label);
        return normalized != null ? "agent:" + normalized : null;
    }

    /**
     * Creates a normalized agent key with fallback.
     */
    public static String agentKey(String label, String fallbackId) {
        String normalized = normalizeLabel(label);
        if (normalized != null) {
            return "agent:" + normalized;
        }
        String normalizedId = normalizeLabel(fallbackId);
        return normalizedId != null ? "agent:" + normalizedId : null;
    }

    /**
     * Creates a normalized core key from a label.
     * Used for ALL core flow nodes: Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, User Approval
     *
     * Example: "Check Status" -> "core:check_status"
     */
    public static String coreKey(String label) {
        String normalized = normalizeLabel(label);
        return normalized != null ? "core:" + normalized : null;
    }

    /**
     * Creates a normalized core key with fallback.
     */
    public static String coreKey(String label, String fallbackId) {
        String normalized = normalizeLabel(label);
        if (normalized != null) {
            return "core:" + normalized;
        }
        String normalizedId = normalizeLabel(fallbackId);
        return normalizedId != null ? "core:" + normalizedId : null;
    }

    /**
     * Creates a normalized note key from a label.
     * Used for notes.
     *
     * Example: "My Note" -> "note:my_note"
     */
    public static String noteKey(String label) {
        String normalized = normalizeLabel(label);
        return normalized != null ? "note:" + normalized : null;
    }

    /**
     * Creates a normalized note key with fallback.
     */
    public static String noteKey(String label, String fallbackId) {
        String normalized = normalizeLabel(label);
        if (normalized != null) {
            return "note:" + normalized;
        }
        String normalizedId = normalizeLabel(fallbackId);
        return normalizedId != null ? "note:" + normalizedId : null;
    }

    /**
     * Creates a normalized interface key from a label.
     * Used for interfaces.
     *
     * Example: "User Form" -> "interface:user_form"
     */
    public static String interfaceKey(String label) {
        String normalized = normalizeLabel(label);
        return normalized != null ? "interface:" + normalized : null;
    }

    /**
     * Creates a normalized interface key with fallback.
     */
    public static String interfaceKey(String label, String fallbackId) {
        String normalized = normalizeLabel(label);
        if (normalized != null) {
            return "interface:" + normalized;
        }
        String normalizedId = normalizeLabel(fallbackId);
        return normalizedId != null ? "interface:" + normalizedId : null;
    }

    // ========================================================================
    // KEY VALIDATION METHODS
    // ========================================================================

    /**
     * Checks if a string is a normalized key (has a known prefix).
     *
     * Valid prefixes: trigger:, mcp:, table:, agent:, core:, note:, interface:
     */
    public static boolean isNormalizedKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return key.startsWith("trigger:") || key.startsWith("mcp:") ||
               key.startsWith("table:") || key.startsWith("agent:") ||
               key.startsWith("core:") || key.startsWith("note:") ||
               key.startsWith("interface:");
    }

    /**
     * Checks if a key is a trigger key.
     */
    public static boolean isTriggerKey(String key) {
        return key != null && key.startsWith("trigger:");
    }

    /**
     * Checks if a key is an MCP key.
     */
    public static boolean isMcpKey(String key) {
        return key != null && key.startsWith("mcp:");
    }

    /**
     * Checks if a key is a table key.
     */
    public static boolean isTableKey(String key) {
        return key != null && key.startsWith("table:");
    }

    /**
     * Checks if a key is an agent key.
     */
    public static boolean isAgentKey(String key) {
        return key != null && key.startsWith("agent:");
    }

    /**
     * Checks if a key is a core key.
     */
    public static boolean isCoreKey(String key) {
        return key != null && key.startsWith("core:");
    }

    /**
     * Checks if a key is a note key.
     */
    public static boolean isNoteKey(String key) {
        return key != null && key.startsWith("note:");
    }

    /**
     * Checks if a key is an interface key.
     */
    public static boolean isInterfaceKey(String key) {
        return key != null && key.startsWith("interface:");
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    /**
     * Extracts the node type from a normalized key.
     * Example: "mcp:api_call" -> "mcp"
     */
    public static String getNodeType(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        int colonIndex = key.indexOf(':');
        if (colonIndex > 0) {
            return key.substring(0, colonIndex);
        }
        return null;
    }

    /**
     * Extracts the normalized label from a key (without the prefix).
     * Example: "mcp:api_call" -> "api_call"
     */
    public static String extractLabelFromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        int colonIndex = key.indexOf(':');
        if (colonIndex >= 0 && colonIndex < key.length() - 1) {
            return key.substring(colonIndex + 1);
        }
        // No colon found - return the key itself as the label
        return key;
    }

    /**
     * Extracts the label part from a prefixed node ID (without normalization).
     */
    public static String extractLabel(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return nodeId;
        }
        int colonIndex = nodeId.indexOf(':');
        if (colonIndex >= 0 && colonIndex < nodeId.length() - 1) {
            return nodeId.substring(colonIndex + 1);
        }
        return nodeId;
    }

    /**
     * Extracts and normalizes a label from a prefixed reference.
     */
    public static String extractAndNormalizeLabel(String prefixedRef, String prefix) {
        if (prefixedRef == null || prefixedRef.isBlank()) {
            return null;
        }
        String trimmed = prefixedRef.trim();
        if (trimmed.startsWith(prefix)) {
            String label = trimmed.substring(prefix.length()).trim();
            return normalizeLabel(label);
        }
        return normalizeLabel(trimmed);
    }

    /**
     * Extracts and normalizes a label from a core node reference.
     *
     * Example: "core:my_loop" -> "my_loop"
     */
    public static String extractCoreLabel(String nodeRef) {
        return extractAndNormalizeLabel(nodeRef, "core:");
    }

    /**
     * Extracts and normalizes a trigger label.
     */
    public static String extractTriggerLabel(String triggerRef) {
        return extractAndNormalizeLabel(triggerRef, "trigger:");
    }

    // ========================================================================
    // NODE ID COMPUTATION METHODS
    // ========================================================================

    /**
     * Computes a node ID from a node's properties (label, isAgent flag).
     * This is the canonical way to compute MCP/Agent node IDs.
     *
     * @param label The node label
     * @param isAgent Whether the node is an agent
     * @return The computed node ID (e.g., "mcp:api_call" or "agent:analyzer")
     */
    public static String computeStepNodeId(String label, boolean isAgent) {
        return isAgent ? agentKey(label) : mcpKey(label);
    }

    /**
     * Computes a node ID from a node's properties (label, isAgent flag) with fallback.
     */
    public static String computeStepNodeId(String label, String fallbackId, boolean isAgent) {
        return isAgent ? agentKey(label, fallbackId) : mcpKey(label, fallbackId);
    }

    /**
     * Computes a node ID with a specific prefix.
     *
     * @param label The node label
     * @param prefix The prefix (trigger, mcp, agent, core, table, interface, note)
     * @return The computed node ID
     */
    public static String computeNodeId(String label, String prefix) {
        String normalized = normalizeLabel(label);
        if (normalized == null) {
            return null;
        }
        return prefix + ":" + normalized;
    }

    /**
     * Computes a node ID with a specific prefix and fallback.
     */
    public static String computeNodeId(String label, String fallbackId, String prefix) {
        String normalized = normalizeLabel(label);
        if (normalized != null) {
            return prefix + ":" + normalized;
        }
        String normalizedId = normalizeLabel(fallbackId);
        return normalizedId != null ? prefix + ":" + normalizedId : null;
    }

    /**
     * Gets the appropriate prefix for a step node based on isAgent flag.
     *
     * @param isAgent Whether the node is an agent
     * @return "agent" if isAgent is true, "mcp" otherwise
     */
    public static String getStepPrefix(boolean isAgent) {
        return isAgent ? "agent" : "mcp";
    }

    // ========================================================================
    // NODE TYPE DETECTION METHODS
    // ========================================================================

    /**
     * Checks if a key is a step key (mcp or agent).
     */
    public static boolean isStepKey(String key) {
        return isMcpKey(key) || isAgentKey(key);
    }

    /**
     * Checks if a key is an executable node (trigger, mcp, agent, or core).
     */
    public static boolean isExecutableKey(String key) {
        return isTriggerKey(key) || isMcpKey(key) || isAgentKey(key) || isCoreKey(key);
    }

    /**
     * Checks if a key represents a workflow node (not note or interface).
     */
    public static boolean isWorkflowNodeKey(String key) {
        return isTriggerKey(key) || isMcpKey(key) || isAgentKey(key) ||
               isCoreKey(key) || isTableKey(key);
    }

    // ========================================================================
    // VARIABLE REFERENCE NORMALIZATION
    // ========================================================================

    /**
     * Pattern matching variable references: {{prefix:label.field...}}
     * Captures: prefix (trigger|mcp|agent|core|table|interface), label (up to first dot), rest
     *
     * Examples:
     *   {{mcp:Fetch Profile.output.data}} → {{mcp:fetch_profile.output.data}}
     *   {{trigger:Input Form.output.email}} → {{trigger:input_form.output.email}}
     *   {{agent:My Agent.output.response}} → {{agent:my_agent.output.response}}
     */
    private static final Pattern VARIABLE_REF_PATTERN = Pattern.compile(
        "\\{\\{(trigger|mcp|agent|core|table|interface):([^.}]+)(\\.([^}]*))?}}"
    );

    /**
     * Normalizes variable references within a string.
     * Converts {{prefix:Raw Label.field}} to {{prefix:normalized_label.field}}.
     *
     * This is critical because the LLM may write variable references using
     * human-readable labels (e.g., {{mcp:Fetch Profile.output.data}}) instead
     * of normalized keys (e.g., {{mcp:fetch_profile.output.data}}).
     * SpEL evaluation fails when labels contain spaces.
     *
     * @param value The string containing variable references
     * @return The string with all variable references normalized, or the original if no refs found
     */
    public static String normalizeVariableReferences(String value) {
        if (value == null || !value.contains("{{")) return value;

        Matcher matcher = VARIABLE_REF_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String prefix = matcher.group(1);
            String rawLabel = matcher.group(2);
            String dotAndRest = matcher.group(3); // includes the dot, e.g. ".output.data"

            String normalized = normalizeLabel(rawLabel.trim());
            if (normalized == null) normalized = rawLabel.trim();

            String replacement = "{{" + prefix + ":" + normalized;
            if (dotAndRest != null) {
                replacement += dotAndRest;
            }
            replacement += "}}";

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Deep-normalizes all variable references in a Map structure (recursively).
     * Handles nested Maps, Lists, and String values.
     *
     * Use this to normalize an entire node map at once, e.g., when loading a plan from DB
     * or when applying modifications.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeVariableReferencesDeep(Map<String, Object> map) {
        if (map == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(entry.getKey(), normalizeValueDeep(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object normalizeValueDeep(Object value) {
        if (value instanceof String s) {
            return normalizeVariableReferences(s);
        } else if (value instanceof Map<?, ?> m) {
            Map<Object, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                normalized.put(e.getKey(), normalizeValueDeep(e.getValue()));
            }
            return normalized;
        } else if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizeValueDeep(item));
            }
            return normalized;
        }
        return value;
    }

    // ========================================================================
    // PREFIX CONSTANTS
    // ========================================================================

    public static final String PREFIX_TRIGGER = "trigger";
    public static final String PREFIX_MCP = "mcp";
    public static final String PREFIX_AGENT = "agent";
    public static final String PREFIX_CORE = "core";
    public static final String PREFIX_TABLE = "table";
    public static final String PREFIX_NOTE = "note";
    public static final String PREFIX_INTERFACE = "interface";
}
