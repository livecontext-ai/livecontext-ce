package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TaskNode (Task CRUD operations).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskNode")
class TaskNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private AgentClient mockAgentClient;

    @Mock
    private ServiceRegistry mockRegistry;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1",
            "item-1", 0, new HashMap<>(), mockPlan
        );
        when(mockRegistry.getAgentClient()).thenReturn(mockAgentClient);
    }

    private TaskNode buildNode(Core.TaskConfig config) {
        TaskNode node = new TaskNode("core:task", config);
        node.acceptServices(mockRegistry);
        return node;
    }

    // ==================== Null/missing config ====================

    @Test
    @DisplayName("fails when config is null")
    void failsWhenConfigNull() {
        TaskNode node = new TaskNode("core:task", null);
        node.acceptServices(mockRegistry);
        NodeExecutionResult result = node.execute(context);
        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("configuration is required"));
    }

    @Test
    @DisplayName("fails when agentClient is unavailable")
    void failsWhenAgentClientNull() {
        when(mockRegistry.getAgentClient()).thenReturn(null);
        TaskNode node = new TaskNode("core:task", new Core.TaskConfig(
            "list_tasks", null, null, null, null, null, null, null, null, null, null));
        node.acceptServices(mockRegistry);
        NodeExecutionResult result = node.execute(context);
        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("AgentClient"));
    }

    @Test
    @DisplayName("fails when tenantId is blank")
    void failsWhenTenantIdBlank() {
        ExecutionContext blankCtx = ExecutionContext.create(
            "run-1", "workflow-run-1", "",
            "item-1", 0, new HashMap<>(), mockPlan
        );
        TaskNode node = buildNode(new Core.TaskConfig(
            "list_tasks", null, null, null, null, null, null, null, null, null, null));
        NodeExecutionResult result = node.execute(blankCtx);
        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("tenantId"));
    }

    @Test
    @DisplayName("fails on unknown operation")
    void failsOnUnknownOperation() {
        TaskNode node = buildNode(new Core.TaskConfig(
            "invalid_op", null, null, null, null, null, null, null, null, null, null));
        NodeExecutionResult result = node.execute(context);
        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("Unknown task operation"));
    }

    // ==================== create_task ====================

    @Nested
    @DisplayName("create_task")
    class CreateTask {

        @Test
        @DisplayName("succeeds with valid title")
        void createSucceeds() {
            Core.TaskConfig config = new Core.TaskConfig(
                "create_task", null, "Review doc", "Please review", "high",
                null, null, null, null, null, null);
            TaskNode node = buildNode(config);

            UUID taskId = UUID.randomUUID();
            when(mockAgentClient.createTaskForWorkflow(eq("tenant-1"), anyMap()))
                .thenReturn(Map.of("id", taskId.toString(), "title", "Review doc"));

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) result.output();
            assertEquals("create_task", output.get("operation"));
            assertEquals(true, output.get("success"));
            assertNotNull(output.get("task"));
        }

        @Test
        @DisplayName("fails when title is blank")
        void failsWithoutTitle() {
            Core.TaskConfig config = new Core.TaskConfig(
                "create_task", null, null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            NodeExecutionResult result = node.execute(context);
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("title"));
        }

        @Test
        @DisplayName("fails when agent-service returns null")
        void failsOnTransportError() {
            Core.TaskConfig config = new Core.TaskConfig(
                "create_task", null, "A task", null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            when(mockAgentClient.createTaskForWorkflow(eq("tenant-1"), anyMap())).thenReturn(null);

            NodeExecutionResult result = node.execute(context);
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("agent-service returned no response"));
        }
    }

    // ==================== get_task ====================

    @Nested
    @DisplayName("get_task")
    class GetTask {

        @Test
        @DisplayName("succeeds with valid taskId")
        void getSucceeds() {
            UUID taskId = UUID.randomUUID();
            Core.TaskConfig config = new Core.TaskConfig(
                "get_task", taskId.toString(), null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);

            when(mockAgentClient.getTaskForWorkflow("tenant-1", taskId))
                .thenReturn(Map.of("id", taskId.toString(), "title", "Some task"));

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("fails when taskId is blank")
        void failsWithoutTaskId() {
            Core.TaskConfig config = new Core.TaskConfig(
                "get_task", null, null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            NodeExecutionResult result = node.execute(context);
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("taskId"));
        }

        @Test
        @DisplayName("fails on invalid UUID format")
        void failsOnInvalidUuid() {
            Core.TaskConfig config = new Core.TaskConfig(
                "get_task", "not-a-uuid", null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            NodeExecutionResult result = node.execute(context);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("fails when agent-service returns null")
        void failsOnTransportError() {
            UUID taskId = UUID.randomUUID();
            Core.TaskConfig config = new Core.TaskConfig(
                "get_task", taskId.toString(), null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            when(mockAgentClient.getTaskForWorkflow("tenant-1", taskId)).thenReturn(null);

            NodeExecutionResult result = node.execute(context);
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("agent-service returned no response"));
        }
    }

    // ==================== update_task ====================

    @Nested
    @DisplayName("update_task")
    class UpdateTask {

        @Test
        @DisplayName("succeeds with valid taskId and fields")
        void updateSucceeds() {
            UUID taskId = UUID.randomUUID();
            Core.TaskConfig config = new Core.TaskConfig(
                "update_task", taskId.toString(), "New title", null, "urgent",
                null, null, "in_progress", null, null, null);
            TaskNode node = buildNode(config);

            when(mockAgentClient.updateTaskForWorkflow(eq("tenant-1"), eq(taskId), anyMap()))
                .thenReturn(Map.of("id", taskId.toString(), "title", "New title"));

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("fails without taskId")
        void failsWithoutTaskId() {
            Core.TaskConfig config = new Core.TaskConfig(
                "update_task", null, "New title", null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            NodeExecutionResult result = node.execute(context);
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("taskId"));
        }
    }

    // ==================== delete_task ====================

    @Nested
    @DisplayName("delete_task")
    class DeleteTask {

        @Test
        @DisplayName("succeeds when agent-service deletes")
        void deleteSucceeds() {
            UUID taskId = UUID.randomUUID();
            Core.TaskConfig config = new Core.TaskConfig(
                "delete_task", taskId.toString(), null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            when(mockAgentClient.deleteTaskForWorkflow("tenant-1", taskId)).thenReturn(true);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("fails when agent-service returns false")
        void failsWhenDeleteFails() {
            UUID taskId = UUID.randomUUID();
            Core.TaskConfig config = new Core.TaskConfig(
                "delete_task", taskId.toString(), null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            when(mockAgentClient.deleteTaskForWorkflow("tenant-1", taskId)).thenReturn(false);

            NodeExecutionResult result = node.execute(context);
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("agent-service returned error"));
        }
    }

    // ==================== list_tasks ====================

    @Nested
    @DisplayName("list_tasks")
    class ListTasks {

        @Test
        @DisplayName("succeeds with filters")
        void listSucceeds() {
            Core.TaskConfig config = new Core.TaskConfig(
                "list_tasks", null, null, null, "high", null, null, "pending", "review", 10, null);
            TaskNode node = buildNode(config);

            when(mockAgentClient.listTasksForWorkflow(eq("tenant-1"), anyMap()))
                .thenReturn(Map.of("tasks", List.of(Map.of("id", "t1")), "count", 1, "total", 1));

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) result.output();
            assertEquals("list_tasks", output.get("operation"));
            assertEquals(1, output.get("count"));
        }

        @Test
        @DisplayName("uses default limit of 50")
        void defaultLimit() {
            Core.TaskConfig config = new Core.TaskConfig(
                "list_tasks", null, null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);

            when(mockAgentClient.listTasksForWorkflow(eq("tenant-1"), anyMap()))
                .thenReturn(Map.of("tasks", List.of(), "count", 0, "total", 0));

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());

            // Verify the size parameter was 50
            verify(mockAgentClient).listTasksForWorkflow(eq("tenant-1"), argThat(filters ->
                "50".equals(filters.get("size"))
            ));
        }

        @Test
        @DisplayName("fails when agent-service returns null")
        void failsOnTransportError() {
            Core.TaskConfig config = new Core.TaskConfig(
                "list_tasks", null, null, null, null, null, null, null, null, null, null);
            TaskNode node = buildNode(config);
            when(mockAgentClient.listTasksForWorkflow(eq("tenant-1"), anyMap())).thenReturn(null);

            NodeExecutionResult result = node.execute(context);
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("agent-service returned no response"));
        }
    }
}
