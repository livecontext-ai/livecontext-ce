package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.orchestrator.tools.workflow.builder.BranchPortOverflowException;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Base class containing shared methods for node creators (TriggerCreator, McpCreator, etc.).
 * Provides common utilities for validation, edge creation, type conversion, and session management.
 */
@Slf4j
public abstract class CreatorBase {

    // ==================== Node Types ====================

    public enum NodeType {
        TRIGGER("trigger", "add_trigger"),
        MCP("mcp", "add_mcp"),
        AGENT("agent", "add_agent"),
        DECISION("core", "add_decision"),    // Control nodes use "core:" prefix
        SWITCH("core", "add_switch"),        // Control nodes use "core:" prefix
        SPLIT("core", "add_split"),        // Control nodes use "core:" prefix
        FORK("core", "add_fork"),            // Control nodes use "core:" prefix
        MERGE("core", "add_merge"),          // Control nodes use "core:" prefix
        TRANSFORM("core", "add_transform"),  // Transform is stored in cores but behaves as passthrough step
        WAIT("core", "add_wait"),            // Wait is stored in cores but behaves as passthrough step
        DOWNLOAD_FILE("core", "add_download_file"),  // Download file from URL and store for workflow use
        PUBLIC_LINK("core", "add_public_link"),      // Mint a public, time-limited signed URL for a stored file
        MEDIA("core", "add_media"),                  // Audio/video processing: probe, mux_audio, mix, extract_audio (renderer component)
        EXIT("core", "add_exit"),            // Exit branch execution (terminal node, other parallel branches continue)
        RESPONSE("core", "add_response"),    // Send a message response to chat interface
        OPTION("core", "add_option"),        // Multiple choice branching (N choices, first true wins)
        AGGREGATE("core", "add_aggregate"),  // Aggregate data from parallel executions (Split)
        HTTP_REQUEST("core", "add_http_request"),  // Make HTTP requests to external APIs
        LOOP("core", "add_loop"),                    // While loop with condition and body/exit ports
        APPROVAL("core", "add_approval"),            // User approval with approved/rejected/timeout ports
        DATA_INPUT("core", "add_data_input"),        // Provide input data (text/file) to downstream nodes
        FILTER("core", "add_filter"),              // Filter items based on conditions (AND/OR mode)
        SORT("core", "add_sort"),                  // Sort items by one or more fields (asc/desc)
        LIMIT("core", "add_limit"),                // Limit - pass through only first/last N items
        REMOVE_DUPLICATES("core", "add_remove_duplicates"),  // Remove duplicate items by fields
        SUMMARIZE("core", "add_summarize"),                // Summarize/pivot data (sum, avg, count, min, max, group by)
        DATE_TIME("core", "add_date_time"),                // Date/time operations (parse, format, convert, manipulate)
        CRYPTO_JWT("core", "add_crypto_jwt"),              // Crypto/JWT operations (hash, encrypt, JWT, base64)
        XML("core", "add_xml"),                            // XML parse/convert operations
        COMPRESSION("core", "add_compression"),            // Compress/decompress (ZIP, GZIP)
        RSS("core", "add_rss"),                            // Fetch and parse RSS/Atom feeds
        CONVERT_TO_FILE("core", "add_convert_to_file"),    // Export JSON data to CSV, XLSX, JSON, TXT
        EXTRACT_FROM_FILE("core", "add_extract_from_file"), // Import files: structured (CSV/XLSX/JSON) or text mode (PDF/HTML/DOCX/TXT) with chunking
        COMPARE_DATASETS("core", "add_compare_datasets"),  // Compare two datasets (matched/only-A/only-B)
        SUB_WORKFLOW("core", "add_sub_workflow"),              // Execute another workflow as a function
        RESPOND_TO_WEBHOOK("core", "add_respond_to_webhook"), // Control HTTP response to webhook caller
        SEND_EMAIL("core", "add_send_email"),                  // Send emails via SMTP
        EMAIL_INBOX("core", "add_email_inbox"),                // Read messages and act on a mailbox via IMAP
        CODE("core", "add_code"),                              // Execute user code via Piston sandbox
        SET("core", "add_set"),                                // Set / Edit Fields - assign or transform fields on input
        HTML_EXTRACT("core", "add_html_extract"),              // HTML Extract - parse HTML via CSS selectors (jsoup)
        TASK("core", "add_task"),                              // Task CRUD - create, read, update, delete, list agent tasks
        STOP_ON_ERROR("core", "add_stop_on_error"),            // StopOnError - immediately fail workflow with error message
        SSH("core", "add_ssh"),                                // SSH - execute commands on remote servers via SSH
        SFTP("core", "add_sftp"),                              // SFTP - file operations on remote servers via SFTP
        DATABASE("core", "add_database"),                      // Database - execute SQL queries (PostgreSQL, MySQL, MSSQL)
        TABLE("table", "add_table"),                            // Table CRUD operations (insert_row, read_rows, etc.)
        INTERFACE("interface", "add_interface");  // Visual interface: display data, interactive app, or multi-page navigation

        private final String prefix;
        private final String actionName;

        NodeType(String prefix, String actionName) {
            this.prefix = prefix;
            this.actionName = actionName;
        }

        public String getPrefix() { return prefix; }
        public String getActionName() { return actionName; }
        public String buildNodeId(String normalizedLabel) { return prefix + ":" + normalizedLabel; }
    }

