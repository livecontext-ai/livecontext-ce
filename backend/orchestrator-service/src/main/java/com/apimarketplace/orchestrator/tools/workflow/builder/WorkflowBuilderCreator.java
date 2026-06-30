package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.AgentCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.ClassifyCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.ControlNodeCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.GuardrailCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.McpCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.TableCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.TriggerCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Facade for workflow node creation operations.
 * Delegates to specialized creator classes following the Single Responsibility Principle (SOLID).
 *
 * Extracted creators:
 * - TriggerCreator: handles add_trigger action
 * - McpCreator: handles add_mcp action (catalog tool UUIDs)
 * - TableCreator: handles add_table action (CRUD operations)
 * - AgentCreator: handles add_agent action
 * - ControlNodeCreator: handles add_decision, add_split, add_fork, add_merge, add_transform, add_wait actions
 * - GuardrailCreator: handles add_guardrail action
 * - ClassifyCreator: handles add_classify action
 *
 * This class acts as a facade, providing a unified API while delegating
 * the actual implementation to specialized classes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBuilderCreator {

    private final WorkflowBuilderSessionStore sessionStore;

    // Extracted creators for better code organization (SOLID - Single Responsibility)
    private final TriggerCreator triggerCreator;
    private final McpCreator mcpCreator;
    private final TableCreator tableCreator;
    private final AgentCreator agentCreator;
    private final ControlNodeCreator coreCreator;
    private final GuardrailCreator guardrailCreator;
    private final ClassifyCreator classifyCreator;

    // ==================== Node Types ====================

    public enum NodeType {
        TRIGGER("trigger", "add_trigger"),
        MCP("mcp", "add_mcp"),
        AGENT("agent", "add_agent"),
        DECISION("decision", "add_decision"),
        SPLIT("split", "add_split");

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

    // ==================== Add Trigger ====================

    /**
     * Execute add_trigger action.
     * Delegates to TriggerCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddTrigger(WorkflowBuilderSession session, Map<String, Object> parameters, String tenantId) {
        return triggerCreator.executeAddTrigger(session, parameters, tenantId);
    }

    // ==================== Add Step (MCP/Tool) ====================

    /**
     * Execute add_mcp action with toolId from type parameter.
     * New unified format: type='<tool-uuid>' instead of type='mcp' + params.id
     * Delegates to McpCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddMcp(WorkflowBuilderSession session, Map<String, Object> parameters, String toolId) {
        return mcpCreator.executeAddMcp(session, parameters, toolId);
    }

    // ==================== Add Table (CRUD) ====================

    /**
     * Execute add_table action with a crud/ toolId.
     * Delegates to TableCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddTable(WorkflowBuilderSession session, Map<String, Object> parameters, String toolId) {
        return tableCreator.executeAddTable(session, parameters, toolId);
    }

    // ==================== Add Agent ====================

    /**
     * Execute add_agent action.
     * Delegates to AgentCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddAgent(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return agentCreator.executeAddAgent(session, parameters);
    }

    // ==================== Add Guardrail ====================

    /**
     * Execute add_guardrail action.
     * Delegates to GuardrailCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddGuardrail(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return guardrailCreator.executeAddGuardrail(session, parameters);
    }

    // ==================== Add Classify ====================

    /**
     * Execute add_classify action.
     * Delegates to ClassifyCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddClassify(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return classifyCreator.executeAddClassify(session, parameters);
    }

    // ==================== Add Decision (Condition) ====================

    /**
     * Execute add_decision action.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddDecision(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddDecision(session, parameters);
    }

    // ==================== Add Switch ====================

    /**
     * Execute add_switch action.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddSwitch(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSwitch(session, parameters);
    }

    // ==================== Add Split ====================

    /**
     * Execute add_split action.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddSplit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSplit(session, parameters);
    }

    /**
     * Execute add_fork action.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddFork(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddFork(session, parameters);
    }

    // ==================== Add Merge ====================

    /**
     * Execute add_merge action.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddMerge(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddMerge(session, parameters);
    }

    // ==================== Add Transform ====================

    /**
     * Execute add_transform action.
     * Transform applies data mappings - stored in cores but behaves as a passthrough step at execution.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddTransform(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddTransform(session, parameters);
    }

    // ==================== Add Wait ====================

    /**
     * Execute add_wait action.
     * Wait adds a delay - stored in cores but behaves as a passthrough step at execution.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddWait(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddWait(session, parameters);
    }

    // ==================== Add Download File ====================

    /**
     * Execute add_download_file action.
     * Downloads a file from URL and stores it for use in the workflow.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddDownloadFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddDownloadFile(session, parameters);
    }

    // ==================== Add HTTP Request ====================

    /**
     * Execute add_http_request action.
     * Makes HTTP requests to external APIs and returns the response.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddHttpRequest(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddHttpRequest(session, parameters);
    }

    // ==================== Add Stop ====================

    /**
     * Execute add_exit action.
     * Exit ends execution along this branch - terminal node.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddExit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddExit(session, parameters);
    }

    // ==================== Add Response ====================

    /**
     * Execute add_response action.
     * Sends a message to the chat interface.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddResponse(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddResponse(session, parameters);
    }

    // ==================== Add Option ====================

    /**
     * Execute add_option action.
     * Creates a multiple choice node where each choice has an expression.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddOption(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddOption(session, parameters);
    }

    // ==================== Add Aggregate ====================

    /**
     * Execute add_aggregate action.
     * Aggregates data from parallel Split executions.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddAggregate(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddAggregate(session, parameters);
    }

    // ==================== Add Loop ====================

    /**
     * Execute add_loop action.
     * Creates a loop node with body and exit ports.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddLoop(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddLoop(session, parameters);
    }

    // ==================== Add Approval ====================

    /**
     * Execute add_approval action.
     * Creates an approval node with approved/rejected/timeout ports.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddApproval(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddApproval(session, parameters);
    }

    // ==================== Add Data Input ====================

    /**
     * Execute add_data_input action.
     * Creates a data input node that provides text/file inputs to downstream nodes.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddDataInput(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddDataInput(session, parameters);
    }

    // ==================== Add Interface ====================

    /**
     * Execute add_interface action.
     * Displays an HTML interface linked to workflow outputs.
     * Delegates to ControlNodeCreator for the actual implementation.
     */
    public ToolExecutionResult executeAddInterface(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddInterface(session, parameters);
    }

    // ==================== Data Manipulation Nodes ====================

    public ToolExecutionResult executeAddFilter(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddFilter(session, parameters);
    }

    public ToolExecutionResult executeAddSort(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSort(session, parameters);
    }

    public ToolExecutionResult executeAddLimit(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddLimit(session, parameters);
    }

    public ToolExecutionResult executeAddRemoveDuplicates(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddRemoveDuplicates(session, parameters);
    }

    public ToolExecutionResult executeAddSummarize(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSummarize(session, parameters);
    }

    public ToolExecutionResult executeAddDateTime(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddDateTime(session, parameters);
    }

    public ToolExecutionResult executeAddCryptoJwt(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddCryptoJwt(session, parameters);
    }

    public ToolExecutionResult executeAddXml(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddXml(session, parameters);
    }

    public ToolExecutionResult executeAddCompression(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddCompression(session, parameters);
    }

    public ToolExecutionResult executeAddRss(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddRss(session, parameters);
    }

    public ToolExecutionResult executeAddConvertToFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddConvertToFile(session, parameters);
    }

    public ToolExecutionResult executeAddExtractFromFile(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddExtractFromFile(session, parameters);
    }

    public ToolExecutionResult executeAddCompareDatasets(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddCompareDatasets(session, parameters);
    }

    public ToolExecutionResult executeAddSubWorkflow(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSubWorkflow(session, parameters);
    }

    public ToolExecutionResult executeAddSet(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSet(session, parameters);
    }

    public ToolExecutionResult executeAddHtmlExtract(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddHtmlExtract(session, parameters);
    }

    public ToolExecutionResult executeAddTask(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddTask(session, parameters);
    }

    public ToolExecutionResult executeAddStopOnError(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddStopOnError(session, parameters);
    }

    public ToolExecutionResult executeAddSsh(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSsh(session, parameters);
    }

    public ToolExecutionResult executeAddSftp(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSftp(session, parameters);
    }

    public ToolExecutionResult executeAddDatabase(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddDatabase(session, parameters);
    }

    public ToolExecutionResult executeAddRespondToWebhook(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddRespondToWebhook(session, parameters);
    }

    public ToolExecutionResult executeAddSendEmail(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddSendEmail(session, parameters);
    }

    public ToolExecutionResult executeAddEmailInbox(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddEmailInbox(session, parameters);
    }

    public ToolExecutionResult executeAddCode(WorkflowBuilderSession session, Map<String, Object> parameters) {
        return coreCreator.executeAddCode(session, parameters);
    }

    // ==================== Add Edge ====================

    public ToolExecutionResult executeAddEdge(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // Accept both from/to and source/target (for backward compatibility with schema)
        String from = (String) parameters.get("from");
        if (from == null) from = (String) parameters.get("source");
        String to = (String) parameters.get("to");
        if (to == null) to = (String) parameters.get("target");

        if (from == null || to == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Both 'from' and 'to' (or 'source' and 'target') are required");
        }

        String resolvedFrom = session.resolveNodeReference(from);
        String resolvedTo = session.resolveNodeReference(to);

        createSimpleEdge(session, resolvedFrom, resolvedTo);
        sessionStore.save(session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("message", "Edge created: " + resolvedFrom + " → " + resolvedTo);

        return ToolExecutionResult.success(result);
    }

    // ==================== Helper Methods ====================

    /**
     * Create a simple edge between two nodes.
     * Used by executeAddEdge() for direct edge creation.
     */
    private void createSimpleEdge(WorkflowBuilderSession session, String from, String to) {
        // CRITICAL: Resolve labels to actual node IDs (trigger:xxx, agent:xxx)
        String resolvedFrom = session.resolveNodeReference(from);
        String resolvedTo = session.resolveNodeReference(to);

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

        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", resolvedFrom);
        edge.put("to", resolvedTo);
        session.getEdges().add(edge);

        log.debug("Created edge: {} -> {}", resolvedFrom, resolvedTo);
    }
}
