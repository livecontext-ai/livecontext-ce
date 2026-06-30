package com.apimarketplace.orchestrator.tools.workflow.builder.session;

import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.*;

/**
 * Handles node lookup and finding operations in a workflow session.
 * Single Responsibility: Node search and resolution.
 *
 * Uses {@link LabelNormalizer} as the single source of truth for:
 * - Prefix detection (isTriggerKey, isMcpKey, isAgentKey, isCoreKey)
 * - Key construction (triggerKey, mcpKey, agentKey, coreKey)
 * - Label normalization
 */
public class SessionNodeFinder {

    private final List<Map<String, Object>> triggers;
    private final List<Map<String, Object>> mcps;
    private final List<Map<String, Object>> cores;
    private final List<Map<String, Object>> interfaces;
    private final List<Map<String, Object>> tables;
    private final List<Map<String, Object>> notes;
    private final Map<String, ?> nodeSchemas;

    public SessionNodeFinder(
            List<Map<String, Object>> triggers,
            List<Map<String, Object>> mcps,
            List<Map<String, Object>> cores,
            Map<String, ?> nodeSchemas) {
        this(triggers, mcps, cores, List.of(), List.of(), List.of(), nodeSchemas);
    }

    public SessionNodeFinder(
            List<Map<String, Object>> triggers,
            List<Map<String, Object>> mcps,
            List<Map<String, Object>> cores,
            List<Map<String, Object>> interfaces,
            List<Map<String, Object>> tables,
            List<Map<String, Object>> notes,
            Map<String, ?> nodeSchemas) {
        this.triggers = triggers;
        this.mcps = mcps;
        this.cores = cores;
        this.interfaces = interfaces != null ? interfaces : List.of();
        this.tables = tables != null ? tables : List.of();
        this.notes = notes != null ? notes : List.of();
        this.nodeSchemas = nodeSchemas;
    }