    // Node sizing constants
    protected static final int NODE_BASE_WIDTH = 60;   // Icon + padding
    protected static final int CHAR_WIDTH = 8;         // Approx pixels per character
    protected static final int NODE_MIN_WIDTH = 120;   // Minimum node width
    protected static final int NODE_GAP = 60;          // Gap between nodes

    // ==================== Params Extraction Helper ====================

    /**
     * Extract params from parameters.
     * The LLM agent sends parameters in "params" container:
     *   workflow(action='add_node', type='split', label='X', params={list: '{{...}}'})
     *
     * This method returns the "params" map if present, otherwise returns the parameters map itself
     * for flat parameter access.
     *
     * @param parameters The raw parameters from the tool call
     * @return The params map (from parameters.params) or parameters itself as fallback
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> extractParams(Map<String, Object> parameters) {
        Object params = parameters.get("params");
        if (params instanceof Map) {
            return (Map<String, Object>) params;
        }
        return parameters;
    }

    /**
     * Get a value from params first, then fallback to root parameters.
     * This handles both formats:
     *   - New format: {params: {key: value}}
     *   - Old format: {key: value}
     */
    protected Object getFromParamsOrRoot(Map<String, Object> parameters, String key) {
        Map<String, Object> params = extractParams(parameters);
        Object value = params.get(key);
        if (value != null) {
            return value;
        }
        // Fallback to root parameters
        return parameters.get(key);
    }

    /**
     * Get a string value from params first, then fallback to root parameters.
     */
    protected String getStringFromParamsOrRoot(Map<String, Object> parameters, String key) {
        Object value = getFromParamsOrRoot(parameters, key);
        return safeString(value);
    }

    // ==================== String Conversion Helpers ====================

    /**
     * Safely convert a value to String.
     * Handles cases where a Map or other object is passed instead of a String.
     * Also sanitizes the string to remove null bytes that PostgreSQL rejects.
     */
    protected static String safeString(Object value) {
        if (value == null) {
            return null;
        }
        String result;
        if (value instanceof String) {
            result = (String) value;
        } else if (value instanceof Map) {
            // If it's a Map, try to extract a meaningful value
            Map<?, ?> map = (Map<?, ?>) value;
            // Common patterns: {text: "..."}, {value: "..."}, {name: "..."}
            for (String key : List.of("text", "value", "name", "content", "label", "prompt")) {
                Object extracted = map.get(key);
                if (extracted instanceof String) {
                    log.warn("Extracted '{}' from Map instead of String: {}", key, extracted);
                    result = (String) extracted;
                    return sanitizeString(result);
                }
            }
            // Fallback: convert to JSON-like string
            log.warn("Converting Map to String: {}", map);
            result = map.toString();
        } else {
            // For other types, use String.valueOf
            result = String.valueOf(value);
        }
        return sanitizeString(result);
    }

    /**
     * Sanitize a string by removing null bytes and other problematic characters
     * that PostgreSQL doesn't accept in text fields.
     */
    protected static String sanitizeString(String str) {
        if (str == null) {
            return null;
        }
        // Remove null bytes (\u0000) which PostgreSQL rejects with "unsupported Unicode escape sequence"
        // This can happen when frontend sends malformed Unicode (e.g., \u0000e9 instead of \u00e9)
        String sanitized = str.replace("\u0000", "");
        if (sanitized.length() != str.length()) {
            log.warn("Removed {} null byte(s) from string", str.length() - sanitized.length());
        }
        return sanitized;
    }

    protected String toStringOrNull(Object value) {
        return safeString(value);
    }

    protected Double toDoubleOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    protected Integer toIntegerOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    protected Long toLongOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ==================== Multi-Key Lookup Helpers ====================

