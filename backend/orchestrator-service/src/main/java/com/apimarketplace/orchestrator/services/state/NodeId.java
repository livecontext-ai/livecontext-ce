package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.Objects;

/**
 * Unified node identifier - eliminates alias vs normalized key confusion.
 * <p>
 * This record provides a type-safe way to identify nodes in a workflow graph.
 * All identifiers follow the pattern "type:normalized_label".
 * </p>
 * <p>
 * Examples:
 * <ul>
 *   <li>NodeId.trigger("my_webhook") -> "trigger:my_webhook"</li>
 *   <li>NodeId.step("API Call") -> "mcp:api_call"</li>
 *   <li>NodeId.loop("For Each") -> "core:for_each"</li>
 *   <li>NodeId.split("For Each") -> "core:for_each"</li>
 *   <li>NodeId.decision("Check Value") -> "core:check_value"</li>
 *   <li>NodeId.agent("My Assistant") -> "agent:my_assistant"</li>
 * </ul>
 * </p>
 * <p>
 * The label is automatically normalized using {@link LabelNormalizer#normalizeLabel(String)}
 * to ensure consistent matching between frontend and backend.
 * </p>
 *
 * @param type  The node type prefix (trigger, step, loop, split, decision, agent)
 * @param label The normalized label (lowercase, underscores, alphanumeric only)
 */
public record NodeId(String type, String label) {

    // Node type constants - MUST match LabelNormalizer prefixes
    public static final String TYPE_TRIGGER = "trigger";
    public static final String TYPE_MCP = "mcp";       // Was TYPE_STEP="step" - now matches LabelNormalizer.PREFIX_MCP
    public static final String TYPE_CORE = "core";     // Unified prefix for all control nodes (loop, split, decision, switch, merge, fork)
    public static final String TYPE_AGENT = "agent";

    // Legacy constants - still used internally, scheduled for future migration
    public static final String TYPE_STEP = TYPE_MCP;
    public static final String TYPE_LOOP = TYPE_CORE;
    public static final String TYPE_SPLIT = TYPE_CORE;
    public static final String TYPE_DECISION = TYPE_CORE;

    /**
     * Canonical constructor with validation.
     *
     * @param type  The node type (cannot be null or blank)
     * @param label The normalized label (cannot be null or blank)
     * @throws IllegalArgumentException if type or label is null or blank
     */
    public NodeId {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("NodeId type cannot be null or blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("NodeId label cannot be null or blank");
        }
    }

    // ========================================================================
    // FACTORY METHODS - Use these to create NodeId instances
    // ========================================================================

    /**
     * Creates a trigger NodeId from a label.
     * The label is automatically normalized.
     *
     * @param label The trigger label (e.g., "My Webhook")
     * @return NodeId with type "trigger"
     * @throws IllegalArgumentException if label is null or blank after normalization
     */
    public static NodeId trigger(String label) {
        String normalized = normalizeOrThrow(label, TYPE_TRIGGER);
        return new NodeId(TYPE_TRIGGER, normalized);
    }

    /**
     * Creates a step NodeId from a label.
     * The label is automatically normalized.
     *
     * @param label The step label (e.g., "API Call")
     * @return NodeId with type "step"
     * @throws IllegalArgumentException if label is null or blank after normalization
     */
    public static NodeId step(String label) {
        String normalized = normalizeOrThrow(label, TYPE_STEP);
        return new NodeId(TYPE_STEP, normalized);
    }

    /**
     * Creates a loop NodeId from a label.
     * The label is automatically normalized.
     *
     * @param label The loop label (e.g., "For Each Item")
     * @return NodeId with type "loop"
     * @throws IllegalArgumentException if label is null or blank after normalization
     */
    public static NodeId loop(String label) {
        String normalized = normalizeOrThrow(label, TYPE_LOOP);
        return new NodeId(TYPE_LOOP, normalized);
    }

    /**
     * Creates a split NodeId from a label.
     * The label is automatically normalized.
     *
     * @param label The split label (e.g., "For Each")
     * @return NodeId with type "split"
     * @throws IllegalArgumentException if label is null or blank after normalization
     */
    public static NodeId split(String label) {
        String normalized = normalizeOrThrow(label, TYPE_SPLIT);
        return new NodeId(TYPE_SPLIT, normalized);
    }

    /**
     * Creates a decision NodeId from a label.
     * The label is automatically normalized.
     *
     * @param label The decision label (e.g., "Check Value")
     * @return NodeId with type "decision"
     * @throws IllegalArgumentException if label is null or blank after normalization
     */
    public static NodeId decision(String label) {
        String normalized = normalizeOrThrow(label, TYPE_DECISION);
        return new NodeId(TYPE_DECISION, normalized);
    }

    /**
     * Creates an agent NodeId from a label.
     * The label is automatically normalized.
     *
     * @param label The agent label (e.g., "My Assistant")
     * @return NodeId with type "agent"
     * @throws IllegalArgumentException if label is null or blank after normalization
     */
    public static NodeId agent(String label) {
        String normalized = normalizeOrThrow(label, TYPE_AGENT);
        return new NodeId(TYPE_AGENT, normalized);
    }

