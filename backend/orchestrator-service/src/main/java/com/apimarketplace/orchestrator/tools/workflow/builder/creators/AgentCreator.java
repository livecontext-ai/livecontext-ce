package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
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
 * Handles agent creation for the workflow builder.
 *
 * Agent nodes require a pre-created agent entity (same pattern as InterfaceNodeCreator):
 *   1. agent(action='create', name='...', system_prompt='...') → gets UUID
 *   2. workflow(action='add_node', type='agent', label='...', params={agent_id: '<uuid>', prompt: '...'})
 *
 * The agent entity provides: system prompt, model config, tools config, memory support.
 * The workflow node provides: per-execution prompt, label, and connection wiring.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCreator extends CreatorBase {

    private final WorkflowBuilderSessionStore sessionStore;
    private final AgentClient agentClient;
    private final DataSourceClient dataSourceClient;
    private final ResponseOptimizer responseOptimizer;

    /**
     * Execute add_agent action.
     * Requires agent_id referencing a pre-created AgentEntity.
     */
    public ToolExecutionResult executeAddAgent(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label
        String label = safeString(parameters.get("label"));
        if (label == null) label = safeString(parameters.get("name"));
        var labelError = validateLabel(label, "agent");
        if (labelError != null) return labelError;

        // 2. Trigger must exist before adding agents
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add agent without a trigger. " +
                "Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Validate agent_id (required for type='agent', NOT for type='browser_agent')
        // browser_agent has no separate Agent entity - its config is inline on
        // the node (task, llm, max_steps, …). The plan parser routes by `type`
        // at execution time so the missing agentConfigId is harmless.
        boolean isBrowserAgent = "browser_agent".equalsIgnoreCase(safeString(parameters.get("type")))
            || "browser_agent".equalsIgnoreCase(safeString(parameters.get("agent_type")));
        String agentId = safeString(parameters.get("agent_id"));
        AgentDto agentEntity = null;

        if (!isBrowserAgent) {
            if (agentId == null || agentId.isBlank()) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "AGENT: 'agent_id' is required. Agent nodes need a pre-created agent entity.\n" +
                    "1. See all parameters: agent(action='help')\n" +
                    "2. Create the agent: agent(action='create', name='...', system_prompt='...')\n" +
                    "3. Add to workflow: workflow(action='add_node', type='agent', label='...', params={agent_id: '<uuid>', prompt: '...'}, connect_after='...')");
            }

            // 3b. Validate UUID format
            try {
                UUID.fromString(agentId);
            } catch (IllegalArgumentException e) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "AGENT: 'agent_id' must be a valid UUID. Got: '" + agentId + "'.\n" +
                    "Use the UUID returned by agent(action='create', ...) - e.g. '550e8400-e29b-41d4-a716-446655440000'.");
            }

            // 3c. Verify agent exists via agent-service
            agentEntity = agentClient.getAgent(UUID.fromString(agentId), session.getTenantId());
            if (agentEntity == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "AGENT: No agent found with id '" + agentId + "'.\n" +
                    "The agent may have been deleted. Create a new one: agent(action='create', name='...', system_prompt='...')\n" +
                    "Or list existing agents: agent(action='list')");
            }
        }

        // 4. Extract optional params
        String prompt = safeString(parameters.get("prompt"));
        Object withMemoryObj = parameters.get("withMemory");
        if (withMemoryObj == null) withMemoryObj = parameters.get("with_memory");
        boolean withMemory = withMemoryObj == null || Boolean.parseBoolean(withMemoryObj.toString());

        // 5. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.AGENT.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 6. Resolve connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 7. Build agent node (stored in mcps with isAgent=true)
        Map<String, Object> agentNode = new LinkedHashMap<>();
        agentNode.put("id", UUID.randomUUID().toString());
        // browser_agent uses the same agents-array slot but flags type so the
        // plan parser routes to BrowserAgentNode at execution time.
        agentNode.put("type", isBrowserAgent ? "browser_agent" : "agent");
        agentNode.put("label", label);
        agentNode.put("isAgent", true);
        if (!isBrowserAgent) {
            agentNode.put("agentConfigId", agentId);
            agentNode.put("agentConfigName", agentEntity.getName());
            if (agentEntity.getAvatarUrl() != null && !agentEntity.getAvatarUrl().isBlank()) {
                agentNode.put("agentAvatarUrl", agentEntity.getAvatarUrl());
            }
            agentNode.put("withMemory", withMemory);
        } else {
            // browser_agent stores its config inline (task, llm, max_steps,
            // start_url, expected_output_schema, …). The runner reads from
            // params at execution time.
            Map<String, Object> agentParams = new LinkedHashMap<>();
            for (String key : new String[] {
                "task", "start_url", "llm", "max_steps", "timeout_seconds",
                "expected_output_schema", "interaction_mode",
                "domain_allowlist", "domain_denylist", "screenshot_policy", "session"
            }) {
                Object v = parameters.get(key);
                if (v != null) agentParams.put(key, v);
            }
            agentNode.put("params", agentParams);
        }
        agentNode.put("position", calculatePosition(session, NodeType.AGENT));
        if (prompt != null && !prompt.isBlank()) {
            agentNode.put("prompt", prompt);
        }

        // 8. Add to session (deep-normalize all variable references) and create edge
        session.getMcps().add(LabelNormalizer.normalizeVariableReferencesDeep(agentNode));
        createEdgeIfNeeded(session, connectAfter, nodeId);

        // 9. Store agent schema (fixed outputs - must match AgentOutputSchemaMapper)
        Map<String, String> outputs = Map.of("response", "string", "tool_calls_detail", "array");
        Map<String, String> refs = Map.of(
            "response", "{{" + nodeId + ".output.response}}",
            "tool_calls_detail", "{{" + nodeId + ".output.tool_calls_detail}}"
        );
        session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(nodeId)
                .nodeType("agent")
                .label(label)
                .outputs(outputs)
                .referenceSyntax(refs)
                .build());

        // 10. Handle connect_after_loop parameter (loop exits)
        String exitFrom = (String) parameters.get("connect_after_loop");
        if (exitFrom != null && !exitFrom.isBlank()) {
            String resolvedLoopId = session.resolveNodeReference(exitFrom);
            if (!resolvedLoopId.startsWith("core:")) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "connect_after_loop must reference a loop. Use the loop's label (e.g., 'For Each Item').");
            }
            try {
                session.addPendingLoopExit(resolvedLoopId, nodeId, "agent");
            } catch (IllegalStateException e) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
            }
        }

        // 11. Finalize
        boolean isOrphaned = finalizeNode(session, sessionStore, NodeType.AGENT, nodeId, agentNode, connectAfter);

        // 12. Build response
        List<String> availableColumns = getAvailableColumnsFromSession(session, dataSourceClient, session.getTenantId());
        boolean hasAnyVariables = prompt != null && prompt.contains("{{");

        Map<String, Object> response = responseOptimizer.buildAgentResponse(session, nodeId, label, connectAfter, null,
                agentNode, refs, null, null, availableColumns, hasAnyVariables);

        // Show saved params so LLM knows what was actually stored
        // NOTE: only include actual node params (agent_id, prompt, withMemory).
        // Do NOT include agent entity fields (name, system_prompt) - those are NOT node params.
        Map<String, Object> savedParams = new LinkedHashMap<>();
        if (isBrowserAgent) {
            for (String k : new String[] {"task","start_url","llm","max_steps","timeout_seconds","interaction_mode"}) {
                Object v = parameters.get(k);
                if (v != null) savedParams.put(k, v);
            }
        } else {
            savedParams.put("agent_id", agentId);
            savedParams.put("withMemory", withMemory);
        }
        if (prompt != null && !prompt.isBlank()) savedParams.put("prompt", prompt);
        response.put("saved_params", savedParams);
        // Agent entity info shown separately (not as params)
        if (!isBrowserAgent && agentEntity != null) {
            response.put("agent_entity", Map.of("name", agentEntity.getName(), "id", agentId));
        }

        // Progressive validation - check for orphan nodes (only warn if workflow has 3+ nodes)
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
                validation.put("hint", "Other nodes are also disconnected. Use workflow(action='connect', from='Source Label', to='Target Label')");
                response.put("progressive_validation", validation);
            }
        }

        return ToolExecutionResult.success(response);
    }
}