    /**
     * Get a string value by trying multiple keys (aliases).
     * Useful for tolerating LLM parameter naming variations.
     * Example: getString(params, "items", "list", "input") tries all three.
     */
    protected String getString(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof String s && !s.isBlank()) return s;
            if (value instanceof Number n) return n.toString();
        }
        return null;
    }

    // ==================== Loop Context Helpers ====================

    /**
     * Detect if a node is being added inside a loop body by checking the connect_after edge.
     * Returns the loop label (e.g., "my_loop") if inside a loop body, null otherwise.
     *
     * Detection: walks backward from connectAfter through session edges to find
     * if the chain originates from a ":body" port of a loop node.
     */
    protected String detectParentLoop(String connectAfter, WorkflowBuilderSession session) {
        if (connectAfter == null) return null;

        // Direct case: connect_after is "loop_label:body"
        if (connectAfter.contains(":body")) {
            String loopId = connectAfter.split(":body")[0];
            // Could be "core:my_loop:body" -> extract "my_loop"
            if (loopId.startsWith("core:")) loopId = loopId.substring("core:".length());
            return loopId;
        }

        // Indirect case: walk backward through edges to find if we reach a loop:body port
        String current = connectAfter;
        Set<String> visited = new java.util.HashSet<>();
        for (int i = 0; i < 50 && current != null; i++) {
            if (!visited.add(current)) break;
            String predecessor = current;
            // Find the edge whose 'to' matches current (or current normalized)
            String found = null;
            for (Map<String, Object> edge : session.getEdges()) {
                String to = String.valueOf(edge.get("to"));
                String from = String.valueOf(edge.get("from"));
                String toNorm = to.contains(":") ? to : "core:" + to;
                String predNorm = predecessor.contains(":") ? predecessor : "core:" + predecessor;
                if (toNorm.equals(predNorm) || to.equals(predecessor)) {
                    if (from.contains(":body")) {
                        String loopId = from.split(":body")[0];
                        if (loopId.startsWith("core:")) loopId = loopId.substring("core:".length());
                        return loopId;
                    }
                    // Continue backward
                    found = from;
                    break;
                }
            }
            current = found;
        }
        return null;
    }

    // ==================== Label Helpers ====================

    protected String getLabel(Map<String, Object> node) {
        String label = safeString(node.get("label"));
        if (label == null) label = safeString(node.get("name"));
        return label;
    }

    /**
     * Tolerant label extraction: if the nested object (trigger/step/agent) doesn't have a label,
     * try to get it from the root parameters. This handles mixed formats like:
     * {"label": "My Label", "trigger": {"type": "schedule"}}
     *
     * Also merges the label into the node object for downstream consistency.
     */
    protected void mergeRootLabelIntoNode(Map<String, Object> node, Map<String, Object> parameters) {
        if (node == null || parameters == null) return;

        // If node already has a label, nothing to do
        String existingLabel = safeString(node.get("label"));
        if (existingLabel == null) existingLabel = safeString(node.get("name"));
        if (existingLabel != null && !existingLabel.isBlank()) return;

        // Try to get label from root parameters
        String rootLabel = safeString(parameters.get("label"));
        if (rootLabel == null) rootLabel = safeString(parameters.get("name"));

        if (rootLabel != null && !rootLabel.isBlank()) {
            node.put("label", rootLabel);
        }
    }

    // ==================== Validation Helpers ====================

    /**
     * Common validation: check label is provided.
     */
    protected ToolExecutionResult validateLabel(String label, String nodeTypeName) {
        if (label == null || label.isBlank()) {
            String example = switch (nodeTypeName) {
                case "step", "mcp" -> "workflow(action='add_node', type='<tool-uuid-from-catalog>', label='Send Email', params={to: '...', subject: '...'}, connect_after='...')";
                case "agent" -> "workflow(action='add_node', type='agent', label='Classify Email', params={prompt: 'Classify: {{trigger:process_emails.output.subject}}. Return {category}'}, connect_after='...')";
                case "trigger" -> "workflow(action='add_node', type='table', label='Process Rows', params={table_id: 123})";
                case "decision" -> "workflow(action='add_node', type='decision', label='Check', params={conditions: [{condition: '{{mcp:api_call.output.status == 200}}', label: 'OK'}, {label: 'Error'}]}, connect_after='...')";
                case "guardrail" -> "workflow(action='add_node', type='guardrail', label='Check Content', params={rules: ['pii', 'toxicity'], input: '{{mcp:fetch.output.text}}'}, connect_after='...')";
                case "classify" -> "workflow(action='add_node', type='classify', label='Route Ticket', params={categories: ['billing', 'technical', 'other'], input: '{{trigger:ticket.output.subject}}'}, connect_after='...')";
                default -> "workflow(action='add_node', type='" + nodeTypeName + "', label='My " + nodeTypeName + "', params={...}, connect_after='...')";
            };
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "label is required. Example: " + example);
        }
        return null; // null means valid
    }

    /**
     * Common validation: check node doesn't already exist.
     */
    protected ToolExecutionResult validateNodeNotExists(WorkflowBuilderSession session, String nodeId, String label) {
        // Exact same-type id collision (e.g. two agents both labelled "Foo").
        if (session.nodeExists(nodeId)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Node '" + label + "' already exists. Use a different label.");
        }
        // Cross-prefix label collision (e.g. adding a code node "Foo" while an agent
        // "Foo" already exists). Node identity is prefix:normalizedLabel, so the two
        // are distinct ids - but the label resolver (findNodeByNormalizedLabel) keys
        // purely by normalized label with a FIXED prefix priority (trigger, mcp,
        // agent, core, interface, table). Two live nodes sharing a normalized label
        // make every label reference (connect/modify/remove/{{…}}) bind silently to
        // whichever prefix sorts first - the other node becomes unaddressable by
        // label. Reject at creation, mirroring TriggerCreator's existing
        // trigger-label uniqueness guard. This wires the previously-unused
        // WorkflowBuilderSession.validateUniqueLabel (dead since it was added).
        String labelClash = session.validateUniqueLabel(label, nodeId.split(":", 2)[0]);
        if (labelClash != null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_ALREADY_EXISTS, labelClash);
        }
        return null; // null means valid
    }

    /**
     * Get connect_after parameter (NO auto-connection fallback).
     * If connect_after is not provided, the node will be orphaned and a warning will be shown.
     */
    protected String resolveConnectAfter(Map<String, Object> parameters, WorkflowBuilderSession session) {
        String connectAfter = (String) parameters.get("connect_after");
        // NO FALLBACK - if not provided, node will be orphaned
        return connectAfter;
    }

    /**
     * Validate that connect_after node actually exists in the session.
     * Returns error result if node not found, null if valid.
     *
     * Handles ports for branching nodes:
     * - Decision: core:label:if, core:label:else, core:label:elseif_N
     * - Fork: core:label:branch_N
     * - Classify: agent:label:category_N
     * - Loop: core:label:body, core:label:exit
     */
    protected ToolExecutionResult validateConnectAfter(String connectAfter, WorkflowBuilderSession session) {
        if (connectAfter == null || connectAfter.isBlank()) {
            return null; // No validation needed for empty connect_after
        }

        // Resolve reference (handles labels and full IDs)
        String resolvedId = session.resolveNodeReference(connectAfter);

        // Split base node id + port via the single source of truth (EdgeRefParser).
        // Format: prefix:label:port (e.g., core:check:if, agent:classify:category_0).
        String[] portSplit = EdgeRefParser.splitPort(resolvedId);
        String baseNodeId = portSplit[0];
        String port = portSplit[1];

        // Check if base node exists in session
        List<String> allNodeIds = session.getAllNodeIds();
        if (!allNodeIds.contains(baseNodeId)) {
            // Before giving "unknown node" error, check if the user provided a valid node with an invalid port
            // This catches: "Label:approve" (should be "Label:if"), "Label:elseif-0" (should be "Label:elseif_0")
            if (connectAfter.contains(":")) {
                int lastColonIdx = connectAfter.lastIndexOf(':');
                String potentialLabel = connectAfter.substring(0, lastColonIdx);
                String potentialPort = connectAfter.substring(lastColonIdx + 1);

                // Try to resolve the base label (without the port)
                String resolvedBase = session.resolveNodeReference(potentialLabel);
                if (allNodeIds.contains(resolvedBase)) {
                    // The node exists but the port is wrong - show a helpful error
                    List<String> validPorts = getValidPortsForNode(session, resolvedBase);
                    String nodeLabel = session.findNode(resolvedBase)
                        .map(n -> (String) n.get("label"))
                        .orElse(resolvedBase);
                    if (validPorts.isEmpty()) {
                        return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid port '" + potentialPort + "' for node '" + nodeLabel + "'. " +
                            "This node has no ports - connect directly: connect_after='" + nodeLabel + "'");
                    }
                    return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid port '" + potentialPort + "' for node '" + nodeLabel + "'. " +
                        "Valid ports: " + String.join(", ", validPorts) + ". " +
                        "Example: connect_after='" + nodeLabel + ":" + validPorts.get(0) + "'");
                }
            }

            // Build helpful error message with available nodes - use consistent format: "Label" (type)
            List<String> availableNodes = allNodeIds.stream()
                .map(id -> session.formatNodeRef(id, true))
                .sorted()
                .toList();

            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "connect_after references unknown node: '" + connectAfter + "' (resolved to: '" + resolvedId + "'). " +
                "Available nodes: " + String.join(", ", availableNodes) + ". " +
                "Use workflow(action='describe') to see current workflow structure.");
        }

        // If port specified, validate it exists on the node
        if (port != null) {
            var portError = validatePortExists(session, baseNodeId, port, connectAfter);
            if (portError != null) return portError;
        }

        return null; // Valid
    }

    /**
     * Check if a string is a valid port pattern.
     */
    private boolean isValidPort(String port) {
        // EdgeRefParser is the single source of truth for the valid-port set.
        // (The previous hand-rolled copy also silently OMITTED the loop ':iterate'
        // port, wrongly rejecting a connect to a loop's iterate branch.)
        return EdgeRefParser.isValidPort(port);
    }

    /**
     * Validate that a port exists on the specified node.
     */
    private ToolExecutionResult validatePortExists(WorkflowBuilderSession session, String nodeId, String port, String originalRef) {
        // Find the node
        var nodeOpt = session.findNode(nodeId);
        if (nodeOpt.isEmpty()) {
            return null; // Node validation already handled
        }

        Map<String, Object> node = nodeOpt.get();
        String nodeType = (String) node.get("type");

        // Classify node - check classifyOutputs
        if ("classify".equals(nodeType) && port.startsWith("category_")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outputs = (List<Map<String, Object>>) node.get("classifyOutputs");
            if (outputs == null || outputs.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Classify node '" + node.get("label") + "' has no category outputs defined.");
            }
            // Extract index from port (category_0 -> 0)
            try {
                int idx = Integer.parseInt(port.replace("category_", ""));
                if (idx < 0 || idx >= outputs.size()) {
                    return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid category port '" + port + "' for classify node '" + node.get("label") +
                        "'. Valid ports: category_0 to category_" + (outputs.size() - 1));
                }
            } catch (NumberFormatException e) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid category port format: '" + port + "'. Expected: category_N");
            }
        }

        // Fork node - check forkOutputs
        if ("fork".equals(nodeType) && port.startsWith("branch_")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outputs = (List<Map<String, Object>>) node.get("forkOutputs");
            if (outputs == null || outputs.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Fork node '" + node.get("label") + "' has no branch outputs defined.");
            }
            try {
                int idx = Integer.parseInt(port.replace("branch_", ""));
                if (idx < 0 || idx >= outputs.size()) {
                    return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid branch port '" + port + "' for fork node '" + node.get("label") +
                        "'. Valid ports: branch_0 to branch_" + (outputs.size() - 1));
                }
            } catch (NumberFormatException e) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid branch port format: '" + port + "'. Expected: branch_N");
            }
        }

        // Decision node - check decisionConditions
        if ("decision".equals(nodeType)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) node.get("decisionConditions");
            if (conditions == null || conditions.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Decision node '" + node.get("label") + "' has no conditions defined.");
            }
            // Validate port matches conditions (if, else, elseif_N)
            if (port.startsWith("elseif_")) {
                try {
                    int idx = Integer.parseInt(port.replace("elseif_", ""));
                    // elseif_0 corresponds to condition index 1 (after 'if')
                    if (idx < 0 || idx + 1 >= conditions.size()) {
                        return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid elseif port '" + port + "' for decision node '" + node.get("label") + "'.");
                    }
                } catch (NumberFormatException e) {
                    return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid elseif port format: '" + port + "'. Expected: elseif_N");
                }
            }
        }

        return null; // Valid
    }

    /**
     * Get the list of valid port names for a node based on its type and configuration.
     * Used for error messages when an invalid port is specified.
     */
    @SuppressWarnings("unchecked")
    protected List<String> getValidPortsForNode(WorkflowBuilderSession session, String nodeId) {
        var nodeOpt = session.findNode(nodeId);
        if (nodeOpt.isEmpty()) return List.of();

        Map<String, Object> node = nodeOpt.get();
        String nodeType = (String) node.get("type");
        if (nodeType == null) return List.of();

        List<String> ports = new ArrayList<>();
        switch (nodeType) {
            case "decision" -> {
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) node.get("decisionConditions");
                if (conditions == null || conditions.isEmpty()) return List.of();
                int elseifIdx = 0;
                for (int i = 0; i < conditions.size(); i++) {
                    Map<String, Object> cond = conditions.get(i);
                    String condType = (String) cond.get("type");
                    if ("if".equals(condType)) {
                        ports.add("if");
                    } else if ("else".equals(condType)) {
                        ports.add("else");
                    } else {
                        ports.add("elseif_" + elseifIdx);
                        elseifIdx++;
                    }
                }
            }
            case "switch" -> {
                List<Map<String, Object>> switchCases = (List<Map<String, Object>>) node.get("switchCases");
                if (switchCases == null || switchCases.isEmpty()) return List.of();
                int caseIdx = 0;
                for (Map<String, Object> c : switchCases) {
                    String caseType = (String) c.get("type");
                    if ("default".equals(caseType)) {
                        ports.add("default");
                    } else {
                        ports.add("case_" + caseIdx);
                        caseIdx++;
                    }
                }
            }
            case "fork" -> {
                List<Map<String, Object>> forkOutputs = (List<Map<String, Object>>) node.get("forkOutputs");
                if (forkOutputs == null || forkOutputs.isEmpty()) return List.of();
                for (int i = 0; i < forkOutputs.size(); i++) {
                    ports.add("branch_" + i);
                }
            }
            case "loop" -> {
                ports.add("body");
                ports.add("exit");
            }
            case "approval" -> {
                ports.add("approved");
                ports.add("rejected");
                ports.add("timeout");
            }
            case "option" -> {
                List<Map<String, Object>> optionChoices = (List<Map<String, Object>>) node.get("optionChoices");
                if (optionChoices == null || optionChoices.isEmpty()) return List.of();
                for (int i = 0; i < optionChoices.size(); i++) {
                    ports.add("choice_" + i);
                }
            }
            default -> {
                // Check agent-type nodes (classify, guardrail) stored in mcps list
                if (Boolean.TRUE.equals(node.get("isClassify"))) {
                    List<Map<String, Object>> classifyOutputs = (List<Map<String, Object>>) node.get("classifyOutputs");
                    if (classifyOutputs != null) {
                        for (int i = 0; i < classifyOutputs.size(); i++) {
                            ports.add("category_" + i);
                        }
                    }
                } else if (Boolean.TRUE.equals(node.get("isGuardrail"))) {
                    ports.add("pass");
                    ports.add("fail");
                }
            }
        }
        return ports;
    }

    // ==================== Position Calculation ====================

    /**
     * Estimate node width based on label length.
     */
    protected int estimateNodeWidth(String label) {
        if (label == null || label.isEmpty()) {
            return NODE_MIN_WIDTH;
        }
        int width = NODE_BASE_WIDTH + (label.length() * CHAR_WIDTH);
        return Math.max(width, NODE_MIN_WIDTH);
    }

    /**
     * Return empty position so the frontend auto-layout (Dagre) handles positioning.
     * The frontend's needsLayout() detects missing/empty positions and applies
     * a proper graph-based layout algorithm that respects edges and topology.
     */
    protected Map<String, Integer> calculatePosition(WorkflowBuilderSession session, NodeType nodeType) {
        return Map.of();
    }

    // ==================== Edge Creation ====================

    /**
     * Common setup: create edge if needed.
     * @return true if edge was created, false otherwise
     */
    protected boolean createEdgeIfNeeded(WorkflowBuilderSession session, String connectAfter, String nodeId) {
        if (connectAfter != null && !connectAfter.isBlank()) {
            createSimpleEdge(session, connectAfter, nodeId);
            return true;
        }
        return false;
    }

    /**
     * Create a simple edge between two nodes.
     */
    protected void createSimpleEdge(WorkflowBuilderSession session, String from, String to) {
        // CRITICAL: Resolve labels to actual node IDs (trigger:xxx, agent:xxx)
        // This ensures edges always use full node IDs that the validator can find
        String resolvedFrom = session.resolveNodeReference(from);
        String resolvedTo = session.resolveNodeReference(to);

        // Auto-assign port for branching nodes (fork, decision, switch, option, classify, guardrail)
        // When connect_after references a branching node without explicit port, assign next available port
        try {
            resolvedFrom = autoAssignBranchPort(session, resolvedFrom);
        } catch (BranchPortOverflowException e) {
            // Same contract as the already-wired-port skip below: the edge is NOT
            // created (never an out-of-range port) and the node itself stays.
            // KNOWN GAP: the add_node response still reports connection.status
            // "connected" (finalizeNode keys isOrphaned off connect_after being
            // blank, not off actual edge creation) - the orphan only surfaces at
            // the next validate/finish. Threading a skipped-edge signal into
            // every creator's response is a follow-up.
            log.warn("connect_after port overflow - skipping edge: {}", e.getMessage());
            return;
        }

        // Create final copies for lambda expression
        final String finalResolvedFrom = resolvedFrom;
        final String finalResolvedTo = resolvedTo;

        // Check for duplicate edge before adding
        boolean alreadyExists = session.getEdges().stream()
            .anyMatch(e -> finalResolvedFrom.equals(e.get("from")) &&
                          (finalResolvedTo.equals(e.get("to")) || finalResolvedTo.equals(e.get("target"))));

        if (alreadyExists) {
            log.debug("Edge already exists: {} -> {}, skipping duplicate", resolvedFrom, resolvedTo);
            return;
        }

        // One output PORT = one target node. If connect_after references a named
        // branch port (decision if/else, fork branch_N, classify category_N, ...)
        // that is ALREADY wired to a DIFFERENT target, skip - hanging a second
        // successor off one branch port is invalid. The node is still created (it
        // just stays unconnected, which validate() surfaces); connect the second
        // target via a Fork. Mirrors the executeConnect guard + EdgeValidator rule
        // so the connect_after path can't silently build the invalid edge.
        if (EdgeRefParser.splitPort(finalResolvedFrom)[1] != null) {
            boolean portTaken = session.getEdges().stream()
                .filter(e -> finalResolvedFrom.equals(e.get("from")))
                .anyMatch(e -> {
                    String existingTo = e.get("to") != null ? (String) e.get("to") : (String) e.get("target");
                    return existingTo != null && !existingTo.equals(finalResolvedTo);
                });
            if (portTaken) {
                log.warn("connect_after '{}' targets an already-wired port; skipping fan-out edge to {} "
                        + "(one port = one target - insert a Fork to run several nodes from this port)",
                        finalResolvedFrom, finalResolvedTo);
                return;
            }
        }

        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", resolvedFrom);
        edge.put("to", resolvedTo);
        session.getEdges().add(edge);

        log.debug("Created edge: {} -> {} (resolved from original: {} -> {})", resolvedFrom, resolvedTo, from, to);
    }

    /**
     * Resolve a target reference to a node ID.
     * Handles labels, prefixed IDs (mcp:, agent:), and # references.
     */
    protected String resolveTargetReference(WorkflowBuilderSession session, String targetRef) {
        if (targetRef.startsWith("mcp:") || targetRef.startsWith("agent:") || targetRef.startsWith("core:")) {
            return targetRef;
        }
        if (targetRef.startsWith("#")) {
            return session.resolveNodeReference(targetRef);
        }
        String resolved = session.resolveNodeReference(targetRef);
        if (session.nodeExists(resolved)) {
            return resolved;
        }
        // Endpoint did not resolve to a real node. Do NOT fabricate an
        // "mcp:<normalized>" id (same corruption class as the old set_plan
        // importer's fallback): return the canonically-resolved reference so the
        // caller sees the real (possibly still-unresolved) ref instead of a
        // silently-invented mcp: node that would only surface as a confusing
        // INVALID_EDGE_SOURCE later.
        return resolved;
    }

    // ==================== Finalization ====================

    /**
     * Common finalization: record action, save session, etc.
     * @return true if node is orphaned (not connected), false otherwise
     */
    protected boolean finalizeNode(WorkflowBuilderSession session, WorkflowBuilderSessionStore sessionStore,
                                   NodeType nodeType, String nodeId, Map<String, Object> nodeData, String connectAfter) {
        session.setLastAddedNodeId(nodeId);
        session.recordAction(nodeType.getActionName(), nodeId, nodeType.getPrefix(), new LinkedHashMap<>(nodeData));
        sessionStore.save(session);

        // Check if node is orphaned (created without connection)
        boolean isOrphaned = false;
        if (connectAfter == null || connectAfter.isBlank()) {
            // Node created without explicit connection
            // Check if it's not the first node (triggers are first and don't need connection)
            boolean isFirstNode = (nodeType == NodeType.TRIGGER && session.getTriggers().size() == 1)
                               || (nodeType == NodeType.MCP && session.getTriggers().isEmpty() && session.getMcps().size() == 1);
            if (!isFirstNode) {
                isOrphaned = true;
            }
        }

        return isOrphaned;
    }

    // ==================== Available Columns ====================

    /**
     * Get available columns from datasource.
     * Fetches FRESH data from the database to include any columns added via add_columns.
     * Falls back to session cache if datasource fetch fails.
     */
    protected List<String> getAvailableColumnsFromSession(WorkflowBuilderSession session, DataSourceClient dataSourceClient, String tenantId) {
        // Try to get fresh columns from datasource (to include columns added after trigger)
        try {
            String datasourceId = session.getTriggers().stream()
                .map(t -> t.get("datasource_id"))
                .filter(id -> id != null)
                .map(String::valueOf)
                .findFirst()
                .orElse(null);

            if (datasourceId != null) {
                Long dsId = Long.parseLong(datasourceId);
                DataSourceDto ds = dataSourceClient.getDataSource(dsId, tenantId);
                if (ds != null && ds.mappingSpec() != null && !ds.mappingSpec().isEmpty()) {
                    List<String> freshColumns = new ArrayList<>();
                    // Always include 'id' for datasource triggers
                    freshColumns.add("id");
                    freshColumns.addAll(ds.mappingSpec().keySet());
                    log.debug("Fetched fresh columns from datasource {}: {}", dsId, freshColumns);
                    return freshColumns;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch fresh columns from datasource, using cached schema: {}", e.getMessage());
        }

        // Fallback: use cached session schema
        List<String> columns = new ArrayList<>();
        for (var schema : session.getNodeSchemas().values()) {
            if ("trigger".equals(schema.getNodeType()) && schema.getOutputs() != null) {
                columns.addAll(schema.getOutputs().keySet());
            }
        }
        return columns;
    }

    // ==================== Branch Port Auto-Assignment ====================

    /**
     * Auto-assign port qualifier for branching nodes when creating edges via connect_after.
     * Mirrors the logic in WorkflowBuilderConnectionManager.autoAssignBranchPort().
     * Handles: fork (branch_N), decision (if/elseif_N/else), switch (case_N/default),
     *          option (choice_N), classify (category_N), guardrail (pass/fail).
     */
    @SuppressWarnings("unchecked")
    protected String autoAssignBranchPort(WorkflowBuilderSession session, String fromNodeId) {
        // Skip if already has a port qualifier (2+ colons)
        long colonCount = fromNodeId.chars().filter(c -> c == ':').count();
        if (colonCount >= 2) {
            return fromNodeId;
        }

        // Check core: nodes (fork, decision, switch, option)
        if (fromNodeId.startsWith("core:")) {
            for (Map<String, Object> core : session.getCores()) {
                String coreId = (String) core.get("id");
                if (!fromNodeId.equals(coreId)) continue;

                String type = (String) core.get("type");
                if (type == null) continue;

                if ("fork".equals(type)) {
                    List<Map<String, Object>> forkOutputs = (List<Map<String, Object>>) core.get("forkOutputs");
                    int branchCount = forkOutputs != null ? forkOutputs.size() : 0;
                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    int nextBranch = existing.size();
                    if (nextBranch < branchCount) {
                        String ported = fromNodeId + ":branch_" + nextBranch;
                        log.info("Auto-assigned fork port in connect_after: {} → {}", fromNodeId, ported);
                        return ported;
                    }
                    // Auto-extend the declaration so declared == wired stays true
                    // (mirrors WorkflowBuilderConnectionManager - the old code emitted
                    // branch_N past the declared count, which collapsed onto a
                    // declared branch at runtime).
                    String newForkPort = ForkMergeNodeCreator.expandForkOutputs(core, forkOutputs, nextBranch);
                    log.info("Auto-extended fork outputs in connect_after: {} now has {} branches → {}:{}",
                            fromNodeId, nextBranch + 1, fromNodeId, newForkPort);
                    return fromNodeId + ":" + newForkPort;
                }

                if ("decision".equals(type)) {
                    List<Map<String, Object>> conditions = (List<Map<String, Object>>) core.get("decisionConditions");
                    if (conditions == null || conditions.isEmpty()) break;
                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    int nextIdx = existing.size();
                    if (nextIdx == 0) return fromNodeId + ":if";
                    if (nextIdx < conditions.size()) {
                        Map<String, Object> cond = conditions.get(nextIdx);
                        String condType = (String) cond.get("type");
                        if ("else".equals(condType)) return fromNodeId + ":else";
                        return fromNodeId + ":elseif_" + (nextIdx - 1);
                    }
                    // Overflow: more connections than conditions - auto-expand
                    String newPort = DecisionNodeCreator.expandDecisionConditions(core, conditions, nextIdx);
                    log.info("Auto-expanded decision conditions in connect_after: {} → {}:{}", fromNodeId, fromNodeId, newPort);
                    return fromNodeId + ":" + newPort;
                }

                if ("switch".equals(type)) {
                    List<Map<String, Object>> switchCases = (List<Map<String, Object>>) core.get("switchCases");
                    if (switchCases == null || switchCases.isEmpty()) break;
                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    int nextIdx = existing.size();
                    if (nextIdx < switchCases.size()) {
                        Map<String, Object> switchCase = switchCases.get(nextIdx);
                        String caseType = (String) switchCase.get("type");
                        if ("default".equals(caseType)) return fromNodeId + ":default";
                        return fromNodeId + ":case_" + nextIdx;
                    }
                    // Overflow: a switch case carries a condition we cannot invent.
                    throw new BranchPortOverflowException(
                        "Cannot connect after '" + fromNodeId + "': all " + switchCases.size()
                        + " declared switch cases already have an outgoing edge, so no edge was created "
                        + "(the node is added but not connected). Add a case with workflow(action='modify', "
                        + "node='" + fromNodeId + "', switch_cases=[...]), then wire the node with action='connect'.");
                }

                if ("option".equals(type)) {
                    List<Map<String, Object>> optionChoices = (List<Map<String, Object>>) core.get("optionChoices");
                    if (optionChoices == null || optionChoices.isEmpty()) break; // mirror ConnectionManager: unconfigured option gets a port-less edge
                    int choiceCount = optionChoices.size();
                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    int nextIdx = existing.size();
                    if (nextIdx < choiceCount) {
                        String ported = fromNodeId + ":choice_" + nextIdx;
                        log.info("Auto-assigned option port in connect_after: {} → {}", fromNodeId, ported);
                        return ported;
                    }
                    // Overflow: an option choice is user-facing - we cannot invent one.
                    throw new BranchPortOverflowException(
                        "Cannot connect after '" + fromNodeId + "': all " + choiceCount
                        + " declared choices already have an outgoing edge, so no edge was created "
                        + "(the node is added but not connected). Add a choice with workflow(action='modify', "
                        + "node='" + fromNodeId + "', choices=[...]), then wire the node with action='connect'.");
                }

                if ("loop".equals(type)) {
                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    String ported = fromNodeId + ":" + (existing.isEmpty() ? "body" : "exit");
                    log.info("Auto-assigned loop port in connect_after: {} → {}", fromNodeId, ported);
                    return ported;
                }

                if ("approval".equals(type)) {
                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    String[] approvalPorts = {"approved", "rejected", "timeout"};
                    int nextIdx = Math.min(existing.size(), approvalPorts.length - 1);
                    String ported = fromNodeId + ":" + approvalPorts[nextIdx];
                    log.info("Auto-assigned approval port in connect_after: {} → {}", fromNodeId, ported);
                    return ported;
                }

                break;
            }
        }

        // Check agent: nodes (classify, guardrail)
        if (fromNodeId.startsWith("agent:")) {
            for (Map<String, Object> mcp : session.getMcps()) {
                String label = (String) mcp.get("label");
                if (label == null) continue;
                String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
                String expectedNodeId = "agent:" + normalizedLabel;
                if (!fromNodeId.equals(expectedNodeId)) continue;

                if (Boolean.TRUE.equals(mcp.get("isClassify"))) {
                    List<Map<String, Object>> classifyOutputs = (List<Map<String, Object>>) mcp.get("classifyOutputs");
                    if (classifyOutputs == null || classifyOutputs.isEmpty()) break;
                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    int nextIdx = existing.size();
                    if (nextIdx < classifyOutputs.size()) {
                        String ported = fromNodeId + ":category_" + nextIdx;
                        log.info("Auto-assigned classify port in connect_after: {} → {}", fromNodeId, ported);
                        return ported;
                    }
                    // Overflow: a classify category is what the LLM routes on - we cannot invent one.
                    throw new BranchPortOverflowException(
                        "Cannot connect after '" + fromNodeId + "': all " + classifyOutputs.size()
                        + " declared categories already have an outgoing edge, so no edge was created "
                        + "(the node is added but not connected). Add a category with workflow(action='modify', "
                        + "node='" + fromNodeId + "', categories=[...]), then wire the node with action='connect'.");
                }

                if (Boolean.TRUE.equals(mcp.get("isGuardrail"))) {
                    List<Map<String, Object>> existing = session.getOutgoingConnections(fromNodeId);
                    String ported = fromNodeId + ":" + (existing.isEmpty() ? "pass" : "fail");
                    log.info("Auto-assigned guardrail port in connect_after: {} → {}", fromNodeId, ported);
                    return ported;
                }

                break;
            }
        }

        return fromNodeId;
    }

    // ==================== DAG Traversal Utilities ====================

    /**
     * Find the trigger key(s) that are ancestors of a given node by walking backwards through edges.
     * Returns the set of trigger keys (e.g. "trigger:search") that the node is reachable from.
     * Used for same-DAG validation of action_mapping references.
     *
     * @param startNodeId resolved node ID to start traversal from (e.g. "mcp:process_data")
     * @param session the workflow builder session containing edges
     * @return set of trigger keys reachable by walking backward, or empty set if none found
     */
    public static Set<String> findDagTriggerKeys(String startNodeId, WorkflowBuilderSession session) {
        Set<String> visited = new HashSet<>();
        Set<String> triggerKeys = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;

            if (current.startsWith("trigger:")) {
                triggerKeys.add(current);
                continue;
            }

            for (Map<String, Object> edge : session.getEdges()) {
                String to = (String) edge.get("to");
                String from = (String) edge.get("from");
                if (to == null || from == null) continue;

                // Handle port-based edges (e.g. "core:check:if" → strip port for
                // matching) via the single source of truth (EdgeRefParser).
                String toBase = EdgeRefParser.splitPort(to)[0];

                if (current.equals(toBase) || current.equals(to)) {
                    String fromBase = EdgeRefParser.splitPort(from)[0];
                    queue.add(fromBase);
                }
            }
        }

        return triggerKeys;
    }

    /**
     * Check if action_mapping references triggers from a different DAG than the given start node.
     * Returns warnings (non-blocking) for any cross-DAG trigger references.
     * Skips triggers that are already flagged as non-existent (avoids double warnings).
     *
     * @param actionMapping the action_mapping to check
     * @param startNodeId resolved node ID to determine the DAG from
     * @param session the workflow builder session
     * @param alreadyFlaggedTriggers set of trigger labels already flagged as non-existent (to avoid double warnings)
     * @return list of warning strings, empty if all references are same-DAG
     */
    public static List<String> checkCrossDagReferences(Map<String, String> actionMapping,
                                                            String startNodeId,
                                                            WorkflowBuilderSession session,
                                                            Set<String> alreadyFlaggedTriggers) {
        Set<String> dagTriggerKeys = findDagTriggerKeys(startNodeId, session);
        if (dagTriggerKeys.isEmpty()) return List.of();

        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, String> entry : actionMapping.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.startsWith("__")) continue;

            String[] parts = value.split(":");
            if (parts.length < 3 || !"trigger".equals(parts[0])) continue;

            String triggerLabel = parts[1];
            // Skip triggers already flagged as non-existent to avoid double warnings
            if (alreadyFlaggedTriggers.contains(triggerLabel)) continue;

            String triggerKey = "trigger:" + triggerLabel;
            if (!dagTriggerKeys.contains(triggerKey)) {
                warnings.add(entry.getKey() + " -> " + value +
                    " (trigger '" + triggerLabel + "' belongs to a different DAG - " +
                    "interfaces can only fire triggers within their own DAG)");
            }
        }
        return warnings;
    }
}
