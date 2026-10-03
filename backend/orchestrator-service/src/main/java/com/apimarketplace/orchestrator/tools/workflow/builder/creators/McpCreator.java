package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Creates MCP (tool/API) nodes for workflows.
 *
 * Handles catalog tool nodes identified by UUID. CRUD/table operations are handled by {@link TableCreator}.
 *
 * Format:
 *   workflow(action='add_node', type='<tool-uuid>', label='Send Email', params={to: '...', subject: '...'})
 *   - type = tool UUID (from catalog(action='search'))
 *   - params = flat tool parameters (no nested 'input')
 *
 * Reserved fields (not passed to tool): label, name, connect_after, connect_after_loop, interface_id
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpCreator extends CreatorBase {

    private final WorkflowBuilderSessionStore sessionStore;
    private final ToolSchemaFetcher toolSchemaFetcher;
    private final DataSourceClient dataSourceClient;
    private final CredentialClient credentialClient;
    private final ResponseOptimizer responseOptimizer;

    /** Reserved parameter names - not passed to the tool */
    private static final Set<String> RESERVED_PARAMS = Set.of(
        "label", "name", "connect_after", "connect_after_loop", "interface_id",
        "type", "action", "session_id", "params", "parameters",
        // Reserved because everything NOT listed here is forwarded to the external
        // API as a tool parameter. Left out, a credential selector would travel to
        // the provider as an argument it never declared, and the step would still
        // run on the default account: the parameter would look accepted and do
        // nothing, which is the exact failure this list prevents.
        "credential_selector", "credentialSelector"
    );

    /**
     * Execute add_mcp action with toolId from type parameter.
     * New format: type='<tool-uuid>' directly, params are flat.
     *
     * @param session Current workflow builder session
     * @param parameters Flat parameters (tool params + reserved fields)
     * @param toolId Tool UUID from the 'type' field
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddMcp(WorkflowBuilderSession session, Map<String, Object> parameters, String toolId) {
        // 1. Validate label
        String label = getString(parameters, "label", "name");
        if (label == null || label.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "label is REQUIRED. Example: workflow(action='add_node', type='<tool-uuid>', label='Send Email', params={to: '{{...}}', subject: '{{...}}'})");
        }

        // 2. Trigger must exist
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Validate tool ID (passed from type parameter)
        if (toolId == null || toolId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Tool ID is required. Use catalog(action='search') to find tool UUIDs, then:\n" +
                "workflow(action='add_node', type='<tool-uuid>', label='Send Email', params={to: '{{...}}', subject: '{{...}}'})");
        }

        // 3.5. Verify the tool actually exists in the catalog (skip reserved sentinels).
        // Without this check, the LLM can fabricate UUIDs (or strings like "Label_1") and
        // the workflow only fails at execution time. Use the tri-state check so a transient
        // catalog outage (UNKNOWN) doesn't masquerade as "not found".
        Optional<Map<String, Object>> preFetchedToolInfo = Optional.empty();
        if (!ToolSchemaFetcher.isReservedToolSentinel(toolId)) {
            ToolSchemaFetcher.ToolExistence existence = toolSchemaFetcher.checkToolExists(toolId);
            if (existence == ToolSchemaFetcher.ToolExistence.NOT_FOUND) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Tool ID '" + toolId + "' does not exist in the catalog. " +
                    "You MUST use a real UUID returned by catalog(action='search'). " +
                    "Do NOT invent tool IDs or reuse the same UUID across multiple nodes for different tools. " +
                    "Run catalog(action='search', query='<what you need>') first, copy the exact `id` field from the result, " +
                    "and pass it as the `type` parameter.");
            }
            // EXISTS or UNKNOWN: proceed. For EXISTS the cached info is reused below.
            // For UNKNOWN we accept the node tentatively - the catalog was unreachable
            // and we don't want to block legitimate work on a transient outage. The
            // execution layer will surface the real error if the tool truly doesn't exist.
            preFetchedToolInfo = toolSchemaFetcher.fetchToolInfo(toolId);
        }

        // 4. Generate node ID and validate uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.MCP.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 5. Resolve and validate connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 6. Build step node
        Map<String, Object> stepNode = buildStepNode(parameters, label, toolId, session);

        // 7. Resolve tool info (reuses the pre-fetched response from step 3.5) and check credentials
        ToolInfo toolInfo = fetchToolInfo(toolId, session, nodeId, label, preFetchedToolInfo);
        if (toolInfo.iconSlug != null) {
            stepNode.put("iconSlug", toolInfo.iconSlug);
        }

        // 8. Add to session (deep-normalize all variable references) and create edge
        session.getMcps().add(LabelNormalizer.normalizeVariableReferencesDeep(stepNode));
        createEdgeIfNeeded(session, connectAfter, nodeId);

        // 9. Fetch and store schema
        storeToolSchema(session, nodeId, label, toolId);

        // 10. Handle loop exit
        var loopExitError = handleLoopExit(session, parameters, nodeId);
        if (loopExitError != null) return loopExitError;

        // 11. Finalize
        finalizeNode(session, sessionStore, NodeType.MCP, nodeId, stepNode, connectAfter);

        // 12. Build response
        return buildResponse(session, nodeId, label, toolId, connectAfter, stepNode, toolInfo);
    }

    // ==================== Private Helpers ====================

    private record ToolInfo(String iconSlug, String toolName, boolean credentialRequired, boolean userHasCredential, String serviceName) {}

    private Map<String, Object> buildStepNode(Map<String, Object> parameters, String label, String toolId, WorkflowBuilderSession session) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", toolId);

        // Determine type based on toolId
        String stepType = "__transform__".equals(toolId) ? "transform"
            : "__wait__".equals(toolId) ? "wait"
            : "mcp";
        node.put("type", stepType);
        node.put("label", label);
        node.put("position", calculatePosition(session, NodeType.MCP));

        // Flat params - everything not in RESERVED_PARAMS is a tool parameter
        // Variable references are deep-normalized when the node is added to session
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

        String interfaceId = (String) parameters.get("interface_id");
        if (interfaceId != null) {
            node.put("interfaceId", interfaceId);
        }

        // WHICH account this step runs on, when the answer is only known at run
        // time. Lifted out of the tool parameters and onto the node, under the key
        // the plan parser reads, because it configures the step rather than being
        // an argument for the provider.
        // PRESENT, not non-blank. Absent means the step has no dynamic selection;
        // present-but-blank means one was chosen and left unwritten, which fails the
        // run rather than quietly using the account default. firstNonBlank collapses
        // the two, which made add_node unable to express the second state at all
        // while modify could - the same input meaning two things on two surfaces.
        // camelCase first, matching modify, so the same pair of keys cannot mean two
        // different things on the two agent surfaces. Any non-null value is accepted
        // and stringified, matching the plan parser: a number is a legitimate value
        // here (a credential id), and rejecting it only on THIS surface is the
        // accepted-here-dropped-there trap - the node would be created, no error
        // raised, and the step would run on the default account.
        for (String key : new String[]{"credentialSelector", "credential_selector"}) {
            if (parameters.containsKey(key) && parameters.get(key) != null) {
                node.put("credentialSelector", String.valueOf(parameters.get(key)).trim());
                break;
            }
        }

        return node;
    }

    private ToolInfo fetchToolInfo(String toolId, WorkflowBuilderSession session, String nodeId, String label,
                                   Optional<Map<String, Object>> preFetched) {
        String iconSlug = null;
        String toolName = null;
        boolean credentialRequired = false;
        boolean userHasCredential = true;
        String serviceName = null;

        Optional<Map<String, Object>> toolInfoOpt = preFetched != null && preFetched.isPresent()
            ? preFetched
            : toolSchemaFetcher.fetchToolInfo(toolId);
        if (toolInfoOpt.isPresent()) {
            Map<String, Object> info = toolInfoOpt.get();
            iconSlug = info.get("iconSlug") != null ? info.get("iconSlug").toString() : null;
            toolName = info.get("name") != null ? info.get("name").toString() : null;
            // The previous predicate read `credentialMode` from the tool-info
            // payload - a column that has been removed. The agent now derives
            // requirement from `authType`: any value other than "none" implies
            // a credential is required (the per-call resolver decides whether
            // user or platform pool answers; this flag only drives the
            // pre-flight UX hint about whether the user has connected any
            // credential at all).
            Object authType = info.get("authType");
            credentialRequired = authType != null && !"none".equalsIgnoreCase(authType.toString());
        }

        // Check credentials
        if (credentialRequired && iconSlug != null) {
            String serviceType = iconSlug.toLowerCase();
            serviceName = iconSlug.substring(0, 1).toUpperCase() + iconSlug.substring(1);
            try {
                List<CredentialSummaryDto> creds = credentialClient.getAllCredentials(session.getTenantId());
                userHasCredential = creds.stream()
                    .anyMatch(c -> c.getIntegration() != null &&
                        (c.getIntegration().toLowerCase().contains(serviceType) || c.getIntegration().toLowerCase().startsWith(serviceType)));

                if (!userHasCredential) {
                    session.trackMissingCredential(nodeId, serviceType, serviceName, iconSlug);
                }
            } catch (Exception e) {
                log.warn("Failed to check credentials: {}", e.getMessage());
            }
        }

        return new ToolInfo(iconSlug, toolName, credentialRequired, userHasCredential, serviceName);
    }

    private void storeToolSchema(WorkflowBuilderSession session, String nodeId, String label, String toolId) {
        Optional<ToolSchemaFetcher.ToolSchemaResult> schemaOpt = toolSchemaFetcher.fetchToolSchema(toolId);
        if (schemaOpt.isPresent()) {
            var schema = schemaOpt.get();
            session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(nodeId)
                .nodeType("step")
                .label(label)
                .toolId(toolId)
                .outputs(toolSchemaFetcher.pathsToOutputSchema(schema.getPaths()))
                .referenceSyntax(toolSchemaFetcher.generateReferenceSyntax(nodeId, schema.getPaths()))
                .build());
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
                                               String connectAfter, Map<String, Object> stepNode, ToolInfo toolInfo) {
        // Get output refs
        Map<String, String> outputRefs = null;
        Optional<ToolSchemaFetcher.ToolSchemaResult> schemaOpt = toolSchemaFetcher.fetchToolSchema(toolId);
        if (schemaOpt.isPresent()) {
            outputRefs = toolSchemaFetcher.generateReferenceSyntax(nodeId, schemaOpt.get().getPaths());
        }

        // Check missing inputs
        List<String> missingRequired = null;
        Map<String, String> suggestedInputs = null;
        List<String> availableColumns = getAvailableColumnsFromSession(session, dataSourceClient, session.getTenantId());

        Optional<ToolSchemaFetcher.ToolInputSchema> inputSchemaOpt = toolSchemaFetcher.fetchToolInputSchema(toolId);
        Map<String, Object> mergedParams = stepNode.get("params") instanceof Map ? (Map<String, Object>) stepNode.get("params") : Map.of();

        if (inputSchemaOpt.isPresent() && inputSchemaOpt.get().hasRequiredParameters()) {
            Set<String> provided = mergedParams.keySet();
            missingRequired = inputSchemaOpt.get().getRequiredParameters().keySet().stream()
                .filter(p -> !provided.contains(p))
                .toList();

            if (!missingRequired.isEmpty() && !availableColumns.isEmpty()) {
                suggestedInputs = new LinkedHashMap<>();
                Set<String> cols = new HashSet<>(availableColumns);
                for (String m : missingRequired) {
                    String match = cols.stream()
                        .filter(c -> c.toLowerCase().contains(m.toLowerCase()) || m.toLowerCase().contains(c.toLowerCase()))
                        .findFirst()
                        .orElse(cols.stream().findFirst().orElse("column"));
                    suggestedInputs.put(m, "{{" + match + "}}");
                }
            }
        }

        // Check for variables in input
        boolean hasVariables = !mergedParams.isEmpty() && mergedParams.values().stream()
            .anyMatch(v -> v != null && v.toString().contains("{{"));

        // Build response
        Map<String, Object> response = responseOptimizer.buildStepResponse(session, nodeId, label, toolId, connectAfter, null,
            connectAfter != null, outputRefs, missingRequired, suggestedInputs, availableColumns, hasVariables);

        // Show the saved params so LLM knows what was actually stored
        if (!mergedParams.isEmpty()) {
            response.put("saved_params", mergedParams);
        } else {
            response.put("saved_params", "EMPTY - no params provided");
        }

        // Add credential warning if needed
        if (toolInfo.credentialRequired && !toolInfo.userHasCredential && toolInfo.serviceName != null) {
            response.put("CREDENTIAL_NEEDED", Map.of(
                "service", toolInfo.serviceName,
                "status", "NOT_CONNECTED",
                "impact", "Credentials must be connected before execution"
            ));
        }

        return ToolExecutionResult.success(response,
            resultMetadata(toolInfo.iconSlug, toolInfo.toolName, label, toolInfo.credentialRequired));
    }

    /** Metadata of an add_node result: what the node card is drawn with. */
    static Map<String, Object> resultMetadata(String iconSlug, String toolName, String label,
                                              boolean credentialRequired) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (iconSlug != null) {
            metadata.put("iconSlug", iconSlug);
            // Adding a Gmail node to a plan reads no mailbox: the icon labels the node card only, so
            // the result is not restricted data (CASA LC-066) and the agent sees it on any model.
            metadata.put(com.apimarketplace.common.classification.RestrictedDataPolicy.CREDENTIAL_NEEDED_KEY, true);
        }
        if (toolName != null) metadata.put("toolName", toolName);
        metadata.put("label", label);
        if (credentialRequired) metadata.put("credentialRequired", true);
        return metadata;
    }
}
