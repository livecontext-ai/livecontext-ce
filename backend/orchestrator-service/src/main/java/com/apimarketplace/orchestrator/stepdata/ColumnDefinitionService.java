package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.stepdata.ColumnDefinition.ColumnType;
import com.apimarketplace.orchestrator.stepdata.ColumnDefinition.RenderType;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service that defines column definitions based on node type.
 * This is the single source of truth for column order and rendering in the frontend.
 */
@Service
public class ColumnDefinitionService {

    // No hidden fields - show all data as-is
    private static final Set<String> HIDDEN_FIELDS = Set.of();

    // Ordered list of known fields for stable column ordering
    private static final List<String> FIELD_ORDER = List.of(
            // 1. Essential - what happened?
            "id", "status", "nodeType", "durationMs",
            // 2. Data - what went in / out / wrong?
            "input", "output", "errorMessage",
            // 3. Execution context
            "epoch", "spawn", "iteration", "itemIndex",
            // --- Node-specific fields ---
            // Decision
            "selectedBranch", "conditionExpression", "conditionResolved", "conditionResult", "evaluations",
            // Switch
            "switchExpression", "switchValue", "selectedCase", "cases",
            // Loop
            "loopProgress", "loopIteration", "maxIterations", "loopCondition", "loopExitReason", "loopId", "carryValue",
            // Split
            "splitProgress", "totalItems", "processedItems", "list", "strategy", "parallel", "currentItem", "currentIndex",
            // Merge
            "mergeStrategy", "predecessorsTotal", "predecessorsCompleted", "predecessorsSkipped", "waitingFor", "receivedBranches",
            // Fork
            "branchesCount", "branches",
            // Agent
            "model", "provider", "llmIterations", "toolCallsCount", "tokensUsed", "promptTokens", "completionTokens", "response",
            // MCP / HTTP
            "toolName", "apiName", "method", "url", "responseTime",
            // Crud
            "operation", "dataSourceName", "rowsAffected", "whereClause",
            // Transform
            "mappingsCount", "mappings",
            // Wait
            "waitDuration", "actualWait",
            // Trigger
            "triggerType", "itemsSpawned",
            // --- Technical details ---
            "toolId", "normalizedKey", "triggerId", "httpStatus",
            "startTime", "endTime",
            "skipReason", "skipSourceNode", "skippedBranches",
            "itemId"
    );

    // Known field definitions registry: field name -> pre-configured ColumnDefinition
    private static final Map<String, ColumnDefinition> KNOWN_FIELDS = new LinkedHashMap<>();

