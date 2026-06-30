package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Task node - CRUD operations on agent tasks directly from a workflow.
 *
 * Supports five operations:
 *  - create_task: create a new task (optionally assigned to an agent)
 *  - get_task: fetch a single task by ID
 *  - update_task: update an existing task (title, instructions, priority, status, assignee)
 *  - delete_task: cancel/delete a task by ID
 *  - list_tasks: list tasks with optional filters (status, priority, assignee, search)
 *
 * Calls agent-service via AgentClient (REST over internal network).
 */
public class TaskNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(TaskNode.class);

    private final Core.TaskConfig config;
    private AgentClient agentClient;

    public TaskNode(String nodeId, Core.TaskConfig config) {
        super(nodeId, NodeType.TASK);
        this.config = config;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.agentClient = registry.getAgentClient();
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        String operation = config != null ? config.operation() : "list_tasks";

        logger.info("Task node executing: nodeId={}, operation={}, itemId={}",
            nodeId, operation, context.itemId());

        Map<String, Object> earlyInputData = new LinkedHashMap<>();
        earlyInputData.put("operation", operation);

        if (config == null) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Task configuration is required.",
                Map.of("resolved_params", earlyInputData),
                System.currentTimeMillis() - startTime);
        }

        if (agentClient == null) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "AgentClient is not available. Cannot communicate with agent-service.",
                Map.of("resolved_params", earlyInputData),
                System.currentTimeMillis() - startTime);
        }

        String tenantId = context.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "tenantId is required but not available in execution context.",
                Map.of("resolved_params", earlyInputData),
                System.currentTimeMillis() - startTime);
        }

        try {
            Map<String, Object> result = switch (operation) {
                case "create_task" -> executeCreate(context, tenantId, earlyInputData);
                case "get_task" -> executeGet(context, tenantId, earlyInputData);
                case "update_task" -> executeUpdate(context, tenantId, earlyInputData);
                case "delete_task" -> executeDelete(context, tenantId, earlyInputData);
                case "list_tasks" -> executeList(context, tenantId, earlyInputData);
                default -> throw new IllegalArgumentException(
                    "Unknown task operation: " + operation +
                    ". Valid: create_task, get_task, update_task, delete_task, list_tasks");
            };

            result.put("resolved_params", earlyInputData);
            logger.info("Task node completed: nodeId={}, operation={}, success={}",
                nodeId, operation, result.get("success"));
            return successWithMetadata(result, context);

        } catch (Exception e) {
            logger.error("Task node failed: nodeId={}, operation={}, error={}",
                nodeId, operation, e.getMessage(), e);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(),
                Map.of("resolved_params", earlyInputData),
                System.currentTimeMillis() - startTime);
        }
    }

    private Map<String, Object> executeCreate(ExecutionContext context, String tenantId,
                                               Map<String, Object> inputData) {
        String title = resolveTemplateString(config.title(), context);
        String instructions = resolveTemplateString(config.instructions(), context);
        String agentIdStr = resolveTemplateString(config.agentId(), context);
        String reviewerStr = resolveTemplateString(config.reviewerAgentId(), context);
        String priority = config.priority();

        inputData.put("title", title);
        inputData.put("instructions", instructions);
        inputData.put("agent_id", agentIdStr);
        inputData.put("reviewer_agent_id", reviewerStr);
        inputData.put("priority", priority);

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("'title' is required for create_task operation.");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        if (instructions != null && !instructions.isBlank()) request.put("instructions", instructions);
        if (priority != null && !priority.isBlank()) request.put("priority", priority);
        if (agentIdStr != null && !agentIdStr.isBlank()) request.put("agentId", agentIdStr);
        if (reviewerStr != null && !reviewerStr.isBlank()) request.put("reviewerAgentId", reviewerStr);
        if (config.taskContext() != null && !config.taskContext().isEmpty()) {
            // Resolve template expressions inside taskContext values
            Map<String, Object> resolvedCtx = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : config.taskContext().entrySet()) {
                if (e.getValue() instanceof String s) {
                    resolvedCtx.put(e.getKey(), resolveTemplateString(s, context));
                } else {
                    resolvedCtx.put(e.getKey(), e.getValue());
                }
            }
            request.put("taskContext", resolvedCtx);
        }

        Map<String, Object> response = agentClient.createTaskForWorkflow(tenantId, request);
        if (response == null) {
            throw new IllegalStateException("Failed to create task - agent-service returned no response.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "TASK");
        result.put("operation", "create_task");
        result.put("success", true);
        result.put("task", response);
        return result;
    }

    private Map<String, Object> executeGet(ExecutionContext context, String tenantId,
                                            Map<String, Object> inputData) {
        String taskIdStr = resolveTemplateString(config.taskId(), context);
        inputData.put("task_id", taskIdStr);

        if (taskIdStr == null || taskIdStr.isBlank()) {
            throw new IllegalArgumentException("'taskId' is required for get_task operation.");
        }

        UUID taskId = UUID.fromString(taskIdStr.trim());
        Map<String, Object> response = agentClient.getTaskForWorkflow(tenantId, taskId);
        if (response == null) {
            throw new IllegalStateException("Failed to get task " + taskIdStr + " - agent-service returned no response.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "TASK");
        result.put("operation", "get_task");
        result.put("success", true);
        result.put("task", response);
        return result;
    }

    private Map<String, Object> executeUpdate(ExecutionContext context, String tenantId,
                                               Map<String, Object> inputData) {
        String taskIdStr = resolveTemplateString(config.taskId(), context);
        String title = resolveTemplateString(config.title(), context);
        String instructions = resolveTemplateString(config.instructions(), context);
        String agentIdStr = resolveTemplateString(config.agentId(), context);
        String priority = config.priority();
        String status = config.status();

        inputData.put("task_id", taskIdStr);
        inputData.put("title", title);
        inputData.put("instructions", instructions);
        inputData.put("priority", priority);
        inputData.put("status", status);
        inputData.put("agent_id", agentIdStr);

        if (taskIdStr == null || taskIdStr.isBlank()) {
            throw new IllegalArgumentException("'taskId' is required for update_task operation.");
        }

        UUID taskId = UUID.fromString(taskIdStr.trim());
        Map<String, Object> request = new LinkedHashMap<>();
        if (title != null && !title.isBlank()) request.put("title", title);
        if (instructions != null && !instructions.isBlank()) request.put("instructions", instructions);
        if (priority != null && !priority.isBlank()) request.put("priority", priority);
        if (status != null && !status.isBlank()) request.put("status", status);
        if (agentIdStr != null && !agentIdStr.isBlank()) request.put("agentId", agentIdStr);

        Map<String, Object> response = agentClient.updateTaskForWorkflow(tenantId, taskId, request);
        if (response == null) {
            throw new IllegalStateException("Failed to update task " + taskIdStr + " - agent-service returned no response.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "TASK");
        result.put("operation", "update_task");
        result.put("success", true);
        result.put("task", response);
        return result;
    }

    private Map<String, Object> executeDelete(ExecutionContext context, String tenantId,
                                               Map<String, Object> inputData) {
        String taskIdStr = resolveTemplateString(config.taskId(), context);
        inputData.put("task_id", taskIdStr);

        if (taskIdStr == null || taskIdStr.isBlank()) {
            throw new IllegalArgumentException("'taskId' is required for delete_task operation.");
        }

        UUID taskId = UUID.fromString(taskIdStr.trim());
        boolean deleted = agentClient.deleteTaskForWorkflow(tenantId, taskId);
        if (!deleted) {
            throw new IllegalStateException("Failed to delete task " + taskIdStr + " - agent-service returned error.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "TASK");
        result.put("operation", "delete_task");
        result.put("success", true);
        result.put("task_id", taskIdStr);
        return result;
    }

    private Map<String, Object> executeList(ExecutionContext context, String tenantId,
                                             Map<String, Object> inputData) {
        String status = config.status();
        String priority = config.priority();
        String agentIdStr = resolveTemplateString(config.agentId(), context);
        String search = resolveTemplateString(config.search(), context);
        int limit = config.limit() != null ? config.limit() : 50;

        inputData.put("status", status);
        inputData.put("priority", priority);
        inputData.put("agent_id", agentIdStr);
        inputData.put("search", search);
        inputData.put("limit", limit);

        Map<String, String> filters = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) filters.put("status", status);
        if (priority != null && !priority.isBlank()) filters.put("priority", priority);
        if (agentIdStr != null && !agentIdStr.isBlank()) filters.put("assignedTo", agentIdStr);
        if (search != null && !search.isBlank()) filters.put("search", search);
        filters.put("size", String.valueOf(limit));

        Map<String, Object> response = agentClient.listTasksForWorkflow(tenantId, filters);
        if (response == null) {
            throw new IllegalStateException("Failed to list tasks - agent-service returned no response.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "TASK");
        result.put("operation", "list_tasks");
        result.put("success", true);
        result.put("tasks", response.getOrDefault("tasks", List.of()));
        result.put("count", response.getOrDefault("count", 0));
        result.put("total", response.getOrDefault("total", 0));
        return result;
    }

    public Core.TaskConfig getConfig() { return config; }

    public static class Builder {
        private String nodeId;
        private Core.TaskConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder taskConfig(Core.TaskConfig config) { this.config = config; return this; }
        public Builder templateAdapter(Object adapter) { return this; } // injected via acceptServices
        public TaskNode build() { return new TaskNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}