    /**
     * Find a node by its normalized ID.
     */
    public Optional<Map<String, Object>> findNode(String nodeId) {
        if (nodeId == null) return Optional.empty();

        if (LabelNormalizer.isTriggerKey(nodeId)) {
            return triggers.stream()
                    .filter(t -> nodeId.equals(computeTriggerNodeId(t)))
                    .findFirst();
        } else if (LabelNormalizer.isAgentKey(nodeId)) {
            return mcps.stream()
                    .filter(s -> Boolean.TRUE.equals(s.get("isAgent")))
                    .filter(s -> nodeId.equals(computeStepNodeId(s)))
                    .findFirst();
        } else if (LabelNormalizer.isMcpKey(nodeId)) {
            return mcps.stream()
                    .filter(s -> !Boolean.TRUE.equals(s.get("isAgent")))
                    .filter(s -> nodeId.equals(computeStepNodeId(s)))
                    .findFirst();
        } else if (LabelNormalizer.isCoreKey(nodeId)) {
            return cores.stream()
                    .filter(cn -> nodeId.equals(computeCoreNodeId(cn)))
                    .findFirst();
        } else if (LabelNormalizer.isInterfaceKey(nodeId)) {
            return interfaces.stream()
                    .filter(i -> nodeId.equals(computeInterfaceNodeId(i)))
                    .findFirst();
        } else if (LabelNormalizer.isTableKey(nodeId)) {
            return tables.stream()
                    .filter(t -> nodeId.equals(computeTableNodeId(t)))
                    .findFirst();
        } else if (LabelNormalizer.isNoteKey(nodeId)) {
            return notes.stream()
                    .filter(n -> nodeId.equals(computeNoteNodeId(n)))
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * Check if a node exists.
     * Also handles port-based references (e.g., agent:classify:category_0) by checking the base node.
     */
    public boolean nodeExists(String nodeId) {
        // Direct match
        if (findNode(nodeId).isPresent() || nodeSchemas.containsKey(nodeId)) {
            return true;
        }

        // Check for port-based references - extract base node ID
        String baseNodeId = extractBaseNodeId(nodeId);
        if (baseNodeId != null && !baseNodeId.equals(nodeId)) {
            return findNode(baseNodeId).isPresent() || nodeSchemas.containsKey(baseNodeId);
        }

        return false;
    }

    /**
     * Extract base node ID from a port-based reference.
     * Examples: "agent:classify:category_0" -> "agent:classify"
     */
    private String extractBaseNodeId(String nodeRef) {
        if (nodeRef == null) return null;
        // EdgeRefParser is the single source of truth for the port set.
        return EdgeRefParser.splitPort(nodeRef)[0];
    }

    /**
     * Get all node IDs in the session.
     */
    public List<String> getAllNodeIds() {
        List<String> ids = new ArrayList<>();

        for (Map<String, Object> t : triggers) {
            String nodeId = computeTriggerNodeId(t);
            if (nodeId != null) {
                ids.add(nodeId);
            }
        }

        for (Map<String, Object> s : mcps) {
            String nodeId = computeStepNodeId(s);
            if (nodeId != null) {
                ids.add(nodeId);
            }
        }

        for (Map<String, Object> cn : cores) {
            String nodeId = computeCoreNodeId(cn);
            if (nodeId != null) {
                ids.add(nodeId);
            }
        }

        for (Map<String, Object> i : interfaces) {
            String nodeId = computeInterfaceNodeId(i);
            if (nodeId != null) {
                ids.add(nodeId);
            }
        }

        for (Map<String, Object> t : tables) {
            String nodeId = computeTableNodeId(t);
            if (nodeId != null) {
                ids.add(nodeId);
            }
        }

        for (Map<String, Object> n : notes) {
            String nodeId = computeNoteNodeId(n);
            if (nodeId != null) {
                ids.add(nodeId);
            }
        }

        return ids;
    }

    /**
     * Get all labels in the workflow.
     */
    public List<String> getAllLabels() {
        List<String> labels = new ArrayList<>();

        for (Map<String, Object> t : triggers) {
            String label = (String) t.get("label");
            if (label != null) labels.add(label);
        }
        for (Map<String, Object> s : mcps) {
            String label = (String) s.get("label");
            if (label != null) labels.add(label);
        }
        for (Map<String, Object> cn : cores) {
            String label = (String) cn.get("label");
            if (label != null) labels.add(label);
        }
        for (Map<String, Object> i : interfaces) {
            String label = (String) i.get("label");
            if (label != null) labels.add(label);
        }
        for (Map<String, Object> t : tables) {
            String label = (String) t.get("label");
            if (label != null) labels.add(label);
        }
        for (Map<String, Object> n : notes) {
            String label = (String) n.get("label");
            if (label != null) labels.add(label);
        }

        return labels;
    }

    /**
     * Find a node by its normalized label.
     * Searches across all node types: trigger, mcp, agent, core, interface, table, note.
     */
    public String findNodeByNormalizedLabel(String normalizedLabel) {
        if (normalizedLabel == null) return null;

        // Search in order: trigger, mcp, agent, core, interface, table, note
        for (String prefix : List.of(
                LabelNormalizer.PREFIX_TRIGGER,
                LabelNormalizer.PREFIX_MCP,
                LabelNormalizer.PREFIX_AGENT,
                LabelNormalizer.PREFIX_CORE,
                LabelNormalizer.PREFIX_INTERFACE,
                LabelNormalizer.PREFIX_TABLE,
                LabelNormalizer.PREFIX_NOTE)) {
            String candidateId = prefix + ":" + normalizedLabel;
            if (nodeExists(candidateId)) {
                return candidateId;
            }
        }
        return null;
    }

    /**
     * Find a node by its label (will normalize it first).
     */
    public String findNodeByLabel(String label) {
        if (label == null) return null;
        return findNodeByNormalizedLabel(normalizeLabel(label));
    }

    /**
     * Resolve a node reference from a label.
     * Handles port suffixes like :category_N, :elseif_N, :case_N, :branch_N, :if, :else, etc.
     *
     * Examples:
     * - "My Step" -> "mcp:my_step"
     * - "Classify:category_0" -> "agent:classify:category_0"
     * - "agent:classify:category_0" -> "agent:classify:category_0" (already resolved)
     */
    public String resolveNodeReference(String reference) {
        if (reference == null) return null;

        // Check if reference already exists as-is (full node ID)
        if (reference.contains(":") && nodeExists(reference)) {
            return reference;
        }

        // Split off the port via EdgeRefParser (single source of truth for the
        // port set) so node-ref resolution recognises EXACTLY the ports the
        // workflow builder's validation, set_plan import and get_plan export do.
        String[] split = EdgeRefParser.splitPort(reference);
        if (split[1] != null) {
            return resolveBaseReference(split[0]) + ":" + split[1];
        }
        return resolveBaseReference(reference);
    }

    /**
     * Resolve a base reference (without port suffix) to a node ID.
     */
    private String resolveBaseReference(String reference) {
        if (reference == null) return null;

        // If it's already a full node ID, return it
        if (reference.contains(":") && nodeExists(reference)) {
            return reference;
        }

        // Try to find by label
        String nodeId = findNodeByLabel(reference);
        if (nodeId != null) {
            return nodeId;
        }

        // If it has a prefix:label format, try normalizing with same prefix first
        if (reference.contains(":")) {
            int colonIndex = reference.indexOf(':');
            String prefix = reference.substring(0, colonIndex);
            String labelPart = reference.substring(colonIndex + 1);
            String normalizedLabel = normalizeLabel(labelPart);

            if (normalizedLabel != null && !normalizedLabel.isEmpty()) {
                String normalizedRef = prefix + ":" + normalizedLabel;
                if (nodeExists(normalizedRef)) {
                    return normalizedRef;
                }

                // Cross-prefix search: the reference may have a wrong prefix (e.g. mcp: instead of core:)
                for (String altPrefix : List.of("core", "mcp", "table", "agent", "trigger", "interface")) {
                    if (altPrefix.equals(prefix)) continue; // Already tried
                    String candidate = altPrefix + ":" + normalizedLabel;
                    if (nodeExists(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // Return as-is if nothing found
        return reference;
    }

    // ==================== Node ID Computation ====================

    /**
     * Computes the node ID for a trigger node.
     */
    private String computeTriggerNodeId(Map<String, Object> node) {
        String label = getNodeLabel(node);
        return LabelNormalizer.triggerKey(label);
    }

    /**
     * Computes the node ID for a step node (mcp or agent).
     */
    private String computeStepNodeId(Map<String, Object> node) {
        String label = getNodeLabel(node);
        boolean isAgent = Boolean.TRUE.equals(node.get("isAgent"));
        return LabelNormalizer.computeStepNodeId(label, isAgent);
    }

    /**
     * Computes the node ID for a core node (decision, loop, split, switch, merge, fork, etc.).
     * All core nodes use the "core:" prefix regardless of their specific type.
     */
    private String computeCoreNodeId(Map<String, Object> node) {
        String label = getNodeLabel(node);
        return LabelNormalizer.coreKey(label);
    }

    /**
     * Computes the node ID for an interface node.
     * Interfaces use "interface:" prefix with their label or name.
     */
    private String computeInterfaceNodeId(Map<String, Object> node) {
        String label = (String) node.get("label");
        if (label == null) {
            label = (String) node.get("name");
        }
        if (label == null) {
            label = (String) node.get("id");
        }
        return LabelNormalizer.interfaceKey(label);
    }

    /**
     * Computes the node ID for a table node.
     */
    private String computeTableNodeId(Map<String, Object> node) {
        String label = getNodeLabel(node);
        return LabelNormalizer.tableKey(label);
    }

    /**
     * Computes the node ID for a note node.
     */
    private String computeNoteNodeId(Map<String, Object> node) {
        String label = getNodeLabel(node);
        return LabelNormalizer.noteKey(label);
    }

    /**
     * Gets the label from a node, falling back to id if label is null.
     */
    private String getNodeLabel(Map<String, Object> node) {
        String label = (String) node.get("label");
        if (label == null) {
            label = (String) node.get("id");
        }
        return label;
    }

    // ==================== Utility Methods ====================

    /**
     * Normalize a label to a valid identifier.
     * Delegates to {@link LabelNormalizer#normalizeLabel(String)}.
     */
    public static String normalizeLabel(String label) {
        String normalized = LabelNormalizer.normalizeLabel(label);
        return normalized != null ? normalized : "";
    }

    /**
     * Get a list of similar labels for "did you mean" suggestions.
     */
    public List<String> getSimilarLabels(String label) {
        if (label == null) return List.of();

        String normalizedInput = normalizeLabel(label).toLowerCase();
        List<String> allLabels = getAllLabels();

        return allLabels.stream()
            .filter(l -> {
                String normalized = normalizeLabel(l).toLowerCase();
                return normalized.contains(normalizedInput) ||
                       normalizedInput.contains(normalized) ||
                       levenshteinDistance(normalized, normalizedInput) <= 3;
            })
            .limit(3)
            .toList();
    }

    /**
     * Simple Levenshtein distance for "did you mean" suggestions.
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }
}