    static {
        // Common
        reg("id", "ID", ColumnType.NUMBER, RenderType.TEXT, 50, true, true, null);
        reg("status", "Status", ColumnType.STRING, RenderType.STATUS_BADGE, 110, true, true, null);
        reg("toolId", "Tool ID", ColumnType.STRING, RenderType.TEXT, 150, true, true, null);
        reg("nodeType", "Node Type", ColumnType.STRING, RenderType.BADGE, 120, true, true, null);
        reg("normalizedKey", "Key", ColumnType.STRING, RenderType.TEXT, 130, true, true, null);
        reg("epoch", "Epoch", ColumnType.NUMBER, RenderType.TEXT, 60, true, true, null);
        reg("spawn", "Spawn", ColumnType.NUMBER, RenderType.TEXT, 60, true, true, null);
        reg("iteration", "Iteration", ColumnType.NUMBER, RenderType.TEXT, 80, true, true, null);
        reg("triggerId", "Trigger ID", ColumnType.STRING, RenderType.TEXT, 130, true, true, null);
        reg("startTime", "Started", ColumnType.DATETIME, RenderType.RELATIVE_TIME, 120, true, false, null);
        reg("endTime", "Ended", ColumnType.DATETIME, RenderType.RELATIVE_TIME, 120, true, false, null);
        reg("durationMs", "Duration", ColumnType.NUMBER, RenderType.DURATION, 90, true, false, null);
        reg("httpStatus", "HTTP Status", ColumnType.NUMBER, RenderType.HTTP_STATUS_BADGE, 110, true, true, null);
        reg("itemIndex", "Item Idx", ColumnType.NUMBER, RenderType.TEXT, 70, true, true, null);
        reg("skipReason", "Skip Reason", ColumnType.STRING, RenderType.TEXT, 180, false, true, null);
        reg("skipSourceNode", "Skip Source", ColumnType.STRING, RenderType.TEXT, 130, true, true, null);
        // Decision
        reg("selectedBranch", "Selected Branch", ColumnType.STRING, RenderType.BRANCH_BADGE, 130, true, true, null);
        reg("conditionExpression", "Condition", ColumnType.STRING, RenderType.CODE, 200, false, false, null);
        reg("conditionResolved", "Resolved", ColumnType.STRING, RenderType.CODE, 150, false, false, null);
        reg("conditionResult", "Result", ColumnType.BOOLEAN, RenderType.BOOLEAN_BADGE, 80, true, true, null);
        reg("evaluations", "Branch Evaluations", ColumnType.JSON, RenderType.EVALUATIONS_TABLE, 400, false, false, true);
        // Switch
        reg("switchExpression", "Expression", ColumnType.STRING, RenderType.CODE, 180, false, false, null);
        reg("switchValue", "Evaluated Value", ColumnType.STRING, RenderType.TEXT, 140, true, true, null);
        reg("selectedCase", "Selected Case", ColumnType.STRING, RenderType.BADGE, 120, true, true, null);
        reg("cases", "Cases", ColumnType.JSON, RenderType.CASES_TABLE, 350, false, false, true);
        // Loop
        reg("loopProgress", "Progress", ColumnType.JSON, RenderType.LOOP_PROGRESS, 150, false, false, null);
        reg("loopIteration", "Iteration", ColumnType.NUMBER, RenderType.TEXT, 90, true, true, null);
        reg("maxIterations", "Max", ColumnType.NUMBER, RenderType.TEXT, 70, true, false, null);
        reg("loopCondition", "Condition", ColumnType.STRING, RenderType.CODE, 180, false, false, null);
        reg("loopExitReason", "Exit Reason", ColumnType.STRING, RenderType.BADGE, 130, true, true, null);
        reg("loopId", "Loop ID", ColumnType.STRING, RenderType.TEXT, 100, true, true, null);
        reg("carryValue", "Carry Value", ColumnType.JSON, RenderType.JSON_PREVIEW, 200, false, false, true);
        // Split
        reg("splitProgress", "Progress", ColumnType.JSON, RenderType.SPLIT_PROGRESS, 150, false, false, null);
        reg("totalItems", "Total Items", ColumnType.NUMBER, RenderType.TEXT, 100, true, false, null);
        reg("processedItems", "Processed", ColumnType.NUMBER, RenderType.TEXT, 100, true, false, null);
        reg("list", "List Expression", ColumnType.STRING, RenderType.CODE, 200, false, false, null);
        reg("strategy", "Strategy", ColumnType.STRING, RenderType.BADGE, 130, true, true, null);
        reg("parallel", "Parallel", ColumnType.BOOLEAN, RenderType.BOOLEAN_BADGE, 80, true, true, null);
        reg("currentItem", "Current Item", ColumnType.JSON, RenderType.JSON_PREVIEW, 200, false, false, true);
        reg("currentIndex", "Index", ColumnType.NUMBER, RenderType.TEXT, 70, true, true, null);
        // Merge
        reg("mergeStrategy", "Strategy", ColumnType.STRING, RenderType.BADGE, 130, true, true, null);
        reg("predecessorsTotal", "Expected", ColumnType.NUMBER, RenderType.TEXT, 90, true, false, null);
        reg("predecessorsCompleted", "Completed", ColumnType.NUMBER, RenderType.TEXT, 100, true, false, null);
        reg("predecessorsSkipped", "Skipped", ColumnType.NUMBER, RenderType.TEXT, 90, true, false, null);
        reg("waitingFor", "Waiting For", ColumnType.JSON, RenderType.STRING_LIST, 200, false, false, null);
        reg("receivedBranches", "Received Data", ColumnType.JSON, RenderType.JSON_PREVIEW, 250, false, false, true);
        // Fork
        reg("branchesCount", "Branches", ColumnType.NUMBER, RenderType.TEXT, 90, true, false, null);
        reg("branches", "Branch List", ColumnType.JSON, RenderType.STRING_LIST, 250, false, false, null);
        // Agent
        reg("model", "Model", ColumnType.STRING, RenderType.BADGE, 120, true, true, null);
        reg("provider", "Provider", ColumnType.STRING, RenderType.TEXT, 100, true, true, null);
        reg("llmIterations", "LLM Iterations", ColumnType.NUMBER, RenderType.TEXT, 110, true, false, null);
        reg("toolCallsCount", "Tool Calls", ColumnType.NUMBER, RenderType.TEXT, 100, true, false, null);
        reg("tokensUsed", "Tokens", ColumnType.NUMBER, RenderType.TEXT, 90, true, false, null);
        reg("promptTokens", "Prompt Tokens", ColumnType.NUMBER, RenderType.TEXT, 110, true, false, null);
        reg("completionTokens", "Completion Tokens", ColumnType.NUMBER, RenderType.TEXT, 130, true, false, null);
        reg("response", "Response", ColumnType.STRING, RenderType.TEXT_PREVIEW, 300, false, false, true);
        // MCP / HTTP
        reg("toolName", "Tool", ColumnType.STRING, RenderType.TEXT, 130, true, true, null);
        reg("apiName", "API", ColumnType.STRING, RenderType.TEXT, 130, true, true, null);
        reg("method", "Method", ColumnType.STRING, RenderType.HTTP_METHOD_BADGE, 90, true, true, null);
        reg("url", "URL", ColumnType.STRING, RenderType.TEXT, 250, false, false, null);
        reg("responseTime", "Response Time", ColumnType.NUMBER, RenderType.DURATION, 120, true, false, null);
        // Crud
        reg("operation", "Operation", ColumnType.STRING, RenderType.BADGE, 100, true, true, null);
        reg("dataSourceName", "Table", ColumnType.STRING, RenderType.TEXT, 130, true, true, null);
        reg("rowsAffected", "Rows Affected", ColumnType.NUMBER, RenderType.TEXT, 110, true, false, null);
        reg("whereClause", "Where Clause", ColumnType.JSON, RenderType.JSON_PREVIEW, 200, false, false, null);
        // Transform
        reg("mappingsCount", "Mappings", ColumnType.NUMBER, RenderType.TEXT, 90, true, false, null);
        reg("mappings", "Mapping Details", ColumnType.JSON, RenderType.JSON_PREVIEW, 300, false, false, true);
        // Wait
        reg("waitDuration", "Configured Wait", ColumnType.NUMBER, RenderType.DURATION, 130, true, false, null);
        reg("actualWait", "Actual Wait", ColumnType.NUMBER, RenderType.DURATION, 110, true, false, null);
        // Trigger
        reg("triggerType", "Type", ColumnType.STRING, RenderType.BADGE, 100, true, true, null);
        reg("itemsSpawned", "Items Spawned", ColumnType.NUMBER, RenderType.TEXT, 110, true, false, null);
        // Skip
        reg("skippedBranches", "Skipped Branches", ColumnType.JSON, RenderType.STRING_LIST, 200, false, false, null);
        // Common data
        reg("input", "Input", ColumnType.JSON, RenderType.JSON_PREVIEW, 200, false, false, true);
        reg("output", "Output", ColumnType.JSON, RenderType.JSON_NAVIGABLE, 300, false, false, true);
        // Error
        reg("errorMessage", "Error", ColumnType.STRING, RenderType.TEXT_PREVIEW, 250, false, false, null);
        // Generic
        reg("itemId", "Item ID", ColumnType.STRING, RenderType.TEXT, 100, true, true, null);
    }

