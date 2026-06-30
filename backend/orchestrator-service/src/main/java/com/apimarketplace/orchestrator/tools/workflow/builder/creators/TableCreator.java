package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Creates table/CRUD nodes for workflows (insert_row, get_rows, update_row, delete_row, find_rows).
 *
 * Extracted from McpCreator to follow Single Responsibility Principle - McpCreator handles
 * MCP tool nodes (catalog UUIDs), while TableCreator handles CRUD operations (crud/* toolIds).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TableCreator extends CreatorBase {

    private final WorkflowBuilderSessionStore sessionStore;
    private final DataSourceClient dataSourceClient;
    private final ResponseOptimizer responseOptimizer;
    private final NodeLibraryService nodeLibraryService;

    /** Reserved parameter names - not passed to the tool */
    private static final Set<String> RESERVED_PARAMS = Set.of(
        "label", "name", "connect_after", "connect_after_loop", "interface_id",
        "type", "action", "session_id", "params", "parameters",
        // CRUD-specific fields go into step.crud, not step.params
        "rows", "where", "set", "columns", "limit", "offset",
        "dataSourceId", "datasource_id", "table_id"
    );

    /**
     * Execute add_table action with a crud/ toolId.
     *
     * @param session Current workflow builder session
     * @param parameters Flat parameters (tool params + reserved fields)
     * @param toolId CRUD tool ID (e.g. "crud/create-row", "crud/read-row")
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddTable(WorkflowBuilderSession session, Map<String, Object> parameters, String toolId) {
        // 0. Backward-compat: if agent passed nested crud:{...}, flatten into top-level params.
        // Validator + node_type_documentation expect columns/set/where/table_id at the top level.
        if (parameters.containsKey("crud")) {
            Object crudObj = parameters.get("crud");
            Map<String, Object> merged = new LinkedHashMap<>(parameters);
            merged.remove("crud");
            if (crudObj instanceof Map<?, ?> crudMap) {
                // Top-level keys win on conflict (putIfAbsent skips existing)
                for (Map.Entry<?, ?> e : crudMap.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    merged.putIfAbsent(k, e.getValue());
                }
            }
            // Non-Map (String/null/other) - silently dropped, no NPE.
            parameters = merged;
        }

        // 1. Validate label
        String label = getString(parameters, "label", "name");
        if (label == null || label.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "label is REQUIRED. Example: workflow(action='add_node', type='insert_row', label='Save Record', params={table_id: X, columns: {col1: 'val'}})");
        }

        // 2. Trigger must exist
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Validate tool ID
        if (toolId == null || toolId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Tool ID is required for table operations.");
        }

        // 4. Generate node ID and validate uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.TABLE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 5. Resolve and validate connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 6. Build step node
        Map<String, Object> stepNode = buildStepNode(parameters, label, toolId, session);

        // 7. Add CRUD-specific parameters
        addCrudParameters(stepNode, parameters);

        // 8. Add to session tables list (deep-normalize variable references) and create edge
        session.getTables().add(LabelNormalizer.normalizeVariableReferencesDeep(stepNode));
        createEdgeIfNeeded(session, connectAfter, nodeId);

        // 9. Handle loop exit
        var loopExitError = handleLoopExit(session, parameters, nodeId);
        if (loopExitError != null) return loopExitError;

        // 10. Finalize
        finalizeNode(session, sessionStore, NodeType.TABLE, nodeId, stepNode, connectAfter);

        // 11. Build response
        return buildResponse(session, nodeId, label, toolId, connectAfter, stepNode);
    }

    // ==================== Private Helpers ====================

    private Map<String, Object> buildStepNode(Map<String, Object> parameters, String label, String toolId, WorkflowBuilderSession session) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", toolId);
        // Map toolId to valid step type - crud-find is special (not crud-find-rows)
        String stepType = switch (toolId) {
            case "crud/find-rows" -> "crud-find";
            default -> "crud-" + toolId.substring("crud/".length());
        };
        node.put("type", stepType);
        node.put("label", label);
        node.put("position", calculatePosition(session, NodeType.TABLE));

        // Flat params - everything not in RESERVED_PARAMS is a tool parameter
        Map<String, Object> toolParameters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String key = entry.getKey();
            if (!RESERVED_PARAMS.contains(key) && entry.getValue() != null) {
                toolParameters.put(key, entry.getValue());
            }
        }
        if (!toolParameters.isEmpty()) {
            node.put("params", toolParameters);
        }

        return node;
    }

    /**
     * CRUD fields (rows, where, set, columns, limit, offset) must live in step.crud, NOT step.params.
     * The frontend plan importer reads step.crud to populate dataSourceData, while step.params feeds
     * paramExpressions (Record&lt;string, string&gt;). Complex objects in params get String()-ified to
     * "[object Object]" by the frontend, losing data silently.
     */
    @SuppressWarnings("unchecked")
    private void addCrudParameters(Map<String, Object> stepNode, Map<String, Object> parameters) {
        // 1. Extract dataSourceId
        Long dataSourceId = toLongOrNull(parameters.get("dataSourceId"));
        if (dataSourceId == null) dataSourceId = toLongOrNull(parameters.get("datasource_id"));
        if (dataSourceId == null) dataSourceId = toLongOrNull(parameters.get("table_id"));
        if (dataSourceId != null) {
            stepNode.put("dataSourceId", dataSourceId);
        }

        // 2. Build crud block from CRUD-specific fields
        Map<String, Object> crud = new LinkedHashMap<>();
        Set<String> crudKeys = Set.of("rows", "where", "set", "columns", "limit", "offset");

        // Collect from top-level parameters (already flattened from nested crud:{} in executeAddTable)
        for (String key : crudKeys) {
            if (parameters.containsKey(key) && parameters.get(key) != null) {
                crud.put(key, parameters.get(key));
            }
        }

        stepNode.put("crud", crud);

        // 3. Remove CRUD fields from params so they don't end up in paramExpressions
        if (stepNode.containsKey("params") && stepNode.get("params") instanceof Map) {
            Map<String, Object> params = (Map<String, Object>) stepNode.get("params");
            crudKeys.forEach(params::remove);
            // Also remove dataSourceId variants from params
            params.remove("dataSourceId");
            params.remove("datasource_id");
            params.remove("table_id");
        }
    }

    private ToolExecutionResult handleLoopExit(WorkflowBuilderSession session, Map<String, Object> parameters, String nodeId) {
        String exitFrom = (String) parameters.get("connect_after_loop");
        if (exitFrom != null && !exitFrom.isBlank()) {
            String resolvedLoopId = session.resolveNodeReference(exitFrom);
            if (!resolvedLoopId.startsWith("core:")) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "connect_after_loop must reference a loop.");
            }
            try {
                session.addPendingLoopExit(resolvedLoopId, nodeId, "step");
            } catch (IllegalStateException e) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult buildResponse(WorkflowBuilderSession session, String nodeId, String label, String toolId,
                                               String connectAfter, Map<String, Object> stepNode) {
        // Get output refs from node_type_documentation
        Map<String, String> outputRefs = getCrudOutputRefs(toolId, nodeId);

        // Check for variables in input
        Map<String, Object> mergedParams = stepNode.get("params") instanceof Map ? (Map<String, Object>) stepNode.get("params") : Map.of();
        boolean hasVariables = !mergedParams.isEmpty() && mergedParams.values().stream()
            .anyMatch(v -> v != null && v.toString().contains("{{"));

        List<String> availableColumns = getAvailableColumnsFromSession(session, dataSourceClient, session.getTenantId());

        // Detect if inside a loop body
        String parentLoopId = detectParentLoop(connectAfter, session);

        // Build response
        Map<String, Object> response = responseOptimizer.buildStepResponse(session, nodeId, label, toolId, connectAfter, parentLoopId,
            connectAfter != null, outputRefs, null, null, availableColumns, hasVariables);

        // Show the saved params
        if (!mergedParams.isEmpty()) {
            response.put("saved_params", mergedParams);
        } else {
            response.put("saved_params", "EMPTY - no params provided");
        }

        // Build metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("label", label);

        return ToolExecutionResult.success(response, metadata);
    }

    /**
     * Map crud/ toolId to node_type_documentation type and generate output refs.
     * E.g., "crud/create-row" -> "insert_row" -> outputs: {row_id, created_at, inserted_values}
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getCrudOutputRefs(String toolId, String nodeId) {
        String docType = switch (toolId) {
            case "crud/create-row" -> "insert_row";
            case "crud/read-row" -> "get_rows";
            case "crud/update-row" -> "update_row";
            case "crud/delete-row" -> "delete_row";
            case "crud/find-rows" -> "find_rows";
            case "crud/create-column" -> "create_column";
            default -> null;
        };
        if (docType == null) return null;

        return nodeLibraryService.findByType(docType)
            .map(NodeTypeDocumentationEntity::getOutputs)
            .filter(outputs -> outputs != null && !outputs.isEmpty())
            .map(outputs -> {
                Map<String, String> refs = new LinkedHashMap<>();
                for (String field : outputs.keySet()) {
                    refs.put(field, "{{" + nodeId + ".output." + field + "}}");
                }
                return refs;
            })
            .orElse(null);
    }
}
