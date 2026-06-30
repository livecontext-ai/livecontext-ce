package com.apimarketplace.orchestrator.tools.visualization;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;

/**
 * Provider for visualization utilities (internal use only).
 *
 * NOTE: The visualize tool is NO LONGER exposed to the LLM.
 * Instead, the LLM should include [visualize:type:id] markers in its response.
 * The frontend parses these markers and renders the visualization.
 *
 * This class is kept for:
 * - Anti-duplicate tracking: wasAlreadyVisualized(), markAsVisualized()
 * - Used by other providers (ApplicationCrudModule, WorkflowBuilderProvider)
 *
 * Features:
 * - Anti-duplicate: Tracks visualized resources per conversation to avoid re-display
 * - Auto-cleanup: Cache entries expire after 30 minutes of inactivity
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisualizationToolsProvider implements ToolsProvider {

    private final WorkflowManagementService workflowManagementService;
    private final DataSourceClient dataSourceClient;
    private final InterfaceClient interfaceClient;
    private final AgentClient agentClient;

    // Cache to track visualized resources per conversation: conversationId -> Set of "type:id"
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> visualizedCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    // Track last access time for cleanup
    private final java.util.concurrent.ConcurrentHashMap<String, Long> cacheAccessTime =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final long CACHE_EXPIRY_MS = 30 * 60 * 1000; // 30 minutes

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.VISUALIZATION;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        // Tool no longer exposed to LLM - use [visualize:type:id] marker instead
        return List.of();
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        // Tool no longer exposed - inform LLM to use marker instead
        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "The visualize tool is deprecated. Instead, include [visualize:type:id] marker in your response. " +
            "Example: [visualize:workflow:abc-123]");
    }

    // ==================== Tool Definition ====================

    private AgentToolDefinition buildVisualize() {
        List<ToolParameter> params = List.of(
            enumParam("type", "Type of resource to visualize", true, List.of("workflow", "table", "interface", "agent")),
            stringParam("id", "Resource ID (UUID for workflow/interface, integer for table)", true),
            stringParam("title", "Optional title to display above the visualization", false)
        );

        return AgentToolDefinition.builder()
            .name("visualize")
            .description("""
                Display a visual preview of a resource in the chat.
                Use this to show the user a workflow diagram, table data, or interface preview.
                The visualization appears inline in the conversation automatically.

                IMPORTANT: Call this tool ONCE per resource. After calling visualize, the resource IS displayed.
                Do NOT call visualize again for the same resource. Do NOT call list tools after visualize.
                Once visualize returns success, respond to the user - the visualization is already shown.
                """)
            .category(ToolCategory.VISUALIZATION)
            .parameters(params)
            .requiredParameters(List.of("type", "id"))
            .inputSchema(generateInputSchema(params, List.of("type", "id")))
            .helpText("""
                Displays a visual preview in the chat:
                - workflow: Mini workflow diagram (readonly ReactFlow)
                - table: Data table with pagination
                - interface: HTML preview in iframe (expandable)

                Example: visualize(type="workflow", id="abc-123", title="Order Processing Workflow")
                """)
            .requiresAuth(true)
            .tags(List.of("visualization", "display", "preview"))
            .build();
    }

    // ==================== Tool Execution ====================

    private ToolExecutionResult executeVisualize(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String type = (String) parameters.get("type");
        String id = (String) parameters.get("id");
        String title = (String) parameters.get("title");

        if (type == null || type.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "type is required (workflow, table, interface, or agent)");
        }
        if (id == null || id.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "id is required");
        }

        // Get conversationId from context for duplicate tracking
        String conversationId = getConversationId(context);
        String cacheKey = type.toLowerCase() + ":" + id;

        // Check if already visualized in this conversation
        // Note: We still return visualization metadata so frontend can display it (e.g., after page refresh)
        if (conversationId != null && isAlreadyVisualized(conversationId, cacheKey)) {
            log.info("🔄 [VISUALIZE] Already displayed {}:{} in conversation {}", type, id, conversationId);

            // Fetch title for the visualization
            String displayTitle = fetchDisplayTitle(type, id, title, tenantId);

            // Return with visualization metadata so frontend can still display it
            Map<String, Object> metadata = Map.of(
                "visualization", Map.of(
                    "type", type,
                    "id", id,
                    "title", displayTitle != null ? displayTitle : type + " " + id
                )
            );

            return ToolExecutionResult.success(Map.of(
                "status", "already_displayed",
                "type", type,
                "id", id,
                "message", "This " + type + " is already displayed in the conversation. No need to visualize again."
            ), metadata);
        }

        // Execute visualization (accept both "table" and "datasource" for backwards compatibility)
        ToolExecutionResult result = switch (type.toLowerCase()) {
            case "workflow" -> visualizeWorkflow(id, title, tenantId);
            case "table", "datasource" -> visualizeDatasource(id, title, tenantId);
            case "interface" -> visualizeInterface(id, title, tenantId);
            case "agent" -> visualizeAgent(id, title, tenantId);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid type. Valid values: workflow, table, interface, agent");
        };

        // Track successful visualization
        if (result.success() && conversationId != null) {
            markAsVisualized(conversationId, cacheKey);
        }

        return result;
    }

    private String getConversationId(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) {
            return null;
        }
        Object convId = context.credentials().get("conversationId");
        return convId != null ? convId.toString() : null;
    }

    private boolean isAlreadyVisualized(String conversationId, String cacheKey) {
        cleanupExpiredCache();
        java.util.Set<String> visualized = visualizedCache.get(conversationId);
        return visualized != null && visualized.contains(cacheKey);
    }

    private void markAsVisualized(String conversationId, String cacheKey) {
        visualizedCache.computeIfAbsent(conversationId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
            .add(cacheKey);
        cacheAccessTime.put(conversationId, System.currentTimeMillis());
        log.debug("📌 [VISUALIZE] Marked {}:{} as visualized", conversationId, cacheKey);
    }

    /**
     * Also allows external marking (for auto-visualize from create)
     */
    public void markAsVisualized(String conversationId, String type, String id) {
        if (conversationId != null) {
            markAsVisualized(conversationId, type + ":" + id);
        }
    }

    /**
     * Check if a resource was already visualized in this conversation.
     * Used by get actions to return short responses and avoid loops.
     */
    public boolean wasAlreadyVisualized(String conversationId, String type, String id) {
        if (conversationId == null) return false;
        return isAlreadyVisualized(conversationId, type + ":" + id);
    }

    private void cleanupExpiredCache() {
        long now = System.currentTimeMillis();
        cacheAccessTime.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > CACHE_EXPIRY_MS) {
                visualizedCache.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Fetch the display title for a resource (used for already_displayed responses)
     */
    private String fetchDisplayTitle(String type, String id, String providedTitle, String tenantId) {
        if (providedTitle != null && !providedTitle.isBlank()) {
            return providedTitle;
        }

        try {
            return switch (type.toLowerCase()) {
                case "workflow" -> {
                    var workflow = workflowManagementService.getWorkflow(UUID.fromString(id));
                    yield workflow.map(w -> w.getName()).orElse(null);
                }
                case "table", "datasource" -> {
                    DataSourceDto datasource = dataSourceClient.getDataSource(Long.parseLong(id), tenantId);
                    yield datasource != null ? datasource.name() : null;
                }
                case "interface" -> {
                    InterfaceDto iface = interfaceClient.getInterface(UUID.fromString(id), tenantId);
                    yield iface != null ? iface.getName() : null;
                }
                case "agent" -> {
                    AgentDto agent = agentClient.getAgent(UUID.fromString(id), tenantId);
                    yield agent != null ? agent.getName() : null;
                }
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Failed to fetch display title for {}:{}: {}", type, id, e.getMessage());
            return null;
        }
    }

    private ToolExecutionResult visualizeWorkflow(String id, String title, String tenantId) {
        try {
            UUID workflowId = UUID.fromString(id);
            var workflowOpt = workflowManagementService.getWorkflow(workflowId);

            if (workflowOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + id);
            }

            var workflow = workflowOpt.get();

            // Check tenant access
            String orgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, workflow.getTenantId(), workflow.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found: " + id);
            }

            String displayTitle = title != null ? title : workflow.getName();

            Map<String, Object> result = Map.of(
                "display", Map.of(
                    "type", "workflow",
                    "id", id,
                    "title", displayTitle,
                    "name", workflow.getName(),
                    "status", workflow.getStatus() != null ? workflow.getStatus() : "DRAFT"
                ),
                "marker", "[visualize:workflow:" + id + "]",
                "message", "Done. Workflow '" + displayTitle + "' is now displayed to the user. No need to get or describe it again."
            );

            // Put visualization in metadata for frontend display
            Map<String, Object> metadata = Map.of(
                "visualization", Map.of(
                    "type", "workflow",
                    "id", id,
                    "title", displayTitle
                )
            );

            return ToolExecutionResult.success(result, metadata);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow ID format. Expected UUID.");
        } catch (Exception e) {
            log.error("Error visualizing workflow {}: {}", id, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to visualize workflow: " + e.getMessage());
        }
    }

    private ToolExecutionResult visualizeDatasource(String id, String title, String tenantId) {
        try {
            Long datasourceId = Long.parseLong(id);
            DataSourceDto datasource = dataSourceClient.getDataSource(datasourceId, tenantId);

            if (datasource == null) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Table not found: " + id);
            }

            String displayTitle = title != null ? title : datasource.name();

            Map<String, Object> result = Map.of(
                "display", Map.of(
                    "type", "table",
                    "id", id,
                    "title", displayTitle,
                    "name", datasource.name(),
                    "sourceType", datasource.sourceType().name()
                ),
                "marker", "[visualize:datasource:" + id + "]",
                "message", "Done. Table '" + displayTitle + "' is now displayed to the user. No need to get or list it again."
            );

            // Put visualization in metadata for frontend display
            Map<String, Object> metadata = Map.of(
                "visualization", Map.of(
                    "type", "table",
                    "id", id,
                    "title", displayTitle
                )
            );

            return ToolExecutionResult.success(result, metadata);
        } catch (NumberFormatException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid table ID format. Expected integer.");
        } catch (Exception e) {
            log.error("Error visualizing table {}: {}", id, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to visualize table: " + e.getMessage());
        }
    }

    private ToolExecutionResult visualizeInterface(String id, String title, String tenantId) {
        try {
            UUID interfaceId = UUID.fromString(id);
            InterfaceDto interfaceEntity = interfaceClient.getInterface(interfaceId, tenantId);

            if (interfaceEntity == null) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Interface not found: " + id);
            }

            String displayTitle = title != null ? title : interfaceEntity.getName();

            Map<String, Object> result = Map.of(
                "display", Map.of(
                    "type", "interface",
                    "id", id,
                    "title", displayTitle,
                    "name", interfaceEntity.getName(),
                    "hasTargetTable", interfaceEntity.getTargetTable() != null && !interfaceEntity.getTargetTable().isBlank()
                ),
                "marker", "[visualize:interface:" + id + "]",
                "message", "Interface '" + displayTitle + "' is now displayed to the user. No need to get or describe it again."
            );

            // Put visualization in metadata for frontend display
            Map<String, Object> metadata = Map.of(
                "visualization", Map.of(
                    "type", "interface",
                    "id", id,
                    "title", displayTitle
                )
            );

            return ToolExecutionResult.success(result, metadata);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid interface ID format. Expected UUID.");
        } catch (Exception e) {
            log.error("Error visualizing interface {}: {}", id, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to visualize interface: " + e.getMessage());
        }
    }

    private ToolExecutionResult visualizeAgent(String id, String title, String tenantId) {
        try {
            UUID agentId = UUID.fromString(id);
            AgentDto agent = agentClient.getAgent(agentId, tenantId);
            if (agent == null) {
                return ToolExecutionResult.failure(
                    ToolErrorCode.RESOURCE_NOT_FOUND, "Agent not found: " + id);
            }
            String displayTitle = title != null ? title : agent.getName();
            Map<String, Object> result = Map.of(
                "display", Map.of(
                    "type", "agent",
                    "id", id,
                    "title", displayTitle,
                    "name", agent.getName(),
                    "modelProvider", agent.getModelProvider() != null ? agent.getModelProvider() : "",
                    "modelName", agent.getModelName() != null ? agent.getModelName() : ""
                ),
                "marker", "[visualize:agent:" + id + "]",
                "message", "Displaying agent '" + displayTitle + "'. The user can now see the agent configuration."
            );
            Map<String, Object> metadata = Map.of(
                "visualization", Map.of("type", "agent", "id", id, "title", displayTitle)
            );
            return ToolExecutionResult.success(result, metadata);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(
                ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid agent ID format. Expected UUID.");
        } catch (Exception e) {
            log.error("Error visualizing agent {}: {}", id, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to visualize agent: " + e.getMessage());
        }
    }
}