    private static void reg(String field, String header, ColumnType type, RenderType renderType,
                             int width, boolean sortable, boolean filterable, Boolean expandable) {
        KNOWN_FIELDS.put(field, new ColumnDefinition(field, header, type, renderType, width, sortable, filterable, expandable));
    }

    /**
     * Derive column definitions from actual row data.
     * Scans all rows to find fields with at least one meaningful value,
     * then creates columns using known field definitions or inferred types.
     */
    public List<ColumnDefinition> deriveColumnsFromRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        // Collect fields that have at least one non-null value across all rows
        Set<String> presentFields = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() != null && !HIDDEN_FIELDS.contains(entry.getKey())) {
                    presentFields.add(entry.getKey());
                }
            }
        }

        // Order fields: known fields in FIELD_ORDER first, then any unknown fields
        List<String> orderedFields = new ArrayList<>();
        for (String field : FIELD_ORDER) {
            if (presentFields.contains(field)) {
                orderedFields.add(field);
                presentFields.remove(field);
            }
        }
        // Append any remaining fields not in FIELD_ORDER
        orderedFields.addAll(presentFields);

        // Build column definitions
        List<ColumnDefinition> columns = new ArrayList<>();
        for (String field : orderedFields) {
            ColumnDefinition known = KNOWN_FIELDS.get(field);
            if (known != null) {
                columns.add(known);
            } else {
                // Infer column type from actual values
                columns.add(inferColumnDefinition(field, rows));
            }
        }

        return columns;
    }

    /**
     * Infer a column definition for an unknown field by inspecting values across rows.
     */
    private ColumnDefinition inferColumnDefinition(String field, List<Map<String, Object>> rows) {
        // Find first non-null value to infer type
        Object sampleValue = null;
        for (Map<String, Object> row : rows) {
            Object val = row.get(field);
            if (val != null) {
                sampleValue = val;
                break;
            }
        }

        ColumnType type;
        RenderType renderType;
        int width;
        boolean sortable;
        boolean filterable;
        Boolean expandable = null;

        if (sampleValue instanceof Map || sampleValue instanceof List) {
            type = ColumnType.JSON;
            renderType = RenderType.JSON_PREVIEW;
            width = 250;
            sortable = false;
            filterable = false;
            expandable = true;
        } else if (sampleValue instanceof Boolean) {
            type = ColumnType.BOOLEAN;
            renderType = RenderType.BOOLEAN_BADGE;
            width = 80;
            sortable = true;
            filterable = true;
        } else if (sampleValue instanceof Number) {
            type = ColumnType.NUMBER;
            renderType = RenderType.TEXT;
            width = 90;
            sortable = true;
            filterable = true;
        } else {
            type = ColumnType.STRING;
            renderType = RenderType.TEXT;
            width = 150;
            sortable = true;
            filterable = true;
        }

        // Build human-readable header from camelCase field name
        String header = camelCaseToHeader(field);

        return new ColumnDefinition(field, header, type, renderType, width, sortable, filterable, expandable);
    }

    /**
     * Convert camelCase field name to a human-readable header.
     * e.g. "myFieldName" -> "My Field Name"
     */
    private String camelCaseToHeader(String field) {
        if (field == null || field.isEmpty()) return field;
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(field.charAt(0)));
        for (int i = 1; i < field.length(); i++) {
            char c = field.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Get ordered column definitions for a specific node type.
     * The order returned here is the order displayed in the frontend.
     */
    public List<ColumnDefinition> getColumnsForNodeType(NodeType nodeType, String stepId) {
        List<ColumnDefinition> columns = new ArrayList<>();

        // Common columns for all node types (always first)
        addCommonColumns(columns);

        // Node-specific columns
        if (nodeType == null) {
            addGenericColumns(columns);
        } else {
            switch (nodeType) {
                case TRIGGER -> addTriggerColumns(columns);
                case DECISION -> addDecisionColumns(columns);
                case SWITCH -> addSwitchColumns(columns);
                case LOOP_CONTROLLER -> addLoopColumns(columns);
                case SPLIT_CONTROLLER -> addSplitColumns(columns);
                case MERGE -> addMergeColumns(columns);
                case FORK -> addForkColumns(columns);
                case AGENT -> addAgentColumns(columns);
                case MCP -> addMcpColumns(columns, stepId);
                default -> addGenericColumns(columns);
            }
        }

        // Error column (always last if applicable)
        addErrorColumn(columns);

        return columns;
    }

    private void addCommonColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("itemNumber")
                .header("#")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(50)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("status")
                .header("Status")
                .type(ColumnType.STRING)
                .renderType(RenderType.STATUS_BADGE)
                .width(110)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("epoch")
                .header("Epoch")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(60)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("itemIndex")
                .header("Item Idx")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(70)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("startTime")
                .header("Started")
                .type(ColumnType.DATETIME)
                .renderType(RenderType.RELATIVE_TIME)
                .width(120)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("durationMs")
                .header("Duration")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.DURATION)
                .width(90)
                .sortable(true)
                .filterable(false)
                .build());
    }

    private void addTriggerColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("triggerType")
                .header("Type")
                .type(ColumnType.STRING)
                .renderType(RenderType.BADGE)
                .width(100)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("input")
                .header("Input Data")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_NAVIGABLE)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("itemsSpawned")
                .header("Items Spawned")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(110)
                .sortable(true)
                .filterable(false)
                .build());
    }

    private void addDecisionColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("selectedBranch")
                .header("Selected Branch")
                .type(ColumnType.STRING)
                .renderType(RenderType.BRANCH_BADGE)
                .width(130)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("conditionExpression")
                .header("Condition")
                .type(ColumnType.STRING)
                .renderType(RenderType.CODE)
                .width(200)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("conditionResolved")
                .header("Resolved")
                .type(ColumnType.STRING)
                .renderType(RenderType.CODE)
                .width(150)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("conditionResult")
                .header("Result")
                .type(ColumnType.BOOLEAN)
                .renderType(RenderType.BOOLEAN_BADGE)
                .width(80)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("evaluations")
                .header("Branch Evaluations")
                .type(ColumnType.JSON)
                .renderType(RenderType.EVALUATIONS_TABLE)
                .width(400)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addSwitchColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("switchExpression")
                .header("Expression")
                .type(ColumnType.STRING)
                .renderType(RenderType.CODE)
                .width(180)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("switchValue")
                .header("Evaluated Value")
                .type(ColumnType.STRING)
                .renderType(RenderType.TEXT)
                .width(140)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("selectedCase")
                .header("Selected Case")
                .type(ColumnType.STRING)
                .renderType(RenderType.BADGE)
                .width(120)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("cases")
                .header("Cases")
                .type(ColumnType.JSON)
                .renderType(RenderType.CASES_TABLE)
                .width(350)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addLoopColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("loopProgress")
                .header("Progress")
                .type(ColumnType.JSON)
                .renderType(RenderType.LOOP_PROGRESS)
                .width(150)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("loopIteration")
                .header("Iteration")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(90)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("maxIterations")
                .header("Max")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(70)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("loopCondition")
                .header("Condition")
                .type(ColumnType.STRING)
                .renderType(RenderType.CODE)
                .width(180)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("conditionResult")
                .header("Continue?")
                .type(ColumnType.BOOLEAN)
                .renderType(RenderType.BOOLEAN_BADGE)
                .width(90)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("loopExitReason")
                .header("Exit Reason")
                .type(ColumnType.STRING)
                .renderType(RenderType.BADGE)
                .width(130)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("carryValue")
                .header("Carry Value")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(200)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addSplitColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("splitProgress")
                .header("Progress")
                .type(ColumnType.JSON)
                .renderType(RenderType.SPLIT_PROGRESS)
                .width(150)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("totalItems")
                .header("Total Items")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(100)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("processedItems")
                .header("Processed")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(100)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("list")
                .header("List Expression")
                .type(ColumnType.STRING)
                .renderType(RenderType.CODE)
                .width(200)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("strategy")
                .header("Strategy")
                .type(ColumnType.STRING)
                .renderType(RenderType.BADGE)
                .width(130)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("parallel")
                .header("Parallel")
                .type(ColumnType.BOOLEAN)
                .renderType(RenderType.BOOLEAN_BADGE)
                .width(80)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("currentItem")
                .header("Current Item")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(200)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("currentIndex")
                .header("Index")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(70)
                .sortable(true)
                .filterable(true)
                .build());
    }

    private void addMergeColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("mergeStrategy")
                .header("Strategy")
                .type(ColumnType.STRING)
                .renderType(RenderType.BADGE)
                .width(130)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("predecessorsTotal")
                .header("Expected")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(90)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("predecessorsCompleted")
                .header("Completed")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(100)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("predecessorsSkipped")
                .header("Skipped")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(90)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("waitingFor")
                .header("Waiting For")
                .type(ColumnType.JSON)
                .renderType(RenderType.STRING_LIST)
                .width(200)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("receivedBranches")
                .header("Received Data")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(250)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addForkColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("branchesCount")
                .header("Branches")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(90)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("branches")
                .header("Branch List")
                .type(ColumnType.JSON)
                .renderType(RenderType.STRING_LIST)
                .width(250)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("output")
                .header("Output")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_NAVIGABLE)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addAgentColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("model")
                .header("Model")
                .type(ColumnType.STRING)
                .renderType(RenderType.BADGE)
                .width(120)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("provider")
                .header("Provider")
                .type(ColumnType.STRING)
                .renderType(RenderType.TEXT)
                .width(100)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("llmIterations")
                .header("LLM Iterations")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(110)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("toolCallsCount")
                .header("Tool Calls")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(100)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("tokensUsed")
                .header("Tokens")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(90)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("promptTokens")
                .header("Prompt Tokens")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(110)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("completionTokens")
                .header("Completion Tokens")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(130)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("response")
                .header("Response")
                .type(ColumnType.STRING)
                .renderType(RenderType.TEXT_PREVIEW)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("input")
                .header("Input")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(200)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addMcpColumns(List<ColumnDefinition> columns, String stepId) {
        // Check for special step types
        boolean isTransform = stepId != null && stepId.contains("__transform__");
        boolean isWait = stepId != null && stepId.contains("__wait__");
        boolean isCrud = stepId != null && stepId.startsWith("crud/");
        boolean isHttpRequest = stepId != null && stepId.equals("http-request");

        if (isTransform) {
            addTransformColumns(columns);
        } else if (isWait) {
            addWaitColumns(columns);
        } else if (isCrud) {
            addCrudColumns(columns);
        } else if (isHttpRequest) {
            addHttpRequestColumns(columns);
        } else {
            addDefaultMcpColumns(columns);
        }
    }

    private void addDefaultMcpColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("httpStatus")
                .header("HTTP Status")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.HTTP_STATUS_BADGE)
                .width(110)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("toolName")
                .header("Tool")
                .type(ColumnType.STRING)
                .renderType(RenderType.TEXT)
                .width(130)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("apiName")
                .header("API")
                .type(ColumnType.STRING)
                .renderType(RenderType.TEXT)
                .width(130)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("input")
                .header("Input")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(200)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("output")
                .header("Output")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_NAVIGABLE)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addTransformColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("mappingsCount")
                .header("Mappings")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(90)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("mappings")
                .header("Mapping Details")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("input")
                .header("Input")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(200)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("output")
                .header("Output")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_NAVIGABLE)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addWaitColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("waitDuration")
                .header("Configured Wait")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.DURATION)
                .width(130)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("actualWait")
                .header("Actual Wait")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.DURATION)
                .width(110)
                .sortable(true)
                .filterable(false)
                .build());
    }

    private void addCrudColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("operation")
                .header("Operation")
                .type(ColumnType.STRING)
                .renderType(RenderType.BADGE)
                .width(100)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("dataSourceName")
                .header("Table")
                .type(ColumnType.STRING)
                .renderType(RenderType.TEXT)
                .width(130)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("rowsAffected")
                .header("Rows Affected")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.TEXT)
                .width(110)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("whereClause")
                .header("Where Clause")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(200)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("output")
                .header("Result")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_NAVIGABLE)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addHttpRequestColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("method")
                .header("Method")
                .type(ColumnType.STRING)
                .renderType(RenderType.HTTP_METHOD_BADGE)
                .width(90)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("url")
                .header("URL")
                .type(ColumnType.STRING)
                .renderType(RenderType.TEXT)
                .width(250)
                .sortable(false)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("httpStatus")
                .header("Status")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.HTTP_STATUS_BADGE)
                .width(100)
                .sortable(true)
                .filterable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("responseTime")
                .header("Response Time")
                .type(ColumnType.NUMBER)
                .renderType(RenderType.DURATION)
                .width(120)
                .sortable(true)
                .filterable(false)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("input")
                .header("Request")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(200)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("output")
                .header("Response")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_NAVIGABLE)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addGenericColumns(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("input")
                .header("Input")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_PREVIEW)
                .width(200)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());

        columns.add(ColumnDefinition.builder()
                .field("output")
                .header("Output")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_NAVIGABLE)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build());
    }

    private void addErrorColumn(List<ColumnDefinition> columns) {
        columns.add(ColumnDefinition.builder()
                .field("errorMessage")
                .header("Error")
                .type(ColumnType.STRING)
                .renderType(RenderType.TEXT_PREVIEW)
                .width(250)
                .sortable(false)
                .filterable(false)
                .build());
    }
}
