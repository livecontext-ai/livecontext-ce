package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
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
 * Handles classify node creation for the workflow builder.
 * Classify nodes categorize content into predefined categories using AI semantic understanding.
 *
 * Parameters:
 * - prompt: string (required) - Classification instruction INCLUDING data via {{type:label.output.field}}
 * - categories: array of {label, description} (required, min 2)
 * - provider: string (optional, e.g. "openai", "anthropic", "google", "mistral", "deepseek")
 * - model: string (optional)
 * - temperature: number (optional, 0-1)
 *
 * Extracted from WorkflowBuilderCreator for SOLID compliance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassifyCreator extends CreatorBase {

    private static final String EXAMPLE = "workflow(action='add_node', type='classify', label='Route Ticket', " +
        "params={prompt: 'Classify this support ticket by issue type: {{trigger:ticket.output.description}}', " +
        "categories: [{label: 'billing', description: 'Payment issues'}, {label: 'technical', description: 'Bugs'}]}, " +
        "connect_after='Ticket Form')";

    private final WorkflowBuilderSessionStore sessionStore;
    private final ResponseOptimizer responseOptimizer;

    /**
     * Execute add_classify action.
     * Creates a classify node that categorizes content into specified categories.
     */
    public ToolExecutionResult executeAddClassify(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label
        String label = getLabel(parameters);
        var labelError = validateLabel(label, "classify");
        if (labelError != null) return labelError;

        // 2. MANDATORY: Trigger must exist
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST. Create a trigger first: " +
                "workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Validate required fields: prompt, categories
        // prompt contains EVERYTHING: classification instruction + data references via {{...}}
        // (matches frontend ClassifyParametersForm which has only a prompt field, no separate input)
        String prompt = safeString(parameters.get("prompt"));
        if (prompt == null || prompt.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "'prompt' is required - classification instruction INCLUDING data references. " +
                "Use {{type:label.output.field}} to include data. Example: " + EXAMPLE);
        }

        // 4. Validate categories: array of {label, description} with min 2 items
        Object categoriesObj = parameters.get("categories");
        List<Map<String, String>> categories = parseCategories(categoriesObj);
        if (categories == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "'categories' must be array of {label, description} objects (min 2). Example: " + EXAMPLE);
        }
        if (categories.size() < 2) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "'categories' requires at least 2 items. Example: " + EXAMPLE);
        }

        // Validate each category has label and description
        for (int i = 0; i < categories.size(); i++) {
            Map<String, String> cat = categories.get(i);
            if (cat.get("label") == null || cat.get("label").isBlank()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "categories[" + i + "] missing 'label'. Each category needs {label, description}. Example: " + EXAMPLE);
            }
            if (cat.get("description") == null || cat.get("description").isBlank()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "categories[" + i + "] missing 'description'. AI needs it to understand the category. Example: " + EXAMPLE);
            }
        }

        // 5. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = "agent:" + normalizedLabel;
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 6. Resolve connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 7. Build classify node
        // Note: isAgent=true is required because classify uses "agent:" prefix
        // and SessionNodeFinder.findNode() filters mcps by isAgent for agent: keys
        Map<String, Object> classifyNode = new LinkedHashMap<>();
        classifyNode.put("id", UUID.randomUUID().toString());
        classifyNode.put("type", "classify");
        classifyNode.put("label", label);
        classifyNode.put("isAgent", true);      // Required for agent: prefix lookup
        classifyNode.put("isClassify", true);   // Marks this as a classify node
        classifyNode.put("prompt", prompt);     // Contains instruction + data refs (execution resolveContent() falls back to prompt)
        classifyNode.put("categories", categories);
        classifyNode.put("position", calculatePosition(session, NodeType.MCP));

        // Build classifyOutputs for category ports (like forkOutputs for fork)
        // This enables edges from agent:label:category_N to target nodes
        List<Map<String, Object>> classifyOutputs = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            Map<String, String> cat = categories.get(i);
            classifyOutputs.add(Map.of(
                "id", nodeId + "-output-" + i,
                "label", cat.get("label"),
                "port", "category_" + i
            ));
        }
        classifyNode.put("classifyOutputs", classifyOutputs);

        // Optional parameters
        String provider = safeString(parameters.get("provider"));
        if (provider != null && !provider.isBlank()) {
            classifyNode.put("provider", provider);
        }
        String model = safeString(parameters.get("model"));
        if (model != null && !model.isBlank()) {
            classifyNode.put("model", model);
        }
        Object tempObj = parameters.get("temperature");
        if (tempObj instanceof Number temp) {
            classifyNode.put("temperature", temp.doubleValue());
        }

        // 8. Parent loop handling removed (while-loop cleanup)
        String parentLoopId = null;

        // 9. Add to session (deep-normalize all variable references) and create edge
        session.getMcps().add(LabelNormalizer.normalizeVariableReferencesDeep(classifyNode));
        createEdgeIfNeeded(session, connectAfter, nodeId);

        // 10. Store schema with correct output names
        Map<String, String> outputs = Map.of(
            "selected_category", "string",
            "confidence", "number"
        );
        Map<String, String> refs = Map.of(
            "selected_category", "{{agent:" + normalizedLabel + ".output.selected_category}}",
            "confidence", "{{agent:" + normalizedLabel + ".output.confidence}}"
        );
        session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(nodeId)
                .nodeType("classify")
                .label(label)
                .outputs(outputs)
                .referenceSyntax(refs)
                .build());

        // 11. Finalize
        boolean isOrphaned = finalizeNode(session, sessionStore, NodeType.MCP, nodeId, classifyNode, connectAfter);

        // 12. Check for data variables
        boolean hasVariables = prompt.contains("{{");

        // 13. Build response with warnings - pass full categories with descriptions
        Map<String, Object> response = responseOptimizer.buildClassifyResponse(session, nodeId, label,
            connectAfter, parentLoopId, categories, refs, hasVariables);

        // Show saved params so LLM knows what was actually stored
        Map<String, Object> savedParams = new LinkedHashMap<>();
        savedParams.put("prompt", prompt);
        savedParams.put("categories", categories);
        response.put("saved_params", savedParams);

        // 15. Progressive validation - check for orphan nodes
        int totalNodes = session.getTriggers().size() + session.getMcps().size() + session.getCores().size();
        if (totalNodes >= 3) {
            List<String> orphans = session.findOrphanNodes().stream()
                .filter(id -> !id.equals(nodeId))
                .toList();
            if (!orphans.isEmpty()) {
                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("other_orphan_nodes", orphans.stream()
                    .map(id -> Map.of("id", id, "logical_id", session.getLogicalId(id)))
                    .toList());
                validation.put("hint", "Other nodes are disconnected. Use workflow(action='connect', from='...', to='...')");
                response.put("progressive_validation", validation);
            }
        }

        return ToolExecutionResult.success(response);
    }

    /**
     * Parse categories from various input formats.
     * Accepts: [{label, description}, ...] or ["label1", "label2"] (legacy, adds empty description)
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseCategories(Object categoriesObj) {
        if (categoriesObj == null) return null;
        if (!(categoriesObj instanceof List<?> list)) return null;
        if (list.isEmpty()) return null;

        List<Map<String, String>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                // Object format: {label, description}
                Map<String, String> cat = new LinkedHashMap<>();
                cat.put("label", safeString(map.get("label")));
                cat.put("description", safeString(map.get("description")));
                result.add(cat);
            } else if (item instanceof String str) {
                // Legacy string format - reject, require proper format
                return null;
            } else {
                return null;
            }
        }
        return result;
    }
}
