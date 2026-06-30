package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.tools.workflow.builder.response.AgentResponseBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.response.ControlNodeResponseBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.response.TriggerStepResponseBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ResponseOptimizer - LLM-optimized response formatting for workflow.
 *
 * This class serves as a facade for specialized response builders:
 * <ul>
 *   <li>{@link TriggerStepResponseBuilder} - Trigger and step responses</li>
 *   <li>{@link AgentResponseBuilder} - Agent, guardrail, and classify responses</li>
 *   <li>{@link ControlNodeResponseBuilder} - Decision, loop, and split responses</li>
 * </ul>
 *
 * CORE PRINCIPLE: Efficiency = Clarity for the LLM (even if it uses more tokens)
 *
 * Priority Order:
 * 1. EFFICIENCY & CLARITY: LLM must understand QUICKLY and CORRECTLY
 * 2. JIT (Just-In-Time): Right info at the right moment
 * 3. TOKEN OPTIMIZATION: Last priority (never sacrifice clarity)
 *
 * @see TriggerStepResponseBuilder
 * @see AgentResponseBuilder
 * @see ControlNodeResponseBuilder
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseOptimizer {

    private final IntelligentContextAnalyzer contextAnalyzer;
    private final GraphAnalyzer graphAnalyzer;
    private final ResponseContextBuilder contextBuilder;
    private final TriggerStepResponseBuilder triggerStepResponseBuilder;
    private final AgentResponseBuilder agentResponseBuilder;
    private final ControlNodeResponseBuilder controlNodeResponseBuilder;

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOP-LEVEL RESPONSE BUILDERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Build optimized response after adding a node.
     *
     * @param session Workflow builder session
     * @param nodeType Type of node added (trigger, step, agent, decision, loop)
     * @param nodeData Node data
     * @return Optimized response map
     */
    public Map<String, Object> buildNodeAddedResponse(
            WorkflowBuilderSession session,
            String nodeType,
            Map<String, Object> nodeData
    ) {
        Map<String, Object> response = new LinkedHashMap<>();

        // RÉSULTAT (100-200 tokens)
        response.put("result", buildResultSection(nodeType, nodeData));

        // CONTEXTE (150-300 tokens)
        response.put("context", buildContextSection(session, nodeType, nodeData));

        // NEXT_OPTIONS (150-200 tokens)
        response.put("next_options", buildNextOptionsSection(session, nodeType, nodeData));

        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION BUILDERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Build RÉSULTAT section (100-200 tokens).
     */
    private Map<String, Object> buildResultSection(String nodeType, Map<String, Object> nodeData) {
        Map<String, Object> result = new LinkedHashMap<>();

        String nodeId = (String) nodeData.get("id");
        String label = (String) nodeData.get("label");

        result.put("node_type", nodeType);
        result.put("id", nodeId);
        result.put("label", label);

        // Add type-specific details (minimal)
        switch (nodeType) {
            case "trigger" -> {
                result.put("trigger_type", nodeData.get("type"));
                if (nodeData.containsKey("schedule")) {
                    result.put("schedule", nodeData.get("schedule"));
                }
            }
            case "agent" -> {
                result.put("task", nodeData.get("task"));
            }
            case "step" -> {
                result.put("step_type", nodeData.get("type"));
            }
            case "decision" -> {
                result.put("branches", nodeData.get("branches"));
            }
            case "loop" -> {
                result.put("condition", nodeData.get("condition"));
            }
        }

        return result;
    }

    /**
     * Build CONTEXTE section (150-300 tokens).
     */
    private Map<String, Object> buildContextSection(
            WorkflowBuilderSession session,
            String nodeType,
            Map<String, Object> nodeData
    ) {
        Map<String, Object> context = new LinkedHashMap<>();

        // Completeness assessment
        context.put("completeness", assessCompleteness(session));

        // Output schema for this node (JIT - only when relevant)
        if ("agent".equals(nodeType) || "step".equals(nodeType)) {
            context.put("output", getNodeOutputDescription(nodeType, nodeData));
        }

        // Accessible variables - simplified calculation without full WorkflowPlan conversion
        String nodeId = (String) nodeData.get("id");
        if (nodeId != null && !session.getEdges().isEmpty()) {
            Set<String> accessibleNodeIds = contextBuilder.getAccessibleNodes(session, nodeId);
            if (!accessibleNodeIds.isEmpty()) {
                context.put("accessible_variables_count", accessibleNodeIds.size());
                context.put("accessible_nodes", new ArrayList<>(accessibleNodeIds));
                context.put("hint", "Use describe(node_id='...') for full variable list from each node");
            }
        }

        return context;
    }

    /**
     * Build next options section - GENERIC references only.
     */
    private Map<String, Object> buildNextOptionsSection(
            WorkflowBuilderSession session,
            String nodeType,
            Map<String, Object> nodeData
    ) {
        Map<String, Object> nextOptions = new LinkedHashMap<>();

        nextOptions.put("available_actions", "workflow(action='list_nodes')");
        nextOptions.put("help", "workflow(action='help', topics=['node-id']) for detailed syntax");

        boolean hasTrigger = !session.getTriggers().isEmpty();
        boolean hasProcessing = !session.getMcps().isEmpty();
        if (hasTrigger && hasProcessing) {
            nextOptions.put("finalize", "workflow(action='finish') when ready");
        }

        return nextOptions;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get recommendation based on what was added.
     */
    private IntelligentContextAnalyzer.RecommendedNextStep getRecommendation(
            WorkflowBuilderSession session,
            String nodeType,
            Map<String, Object> nodeData
    ) {
        return switch (nodeType) {
            case "agent" -> contextAnalyzer.analyzeAfterAgent(session, nodeData);
            case "step" -> contextAnalyzer.analyzeAfterStep(session, nodeData);
            case "trigger" -> contextAnalyzer.analyzeAfterTrigger(session, nodeData);
            default -> new IntelligentContextAnalyzer.RecommendedNextStep(
                IntelligentContextAnalyzer.RecommendationStrength.WEAK,
                "Continue building or finalize workflow",
                null,
                List.of()
            );
        };
    }

    /**
     * Assess workflow completeness.
     */
    private Map<String, Boolean> assessCompleteness(WorkflowBuilderSession session) {
        Map<String, Boolean> completeness = new LinkedHashMap<>();

        completeness.put("has_trigger", !session.getTriggers().isEmpty());
        completeness.put("has_processing", !session.getMcps().isEmpty());

        boolean hasStorage = session.getMcps().stream()
            .anyMatch(step -> {
                Object id = step.get("id");
                if (id == null) return false;
                String stepId = id.toString();
                return stepId.startsWith("crud/") &&
                       (stepId.contains("insert") || stepId.contains("update") || stepId.contains("create"));
            });
        completeness.put("has_storage", hasStorage);
        completeness.put("has_display", !session.getInterfaces().isEmpty());

        return completeness;
    }

    /**
     * Get node output description (minimal, JIT).
     */
    private Map<String, String> getNodeOutputDescription(String nodeType, Map<String, Object> nodeData) {
        String label = (String) nodeData.get("label");
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);

        if ("agent".equals(nodeType)) {
            return Map.of(
                "description", "This agent will return structured or text results",
                "reference", "{{agent:" + normalizedLabel + ".output.response}}"
            );
        }

        if ("step".equals(nodeType)) {
            String stepType = (String) nodeData.get("type");
            return switch (stepType) {
                case "http" -> Map.of(
                    "description", "HTTP response body and status",
                    "reference", "{{mcp:" + normalizedLabel + ".output.response}}"
                );
                case "datasource" -> Map.of(
                    "description", "Query/operation result",
                    "reference", "{{mcp:" + normalizedLabel + ".output.result}}"
                );
                case null, default -> Map.of(
                    "description", "Step execution result",
                    "reference", "{{mcp:" + normalizedLabel + ".output}}"
                );
            };
        }

        return Map.of();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DELEGATED RESPONSE BUILDERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Build response for trigger.
     * @see TriggerStepResponseBuilder#buildTriggerResponse
     */
    public Map<String, Object> buildTriggerResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String type,
            String datasourceId,
            Map<String, String> outputs,
            Map<String, String> referenceSyntax
    ) {
        return triggerStepResponseBuilder.buildTriggerResponse(
            session, nodeId, label, type, datasourceId, outputs, referenceSyntax);
    }

    /**
     * Build response for step.
     * @see TriggerStepResponseBuilder#buildStepResponse
     */
    public Map<String, Object> buildStepResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String toolId,
            String connectAfter,
            String parentLoopId,
            boolean autoConnected,
            Map<String, String> outputRefs,
            List<String> missingRequired,
            Map<String, String> suggestedInputs,
            List<String> availableColumns,
            boolean hasAnyVariables
    ) {
        return triggerStepResponseBuilder.buildStepResponse(
            session, nodeId, label, toolId, connectAfter, parentLoopId, autoConnected,
            outputRefs, missingRequired, suggestedInputs, availableColumns, hasAnyVariables);
    }

    /**
     * Build response for agent.
     * @see AgentResponseBuilder#buildAgentResponse
     */
    public Map<String, Object> buildAgentResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String connectAfter,
            String parentLoopId,
            Map<String, Object> agentNode,
            Map<String, String> refs,
            List<String> missingToolInputs,
            Map<String, String> suggestedInputs,
            List<String> availableColumns,
            boolean hasAnyVariables
    ) {
        return agentResponseBuilder.buildAgentResponse(
            session, nodeId, label, connectAfter, parentLoopId, agentNode,
            refs, missingToolInputs, suggestedInputs, availableColumns, hasAnyVariables);
    }

    /**
     * Build response for guardrail.
     * @see AgentResponseBuilder#buildGuardrailResponse
     */
    public Map<String, Object> buildGuardrailResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String connectAfter,
            String parentLoopId,
            List<String> rules,
            Map<String, String> refs,
            boolean hasVariables
    ) {
        return agentResponseBuilder.buildGuardrailResponse(
            session, nodeId, label, connectAfter, parentLoopId, rules, refs, hasVariables);
    }

    /**
     * Build response for classify.
     * Categories include both label and description: [{label: "billing", description: "Payment issues"}, ...]
     * @see AgentResponseBuilder#buildClassifyResponse
     */
    public Map<String, Object> buildClassifyResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String connectAfter,
            String parentLoopId,
            List<Map<String, String>> categories,
            Map<String, String> refs,
            boolean hasVariables
    ) {
        return agentResponseBuilder.buildClassifyResponse(
            session, nodeId, label, connectAfter, parentLoopId, categories, refs, hasVariables);
    }

    /**
     * Build response for decision.
     * @see ControlNodeResponseBuilder#buildDecisionResponse
     */
    public Map<String, Object> buildDecisionResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            List<Map<String, Object>> conditions
    ) {
        return controlNodeResponseBuilder.buildDecisionResponse(session, nodeId, label, conditions);
    }

    /**
     * Build response for loop.
     * @see ControlNodeResponseBuilder#buildLoopResponse
     */
    public Map<String, Object> buildLoopResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String loopCondition
    ) {
        return controlNodeResponseBuilder.buildLoopResponse(session, nodeId, label, loopCondition);
    }

    /**
     * Build response for split.
     * @see ControlNodeResponseBuilder#buildSplitResponse
     */
    public Map<String, Object> buildSplitResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String list,
            int maxItems
    ) {
        return controlNodeResponseBuilder.buildSplitResponse(session, nodeId, label, list, maxItems);
    }

    /**
     * Build response for datasource step (with JIT schema).
     */
    public Map<String, Object> buildDatasourceStepResponse(
            WorkflowBuilderSession session,
            Map<String, Object> stepData,
            Map<String, Object> datasourceSchema
    ) {
        Map<String, Object> response = buildNodeAddedResponse(session, "step", stepData);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.get("context");
        context.put("datasource_schema", datasourceSchema);
        context.put("validation", "✅ All row_data keys match datasource columns");

        return response;
    }

    /**
     * Build response for interface step.
     */
    public Map<String, Object> buildInterfaceStepResponse(
            WorkflowBuilderSession session,
            Map<String, Object> stepData,
            String interfaceId
    ) {
        Map<String, Object> response = buildNodeAddedResponse(session, "step", stepData);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.get("context");
        @SuppressWarnings("unchecked")
        Map<String, Boolean> completeness = (Map<String, Boolean>) context.get("completeness");
        completeness.put("has_display", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> nextOptions = (Map<String, Object>) response.get("next_options");
        nextOptions.put("recommended", "✅ Workflow is complete! Call workflow(action='finish') to save and publish it.");
        nextOptions.put("action", "workflow(action='finish')");

        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOKEN ESTIMATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Estimate token count for a response (rough approximation: 1 token ≈ 4 chars).
     */
    public int estimateTokenCount(Map<String, Object> response) {
        String json = response.toString();
        return json.length() / 4;
    }
}