    // ========================================================================
    // PARSING METHODS - For converting strings to NodeId
    // ========================================================================

    /**
     * Parse a raw string into NodeId.
     * Handles both prefixed ("mcp:my_step") and non-prefixed ("my_step") formats.
     * <p>
     * For non-prefixed strings, defaults to "step" type for backward compatibility.
     * </p>
     *
     * @param raw The raw string to parse
     * @return The parsed NodeId
     * @throws IllegalArgumentException if raw is null or blank
     */
    public static NodeId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("NodeId cannot be null or blank");
        }

        String trimmed = raw.trim();
        int colonIndex = trimmed.indexOf(':');

        if (colonIndex > 0 && colonIndex < trimmed.length() - 1) {
            String type = trimmed.substring(0, colonIndex);
            String label = trimmed.substring(colonIndex + 1);
            return new NodeId(type, label);
        }

        // Fallback: assume step type for backward compatibility
        String normalized = LabelNormalizer.normalizeLabel(trimmed);
        if (normalized == null) {
            throw new IllegalArgumentException("NodeId label cannot be blank after normalization: " + raw);
        }
        return new NodeId(TYPE_STEP, normalized);
    }

    /**
     * Try to parse a raw string into NodeId, returning null on failure.
     *
     * @param raw The raw string to parse
     * @return The parsed NodeId, or null if parsing fails
     */
    public static NodeId tryParse(String raw) {
        try {
            return parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parse with explicit fallback type for non-prefixed strings.
     *
     * @param raw          The raw string to parse
     * @param fallbackType The type to use if no prefix is found
     * @return The parsed NodeId
     */
    public static NodeId parseWithFallback(String raw, String fallbackType) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("NodeId cannot be null or blank");
        }

        String trimmed = raw.trim();
        int colonIndex = trimmed.indexOf(':');

        if (colonIndex > 0 && colonIndex < trimmed.length() - 1) {
            String type = trimmed.substring(0, colonIndex);
            String label = trimmed.substring(colonIndex + 1);
            return new NodeId(type, label);
        }

        // Use fallback type
        String normalized = LabelNormalizer.normalizeLabel(trimmed);
        if (normalized == null) {
            throw new IllegalArgumentException("NodeId label cannot be blank after normalization: " + raw);
        }
        return new NodeId(fallbackType, normalized);
    }

    // ========================================================================
    // INSTANCE METHODS
    // ========================================================================

    /**
     * Returns the canonical string representation: "type:label".
     *
     * @return The key in format "type:label"
     */
    public String toKey() {
        return type + ":" + label;
    }

    /**
     * Returns true if this is a trigger node.
     *
     * @return true if type equals "trigger"
     */
    public boolean isTrigger() {
        return TYPE_TRIGGER.equals(type);
    }

    /**
     * Returns true if this is a step node.
     *
     * @return true if type equals "step"
     */
    public boolean isStep() {
        return TYPE_STEP.equals(type);
    }

    /**
     * Returns true if this is a loop node.
     *
     * @return true if type equals "loop"
     */
    public boolean isLoop() {
        return TYPE_LOOP.equals(type);
    }

    /**
     * Returns true if this is a split node.
     *
     * @return true if type equals "split"
     */
    public boolean isSplit() {
        return TYPE_SPLIT.equals(type);
    }

    /**
     * Returns true if this is a decision node.
     *
     * @return true if type equals "decision"
     */
    public boolean isDecision() {
        return TYPE_DECISION.equals(type);
    }

    /**
     * Returns true if this is an agent node.
     *
     * @return true if type equals "agent"
     */
    public boolean isAgent() {
        return TYPE_AGENT.equals(type);
    }

    /**
     * Returns true if this is an execution node (step or agent).
     * These are nodes that require user action to execute.
     *
     * @return true if type is "step" or "agent"
     */
    public boolean isExecutableNode() {
        return isStep() || isAgent();
    }

    /**
     * Returns true if this is a control flow node (loop, split, or decision).
     *
     * @return true if type is "loop", "split", or "decision"
     */
    public boolean isCore() {
        return isLoop() || isSplit() || isDecision();
    }

    // ========================================================================
    // EQUALITY AND HASH CODE - Based on canonical key
    // ========================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeId other)) return false;
        return toKey().equals(other.toKey());
    }

    @Override
    public int hashCode() {
        return toKey().hashCode();
    }

    @Override
    public String toString() {
        return toKey();
    }

    // ========================================================================
    // PRIVATE HELPER METHODS
    // ========================================================================

    /**
     * Normalize a label or throw if the result is null/blank.
     *
     * @param label The label to normalize
     * @param type  The node type (for error message)
     * @return The normalized label
     * @throws IllegalArgumentException if normalization results in null or blank
     */
    private static String normalizeOrThrow(String label, String type) {
        String normalized = LabelNormalizer.normalizeLabel(label);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(
                    String.format("Cannot create %s NodeId: label '%s' is null or blank after normalization",
                            type, label));
        }
        return normalized;
    }
}
