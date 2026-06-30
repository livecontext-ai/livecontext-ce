package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.ToolRef;

import java.util.Map;

/**
 * Interface for tool execution.
 * Follows SOLID principles and best practices.
 */
public interface ToolsGateway {

    /**
     * Simulate tool execution.
     */
    ExecutionResult simulateTool(ToolRef tool, Map<String, Object> input, String tenantId);

    /**
     * Execute a tool.
     */
    ExecutionResult executeTool(ToolRef tool, Map<String, Object> input, String tenantId);

    /**
     * Execute a tool with explicit billing identifiers. Callers from
     * billable surfaces (workflow steps, agent loop, image-gen module)
     * should pass identifiers extracted from their context so the
     * downstream {@code CatalogBillingDispatcher} can:
     * <ul>
     *   <li>SKIP billing when {@code __workflowRunId__} is present
     *       (the workflow path's {@code StepCompletionOrchestrator}
     *       handles {@code WORKFLOW_NODE} + markup billing for those
     *       calls - the dispatcher must not double-bill).</li>
     *   <li>Build idempotent {@code sourceId}s from
     *       {@code __streamId__ / __toolCallId__ / __callIndex__} for
     *       chat-scope calls (e.g. agent invokes image-gen directly).</li>
     * </ul>
     *
     * <p>The default implementation forwards to the 3-arg
     * {@link #executeTool(ToolRef, Map, String)} so existing
     * implementations don't need to update - they simply lose the
     * billing-skip optimisation until they override this overload.
     */
    default ExecutionResult executeTool(ToolRef tool, Map<String, Object> input, String tenantId,
                                         Map<String, Object> billingIdentifiers) {
        return executeTool(tool, input, tenantId);
    }
}
