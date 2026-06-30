package com.apimarketplace.orchestrator.utils;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parser for V2 edge reference format.
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
 * Formats supported:
 * - "trigger:label" -> { nodeType: "trigger", nodeLabel: "label", port: null }
 * - "mcp:label" -> { nodeType: "mcp", nodeLabel: "label", port: null }
 * - "table:label" -> { nodeType: "table", nodeLabel: "label", port: null }
 * - "agent:label" -> { nodeType: "agent", nodeLabel: "label", port: null }
 * - "core:label" -> { nodeType: "core", nodeLabel: "label", port: null }
 * - "core:label:if" -> { nodeType: "core", nodeLabel: "label", port: "if" } (decision)
 * - "core:label:else" -> { nodeType: "core", nodeLabel: "label", port: "else" } (decision)
 * - "core:label:elseif_0" -> { nodeType: "core", nodeLabel: "label", port: "elseif_0" } (decision)
 * - "core:label:case_0" -> { nodeType: "core", nodeLabel: "label", port: "case_0" } (switch)
 * - "core:label:default" -> { nodeType: "core", nodeLabel: "label", port: "default" } (switch)
 * - "core:label:body" -> { nodeType: "core", nodeLabel: "label", port: "body" } (loop)
 * - "core:label:iterate" -> { nodeType: "core", nodeLabel: "label", port: "iterate" } (loop)
 * - "core:label:exit" -> { nodeType: "core", nodeLabel: "label", port: "exit" } (loop)
 * - "core:label:branch_0" -> { nodeType: "core", nodeLabel: "label", port: "branch_0" } (fork)
 * - "core:label:branch_1" -> { nodeType: "core", nodeLabel: "label", port: "branch_1" } (fork)
 * - "note:label" -> { nodeType: "note", nodeLabel: "label", port: null }
 *
 * Note: Split does NOT use ports. It uses internal parallel spawning mechanism.
 * Items are passed via execution context ({{item}}, {{item.field}}), not via edge ports.
 * - "interface:label" -> { nodeType: "interface", nodeLabel: "label", port: null }
 */
public final class EdgeRefParser {

    private EdgeRefParser() {
        // Utility class
    }

    private static final Set<String> VALID_NODE_TYPES = Set.of(
        "trigger", "mcp", "table", "agent", "core", "note", "interface"
    );

    private static final Set<String> NODES_WITH_PORTS = Set.of(
        "core", "agent"  // agent supports category ports for classify nodes
    );

    private static final Pattern ELSEIF_PATTERN = Pattern.compile("^elseif_(\\d+)$");
    private static final Pattern CASE_PATTERN = Pattern.compile("^case_(\\d+)$");
    private static final Pattern BRANCH_PATTERN = Pattern.compile("^branch_(\\d+)$");
    private static final Pattern CHOICE_PATTERN = Pattern.compile("^choice_(\\d+)$");
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("^category_(\\d+)$");  // For classify nodes
    private static final Pattern GUARDRAIL_PATTERN = Pattern.compile("^(pass|fail)$");  // For guardrail nodes
    private static final Pattern APPROVAL_PATTERN = Pattern.compile("^(approved|rejected|timeout)$");  // For approval nodes
    private static final Pattern PATH_PATTERN = Pattern.compile("^path_(\\d+)$");  // Approval CUSTOM paths (beyond approved/rejected/timeout)

    /**
     * Parsed edge reference.
     */
    public record EdgeRef(
        String nodeType,
        String nodeLabel,
        String port
    ) {
        /**
         * Check if this ref has a port.
         */
        public boolean hasPort() {
            return port != null && !port.isBlank();
        }

        /**
         * Get the node key (nodeType:nodeLabel) without port.
         */
        public String getNodeKey() {
            return nodeType + ":" + nodeLabel;
        }
    }

    /**
     * Parse an edge reference string into its components.
     *
     * @param ref Edge reference string (e.g., "core:check:if", "mcp:fetch_data", "agent:classify:category_0")
     * @return Parsed EdgeRef object, or null if invalid
     */
    public static EdgeRef parse(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }

        String[] parts = ref.split(":");
        if (parts.length < 2) {
            return null;
        }

        String nodeType = parts[0];
        if (!VALID_NODE_TYPES.contains(nodeType)) {
            return null;
        }

        // For nodes with ports (core, agent), the format is type:label:port
        // For other nodes, any additional parts are part of the label
        if (NODES_WITH_PORTS.contains(nodeType) && parts.length >= 3) {
            // Last part is the port
            String port = parts[parts.length - 1];
            // Everything in between is the label
            String nodeLabel = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
            return new EdgeRef(nodeType, nodeLabel, port);
        }

