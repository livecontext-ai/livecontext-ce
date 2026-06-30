package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SubWorkflowNode.
 * SubWorkflowNode executes another workflow by firing its trigger (reusable run pattern).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubWorkflowNode")
class SubWorkflowNodeTest {

    private static final String NODE_ID = "core:sub_workflow";
    private static final String WORKFLOW_ID = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_ID = "tenant-1";
    private static final String RUN_ID_PUBLIC = "run-public-1";

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private ReusableTriggerService reusableTriggerService;

    @Mock
    private StepOutputService stepOutputService;

    @Mock
    private WorkflowStepDataRepository workflowStepDataRepository;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("input_key", "input_value");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            TENANT_ID,
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    private SubWorkflowNode createNode(Core.SubWorkflowConfig config) {
        SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);
        node.setWorkflowRepository(workflowRepository);
        node.setWorkflowRunRepository(workflowRunRepository);
        node.setReusableTriggerService(reusableTriggerService);
        node.setStepOutputService(stepOutputService);
        node.setWorkflowStepDataRepository(workflowStepDataRepository);
        return node;
    }

    private WorkflowEntity createMockEntity() {
        return createMockEntity(null);
    }

    private WorkflowEntity createMockEntity(Integer pinnedVersion) {
        WorkflowEntity entity = mock(WorkflowEntity.class);
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("id", WORKFLOW_ID);
        planMap.put("name", "Test Sub Workflow");
        planMap.put("triggers", List.of(Map.of("id", "t1", "type", "manual", "label", "Start")));
        planMap.put("steps", List.of());
        planMap.put("edges", List.of());
        when(entity.getPlan()).thenReturn(planMap);
        lenient().when(entity.getPinnedVersion()).thenReturn(pinnedVersion);
        return entity;
    }

    private WorkflowRunEntity createMockRun(RunStatus status) {
        return createMockRun(status, null);
    }

    private WorkflowRunEntity createMockRun(RunStatus status, Integer planVersion) {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        lenient().when(run.getRunIdPublic()).thenReturn(RUN_ID_PUBLIC);
        // lenient: the concurrent-dispatch test reloads the child run via
        // findByRunIdPublic and only fires it (no status read on the reloaded
        // mock), so a strict getStatus stub would be flagged unused there.
        lenient().when(run.getStatus()).thenReturn(status);
        lenient().when(run.getTenantId()).thenReturn(TENANT_ID);
        if (planVersion != null) {
            lenient().when(run.getPlanVersion()).thenReturn(planVersion);
        }
        return run;
    }

    private TriggerExecutionResult createSuccessTriggerResult(int epoch) {
        return TriggerExecutionResult.success(RUN_ID_PUBLIC, "trigger:start",
            TriggerType.MANUAL, Set.of(), epoch);
    }

    private TriggerExecutionResult createFailureTriggerResult(String error) {
        return TriggerExecutionResult.failure(RUN_ID_PUBLIC, "trigger:start",
            TriggerType.MANUAL, error);
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create SubWorkflowNode with nodeId and config")
        void shouldCreateWithNodeIdAndConfig() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 3);
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);

            assertEquals(NODE_ID, node.getNodeId());
            assertEquals(NodeType.SUB_WORKFLOW, node.getType());
            assertNotNull(node.getSubWorkflowConfig());
            assertEquals(WORKFLOW_ID, node.getSubWorkflowConfig().workflowId());
            assertEquals(60, node.getSubWorkflowConfig().timeoutSeconds());
            assertEquals(3, node.getSubWorkflowConfig().maxDepth());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, null);

            assertEquals(NODE_ID, node.getNodeId());
            assertEquals(NodeType.SUB_WORKFLOW, node.getType());
            assertNull(node.getSubWorkflowConfig());
        }

        @Test
        @DisplayName("Should apply defaults for zero/negative timeout and maxDepth")
        void shouldApplyDefaults() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 0, 0);

            assertEquals(300, config.timeoutSeconds());
            assertEquals(5, config.maxDepth());
        }

        @Test
        @DisplayName("Should cap maxDepth at 10")
        void shouldCapMaxDepthAt10() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 20);

            assertEquals(10, config.maxDepth());
        }

        @Test
        @DisplayName("Should create config with triggerId")
        void shouldCreateConfigWithTriggerId() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(
                WORKFLOW_ID, null, 60, 3, "trigger:custom");

            assertEquals("trigger:custom", config.triggerId());
        }

        @Test
        @DisplayName("Should create config without triggerId (backward compatible)")
        void shouldCreateConfigWithoutTriggerId() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 3);

            assertNull(config.triggerId());
        }

        @Test
        @DisplayName("Should create SubWorkflowNode using builder")
        void shouldCreateUsingBuilder() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, "#{trigger.data}", 120, 3);

            SubWorkflowNode node = SubWorkflowNode.builder()
                .nodeId(NODE_ID)
                .subWorkflowConfig(config)
                .build();

            assertEquals(NODE_ID, node.getNodeId());
            assertEquals(NodeType.SUB_WORKFLOW, node.getType());
            assertEquals(WORKFLOW_ID, node.getSubWorkflowConfig().workflowId());
        }
    }

    // ===============================================================
    // execute() - Basic trigger execution
    // ===============================================================

    @Nested
    @DisplayName("execute() - Basic trigger execution")
    class BasicExecutionTests {

        @Test
        @DisplayName("Should fire trigger on active run and return outputs")
        void shouldFireTriggerOnActiveRunAndReturnOutputs() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            TriggerExecutionResult triggerResult = createSuccessTriggerResult(1);
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap())).thenReturn(triggerResult);

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            assertEquals(true, execResult.output().get("success"));
            assertEquals(WORKFLOW_ID, execResult.output().get("subWorkflowId"));
            assertEquals(RUN_ID_PUBLIC, execResult.output().get("subRunId"));
            assertNotNull(execResult.output().get("result"));
        }

        @Test
        @DisplayName("Should include mandatory metadata in output")
        void shouldIncludeMandatoryMetadata() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            assertEquals("SUB_WORKFLOW", execResult.output().get("node_type"));
            assertEquals(0, execResult.output().get("item_index"));
            assertEquals(0, execResult.output().get("itemIndex"));
            assertEquals("item-1", execResult.output().get("item_id"));
            assertNotNull(execResult.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should collect step outputs from epoch")
        void shouldCollectStepOutputsFromEpoch() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(2));

            UUID storageId = UUID.randomUUID();
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 2))
                .thenReturn(List.of(outputRef("mcp:api_call", storageId)));

            Map<String, Object> stepOutput = Map.of("data", "result_value");
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID)).thenReturn(stepOutput);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertNotNull(result);
            assertEquals(stepOutput, result.get("mcp:api_call"));
        }
    }

    // ===============================================================
    // execute() - Pinned version run lookup
    // ===============================================================

    @Nested
    @DisplayName("execute() - Pinned version")
    class PinnedVersionTests {

        @Test
        @DisplayName("Should find run by pinned version when workflow is pinned")
        void shouldFindRunByPinnedVersion() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity(3); // pinned to version 3
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER, 3);
            when(workflowRunRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), eq(3), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            // Verify we used pinned version lookup
            verify(workflowRunRepository).findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), eq(3), anyCollection());
            // Should NOT use unpinned lookup
            verify(workflowRunRepository, never()).findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                any(), anyCollection());
        }

        @Test
        @DisplayName("Should use latest run when workflow is not pinned")
        void shouldUseLatestRunWhenNotPinned() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity(null); // not pinned
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            verify(workflowRunRepository).findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection());
        }
    }

    // ===============================================================
    // execute() - No active run / terminal status
    // ===============================================================

    @Nested
    @DisplayName("execute() - No active run")
    class NoActiveRunTests {

        @Test
        @DisplayName("Should fail when no active run found")
        void shouldFailWhenNoActiveRunFound() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.empty());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No active run found"));
        }

        @Test
        @DisplayName("Should fail when pinned run not found")
        void shouldFailWhenPinnedRunNotFound() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity(5); // pinned to version 5
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            when(workflowRunRepository.findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), eq(5), anyCollection())).thenReturn(Optional.empty());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No active run found"));
        }
    }

    // ===============================================================
    // execute() - Trigger resolution
    // ===============================================================

    @Nested
    @DisplayName("execute() - Trigger resolution")
    class TriggerResolutionTests {

        @Test
        @DisplayName("Should use config triggerId when set")
        void shouldUseConfigTriggerId() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(
                WORKFLOW_ID, null, 60, 5, "trigger:custom");
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), eq("trigger:custom"), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            verify(reusableTriggerService).executeTriggerInternal(
                eq(run), eq("trigger:custom"), any(), any(), eq(true), anyMap());
        }

        @Test
        @DisplayName("Should fail when no fireable trigger in plan")
        void shouldFailWhenNoFireableTrigger() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            // Create entity with only unfireable triggers
            WorkflowEntity entity = mock(WorkflowEntity.class);
            Map<String, Object> planMap = new HashMap<>();
            planMap.put("id", WORKFLOW_ID);
            planMap.put("name", "Test");
            planMap.put("triggers", List.of(Map.of("id", "t1", "type", "workflow", "label", "OnComplete")));
            planMap.put("steps", List.of());
            planMap.put("edges", List.of());
            when(entity.getPlan()).thenReturn(planMap);
            when(entity.getPinnedVersion()).thenReturn(null);
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No fireable trigger"));
        }
    }

    // ===============================================================
    // execute() - Trigger failure propagation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Trigger failure")
    class TriggerFailureTests {

        @Test
        @DisplayName("Should propagate trigger failure message")
        void shouldPropagateTriggerFailureMessage() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createFailureTriggerResult("Step mcp:api_call failed"));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("Step mcp:api_call failed"));
        }

        @Test
        @DisplayName("Should handle trigger exception")
        void shouldHandleTriggerException() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenThrow(new RuntimeException("Internal engine error"));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
        }
    }

    // ===============================================================
    // execute() - Anti-recursion depth guard
    // ===============================================================

    @Nested
    @DisplayName("execute() - Anti-recursion")
    class AntiRecursionTests {

        @Test
        @DisplayName("Should fail when recursion depth exceeds maxDepth")
        void shouldFailWhenRecursionDepthExceedsMaxDepth() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 3);
            SubWorkflowNode node = createNode(config);

            // Set depth to 3 (equals maxDepth)
            ExecutionContext deepContext = context.withGlobalData(SubWorkflowNode.DEPTH_KEY, 3);

            NodeExecutionResult execResult = node.execute(deepContext);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("recursion depth"));
        }

        @Test
        @DisplayName("Should succeed when depth is below maxDepth")
        void shouldSucceedWhenDepthBelowMaxDepth() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            // Set depth to 2 (below maxDepth of 5)
            ExecutionContext shallowContext = context.withGlobalData(SubWorkflowNode.DEPTH_KEY, 2);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(shallowContext);

            assertTrue(execResult.isSuccess());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> globalDataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(reusableTriggerService).executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), globalDataCaptor.capture());
            assertEquals(3, globalDataCaptor.getValue().get(SubWorkflowNode.DEPTH_KEY));
            assertEquals(List.of(WORKFLOW_ID), globalDataCaptor.getValue().get(SubWorkflowNode.ANCESTRY_KEY));
        }

        @Test
        @DisplayName("Should treat missing depth as zero")
        void shouldTreatMissingDepthAsZero() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            // No depth set in context - should default to 0
            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
        }

        @Test
        @DisplayName("Should fail before dispatch when target workflow is already in call chain")
        void shouldFailBeforeDispatchWhenTargetWorkflowIsAlreadyInCallChain() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);
            ExecutionContext cyclicContext = context.withGlobalData(
                SubWorkflowNode.ANCESTRY_KEY, List.of("00000000-0000-0000-0000-000000000000", WORKFLOW_ID));

            NodeExecutionResult execResult = node.execute(cyclicContext);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("recursion cycle"));
            verify(workflowRepository, never()).findById(any());
            verify(reusableTriggerService, never()).executeTriggerInternal(
                any(), anyString(), any(), any(), anyBoolean(), anyMap());
        }

        @Test
        @DisplayName("Should fail before dispatch on direct self-reference")
        void shouldFailBeforeDispatchOnDirectSelfReference() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);
            when(mockPlan.getId()).thenReturn(WORKFLOW_ID);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("recursion cycle"));
            verify(workflowRepository, never()).findById(any());
            verify(reusableTriggerService, never()).executeTriggerInternal(
                any(), anyString(), any(), any(), anyBoolean(), anyMap());
        }
    }

    // ===============================================================
    // execute() - Error handling
    // ===============================================================

    @Nested
    @DisplayName("execute() - Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should fail when workflowId is null")
        void shouldFailWhenWorkflowIdIsNull() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(null, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("workflowId is required"));
        }

        @Test
        @DisplayName("Should fail when workflowId is empty")
        void shouldFailWhenWorkflowIdIsEmpty() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig("", null, 60, 5);
            SubWorkflowNode node = createNode(config);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("workflowId is required"));
        }

        @Test
        @DisplayName("Should fail when workflowId is not a valid UUID")
        void shouldFailWhenWorkflowIdIsInvalidUuid() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig("not-a-uuid", null, 60, 5);
            SubWorkflowNode node = createNode(config);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("Invalid workflowId format"));
        }

        @Test
        @DisplayName("Should fail when workflow not found")
        void shouldFailWhenWorkflowNotFound() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.empty());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("Workflow not found"));
        }

        @Test
        @DisplayName("Should fail when WorkflowRepository is not injected")
        void shouldFailWhenRepositoryNotInjected() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);
            // Do not inject services

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("WorkflowRepository not injected"));
        }

        @Test
        @DisplayName("Should fail when WorkflowRunRepository is not injected")
        void shouldFailWhenRunRepositoryNotInjected() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);
            node.setWorkflowRepository(workflowRepository);
            // Do not inject run repository

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("WorkflowRunRepository not injected"));
        }

        @Test
        @DisplayName("Should fail when ReusableTriggerService is not injected")
        void shouldFailWhenTriggerServiceNotInjected() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);
            node.setWorkflowRepository(workflowRepository);
            node.setWorkflowRunRepository(workflowRunRepository);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("ReusableTriggerService not injected"));
        }

        @Test
        @DisplayName("Should fail when workflow plan is null")
        void shouldFailWhenWorkflowPlanIsNull() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = mock(WorkflowEntity.class);
            when(entity.getPlan()).thenReturn(null);
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("has no plan"));
        }

        @Test
        @DisplayName("Should fail when config is null (no workflowId)")
        void shouldFailWhenConfigIsNull() {
            SubWorkflowNode node = createNode(null);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("workflowId is required"));
        }
    }

    // ===============================================================
    // execute() - Force auto mode
    // ===============================================================

    @Nested
    @DisplayName("execute() - Force auto mode")
    class ForceAutoModeTests {

        @Test
        @DisplayName("Should always pass forceAutoMode=true to trigger service")
        void shouldAlwaysForceAutoMode() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                any(), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            node.execute(context);

            // Verify forceAutoMode is always true
            verify(reusableTriggerService).executeTriggerInternal(
                any(), anyString(), any(), any(), eq(true), anyMap());
        }
    }

    // ===============================================================
    // execute() - Run status variants
    // ===============================================================

    @Nested
    @DisplayName("execute() - Run status variants")
    class RunStatusVariantTests {

        @Test
        @DisplayName("Should accept run in RUNNING status")
        void shouldAcceptRunInRunningStatus() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.RUNNING);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);
            assertTrue(execResult.isSuccess());
        }

        @Test
        @DisplayName("Should accept run in PAUSED status")
        void shouldAcceptRunInPausedStatus() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.PAUSED);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);
            assertTrue(execResult.isSuccess());
        }
    }

    // ===============================================================
    // execute() - Trigger resolution edge cases
    // ===============================================================

    @Nested
    @DisplayName("execute() - Trigger resolution edge cases")
    class TriggerResolutionEdgeCaseTests {

        @Test
        @DisplayName("Should skip unfireable triggers and pick first fireable one")
        void shouldSkipUnfireableAndPickFirstFireable() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            // First trigger is "workflow" (unfireable), second is "manual" (fireable)
            WorkflowEntity entity = mock(WorkflowEntity.class);
            Map<String, Object> planMap = new HashMap<>();
            planMap.put("id", WORKFLOW_ID);
            planMap.put("name", "Test");
            planMap.put("triggers", List.of(
                Map.of("id", "t1", "type", "workflow", "label", "OnComplete"),
                Map.of("id", "t2", "type", "error", "label", "OnError"),
                Map.of("id", "t3", "type", "manual", "label", "Manual Start")
            ));
            planMap.put("steps", List.of());
            planMap.put("edges", List.of());
            when(entity.getPlan()).thenReturn(planMap);
            lenient().when(entity.getPinnedVersion()).thenReturn(null);
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), eq("trigger:manual_start"), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            verify(reusableTriggerService).executeTriggerInternal(
                eq(run), eq("trigger:manual_start"), any(), any(), eq(true), anyMap());
        }

        @Test
        @DisplayName("Should fail when plan has empty triggers list")
        void shouldFailWhenEmptyTriggersList() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = mock(WorkflowEntity.class);
            Map<String, Object> planMap = new HashMap<>();
            planMap.put("id", WORKFLOW_ID);
            planMap.put("name", "Test");
            planMap.put("triggers", List.of()); // empty
            planMap.put("steps", List.of());
            planMap.put("edges", List.of());
            when(entity.getPlan()).thenReturn(planMap);
            lenient().when(entity.getPinnedVersion()).thenReturn(null);
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No fireable trigger"));
        }

        @Test
        @DisplayName("Should resolve TriggerType from plan trigger type")
        void shouldResolveTriggerTypeFromPlan() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = mock(WorkflowEntity.class);
            Map<String, Object> planMap = new HashMap<>();
            planMap.put("id", WORKFLOW_ID);
            planMap.put("name", "Test");
            planMap.put("triggers", List.of(Map.of("id", "t1", "type", "webhook", "label", "Hook")));
            planMap.put("steps", List.of());
            planMap.put("edges", List.of());
            when(entity.getPlan()).thenReturn(planMap);
            lenient().when(entity.getPinnedVersion()).thenReturn(null);
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), eq("trigger:hook"), eq(TriggerType.WEBHOOK), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            verify(reusableTriggerService).executeTriggerInternal(
                any(), anyString(), eq(TriggerType.WEBHOOK), any(), eq(true), anyMap());
        }

        @Test
        @DisplayName("Should fallback to MANUAL type when config triggerId does not match plan")
        void shouldFallbackToManualForUnmatchedTriggerId() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(
                WORKFLOW_ID, null, 60, 5, "trigger:nonexistent");
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity(); // has trigger:start
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), eq("trigger:nonexistent"), eq(TriggerType.MANUAL), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            verify(reusableTriggerService).executeTriggerInternal(
                any(), eq("trigger:nonexistent"), eq(TriggerType.MANUAL), any(), eq(true), anyMap());
        }

        @Test
        @DisplayName("Should treat blank triggerId as absent and resolve from plan")
        void shouldTreatBlankTriggerIdAsAbsent() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(
                WORKFLOW_ID, null, 60, 5, "   ");
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity(); // has "Start" manual trigger
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), eq("trigger:start"), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            verify(reusableTriggerService).executeTriggerInternal(
                any(), eq("trigger:start"), any(), any(), eq(true), anyMap());
        }
    }

    @Nested
    @DisplayName("execute() - concurrent sub-run dispatch")
    class ConcurrentSubRunDispatchTests {

        @Test
        @DisplayName("Serializes same child run calls and reloads the run before firing")
        void serializesSameChildRunCallsAndReloadsRunBeforeFiring() throws Exception {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity staleRun = createMockRun(RunStatus.WAITING_TRIGGER);
            WorkflowRunEntity freshFirstRun = createMockRun(RunStatus.WAITING_TRIGGER);
            WorkflowRunEntity freshSecondRun = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(staleRun));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(freshFirstRun), Optional.of(freshSecondRun));

            AtomicInteger activeCalls = new AtomicInteger();
            AtomicInteger maxActiveCalls = new AtomicInteger();
            AtomicInteger nextEpoch = new AtomicInteger();
            List<WorkflowRunEntity> firedRuns = Collections.synchronizedList(new ArrayList<>());
            when(reusableTriggerService.executeTriggerInternal(
                any(WorkflowRunEntity.class), anyString(), any(), any(), eq(true), anyMap()))
                .thenAnswer(invocation -> {
                    firedRuns.add(invocation.getArgument(0));
                    int active = activeCalls.incrementAndGet();
                    maxActiveCalls.updateAndGet(previous -> Math.max(previous, active));
                    try {
                        Thread.sleep(50);
                        return createSuccessTriggerResult(nextEpoch.incrementAndGet());
                    } finally {
                        activeCalls.decrementAndGet();
                    }
                });

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(eq(RUN_ID_PUBLIC), anyInt()))
                .thenReturn(List.of());

            CompletableFuture<NodeExecutionResult> first = CompletableFuture.supplyAsync(() -> node.execute(context));
            CompletableFuture<NodeExecutionResult> second = CompletableFuture.supplyAsync(() -> node.execute(context));

            NodeExecutionResult firstResult = first.get(5, TimeUnit.SECONDS);
            NodeExecutionResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertTrue(firstResult.isSuccess());
            assertTrue(secondResult.isSuccess());
            assertEquals(1, maxActiveCalls.get());
            assertFalse(firedRuns.contains(staleRun));
            assertTrue(firedRuns.contains(freshFirstRun));
            assertTrue(firedRuns.contains(freshSecondRun));
            verify(reusableTriggerService, times(2)).executeTriggerInternal(
                any(WorkflowRunEntity.class), anyString(), any(), any(), eq(true), anyMap());
        }
    }

    // ===============================================================
    // execute() - Output collection edge cases
    // ===============================================================

    @Nested
    @DisplayName("execute() - Output collection edge cases")
    class OutputCollectionEdgeCaseTests {

        private SubWorkflowNode setupNodeWithTrigger() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            return node;
        }

        @Test
        @DisplayName("Should exclude FAILED steps from outputs")
        void shouldExcludeFailedSteps() {
            SubWorkflowNode node = setupNodeWithTrigger();

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should skip completed step with null outputStorageId")
        void shouldSkipCompletedStepWithNullStorageId() {
            SubWorkflowNode node = setupNodeWithTrigger();

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertTrue(result.isEmpty());
            verify(stepOutputService, never()).loadRawOutput(any(), any());
        }

        @Test
        @DisplayName("Should gracefully handle loadRawOutput exception and continue with other steps")
        void shouldHandleLoadRawOutputException() {
            SubWorkflowNode node = setupNodeWithTrigger();

            UUID storageId1 = UUID.randomUUID();
            UUID storageId2 = UUID.randomUUID();

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of(
                    outputRef("mcp:step1", storageId1),
                    outputRef("mcp:step2", storageId2)));

            // step1 throws, step2 succeeds
            when(stepOutputService.loadRawOutput(storageId1, TENANT_ID))
                .thenThrow(new RuntimeException("Storage error"));
            when(stepOutputService.loadRawOutput(storageId2, TENANT_ID))
                .thenReturn(Map.of("data", "ok"));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertEquals(1, result.size());
            assertNotNull(result.get("mcp:step2"));
            assertNull(result.get("mcp:step1"));
        }

        @Test
        @DisplayName("Should collect multiple completed steps")
        void shouldCollectMultipleCompletedSteps() {
            SubWorkflowNode node = setupNodeWithTrigger();

            UUID storageId1 = UUID.randomUUID();
            UUID storageId2 = UUID.randomUUID();
            UUID storageId3 = UUID.randomUUID();

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of(
                    outputRef("mcp:step_a", storageId1),
                    outputRef("mcp:step_b", storageId2),
                    outputRef("mcp:step_c", storageId3)));

            when(stepOutputService.loadRawOutput(storageId1, TENANT_ID)).thenReturn(Map.of("a", 1));
            when(stepOutputService.loadRawOutput(storageId2, TENANT_ID)).thenReturn(Map.of("b", 2));
            when(stepOutputService.loadRawOutput(storageId3, TENANT_ID)).thenReturn(Map.of("c", 3));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertEquals(3, result.size());
            assertNotNull(result.get("mcp:step_a"));
            assertNotNull(result.get("mcp:step_b"));
            assertNotNull(result.get("mcp:step_c"));
        }

        @Test
        @DisplayName("Should handle mix of completed, failed, and no-output steps")
        void shouldHandleMixOfStepStatuses() {
            SubWorkflowNode node = setupNodeWithTrigger();

            UUID storageId = UUID.randomUUID();

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of(outputRef("mcp:good", storageId)));

            when(stepOutputService.loadRawOutput(storageId, TENANT_ID))
                .thenReturn(Map.of("result", "data"));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertEquals(1, result.size());
            assertNotNull(result.get("mcp:good"));
        }

        @Test
        @DisplayName("Should skip step when loadRawOutput returns null")
        void shouldSkipStepWhenLoadReturnsNull() {
            SubWorkflowNode node = setupNodeWithTrigger();

            UUID storageId = UUID.randomUUID();

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of(outputRef("mcp:null_output", storageId)));
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID)).thenReturn(null);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should skip step when loadRawOutput returns empty map")
        void shouldSkipStepWhenLoadReturnsEmptyMap() {
            SubWorkflowNode node = setupNodeWithTrigger();

            UUID storageId = UUID.randomUUID();

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of(outputRef("mcp:empty_output", storageId)));
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID)).thenReturn(Map.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should succeed with empty result when step/output services are null")
        void shouldSucceedWithEmptyResultWhenOutputServicesNull() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);
            node.setWorkflowRepository(workflowRepository);
            node.setWorkflowRunRepository(workflowRunRepository);
            node.setReusableTriggerService(reusableTriggerService);
            // Do NOT set stepOutputService or workflowStepDataRepository

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execResult.output().get("result");
            assertTrue(result.isEmpty());
        }
    }

    // ===============================================================
    // execute() - Timeout handling
    // ===============================================================

    @Nested
    @DisplayName("execute() - Timeout handling")
    class TimeoutTests {

        @Test
        @DisplayName("Should fail when trigger execution exceeds timeout")
        void shouldFailWhenTriggerExceedsTimeout() {
            // Use 1-second timeout
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 1, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            // Simulate slow trigger execution
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenAnswer(invocation -> {
                    Thread.sleep(3000); // 3 seconds > 1 second timeout
                    return createSuccessTriggerResult(1);
                });

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("timed out"));
        }
    }

    // ===============================================================
    // execute() - Null trigger failure message
    // ===============================================================

    @Nested
    @DisplayName("execute() - Trigger result edge cases")
    class TriggerResultEdgeCaseTests {

        @Test
        @DisplayName("Should use fallback message when trigger failure has null message")
        void shouldUseFallbackWhenNullMessage() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            // Create a failure result with null message
            TriggerExecutionResult nullMsgResult = new TriggerExecutionResult(
                RUN_ID_PUBLIC, "trigger:start", TriggerType.MANUAL,
                false, null, Set.of(), 0);

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(nullMsgResult);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("Sub-workflow trigger failed"));
        }

        @Test
        @DisplayName("Should forward correct epoch to step data query")
        void shouldForwardCorrectEpochToStepDataQuery() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            // Return epoch 7
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(7));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 7))
                .thenReturn(List.of());

            node.execute(context);

            // Verify epoch 7 was used, not some other value
            verify(workflowStepDataRepository).findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 7);
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(
                WORKFLOW_ID, "#{trigger.data}", 120, 3, "trigger:custom");

            SubWorkflowNode node = SubWorkflowNode.builder()
                .nodeId("core:call_workflow")
                .subWorkflowConfig(config)
                .build();

            assertEquals("core:call_workflow", node.getNodeId());
            assertEquals(NodeType.SUB_WORKFLOW, node.getType());
            assertEquals(WORKFLOW_ID, node.getSubWorkflowConfig().workflowId());
            assertEquals("#{trigger.data}", node.getSubWorkflowConfig().inputMapping());
            assertEquals(120, node.getSubWorkflowConfig().timeoutSeconds());
            assertEquals(3, node.getSubWorkflowConfig().maxDepth());
            assertEquals("trigger:custom", node.getSubWorkflowConfig().triggerId());
        }

        @Test
        @DisplayName("Should build with null config")
        void shouldBuildWithNullConfig() {
            SubWorkflowNode node = SubWorkflowNode.builder()
                .nodeId("core:sub")
                .subWorkflowConfig(null)
                .build();

            assertEquals("core:sub", node.getNodeId());
            assertNull(node.getSubWorkflowConfig());
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success(NODE_ID, Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure(NODE_ID, "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // Service injection tests
    // ===============================================================

    @Nested
    @DisplayName("Service injection")
    class ServiceInjectionTests {

        @Test
        @DisplayName("Should accept services via setters")
        void shouldAcceptServicesViaSetters() {
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, null);

            node.setWorkflowRepository(workflowRepository);
            node.setWorkflowRunRepository(workflowRunRepository);
            node.setReusableTriggerService(reusableTriggerService);
            node.setStepOutputService(stepOutputService);
            node.setWorkflowStepDataRepository(workflowStepDataRepository);

            assertSame(workflowRepository, node.getWorkflowRepository());
            assertSame(workflowRunRepository, node.getWorkflowRunRepository());
            assertSame(reusableTriggerService, node.getReusableTriggerService());
            assertSame(stepOutputService, node.getStepOutputService());
            assertSame(workflowStepDataRepository, node.getWorkflowStepDataRepository());
        }

        @Test
        @DisplayName("Should wire services via acceptServices from ServiceRegistry")
        void shouldWireServicesViaAcceptServices() {
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, null);

            ServiceRegistry registry = ServiceRegistry.builder()
                .workflowRepository(workflowRepository)
                .workflowRunRepository(workflowRunRepository)
                .reusableTriggerService(reusableTriggerService)
                .stepOutputService(stepOutputService)
                .workflowStepDataRepository(workflowStepDataRepository)
                .build();

            node.acceptServices(registry);

            assertSame(workflowRepository, node.getWorkflowRepository());
            assertSame(workflowRunRepository, node.getWorkflowRunRepository());
            assertSame(reusableTriggerService, node.getReusableTriggerService());
            assertSame(stepOutputService, node.getStepOutputService());
            assertSame(workflowStepDataRepository, node.getWorkflowStepDataRepository());
        }

        @Test
        @DisplayName("Should execute successfully when services are wired via acceptServices")
        void shouldExecuteSuccessfullyWhenWiredViaAcceptServices() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, config);

            // Wire services via acceptServices (the production path)
            ServiceRegistry registry = ServiceRegistry.builder()
                .workflowRepository(workflowRepository)
                .workflowRunRepository(workflowRunRepository)
                .reusableTriggerService(reusableTriggerService)
                .stepOutputService(stepOutputService)
                .workflowStepDataRepository(workflowStepDataRepository)
                .build();
            node.acceptServices(registry);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(run));

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            assertEquals(true, execResult.output().get("success"));
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private WorkflowStepDataRepository.EpochOutputProjection outputRef(String stepAlias, UUID outputStorageId) {
        return new WorkflowStepDataRepository.EpochOutputProjection() {
            @Override
            public String getStepAlias() {
                return stepAlias;
            }

            @Override
            public UUID getOutputStorageId() {
                return outputStorageId;
            }
        };
    }

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
