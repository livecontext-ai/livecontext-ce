package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates workflow steps (MCP and Agent nodes).
 *
 * Rules enforced:
 * - At least one step or agent required
 * - Step must have a label
 * - Non-agent steps must have a tool ID
 * - Required inputs must be provided
 * - CRUD steps must reference valid tables
 * - Input column references must point to valid columns
 * - Agents should have prompt or tools
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepValidator implements WorkflowValidator {

    // Mirrors TemplateEngine.EXPRESSION_PATTERN: allow `}` inside SpEL string literals so
    // {{json('{...}')}} validates correctly.
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{((?:'(?:[^'\\\\]|\\\\.)*'|[^}|])+?)(?:\\|[^}]*)?\\}\\}");

    private final ToolSchemaFetcher toolSchemaFetcher;
    private final DataSourceClient dataSourceClient;
    private final NodeLibraryService nodeLibraryService;

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        validateSteps(session, result);
    }

    private void validateSteps(WorkflowBuilderSession session, ValidationResult result) {
        // Rule: At least one step, agent, core, interface, or table required
        if (session.getMcps().isEmpty() && session.getCores().isEmpty()
                && session.getInterfaces().isEmpty() && session.getTables().isEmpty()) {
            result.addError("MISSING_MCPS", "mcps",
                    "Workflow must have at least one step, agent, core, interface, or table node.");
        }

        for (Map<String, Object> step : session.getMcps()) {
            validateMcpOrAgentStep(step, session, result);
        }

        // Also validate table (CRUD) nodes - stored in tables[] with table: prefix
        for (Map<String, Object> step : session.getTables()) {
            validateTableStep(step, session, result);
        }
    }

    private void validateMcpOrAgentStep(Map<String, Object> step, WorkflowBuilderSession session, ValidationResult result) {
        String label = (String) step.get("label");
        String nodeId = getStepNodeId(step);

        // Rule: Label required
        if (label == null || label.isBlank()) {
            result.addError("MISSING_LABEL", nodeId, "Step must have a label.");
            return;
        }

        // Rule: tool_id (now stored as 'id') required for non-agent steps
        Boolean isAgent = (Boolean) step.get("isAgent");
        String toolId = (String) step.get("id");
        if (toolId == null) {
            toolId = (String) step.get("toolId"); // Legacy fallback
        }

        if (isAgent == null || !isAgent) {
            if (toolId == null) {
                result.addError("MISSING_TOOL_ID", nodeId,
                        "Step '" + label + "' requires id (tool ID). Use catalog(action='search') to find tools.");
            } else {
                // Rule: Tool ID must reference a real catalog tool. Skip reserved
                // sentinels and CRUD pseudo-IDs (those have their own validation).
                // Mirrors the check applied at construction time in McpCreator and
                // WorkflowBuilderPlanExporter - workflow(action='validate') is the
                // last line of defense for plans loaded from DB or imported elsewhere.
                if (!ToolSchemaFetcher.isReservedToolSentinel(toolId) && !toolId.startsWith("crud/")) {
                    ToolSchemaFetcher.ToolExistence existence = toolSchemaFetcher.checkToolExists(toolId);
                    if (existence == ToolSchemaFetcher.ToolExistence.NOT_FOUND) {
                        result.addError("TOOL_NOT_FOUND", nodeId,
                                "Step '" + label + "' references tool id '" + toolId
                                + "' which does not exist in the catalog. "
                                + "Use catalog(action='search') and copy the exact `id` from the result.");
                    }
                    // UNKNOWN (transient catalog outage) is permissive - same policy as construction time.
                }

                // Rule: Required inputs must be provided
                validateStepRequiredInputs(step, nodeId, label, toolId, result);

                // Rule: CRUD operations must reference valid tables
                if (toolId.startsWith("crud/")) {
                    validateCrudStepDataSource(step, nodeId, label, session, result);
                }
            }
        }

        // Rule: Input references must point to valid columns
        validateInputColumnReferences(step, nodeId, label, session, result);

        // Rule: Agents need prompt or tools
        if (isAgent != null && isAgent) {
            if (step.get("prompt") == null && step.get("tools") == null) {
                result.addWarning("AGENT_NO_CONFIG", nodeId,
                        "Agent '" + label + "' has no prompt or tools defined.");
            }

            // Rule: Guardrail/Classify required params from node_type_documentation
            Boolean isGuardrail = (Boolean) step.get("isGuardrail");
            Boolean isClassify = (Boolean) step.get("isClassify");
            if (Boolean.TRUE.equals(isGuardrail)) {
                validateAgentRequiredParams(step, nodeId, label, "guardrail", result);
            } else if (Boolean.TRUE.equals(isClassify)) {
                validateAgentRequiredParams(step, nodeId, label, "classify", result);
            }
        }
    }

    private void validateTableStep(Map<String, Object> step, WorkflowBuilderSession session, ValidationResult result) {
        String label = (String) step.get("label");
        String nodeId = "table:" + WorkflowBuilderSession.normalizeLabel(label);

        // Rule: Label required
        if (label == null || label.isBlank()) {
            result.addError("MISSING_LABEL", nodeId, "Table step must have a label.");
            return;
        }

        String toolId = (String) step.get("id");
        if (toolId != null && toolId.startsWith("crud/")) {
            validateCrudStepDataSource(step, nodeId, label, session, result);
            validateCrudRequiredParams(step, nodeId, label, toolId, result);
        }

        // Rule: Input references must point to valid columns
        validateInputColumnReferences(step, nodeId, label, session, result);
    }

    /**
     * Validate required parameters for CRUD table nodes using node_type_documentation.
     * Checks that params marked as required in the doc are present in the step's params.
     * Skips table_id since it's validated separately by validateCrudStepDataSource.
     */
    @SuppressWarnings("unchecked")
    private void validateCrudRequiredParams(Map<String, Object> step, String nodeId, String label,
                                            String toolId, ValidationResult result) {
        // Map crud/ toolId to doc type
        String docType = switch (toolId) {
            case "crud/create-row" -> "insert_row";
            case "crud/read-row" -> "get_rows";
            case "crud/update-row" -> "update_row";
            case "crud/delete-row" -> "delete_row";
            case "crud/find-rows" -> "find_rows";
            case "crud/create-column" -> "create_column";
            default -> null;
        };
        if (docType == null) return;

        Optional<NodeTypeDocumentationEntity> nodeOpt = nodeLibraryService.findByType(docType);
        if (nodeOpt.isEmpty()) return;

        Map<String, Object> schemaParams = nodeOpt.get().getParameters();
        if (schemaParams == null || schemaParams.isEmpty()) return;

        for (Map.Entry<String, Object> entry : schemaParams.entrySet()) {
            String paramName = entry.getKey();
            if (!(entry.getValue() instanceof Map)) continue;
            // table_id is validated separately by validateCrudStepDataSource
            if ("table_id".equals(paramName)) continue;

            Map<String, Object> paramSchema = (Map<String, Object>) entry.getValue();
            boolean required = Boolean.TRUE.equals(paramSchema.get("required"));

            if (required && !hasCrudParam(step, paramName, docType)) {
                String desc = (String) paramSchema.get("description");
                String hint = desc != null ? " (" + desc + ")" : "";
                result.addError("CRUD_MISSING_PARAM", nodeId,
                    "'" + label + "' requires '" + paramName + "'" + hint +
                    ". Fix: workflow(action='modify', node='" + label + "', params={" + paramName + ": ...})");
            }
        }
    }

    /**
     * Check whether a CRUD required param is present on the step, regardless of which
     * storage path produced the node. CRUD fields move between paths depending on the
     * code flow that created them:
     *
     * <ul>
     *   <li>{@code step.crud.<name>} - canonical, written by TableCreator.addCrudParameters
     *       on add_node</li>
     *   <li>{@code step.crud.rows[0].<name>} - used by WorkflowBuilderTableOperations
     *       which wraps insert_row columns as {@code rows: [{columns: {...}}]}</li>
     *   <li>{@code step.params.<name>} - legacy / agent that bypasses the creator</li>
     *   <li>{@code step.<name>} - top-level, produced by workflow(action='modify')
     *       because WorkflowBuilderModifier.harmonizeParams has no CRUD routing and
     *       NodeFieldMerger lands unknown keys at the top of the node map</li>
     * </ul>
     *
     * If any of these paths contains the param, we accept it. The execution engine
     * looks at all four too - what matters is that the information exists somewhere
     * on the step, not that it's in one particular slot.
     */
    @SuppressWarnings("unchecked")
    private boolean hasCrudParam(Map<String, Object> step, String paramName, String docType) {
        // 1. Top-level on the step (modify path)
        if (step.get(paramName) != null) return true;

        // 2. step.params.<name> (legacy / direct agent writes)
        Object paramsObj = step.get("params");
        if (paramsObj instanceof Map<?, ?> params && params.get(paramName) != null) return true;

        // 3. step.crud.<name> (canonical add_node path)
        Object crudObj = step.get("crud");
        if (crudObj instanceof Map<?, ?> crud) {
            if (crud.get(paramName) != null) return true;

            // 4. step.crud.rows[0].<name> - insert_row path from WorkflowBuilderTableOperations
            // wraps columns inside rows[]. Only makes sense for insert_row + columns.
            if ("insert_row".equals(docType) && "columns".equals(paramName)) {
                Object rowsObj = crud.get("rows");
                if (rowsObj instanceof List<?> rows && !rows.isEmpty()
                        && rows.get(0) instanceof Map<?, ?> firstRow
                        && firstRow.get("columns") != null) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Validate required parameters for guardrail/classify agents using node_type_documentation.
     * These agent subtypes have required params (e.g., guardrail needs 'input' and 'rules')
     * that regular agent validation (prompt/tools check) doesn't cover.
     */
    @SuppressWarnings("unchecked")
    private void validateAgentRequiredParams(Map<String, Object> step, String nodeId, String label,
                                             String docType, ValidationResult result) {
        Optional<NodeTypeDocumentationEntity> nodeOpt = nodeLibraryService.findByType(docType);
        if (nodeOpt.isEmpty()) return;

        Map<String, Object> schemaParams = nodeOpt.get().getParameters();
        if (schemaParams == null || schemaParams.isEmpty()) return;

        // Guardrail stores 'input' as 'content' internally; check both
        Set<String> skipParams = Set.of("label");

        for (Map.Entry<String, Object> entry : schemaParams.entrySet()) {
            String paramName = entry.getKey();
            if (!(entry.getValue() instanceof Map)) continue;
            if (skipParams.contains(paramName)) continue;

            Map<String, Object> paramSchema = (Map<String, Object>) entry.getValue();
            boolean required = Boolean.TRUE.equals(paramSchema.get("required"));

            if (required) {
                // Check the step directly (guardrail stores 'input' as 'content')
                boolean found = step.containsKey(paramName)
                    || ("input".equals(paramName) && step.containsKey("content"));
                if (!found) {
                    String desc = (String) paramSchema.get("description");
                    String hint = desc != null ? " - " + desc : "";
                    result.addError("AGENT_MISSING_PARAM", nodeId,
                        "'" + label + "' (" + docType + ") requires '" + paramName + "'" + hint +
                        ". Fix: workflow(action='modify', node='" + label + "', params={" + paramName + ": ...})");
                }
            }
        }
    }

    /**
     * Validate that all required inputs for a step are provided.
     */
    @SuppressWarnings("unchecked")
    private void validateStepRequiredInputs(Map<String, Object> step, String nodeId, String label,
                                            String toolId, ValidationResult result) {
        Optional<ToolSchemaFetcher.ToolInputSchema> inputSchemaOpt = toolSchemaFetcher.fetchToolInputSchema(toolId);
        if (inputSchemaOpt.isEmpty() || !inputSchemaOpt.get().hasRequiredParameters()) {
            return; // No required inputs or couldn't fetch schema
        }

        var inputSchema = inputSchemaOpt.get();
        Map<String, Object> providedParams = (Map<String, Object>) step.get("params");
        Set<String> providedKeys = providedParams != null ? providedParams.keySet() : Set.of();

        List<String> missingRequired = inputSchema.getRequiredParameters().keySet().stream()
                .filter(param -> !providedKeys.contains(param))
                .toList();

        if (!missingRequired.isEmpty()) {
            result.addError("MISSING_REQUIRED_INPUTS", nodeId,
                    "Step '" + label + "' is missing required params: " + missingRequired +
                    ". Use workflow(action='modify', node='" + label + "', params={...}) to add them.");
        }
    }

    /**
     * Validate that CRUD operations reference valid tables (dataSourceId must exist).
     */
    @SuppressWarnings("unchecked")
    private void validateCrudStepDataSource(Map<String, Object> step, String nodeId, String label,
                                            WorkflowBuilderSession session, ValidationResult result) {
        Long dataSourceId = extractDataSourceId(step);

        if (dataSourceId == null) {
            result.addError("CRUD_MISSING_DATASOURCE", nodeId,
                    "CRUD step '" + label + "' requires dataSourceId (table_id). " +
                    "SOLUTION:\n" +
                    "1. List tables: table(action='list')\n" +
                    "2. Use existing table_id: workflow(action='modify', node='" + label + "', params={dataSourceId: <table-id>})\n" +
                    "OR create table first: table(action='create', name='...', columns=[...])");
            return;
        }

        // Validate that the table exists for this tenant
        String tenantId = session.getTenantId();
        DataSourceDto ds = dataSourceClient.findByIdAndTenantId(dataSourceId, tenantId);

        if (ds == null) {
            result.addError("CRUD_INVALID_DATASOURCE", nodeId,
                    "CRUD step '" + label + "' references table_id=" + dataSourceId + " which does not exist. " +
                    "SOLUTION:\n" +
                    "1. List available tables: table(action='list')\n" +
                    "2. Use valid table_id from list\n" +
                    "OR create table first: table(action='create', name='...', columns=[...])");
        } else {
            log.debug("✓ CRUD step '{}' references valid table: {} (id={})",
                     label, ds.name(), dataSourceId);
        }
    }

    /**
     * Extract dataSourceId from step (tries multiple locations for compatibility).
     */
    @SuppressWarnings("unchecked")
    private Long extractDataSourceId(Map<String, Object> step) {
        // Try step level
        Long dataSourceId = toLongOrNull(step.get("dataSourceId"));
        if (dataSourceId != null) return dataSourceId;

        dataSourceId = toLongOrNull(step.get("datasource_id"));
        if (dataSourceId != null) return dataSourceId;

        // Try params level
        Map<String, Object> params = (Map<String, Object>) step.get("params");
        if (params != null) {
            dataSourceId = toLongOrNull(params.get("dataSourceId"));
            if (dataSourceId != null) return dataSourceId;

            dataSourceId = toLongOrNull(params.get("datasource_id"));
            if (dataSourceId != null) return dataSourceId;

            dataSourceId = toLongOrNull(params.get("table_id"));
            if (dataSourceId != null) return dataSourceId;
        }

        return null;
    }

    /**
     * Convert object to Long, return null if not a valid number.
     */
    private Long toLongOrNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        if (obj instanceof String) {
            try {
                return Long.parseLong((String) obj);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Validate that param column references (e.g., {{column_name}}) point to valid trigger columns.
     * Supports both simple {{column}} and full {{trigger:label.column}} syntax.
     */
    @SuppressWarnings("unchecked")
    private void validateInputColumnReferences(Map<String, Object> step, String nodeId, String label,
                                               WorkflowBuilderSession session, ValidationResult result) {
        Map<String, Object> params = (Map<String, Object>) step.get("params");
        if (params == null || params.isEmpty()) {
            return;
        }

        // Get available columns from trigger schema
        Set<String> availableColumns = getAvailableTriggerColumns(session);
        if (availableColumns.isEmpty()) {
            return; // No trigger schema to validate against
        }

        // Get trigger label for suggestions
        String triggerLabel = session.getTriggers().stream()
            .map(t -> (String) t.get("label"))
            .filter(l -> l != null)
            .findFirst()
            .orElse("trigger");
        String normalizedTriggerLabel = WorkflowBuilderSession.normalizeLabel(triggerLabel);

        // Check each param value for column references
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String paramName = entry.getKey();
            Object paramValue = entry.getValue();

            if (paramValue instanceof String strValue) {
                // Check for invalid {{column}} syntax (must use {{trigger:label.column}})
                List<String> simpleRefs = extractSimpleColumnReferences(strValue);
                if (!simpleRefs.isEmpty()) {
                    for (String colName : simpleRefs) {
                        result.addError("INVALID_SYNTAX", nodeId,
                                "Invalid syntax: {{" + colName + "}} must be {{trigger:" + normalizedTriggerLabel + "." + colName + "}}. " +
                                "Use explicit type prefix for consistency with {{mcp:...}}, {{agent:...}}, {{core:...}}");
                    }
                }

                List<String> invalidRefs = extractAndValidateReferences(strValue, availableColumns);
                if (!invalidRefs.isEmpty()) {
                    result.addWarning("INVALID_COLUMN_REFERENCE", nodeId,
                            "Step '" + label + "' param '" + paramName + "' references unknown column(s): " +
                            invalidRefs + ". Available columns: " + availableColumns);
                }
            }
        }
    }

    /**
     * Get all available columns from the trigger's output schema.
     * Reads LIVE trigger data from the session (not the stale nodeSchemas cache)
     * so columns added via modify are always visible.
     */
    @SuppressWarnings("unchecked")
    private Set<String> getAvailableTriggerColumns(WorkflowBuilderSession session) {
        Set<String> columns = new HashSet<>();

        for (Map<String, Object> trigger : session.getTriggers()) {
            String type = (String) trigger.get("type");
            Object dsIdObj = trigger.get("datasource_id");

            if (dsIdObj != null && (type == null || "datasource".equals(type))) {
                try {
                    Long dsId = Long.parseLong(String.valueOf(dsIdObj));
                    DataSourceDto ds = dataSourceClient.getDataSource(dsId, session.getTenantId());
                    if (ds != null && ds.mappingSpec() != null && !ds.mappingSpec().isEmpty()) {
                        columns.add("id");
                        columns.addAll(ds.mappingSpec().keySet());
                    }
                } catch (Exception e) {
                    log.warn("Validator: Failed to fetch columns from datasource: {}", e.getMessage());
                }
            } else if ("form".equals(type)) {
                Map<String, Object> params = (Map<String, Object>) trigger.get("params");
                if (params != null) {
                    List<Map<String, Object>> fields = (List<Map<String, Object>>) params.get("fields");
                    if (fields != null) {
                        for (Map<String, Object> field : fields) {
                            String name = field.get("name") instanceof String s ? s : null;
                            if (name != null && !name.isBlank()) {
                                columns.add(name);
                            }
                        }
                    }
                }
                columns.add("submittedAt");
            } else if ("webhook".equals(type)) {
                columns.addAll(List.of("payload", "headers", "query", "method", "triggered_at", "triggered_by"));
            } else if ("chat".equals(type)) {
                columns.addAll(List.of("message", "extracted_message", "conversation_id", "attachments",
                        "matched", "match_type", "match_value", "triggered_at", "triggered_by",
                        "trigger_id", "item_id", "item_index", "data", "count"));
            }
        }

        if (!columns.isEmpty()) {
            return columns;
        }

        // Final fallback: cached nodeSchemas (covers edge cases)
        return session.getNodeSchemas().values().stream()
                .filter(schema -> "trigger".equals(schema.getNodeType()))
                .flatMap(schema -> schema.getOutputs().keySet().stream())
                .collect(Collectors.toSet());
    }

    /**
     * Extract simple {{column}} references (without type prefix).
     * Returns list of column names using simple syntax (which is now invalid).
     */
    private List<String> extractSimpleColumnReferences(String template) {
        List<String> simpleRefs = new ArrayList<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(template);

        while (matcher.find()) {
            String ref = matcher.group(1); // Content inside {{}}

            // Detect simple {{column}} format (no : or ., and not a system variable)
            if (!ref.contains(":") && !ref.contains(".") && !isSystemVariable(ref)) {
                simpleRefs.add(ref);
            }
        }

        return simpleRefs;
    }

    /**
     * Extract column references from a template string and validate them.
     * Handles both {{column}} and {{trigger:label.column}} formats.
     * Returns list of invalid references.
     */
    private List<String> extractAndValidateReferences(String template, Set<String> availableColumns) {
        List<String> invalidRefs = new ArrayList<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(template);

        while (matcher.find()) {
            String ref = matcher.group(1); // Content inside {{}}
            String columnName = extractColumnName(ref);
            if (columnName != null && !availableColumns.contains(columnName)) {
                // Check if it's a known system variable that should be skipped
                if (!isSystemVariable(ref)) {
                    invalidRefs.add(columnName);
                }
            }
        }

        return invalidRefs;
    }

    /**
     * Extract the column name from a reference string.
     * Returns null if this is not a trigger column reference.
     */
    private String extractColumnName(String ref) {
        // Skip step references, control node references, etc.
        if (ref.startsWith("mcp:") || ref.startsWith("core:") || ref.startsWith("agent:")
                || ref.startsWith("interface:") || ref.startsWith("table:")) {
            return null;
        }

        // Skip SpEL function calls - e.g. json(...), now(), concat(...)
        if (ref.contains("(")) {
            return null;
        }

        // Handle trigger:label.data.column or trigger:label.column
        if (ref.startsWith("trigger:")) {
            String[] parts = ref.split("\\.");
            if (parts.length >= 2) {
                return parts[parts.length - 1]; // Last part is the column
            }
            return null;
        }

        // current_item fields come from split items, not triggers - skip
        if (ref.startsWith("current_item")) {
            return null;
        }

        // Simple {{column}} format
        if (!ref.contains(".") && !ref.contains(":")) {
            return ref;
        }

        return null;
    }

    /**
     * Check if a reference is a known system variable that should be skipped.
     */
    private boolean isSystemVariable(String ref) {
        return ref.equals("current_item") ||
               ref.startsWith("mcp:") ||
               ref.startsWith("core:") ||
               ref.startsWith("agent:") ||
               ref.equals("data") ||
               ref.equals("result");
    }

    private String getStepNodeId(Map<String, Object> step) {
        String label = (String) step.get("label");
        Boolean isAgent = (Boolean) step.get("isAgent");
        String prefix = (isAgent != null && isAgent) ? "agent:" : "mcp:";
        return prefix + WorkflowBuilderSession.normalizeLabel(label);
    }

}