        // For nodes without ports or when no port is specified
        String nodeLabel = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        return new EdgeRef(nodeType, nodeLabel, null);
    }

    /**
     * Build an edge reference string from components.
     *
     * @param nodeType Type of node
     * @param nodeLabel Normalized label of node
     * @param port Optional port (for decision, switch, loop)
     * @return Edge reference string
     */
    public static String build(String nodeType, String nodeLabel, String port) {
        if (nodeType == null || nodeLabel == null || nodeLabel.isBlank()) {
            throw new IllegalArgumentException("nodeType and nodeLabel are required");
        }

        if (!VALID_NODE_TYPES.contains(nodeType)) {
            throw new IllegalArgumentException("Invalid node type: " + nodeType);
        }

        if (port != null && !port.isBlank()) {
            if (!NODES_WITH_PORTS.contains(nodeType)) {
                throw new IllegalArgumentException("Node type " + nodeType + " does not support ports");
            }
            return nodeType + ":" + nodeLabel + ":" + port;
        }

        return nodeType + ":" + nodeLabel;
    }

    /**
     * Build an edge reference string without a port.
     */
    public static String build(String nodeType, String nodeLabel) {
        return build(nodeType, nodeLabel, null);
    }

    /**
     * Check if a reference string has a port.
     */
    public static boolean hasPort(String ref) {
        EdgeRef parsed = parse(ref);
        return parsed != null && parsed.hasPort();
    }

    /**
     * Get the node key (without port) from a reference.
     * "core:check:if" -> "core:check"
     * "mcp:fetch" -> "mcp:fetch"
     */
    public static String getNodeKey(String ref) {
        EdgeRef parsed = parse(ref);
        return parsed != null ? parsed.getNodeKey() : null;
    }

    /**
     * Get just the port from a reference, or null if none.
     */
    public static String getPort(String ref) {
        EdgeRef parsed = parse(ref);
        return parsed != null ? parsed.port() : null;
    }

    /**
     * Split any reference into {@code [baseRef, port]} recognising EXACTLY the
     * ports this parser defines ({@link #isValidPort}).
     *
     * <p>Unlike {@link #parse}, this works on BOTH fully-qualified node ids
     * ({@code "core:check:if"}) and bare/partial labels ({@code "Check:if"},
     * {@code "check:if"}): it inspects only the final {@code ':'}-segment and
     * treats it as a port iff {@link #isValidPort} accepts it - it does not
     * require a leading {@code type:} prefix. {@code port} is {@code null} when
     * there is no recognised trailing port (so the whole ref is the base).
     *
     * <p>This is the SINGLE SOURCE OF TRUTH for "is the trailing segment a
     * port?". The workflow builder's set_plan import, plan validation and
     * get_plan export all delegate here, so they can never drift to different
     * port sets again (the bug that made guardrail/approval/option-port edges
     * pass import but fail validation, or vice-versa).
     *
     * @param ref any reference, possibly null
     * @return a 2-element array {@code [baseRef, port]}; {@code port} is null
     *         when absent. For {@code ref == null} returns {@code [null, null]}.
     */
    public static String[] splitPort(String ref) {
        if (ref == null) {
            return new String[]{null, null};
        }
        int firstColon = ref.indexOf(':');
        int lastColon = ref.lastIndexOf(':');
        boolean hasValidTypePrefix = firstColon > 0
                && VALID_NODE_TYPES.contains(ref.substring(0, firstColon));

        if (hasValidTypePrefix) {
            // Fully-qualified ref "type:label[:port]". A port requires a 3rd
            // segment (lastColon > firstColon) AND a recognised port value. This
            // keeps "core:if" (2 segments - a node whose LABEL is "if") intact
            // instead of mis-splitting it to base "core" + port "if", while still
            // splitting a wrong-prefix ref like "mcp:my_fork:branch_0" (the type
            // may be wrong and get cross-prefix-recovered downstream). An
            // unrecognised 3rd segment is left in the base for the validator.
            if (lastColon > firstColon) {
                String candidate = ref.substring(lastColon + 1);
                if (isValidPort(candidate)) {
                    return new String[]{ref.substring(0, lastColon), candidate};
                }
            }
            return new String[]{ref, null};
        }

        // Bare label ref ("Check:if") - no valid type prefix, so a single colon
        // can be label:port. Treat the last segment as a port iff it's recognised.
        if (lastColon > 0 && lastColon < ref.length() - 1) {
            String candidate = ref.substring(lastColon + 1);
            if (isValidPort(candidate)) {
                return new String[]{ref.substring(0, lastColon), candidate};
            }
        }
        return new String[]{ref, null};
    }

    /**
     * Check if a reference is for a specific node type.
     */
    public static boolean isNodeType(String ref, String expectedType) {
        EdgeRef parsed = parse(ref);
        return parsed != null && expectedType.equals(parsed.nodeType());
    }

    /**
     * Check if a reference matches a given node key (ignoring port).
     */
    public static boolean matchesNodeKey(String ref, String nodeKey) {
        String refKey = getNodeKey(ref);
        return refKey != null && refKey.equals(nodeKey);
    }

    /**
     * Parse a decision port.
     * "if" -> { type: "if", index: -1 }
     * "else" -> { type: "else", index: -1 }
     * "elseif_0" -> { type: "elseif", index: 0 }
     */
    public static DecisionPort parseDecisionPort(String port) {
        if (port == null) {
            return null;
        }
        if ("if".equals(port)) {
            return new DecisionPort("if", -1);
        }
        if ("else".equals(port)) {
            return new DecisionPort("else", -1);
        }
        var matcher = ELSEIF_PATTERN.matcher(port);
        if (matcher.matches()) {
            int index = Integer.parseInt(matcher.group(1));
            return new DecisionPort("elseif", index);
        }
        return null;
    }

    /**
     * Parse a switch port.
     * "case_0" -> { type: "case", index: 0 }
     * "default" -> { type: "default", index: -1 }
     */
    public static SwitchPort parseSwitchPort(String port) {
        if (port == null) {
            return null;
        }
        if ("default".equals(port)) {
            return new SwitchPort("default", -1);
        }
        var matcher = CASE_PATTERN.matcher(port);
        if (matcher.matches()) {
            int index = Integer.parseInt(matcher.group(1));
            return new SwitchPort("case", index);
        }
        return null;
    }

    /**
     * Parse a loop port.
     * "body" -> LoopPort.BODY
     * "iterate" -> LoopPort.ITERATE
     * "exit" -> LoopPort.EXIT
     */
    public static LoopPort parseLoopPort(String port) {
        if (port == null) {
            return null;
        }
        return switch (port) {
            case "body" -> LoopPort.BODY;
            case "iterate" -> LoopPort.ITERATE;
            case "exit" -> LoopPort.EXIT;
            default -> null;
        };
    }

    /**
     * Build a decision port string.
     */
    public static String buildDecisionPort(String type, int index) {
        if ("elseif".equals(type) && index >= 0) {
            return "elseif_" + index;
        }
        return type;
    }

    /**
     * Build a switch port string.
     */
    public static String buildSwitchPort(String type, int index) {
        if ("case".equals(type) && index >= 0) {
            return "case_" + index;
        }
        return "default";
    }

    /**
     * Parsed decision port.
     */
    public record DecisionPort(String type, int index) {}

    /**
     * Parsed switch port.
     */
    public record SwitchPort(String type, int index) {}

    /**
     * Loop port types.
     */
    public enum LoopPort {
        BODY, ITERATE, EXIT
    }

    /**
     * Parse a fork port.
     * "branch_0" -> { index: 0 }
     * "branch_1" -> { index: 1 }
     */
    public static ForkPort parseForkPort(String port) {
        if (port == null) {
            return null;
        }
        var matcher = BRANCH_PATTERN.matcher(port);
        if (matcher.matches()) {
            int index = Integer.parseInt(matcher.group(1));
            return new ForkPort(index);
        }
        return null;
    }

    /**
     * Build a fork port string.
     * @param index Branch index (0, 1, 2, ...)
     * @return Port string like "branch_0", "branch_1", etc.
     */
    public static String buildForkPort(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Fork branch index must be >= 0");
        }
        return "branch_" + index;
    }

    /**
     * Parsed fork port.
     */
    public record ForkPort(int index) {}

    /**
     * Parse an option port.
     * "choice_0" -> { index: 0 }
     * "choice_1" -> { index: 1 }
     */
    public static OptionPort parseOptionPort(String port) {
        if (port == null) {
            return null;
        }
        var matcher = CHOICE_PATTERN.matcher(port);
        if (matcher.matches()) {
            int index = Integer.parseInt(matcher.group(1));
            return new OptionPort(index);
        }
        return null;
    }

    /**
     * Build an option port string.
     * @param index Choice index (0, 1, 2, ...)
     * @return Port string like "choice_0", "choice_1", etc.
     */
    public static String buildOptionPort(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Option choice index must be >= 0");
        }
        return "choice_" + index;
    }

    /**
     * Parsed option port.
     */
    public record OptionPort(int index) {}

    /**
     * Parse a category port (for classify nodes).
     * "category_0" -> { index: 0 }
     * "category_1" -> { index: 1 }
     */
    public static CategoryPort parseCategoryPort(String port) {
        if (port == null) {
            return null;
        }
        var matcher = CATEGORY_PATTERN.matcher(port);
        if (matcher.matches()) {
            int index = Integer.parseInt(matcher.group(1));
            return new CategoryPort(index);
        }
        return null;
    }

    /**
     * Build a category port string.
     * @param index Category index (0, 1, 2, ...)
     * @return Port string like "category_0", "category_1", etc.
     */
    public static String buildCategoryPort(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Category index must be >= 0");
        }
        return "category_" + index;
    }

    /**
     * Parsed category port (for classify nodes).
     */
    public record CategoryPort(int index) {}

    /**
     * Parse a guardrail port.
     * "pass" -> { passed: true }
     * "fail" -> { passed: false }
     */
    public static GuardrailPort parseGuardrailPort(String port) {
        if (port == null) {
            return null;
        }
        var matcher = GUARDRAIL_PATTERN.matcher(port);
        if (matcher.matches()) {
            return new GuardrailPort("pass".equals(port));
        }
        return null;
    }

    /**
     * Build a guardrail port string.
     * @param passed Whether this is the pass or fail branch
     * @return Port string "pass" or "fail"
     */
    public static String buildGuardrailPort(boolean passed) {
        return passed ? "pass" : "fail";
    }

    /**
     * Parsed guardrail port.
     */
    public record GuardrailPort(boolean passed) {}

    /**
     * Parse an approval port.
     * "approved" -> { type: "approved" }
     * "rejected" -> { type: "rejected" }
     * "timeout" -> { type: "timeout" }
     */
    public static ApprovalPort parseApprovalPort(String port) {
        if (port == null) {
            return null;
        }
        var matcher = APPROVAL_PATTERN.matcher(port);
        if (matcher.matches()) {
            return new ApprovalPort(port);
        }
        return null;
    }

    /**
     * Build an approval port string.
     * @param type "approved", "rejected", or "timeout"
     * @return Port string
     */
    public static String buildApprovalPort(String type) {
        if (!Set.of("approved", "rejected", "timeout").contains(type)) {
            throw new IllegalArgumentException("Invalid approval port: " + type);
        }
        return type;
    }

    /**
     * Parsed approval port.
     */
    public record ApprovalPort(String type) {}

    /**
     * Parse an approval CUSTOM-path port.
     * "path_0" -> { index: 0 }
     *
     * <p>Beyond the built-in approved/rejected/timeout ports, an approval node
     * may declare custom decision paths. The builder emits these as {@code path_N}
     * and {@link com.apimarketplace.orchestrator.execution.v2.engine.ApprovalNodeWirer}
     * wires them generically, so they are runtime-legal edge ports.
     */
    public static ApprovalPathPort parseApprovalPathPort(String port) {
        if (port == null) {
            return null;
        }
        var matcher = PATH_PATTERN.matcher(port);
        if (matcher.matches()) {
            return new ApprovalPathPort(Integer.parseInt(matcher.group(1)));
        }
        return null;
    }

    /**
     * Build an approval custom-path port string.
     * @param index Path index (0, 1, 2, ...)
     * @return Port string like "path_0", "path_1", etc.
     */
    public static String buildApprovalPathPort(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Approval path index must be >= 0");
        }
        return "path_" + index;
    }

    /**
     * Parsed approval custom-path port.
     */
    public record ApprovalPathPort(int index) {}

    // Note: Split does NOT use edge ports. Items are passed via execution context.
    // No parseSplitPort() method needed.

    /**
     * Determine the type of port from its string representation.
     * @param port Port string (e.g., "if", "branch_0", "body", "category_0", "pass")
     * @return Port type: "decision", "switch", "loop", "fork", "option", "classify", "guardrail", or null if unknown
     *
     * Note: Split does not use edge ports (uses internal parallel spawning).
     */
    public static String getPortType(String port) {
        if (port == null) {
            return null;
        }
        if (parseDecisionPort(port) != null) {
            return "decision";
        }
        if (parseSwitchPort(port) != null) {
            return "switch";
        }
        if (parseLoopPort(port) != null) {
            return "loop";
        }
        if (parseForkPort(port) != null) {
            return "fork";
        }
        if (parseOptionPort(port) != null) {
            return "option";
        }
        if (parseCategoryPort(port) != null) {
            return "classify";
        }
        if (parseGuardrailPort(port) != null) {
            return "guardrail";
        }
        if (parseApprovalPort(port) != null) {
            return "approval";
        }
        if (parseApprovalPathPort(port) != null) {
            return "approval";  // custom approval path (path_N)
        }
        // Split doesn't use edge ports
        return null;
    }

    /**
     * Check if a port string is valid for any known port type.
     */
    public static boolean isValidPort(String port) {
        return getPortType(port) != null;
    }
}
