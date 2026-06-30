package com.apimarketplace.orchestrator.execution.v2;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.engine.WorkflowResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.services.merge.MergeIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowExecutionServiceV2.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowExecutionServiceV2")
class WorkflowExecutionServiceV2Test {

    @Mock private UnifiedExecutionEngine mockEngine;
    @Mock private ExecutionTreeBuilder mockTreeBuilder;
    @Mock private V2ExecutionEventService mockEventService;
    @Mock private MergeIntegrationService mockMergeService;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository mockRunRepository;
    @Mock private WorkflowPlan mockPlan;
    @Mock private WorkflowExecution mockExecution;
    @Mock private ExecutionTree mockTree;

    private WorkflowExecutionServiceV2 service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionServiceV2(
            mockEngine, mockTreeBuilder, mockEventService, mockMergeService, mockRunRepository
        );
    }

    @Nested
    @DisplayName("executeWorkflow with trigger selection")
    class ExecuteWorkflowWithTrigger {

        @Test
        @DisplayName("should build tree and execute workflow")
        void shouldBuildTreeAndExecute() throws Exception {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            when(mockTreeBuilder.build(eq("run-1"), anyString(), eq("tenant-1"), eq(mockPlan),
                    org.mockito.ArgumentMatchers.<String>any(), org.mockito.ArgumentMatchers.<String>any())).thenReturn(mockTree);

            WorkflowResult expectedResult = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                1, 1, 0, List.of(), Optional.empty()
            );

            when(mockEngine.executeWorkflow(eq(mockTree), anyList(), eq(mockExecution), eq(mockEventService)))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

            List<Map<String, Object>> items = List.of(Map.of("data", "value"));
            CompletableFuture<WorkflowResult> future = service.executeWorkflow(
                mockPlan, items, "tenant-1", mockExecution, "trigger:my_webhook"
            );

            WorkflowResult result = future.get();
            assertNotNull(result);
            assertEquals("run-1", result.runId());
            assertEquals(1, result.successItems());

            verify(mockEventService).initializeExecution(mockExecution);
            verify(mockMergeService).initializeForWorkflow("run-1", mockPlan);
        }

        @Test
        @DisplayName("should create trigger items with correct IDs using triggerId")
        void shouldCreateTriggerItemsWithCorrectIds() throws Exception {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(),
                    org.mockito.ArgumentMatchers.<String>any(), org.mockito.ArgumentMatchers.<String>any())).thenReturn(mockTree);

            WorkflowResult expectedResult = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                2, 2, 0, List.of(), Optional.empty()
            );
            when(mockEngine.executeWorkflow(eq(mockTree), anyList(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

            List<Map<String, Object>> items = List.of(
                Map.of("key", "val1"),
                Map.of("key", "val2")
            );

            service.executeWorkflow(mockPlan, items, "tenant-1", mockExecution, "trigger:webhook");

            // Verify engine was called with the correct trigger items
            verify(mockEngine).executeWorkflow(eq(mockTree), argThat(triggerItems -> {
                if (triggerItems.size() != 2) return false;
                return triggerItems.get(0).itemId().equals("trigger:webhook-0")
                    && triggerItems.get(1).itemId().equals("trigger:webhook-1");
            }), eq(mockExecution), eq(mockEventService));
        }

        @Test
        @DisplayName("should return failed result when tree builder throws")
        void shouldReturnFailedWhenTreeBuilderThrows() throws Exception {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(),
                    org.mockito.ArgumentMatchers.<String>any(), org.mockito.ArgumentMatchers.<String>any()))
                .thenThrow(new RuntimeException("Build error"));

            List<Map<String, Object>> items = List.of(Map.of("data", "value"));
            CompletableFuture<WorkflowResult> future = service.executeWorkflow(
                mockPlan, items, "tenant-1", mockExecution, "trigger:webhook"
            );

            WorkflowResult result = future.get();
            assertNotNull(result);

            // Verify failure event was emitted
            verify(mockEventService).emitWorkflowComplete(mockExecution, false, "Build error");
            verify(mockEventService).cleanupExecution("run-1");
        }

        @Test
        @DisplayName("should handle engine execution exception")
        void shouldHandleEngineException() throws Exception {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(),
                    org.mockito.ArgumentMatchers.<String>any(), org.mockito.ArgumentMatchers.<String>any())).thenReturn(mockTree);

            CompletableFuture<WorkflowResult> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("Execution error"));
            when(mockEngine.executeWorkflow(any(), anyList(), any(), any()))
                .thenReturn(failedFuture);

            List<Map<String, Object>> items = List.of(Map.of("data", "value"));
            CompletableFuture<WorkflowResult> future = service.executeWorkflow(
                mockPlan, items, "tenant-1", mockExecution, "trigger:webhook"
            );

            WorkflowResult result = future.get();
            assertNotNull(result);
            // The exceptionally handler converts exceptions to WorkflowResult.failed
        }
    }

    @Nested
    @DisplayName("executeWorkflow backward-compatible overload")
    class ExecuteWorkflowBackwardCompatible {

        @Test
        @DisplayName("should use first trigger when triggers exist")
        void shouldUseFirstTrigger() throws Exception {
            Trigger trigger = mock(Trigger.class);
            when(trigger.getNormalizedKey()).thenReturn("trigger:my_webhook");
            when(mockPlan.getTriggers()).thenReturn(List.of(trigger));

            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(),
                    org.mockito.ArgumentMatchers.<String>any(), org.mockito.ArgumentMatchers.<String>any())).thenReturn(mockTree);

            WorkflowResult expectedResult = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                1, 1, 0, List.of(), Optional.empty()
            );
            when(mockEngine.executeWorkflow(any(), anyList(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

            List<Map<String, Object>> items = List.of(Map.of("data", "value"));
            CompletableFuture<WorkflowResult> future = service.executeWorkflow(
                mockPlan, items, "tenant-1", mockExecution
            );

            WorkflowResult result = future.get();
            assertNotNull(result);

            // Verify items used first trigger's key
            verify(mockEngine).executeWorkflow(eq(mockTree), argThat(triggerItems ->
                triggerItems.get(0).itemId().equals("trigger:my_webhook-0")
            ), any(), any());
        }

        @Test
        @DisplayName("should use default trigger ID when no triggers in plan")
        void shouldUseDefaultTriggerId() throws Exception {
            when(mockPlan.getTriggers()).thenReturn(null);

            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(),
                    org.mockito.ArgumentMatchers.<String>any(), org.mockito.ArgumentMatchers.<String>any())).thenReturn(mockTree);

            WorkflowResult expectedResult = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                1, 1, 0, List.of(), Optional.empty()
            );
            when(mockEngine.executeWorkflow(any(), anyList(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

            List<Map<String, Object>> items = List.of(Map.of("data", "value"));
            service.executeWorkflow(mockPlan, items, "tenant-1", mockExecution);

            verify(mockEngine).executeWorkflow(eq(mockTree), argThat(triggerItems ->
                triggerItems.get(0).itemId().equals("trigger:default-0")
            ), any(), any());
        }

        @Test
        @DisplayName("should use default trigger ID when triggers list is empty")
        void shouldUseDefaultWhenEmpty() throws Exception {
            when(mockPlan.getTriggers()).thenReturn(List.of());

            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(),
                    org.mockito.ArgumentMatchers.<String>any(), org.mockito.ArgumentMatchers.<String>any())).thenReturn(mockTree);

            WorkflowResult expectedResult = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                1, 1, 0, List.of(), Optional.empty()
            );
            when(mockEngine.executeWorkflow(any(), anyList(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

            List<Map<String, Object>> items = List.of(Map.of("data", "value"));
            service.executeWorkflow(mockPlan, items, "tenant-1", mockExecution);

            verify(mockEngine).executeWorkflow(eq(mockTree), argThat(triggerItems ->
                triggerItems.get(0).itemId().equals("trigger:default-0")
            ), any(), any());
        }
    }

    @Nested
    @DisplayName("executeWorkflow with multiple items")
    class ExecuteWorkflowMultipleItems {

        @Test
        @DisplayName("should create correct number of trigger items")
        void shouldCreateCorrectNumberOfItems() throws Exception {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(),
                    org.mockito.ArgumentMatchers.<String>any(), org.mockito.ArgumentMatchers.<String>any())).thenReturn(mockTree);

            WorkflowResult expectedResult = new WorkflowResult(
                "run-1",
                com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                3, 3, 0, List.of(), Optional.empty()
            );
            when(mockEngine.executeWorkflow(any(), anyList(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

            List<Map<String, Object>> items = List.of(
                Map.of("id", 1),
                Map.of("id", 2),
                Map.of("id", 3)
            );

            service.executeWorkflow(mockPlan, items, "tenant-1", mockExecution, "trigger:ds");

            verify(mockEngine).executeWorkflow(eq(mockTree), argThat(triggerItems ->
                triggerItems.size() == 3
                    && triggerItems.get(0).index() == 0
                    && triggerItems.get(1).index() == 1
                    && triggerItems.get(2).index() == 2

            ), any(), any());
        }
    }

    /**
     * PR15 round-2 regression guards for the workflow_runs → ExecutionTree
     * org-context propagation chain. Round-1 audit-C flagged that the V2
     * service was only signature-adapted but not behaviorally pinned, so a
     * regression in the repo fetch or in the org-arg passing to treeBuilder
     * would slip through. These tests close that gap.
     */
    @Nested
    @DisplayName("PR15 - repo fetch → tree org propagation")
    class OrgPropagationFromRepoTests {

        @Test
        @DisplayName("repo returns org-tagged run → treeBuilder.build receives orgId + orgRole")
        void repoOrgPropagatesToTreeBuilder() throws Exception {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            // Repo returns an org-tagged WorkflowRunEntity.
            com.apimarketplace.orchestrator.domain.WorkflowRunEntity runEntity =
                    mock(com.apimarketplace.orchestrator.domain.WorkflowRunEntity.class);
            when(runEntity.getOrgId()).thenReturn("org-acme");
            when(runEntity.getOrgRole()).thenReturn("OWNER");
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            when(mockTreeBuilder.build(eq("run-1"), anyString(), eq("tenant-1"),
                    eq(mockPlan), eq("org-acme"), eq("OWNER"))).thenReturn(mockTree);
            when(mockEngine.executeWorkflow(any(), anyList(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(new WorkflowResult(
                            "run-1",
                            com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                            1, 1, 0, List.of(), Optional.empty())));

            service.executeWorkflow(mockPlan, List.of(Map.of("k", "v")),
                    "tenant-1", mockExecution, "trigger:default").get();

            // Pin that the org args reached the treeBuilder - without this, a
            // regression that drops runEntity.getOrgId()/getOrgRole() before
            // passing to build() would silently demote to personal scope.
            verify(mockTreeBuilder).build(eq("run-1"), anyString(), eq("tenant-1"),
                    eq(mockPlan), eq("org-acme"), eq("OWNER"));
        }

        @Test
        @DisplayName("repo returns personal run (no org) → treeBuilder.build receives null/null")
        void personalRunPropagatesNullOrg() throws Exception {
            when(mockExecution.getRunId()).thenReturn("run-2");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            com.apimarketplace.orchestrator.domain.WorkflowRunEntity runEntity =
                    mock(com.apimarketplace.orchestrator.domain.WorkflowRunEntity.class);
            when(runEntity.getOrgId()).thenReturn(null);
            when(runEntity.getOrgRole()).thenReturn(null);
            when(mockRunRepository.findByRunIdPublic("run-2")).thenReturn(Optional.of(runEntity));

            when(mockTreeBuilder.build(eq("run-2"), anyString(), eq("tenant-1"),
                    eq(mockPlan),
                    org.mockito.ArgumentMatchers.<String>any(),
                    org.mockito.ArgumentMatchers.<String>any())).thenReturn(mockTree);
            when(mockEngine.executeWorkflow(any(), anyList(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(new WorkflowResult(
                            "run-2",
                            com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                            1, 1, 0, List.of(), Optional.empty())));

            service.executeWorkflow(mockPlan, List.of(Map.of("k", "v")),
                    "tenant-1", mockExecution, "trigger:default").get();

            verify(mockTreeBuilder).build(eq("run-2"), anyString(), eq("tenant-1"),
                    eq(mockPlan), isNull(), isNull());
        }

        @Test
        @DisplayName("repo throws → service degrades to null/null, run still executes")
        void repoExceptionFallsBackToPersonalScope() throws Exception {
            when(mockExecution.getRunId()).thenReturn("run-3");
            when(mockExecution.getWorkflowRunId()).thenReturn(UUID.randomUUID());

            // Transient DB blip on the org lookup.
            when(mockRunRepository.findByRunIdPublic("run-3"))
                    .thenThrow(new RuntimeException("transient DB error"));

            when(mockTreeBuilder.build(eq("run-3"), anyString(), eq("tenant-1"),
                    eq(mockPlan), isNull(), isNull())).thenReturn(mockTree);
            when(mockEngine.executeWorkflow(any(), anyList(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(new WorkflowResult(
                            "run-3",
                            com.apimarketplace.orchestrator.domain.execution.NodeStatus.COMPLETED,
                            1, 1, 0, List.of(), Optional.empty())));

            // Run still executes - foundation PR does not yet enforce; PR19/20
            // runtime consumers will detect the demote via their own scope
            // checks. Audit trail: WorkflowExecutionServiceV2 logs ERROR-level
            // so ops sees the degrade.
            service.executeWorkflow(mockPlan, List.of(Map.of("k", "v")),
                    "tenant-1", mockExecution, "trigger:default").get();

            verify(mockTreeBuilder).build(eq("run-3"), anyString(), eq("tenant-1"),
                    eq(mockPlan), isNull(), isNull());
        }
    }
}
