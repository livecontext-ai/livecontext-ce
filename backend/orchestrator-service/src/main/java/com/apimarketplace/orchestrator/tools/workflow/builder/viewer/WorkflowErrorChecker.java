package com.apimarketplace.orchestrator.tools.workflow.builder.viewer;

import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolInputSchema;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolParameter;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Checks workflows for errors and warnings.
 * Identifies orphan nodes, dead ends, missing triggers, and other issues.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowErrorChecker {

    private final ToolSchemaFetcher toolSchemaFetcher;

    /** Matches a single {{...}} placeholder block (non-greedy, one line). */
    private static final java.util.regex.Pattern PLACEHOLDER_BLOCK =
            java.util.regex.Pattern.compile("\\{\\{.*?\\}\\}");

    /**
     * Matches an unambiguous SpEL operator that would only make sense INSIDE {{...}}:
     * a ternary ({@code ? ... :}), a logical operator, or a comparison. Comparisons that
     * collide with HTML/markup ({@code <}, {@code >}) require surrounding whitespace so that
     * {@code <div>} and {@code </div>} are never flagged; the multi-char comparisons
     * ({@code <= >= == !=}) and logical operators are distinctive enough to match unspaced.
     */
    private static final java.util.regex.Pattern OUTSIDE_SPEL_OPERATOR =
            java.util.regex.Pattern.compile("\\?[^{}?]*:|&&|\\|\\||<=|>=|==|!=|\\s<\\s|\\s>\\s");

    /**
     * Result of error checking containing errors and warnings.
     */
    public record CheckResult(
        List<Map<String, Object>> errors,
        List<Map<String, Object>> warnings,
        boolean canCreate,
        String message
    ) {}

    /**
     * Checks the workflow for errors and warnings.
     *
     * @param session The workflow builder session
     * @return CheckResult with errors, warnings, and status
     */
    public CheckResult checkForErrors(WorkflowBuilderSession session) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();

        // Check for missing trigger
        if (session.getTriggers().isEmpty()) {
            errors.add(Map.of(
                "type", "MISSING_TRIGGER",
                "message", "Workflow has no trigger",
                "fix", "Add a trigger first (mandatory)"
            ));
        }

        // Check for missing steps (mcps, cores, agents, interfaces, or tables count as steps)
        if (session.getMcps().isEmpty() && session.getCores().isEmpty()
                && session.getInterfaces().isEmpty() && session.getTables().isEmpty()) {
            errors.add(Map.of(
                "type", "MISSING_STEPS",
                "message", "Workflow has no steps",
                "fix", "Add nodes using workflow(action='list_nodes') to see options"
            ));
        }

        // Check for orphan nodes
        List<String> orphans = session.findOrphanNodes();
        for (String orphan : orphans) {
            String ref = formatNodeRef(session, orphan);
            String logicalRef = session.getLogicalId(orphan);
            errors.add(Map.of(
                "type", "ORPHAN_NODE",
                "node", ref,
                "message", ref + " has no incoming connections - will never be reached",
                "fix", "workflow(action='connect', from='<source label>', to=" + logicalRef + ") OR workflow(action='remove', node=" + logicalRef + ")"
            ));
        }

        // Check for dead ends (warning only) - skip terminal nodes and split/loop bodies
        List<String> deadEnds = session.findDeadEndNodes();
        Set<String> splitLoopBody = findSplitLoopBodyNodes(session);
        for (String deadEnd : deadEnds) {
            if (splitLoopBody.contains(deadEnd)) continue;
            if (isNaturallyTerminal(deadEnd, session)) continue;
            String ref = formatNodeRef(session, deadEnd);
            addDeadEndWarning(warnings, deadEnd, ref, session);
        }

        // Check for steps without tool_id (stored as "id" by McpCreator, fallback "tool_id")
        for (Map<String, Object> step : session.getMcps()) {
            String stepToolId = (String) step.get("id");
            if (stepToolId == null) stepToolId = (String) step.get("tool_id");
            if (stepToolId == null && !Boolean.TRUE.equals(step.get("isAgent"))) {
                String label = (String) step.get("label");
                warnings.add(Map.of(
                    "type", "MISSING_TOOL",
                    "node", "mcp:" + label,
                    "message", "Step \"" + label + "\" has no tool_id",
                    "fix", "workflow(action='modify', node='" + label + "', params={tool_id: '<tool-uuid>'})"
                ));
            }
        }

        // Check that every MCP tool id actually exists in the catalog.
        // Mirrors the construction-time check in McpCreator + the import-time
        // check in WorkflowBuilderPlanExporter - workflow(action='validate')
        // is the last line of defense for plans loaded from DB.
        checkMcpToolIdsExist(session, errors);

        // Check for missing required parameters on MCP tool nodes
        checkMissingRequiredParams(session, errors);

        // Check core nodes for required fields (input, conditions, etc.)
        checkCoreNodeRequiredFields(session, errors);

        // Warn when a value-producing expression leaves SpEL operators OUTSIDE {{...}} (F15/F21)
        checkExpressionBoundaryWarnings(session, warnings);

        // Determine status
        String message;
        boolean canCreate;
        if (errors.isEmpty() && warnings.isEmpty()) {
            message = "No issues found! Workflow is valid.";
            canCreate = true;
        } else if (errors.isEmpty()) {
            message = warnings.size() + " warning(s) found. Workflow can be created.";
            canCreate = true;
        } else {
            message = errors.size() + " error(s) must be fixed before creating workflow.";
            canCreate = false;
        }

        return new CheckResult(errors, warnings, canCreate, message);
    }

    /**
     * Verifies every non-agent MCP node references a real catalog tool.
     * Skips agent nodes, sentinels, CRUD pseudo-IDs (own validator), and
     * nodes without a tool id (already flagged by another check). Treats
     * NOT_FOUND as a hard error and UNKNOWN (transient catalog outage) as
     * permissive - same policy as McpCreator and WorkflowBuilderPlanExporter.
     */
    private void checkMcpToolIdsExist(WorkflowBuilderSession session, List<Map<String, Object>> errors) {
        for (Map<String, Object> step : session.getMcps()) {
            if (Boolean.TRUE.equals(step.get("isAgent"))) continue;

            String toolId = (String) step.get("id");
            if (toolId == null) toolId = (String) step.get("tool_id");
            if (toolId == null || toolId.isBlank()) continue;

            if (ToolSchemaFetcher.isReservedToolSentinel(toolId)) continue;
            if (toolId.startsWith("crud/")) continue;

            ToolSchemaFetcher.ToolExistence existence = toolSchemaFetcher.checkToolExists(toolId);
            if (existence == ToolSchemaFetcher.ToolExistence.NOT_FOUND) {
                String label = (String) step.get("label");
                String ref = "mcp:" + (label != null ? LabelNormalizer.normalizeLabel(label) : "?");
                errors.add(Map.of(
                    "type", "TOOL_NOT_FOUND",
                    "node", ref,
                    "message", "Step \"" + (label != null ? label : "?") + "\" references tool id '"
                        + toolId + "' which does not exist in the catalog.",
                    "fix", "Run catalog(action='search') and use workflow(action='modify', node='"
                        + (label != null ? label : "?") + "', params={tool_id: '<real-uuid>'}). "
                        + "Do NOT invent UUIDs or reuse the same UUID across nodes for different tools."
                ));
            }
        }
    }

    /**
     * Checks MCP tool nodes for missing required parameters.
     * Skips agent nodes, nodes without a tool ID, and non-UUID tool IDs.
     */
    @SuppressWarnings("unchecked")
    private void checkMissingRequiredParams(WorkflowBuilderSession session, List<Map<String, Object>> errors) {
        for (Map<String, Object> step : session.getMcps()) {
            // Skip agent nodes - they don't have tool params
            if (Boolean.TRUE.equals(step.get("isAgent"))) {
                continue;
            }

            // Get tool ID (stored as "id", fallback to "tool_id")
            String toolId = (String) step.get("id");
            if (toolId == null) toolId = (String) step.get("tool_id");
            if (toolId == null || toolId.isBlank()) {
                continue;
            }

            // Fetch tool input schema (handles non-UUID IDs internally)
            Optional<ToolInputSchema> schemaOpt;
            try {
                schemaOpt = toolSchemaFetcher.fetchToolInputSchema(toolId);
            } catch (Exception e) {
                log.debug("Could not fetch input schema for tool {}: {}", toolId, e.getMessage());
                continue;
            }

            if (schemaOpt.isEmpty() || !schemaOpt.get().hasRequiredParameters()) {
                continue;
            }

            ToolInputSchema schema = schemaOpt.get();
            Map<String, ToolParameter> requiredParams = schema.getRequiredParameters();

            // Get provided params
            Map<String, Object> providedParams = Collections.emptyMap();
            Object paramsObj = step.get("params");
            if (paramsObj instanceof Map) {
                providedParams = (Map<String, Object>) paramsObj;
            }

            // Find missing required params
            Map<String, Object> finalProvidedParams = providedParams;
            List<String> missingParams = requiredParams.keySet().stream()
                    .filter(key -> !finalProvidedParams.containsKey(key))
                    .sorted()
                    .collect(Collectors.toList());

            if (!missingParams.isEmpty()) {
                String label = (String) step.get("label");

                // Build type hints for fix suggestion
                String paramHints = missingParams.stream()
                        .map(p -> {
                            ToolParameter tp = requiredParams.get(p);
                            String typeHint = tp != null && tp.type() != null ? tp.type() : "string";
                            return p + ": '<" + typeHint + ">'";
                        })
                        .collect(Collectors.joining(", "));

                errors.add(Map.of(
                        "type", "MISSING_REQUIRED_PARAMS",
                        "node", "mcp:" + label,
                        "message", "Step \"" + label + "\" is missing required parameters: " + missingParams,
                        "fix", "workflow(action='modify', node='" + label + "', params={" + paramHints + "})"
                ));
            }
        }
    }

    /**
     * Adds a dead-end warning with context-specific advice.
     */
    private void addDeadEndWarning(List<Map<String, Object>> warnings, String deadEnd, String ref,
                                   WorkflowBuilderSession session) {
        boolean isSplit = deadEnd.contains("split") || deadEnd.contains("for_each");
        boolean isDecision = deadEnd.contains("decision") || deadEnd.contains("if");

        if (isSplit) {
            warnings.add(Map.of(
                "type", "CONTROL_NODE_NO_EXIT",
                "severity", "HIGH",
                "node", ref,
                "message", "\u26a0\ufe0f Split " + ref + " has NO EXIT - this is suspicious!",
                "reason", "Split nodes should have exit connections to access exit variables",
                "fix", "Add a node with connect_after='" + session.getLogicalId(deadEnd) + "'"
            ));
        } else if (isDecision) {
            warnings.add(Map.of(
                "type", "CONTROL_NODE_NO_EXIT",
                "severity", "HIGH",
                "node", ref,
                "message", "\u26a0\ufe0f Decision " + ref + " has NO BRANCHES - this is suspicious!",
                "reason", "Decision nodes should have conditional branches configured",
                "fix", "Add nodes and connect to decision branches"
            ));
        } else {
            warnings.add(Map.of(
                "type", "DEAD_END",
                "node", ref,
                "message", ref + " has no outgoing connections (may be intentional)",
                "note", "This is OK if it's the final step"
            ));
        }
    }

    /**
     * Finds all nodes that are inside a split or loop body.
     * These nodes are expected to be terminal (no outgoing edges) - the split/loop handles completion.
     * Nodes reached via :exit ports are NOT body nodes (they come after the split/loop).
     */
    private Set<String> findSplitLoopBodyNodes(WorkflowBuilderSession session) {
        Set<String> bodyNodes = new HashSet<>();
        Set<String> splitLoopIds = new HashSet<>();

        for (Map<String, Object> core : session.getCores()) {
            String type = (String) core.get("type");
            if ("split".equals(type) || "loop".equals(type)) {
                String label = (String) core.get("label");
                if (label != null) {
                    splitLoopIds.add(LabelNormalizer.coreKey(label));
                }
            }
        }

        if (splitLoopIds.isEmpty()) return bodyNodes;

        // BFS from each split/loop, following non-exit edges
        for (String rootId : splitLoopIds) {
            Queue<String> queue = new LinkedList<>();
            for (Map<String, Object> edge : session.getOutgoingConnections(rootId)) {
                String from = (String) edge.get("from");
                if (from != null && from.contains(":exit")) continue;
                String to = (String) edge.get("to");
                if (to != null && !splitLoopIds.contains(to) && bodyNodes.add(to)) {
                    queue.add(to);
                }
            }
            while (!queue.isEmpty()) {
                String current = queue.poll();
                for (Map<String, Object> edge : session.getOutgoingConnections(current)) {
                    String to = (String) edge.get("to");
                    if (to != null && !splitLoopIds.contains(to) && bodyNodes.add(to)) {
                        queue.add(to);
                    }
                }
            }
        }
        return bodyNodes;
    }

    /**
     * Checks core nodes for required fields based on node type.
     * This ensures the agent builder validate action catches missing fields
     * (same checks as frontend NodeConfigurationRule and backend node execute()).
     */
    @SuppressWarnings("unchecked")
    private void checkCoreNodeRequiredFields(WorkflowBuilderSession session, List<Map<String, Object>> errors) {
        Set<String> dataProcessingTypes = Set.of("filter", "sort", "limit", "remove_duplicates", "summarize");

        for (Map<String, Object> cn : session.getCores()) {
            String type = (String) cn.get("type");
            String label = (String) cn.get("label");
            if (type == null || label == null) continue;
            String ref = "core:" + LabelNormalizer.normalizeLabel(label);

            // Data processing: input required
            if (dataProcessingTypes.contains(type) && !hasCoreInput(cn, type)) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "'" + label + "' requires an input expression",
                    "fix", "workflow(action='modify', node='" + label + "', params={input: '{{core:step.output.items}}'})"));
            }

            // XML: value required
            if ("xml".equals(type) && !hasConfigField(cn, "xml", "value")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "XML '" + label + "' requires input data",
                    "fix", "workflow(action='modify', node='" + label + "', params={value: '{{...}}'})"));
            }

            // Compression: value required
            if ("compression".equals(type) && !hasConfigField(cn, "compression", "value")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "Compression '" + label + "' requires input data",
                    "fix", "workflow(action='modify', node='" + label + "', params={value: '{{...}}'})"));
            }

            // ConvertToFile: value required
            if ("convert_to_file".equals(type) && !hasConfigField(cn, "convertToFile", "value")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "Convert to File '" + label + "' requires data source",
                    "fix", "workflow(action='modify', node='" + label + "', params={value: '{{...}}'})"));
            }

            // ExtractFromFile: value required
            if ("extract_from_file".equals(type) && !hasConfigField(cn, "extractFromFile", "value")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "Extract from File '" + label + "' requires file content or URL",
                    "fix", "workflow(action='modify', node='" + label + "', params={value: '{{...}}'})"));
            }

            // CompareDatasets: inputA and inputB required
            if ("compare_datasets".equals(type)) {
                if (!hasConfigField(cn, "compareDatasets", "inputA"))
                    errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                        "message", "Compare Datasets '" + label + "' requires Dataset A",
                        "fix", "workflow(action='modify', node='" + label + "', params={inputA: '{{...}}'})"));
                if (!hasConfigField(cn, "compareDatasets", "inputB"))
                    errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                        "message", "Compare Datasets '" + label + "' requires Dataset B",
                        "fix", "workflow(action='modify', node='" + label + "', params={inputB: '{{...}}'})"));
            }

            // RSS: url required
            if ("rss".equals(type) && !hasConfigField(cn, "rss", "url")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "RSS '" + label + "' requires a feed URL",
                    "fix", "workflow(action='modify', node='" + label + "', params={url: 'https://...'})"));
            }

            // Code: code required
            if ("code".equals(type) && !hasConfigField(cn, "code", "code")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "Code '" + label + "' requires code content",
                    "fix", "workflow(action='modify', node='" + label + "', params={code: '...'})"));
            }

            // HttpRequest: url required
            if ("http_request".equals(type) && !hasConfigField(cn, "httpRequest", "url")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "HTTP Request '" + label + "' requires a URL",
                    "fix", "workflow(action='modify', node='" + label + "', params={url: 'https://...'})"));
            }

            // DownloadFile: url required
            if ("download_file".equals(type) && !hasConfigField(cn, "download", "url")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "Download File '" + label + "' requires a URL",
                    "fix", "workflow(action='modify', node='" + label + "', params={url: 'https://...'})"));
            }

            // SendEmail: toEmail and subject required
            if ("send_email".equals(type)) {
                if (!hasConfigField(cn, "sendEmail", "toEmail"))
                    errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                        "message", "Send Email '" + label + "' requires a recipient (toEmail)",
                        "fix", "workflow(action='modify', node='" + label + "', params={toEmail: '...'})"));
                if (!hasConfigField(cn, "sendEmail", "subject"))
                    errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                        "message", "Send Email '" + label + "' requires a subject",
                        "fix", "workflow(action='modify', node='" + label + "', params={subject: '...'})"));
            }

            // EmailInbox: any action other than 'none' needs messageUid; move also needs targetFolder
            if ("email_inbox".equals(type)) {
                String action = configString(cn, "emailInbox", "action");
                if (action != null && !action.isBlank() && !"none".equals(action) && !"list_folders".equals(action)) {
                    if (!hasConfigField(cn, "emailInbox", "messageUid"))
                        errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                            "message", "Email Inbox '" + label + "' action '" + action + "' requires messageUid",
                            "fix", "workflow(action='modify', node='" + label + "', params={messageUid: '{{...uid}}'})"));
                    if ("move".equals(action) && !hasConfigField(cn, "emailInbox", "targetFolder"))
                        errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                            "message", "Email Inbox '" + label + "' move action requires targetFolder",
                            "fix", "workflow(action='modify', node='" + label + "', params={targetFolder: 'Archive'})"));
                }
            }

            // Split: list required
            if ("split".equals(type) && !hasNonBlank(cn, "list")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "Split '" + label + "' requires a list expression",
                    "fix", "workflow(action='modify', node='" + label + "', params={list: '{{...}}'})"));
            }

            // Decision: conditions required (already in CoreValidator but needed here too)
            if ("decision".equals(type)) {
                List<?> conds = (List<?>) cn.get("decisionConditions");
                if (conds == null || conds.isEmpty())
                    errors.add(Map.of("type", "MISSING_CONFIG", "node", ref,
                        "message", "Decision '" + label + "' must have at least one condition",
                        "fix", "Add conditions to the decision node"));
            }

            // Loop: condition required
            if ("loop".equals(type) && cn.get("loopCondition") == null) {
                errors.add(Map.of("type", "MISSING_CONFIG", "node", ref,
                    "message", "Loop '" + label + "' must have a loopCondition",
                    "fix", "workflow(action='modify', node='" + label + "', params={loopCondition: '...'})"));
            }

            // Switch: expression required
            if ("switch".equals(type) && !hasNonBlank(cn, "switchExpression")) {
                errors.add(Map.of("type", "MISSING_CONFIG", "node", ref,
                    "message", "Switch '" + label + "' must have a switchExpression",
                    "fix", "workflow(action='modify', node='" + label + "', params={switchExpression: '...'})"));
            }

            // Aggregate: fields required
            if ("aggregate".equals(type)) {
                Map<String, Object> config = (Map<String, Object>) cn.get("aggregate");
                if (config == null || !(config.get("fields") instanceof List<?> f) || f.isEmpty())
                    errors.add(Map.of("type", "MISSING_CONFIG", "node", ref,
                        "message", "Aggregate '" + label + "' requires at least one field",
                        "fix", "workflow(action='modify', node='" + label + "', params={fields: [{label: '...', expression: '...'}]})"));
            }

            // Response: message required
            if ("response".equals(type) && !hasConfigField(cn, "response", "message")) {
                errors.add(Map.of("type", "MISSING_INPUT", "node", ref,
                    "message", "Response '" + label + "' requires a message",
                    "fix", "workflow(action='modify', node='" + label + "', params={message: '...'})"));
            }
        }
    }

    /**
     * Warns when a VALUE-producing expression embeds a {{...}} placeholder but leaves a
     * comparison/ternary/logical operator OUTSIDE the braces (F15/F21). SpEL is evaluated
     * only inside {{...}} for these fields, so the operator stays literal text and the field
     * silently resolves to the wrong value (a switch matches no case and falls through to
     * default; a transform/aggregate field stores the literal string instead of the computed
     * value) - with no error raised.
     *
     * <p>Scoped to switch / transform / aggregate / set (value-producing fields). Decision and loop
     * CONDITIONS are deliberately NOT checked: they re-evaluate the fully-resolved string as
     * SpEL, so {@code {{x}} > 5} is valid there. This is a non-blocking warning - the heuristic
     * is tuned (pure {{...}} and plain separators like {@code -}/{@code _} never fire) to avoid
     * false positives on composite keys, template strings and URLs.
     */
    private void checkExpressionBoundaryWarnings(WorkflowBuilderSession session,
                                                 List<Map<String, Object>> warnings) {
        for (Map<String, Object> cn : session.getCores()) {
            String type = (String) cn.get("type");
            String label = (String) cn.get("label");
            if (type == null || label == null) continue;
            String ref = "core:" + LabelNormalizer.normalizeLabel(label);

            switch (type) {
                case "switch" -> {
                    if (cn.get("switchExpression") instanceof String expr && expressionEscapesPlaceholder(expr)) {
                        warnings.add(boundaryWarning(ref, "Switch", label, "switchExpression",
                                "the switch will match no case and silently fall through to default"));
                    }
                }
                // Value-producing fields that store a list of {name/label, expression/value} entries.
                case "transform" -> warnEntries(warnings, ref, "Transform", label, "mappings",
                        valueExpressions(cn, "transform", "mappings"));
                case "aggregate" -> warnEntries(warnings, ref, "Aggregate", label, "fields",
                        valueExpressions(cn, "aggregate", "fields"));
                case "set" -> warnEntries(warnings, ref, "Set", label, "assignments",
                        valueExpressions(cn, "set", "assignments"));
                default -> { /* decision/loop conditions re-evaluate the resolved string - unaffected */ }
            }
        }
    }

    private void warnEntries(List<Map<String, Object>> warnings, String ref, String nodeTypeName,
                             String label, String paramKey, List<Map.Entry<String, String>> entries) {
        for (Map.Entry<String, String> entry : entries) {
            if (expressionEscapesPlaceholder(entry.getValue())) {
                warnings.add(boundaryWarning(ref, nodeTypeName, label, paramKey,
                        "field '" + entry.getKey() + "' will store the literal text instead of the computed value"));
            }
        }
    }

    /**
     * Detects the expression-boundary trap: a non-pure {{...}} string whose text OUTSIDE the
     * placeholder blocks still carries a SpEL comparison/ternary/logical operator. Returns false
     * for a single pure {{...}} expression (correctly evaluated whole) and for strings with no
     * placeholder or only plain separators.
     */
    private boolean expressionEscapesPlaceholder(String expr) {
        if (expr == null) return false;
        String trimmed = expr.trim();
        if (!trimmed.contains("{{")) return false;
        // A single pure {{...}} expression is correct - the whole expression is evaluated.
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}") && trimmed.indexOf("{{", 2) == -1) return false;
        // Inspect only the text OUTSIDE the {{...}} blocks.
        String outside = PLACEHOLDER_BLOCK.matcher(trimmed).replaceAll(" ");
        return OUTSIDE_SPEL_OPERATOR.matcher(outside).find();
    }

    /**
     * Extracts (displayName, expression) pairs from a typed config block's entry list, tolerating
     * the two field-name conventions in use: transform/aggregate store {label, expression}, set
     * stores {name, value}. Entries whose expression is missing or non-String are skipped, so a
     * malformed plan never throws here.
     */
    private List<Map.Entry<String, String>> valueExpressions(Map<String, Object> cn, String configKey, String listKey) {
        Object config = cn.get(configKey);
        if (!(config instanceof Map<?, ?> m) || !(m.get(listKey) instanceof List<?> list)) {
            return List.of();
        }
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> entry)) continue;
            Object exprObj = entry.get("expression") != null ? entry.get("expression") : entry.get("value");
            if (!(exprObj instanceof String expr)) continue;
            Object nameObj = entry.get("label") != null ? entry.get("label") : entry.get("name");
            out.add(Map.entry(nameObj != null ? String.valueOf(nameObj) : "?", expr));
        }
        return out;
    }

    private Map<String, Object> boundaryWarning(String ref, String nodeTypeName, String label,
                                                String paramKey, String consequence) {
        return Map.of(
            "type", "EXPRESSION_NOT_EVALUATED",
            "severity", "HIGH",
            "node", ref,
            "message", nodeTypeName + " '" + label + "' mixes a {{...}} reference with a comparison or ternary "
                + "OUTSIDE the braces. SpEL is evaluated only INSIDE {{...}} for this field, so the operator stays "
                + "literal text and " + consequence + " - with no error.",
            "fix", "Wrap the WHOLE expression in one {{...}}. workflow(action='modify', node='" + label
                + "', params={" + paramKey + ": '{{<your full expression>}}'}). "
                + "Example: '{{n}} < 5 ? a : b' becomes '{{n < 5 ? a : b}}'."
        );
    }

    private boolean hasCoreInput(Map<String, Object> cn, String type) {
        // Check params.input
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) cn.get("params");
        if (params != null && params.get("input") instanceof String s && !s.isBlank()) return true;
        // Check typed config input (nested config nodes store input inside their config object)
        String configKey = switch (type) {
            case "remove_duplicates" -> "removeDuplicates";
            case "summarize" -> "summarize";
            case "limit" -> "limit";
            case "filter" -> "filter";
            case "sort" -> "sort";
            default -> null;
        };
        if (configKey != null) {
            Object config = cn.get(configKey);
            if (config instanceof Map<?, ?> m && m.get("input") instanceof String s && !s.isBlank()) return true;
        }
        // Check top-level input
        return cn.get("input") instanceof String s && !s.isBlank();
    }

    @SuppressWarnings("unchecked")
    private boolean hasConfigField(Map<String, Object> cn, String configKey, String field) {
        Object config = cn.get(configKey);
        if (config instanceof Map<?, ?> m) {
            Object val = m.get(field);
            if (val instanceof String s) return !s.isBlank();
            return val != null;
        }
        return false;
    }

    private String configString(Map<String, Object> cn, String configKey, String field) {
        Object config = cn.get(configKey);
        if (config instanceof Map<?, ?> m) {
            Object val = m.get(field);
            return val != null ? String.valueOf(val) : null;
        }
        return null;
    }

    private boolean hasNonBlank(Map<String, Object> cn, String key) {
        return cn.get(key) instanceof String s && !s.isBlank();
    }

    /**
     * Checks if a node is naturally terminal (no outgoing connections expected).
     * These nodes should NOT produce dead-end warnings.
     */
    private boolean isNaturallyTerminal(String nodeId, WorkflowBuilderSession session) {
        // Triggers without successors are normal (unused triggers in multi-trigger workflows)
        if (nodeId.startsWith("trigger:")) return true;

        // Interface nodes are display endpoints
        if (nodeId.startsWith("interface:")) return true;

        // Check core node type for terminal types
        for (Map<String, Object> cn : session.getCores()) {
            String type = (String) cn.get("type");
            String label = (String) cn.get("label");
            if (label == null || type == null) continue;
            String coreId = "core:" + LabelNormalizer.normalizeLabel(label);
            if (coreId.equals(nodeId)) {
                // Terminal nodes: no outgoing connections expected
                return Set.of("exit", "end", "response", "respond_to_webhook", "send_email", "email_inbox", "stop_on_error")
                        .contains(type);
            }
        }
        return false;
    }

    /**
     * Formats a node reference with its label.
     */
    private String formatNodeRef(WorkflowBuilderSession session, String nodeId) {
        return session.formatNodeRefWithLabel(nodeId);
    }
}
