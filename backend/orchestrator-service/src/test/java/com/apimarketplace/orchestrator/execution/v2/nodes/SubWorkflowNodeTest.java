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
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private ProductionRunResolver productionRunResolver;

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
        // The resolver is REQUIRED in production (ExecutionServiceInjector always wires
        // it), so every test drives the shipped path through it rather than a bypass.
        node.setProductionRunResolver(productionRunResolver);
        node.setStepOutputService(stepOutputService);
        node.setWorkflowStepDataRepository(workflowStepDataRepository);
        return node;
    }

    /** The target workflow has a fireable run. */
    private void stubActiveRun(WorkflowRunEntity run) {
        when(productionRunResolver.resolveActiveRun(any(WorkflowEntity.class), anyList()))
            .thenReturn(new ProductionRunResolver.Resolution(
                Optional.of(run), ProductionRunResolver.Outcome.FOUND, "Test Sub Workflow"));
    }

    /** The target workflow has no fireable run. */
    private void stubNoActiveRun() {
        when(productionRunResolver.resolveActiveRun(any(WorkflowEntity.class), anyList()))
            .thenReturn(new ProductionRunResolver.Resolution(
                Optional.empty(), ProductionRunResolver.Outcome.NO_PRODUCTION_RUN, "Test Sub Workflow"));
    }

    private WorkflowEntity createMockEntity() {
        return createMockEntity(null);
    }

    private WorkflowEntity createMockEntity(Integer pinnedVersion) {
        return createMockEntity(pinnedVersion, TENANT_ID, null);
    }

    /**
     * @param entityTenantId owner of the target workflow. Defaults to the caller's tenant
     *                       so the cross-workspace guard passes; production rows always
     *                       carry a tenant ({@code workflows.tenant_id} is NOT NULL).
     * @param entityOrgId    organization tag of the target workflow, null for personal scope
     */
    private WorkflowEntity createMockEntity(Integer pinnedVersion, String entityTenantId, String entityOrgId) {
        WorkflowEntity entity = mock(WorkflowEntity.class);
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("id", WORKFLOW_ID);
        planMap.put("name", "Test Sub Workflow");
        planMap.put("triggers", List.of(Map.of("id", "t1", "type", "manual", "label", "Start")));
        planMap.put("steps", List.of());
        planMap.put("edges", List.of());
        lenient().when(entity.getPlan()).thenReturn(planMap);
        lenient().when(entity.getPinnedVersion()).thenReturn(pinnedVersion);
        lenient().when(entity.getTenantId()).thenReturn(entityTenantId);
        lenient().when(entity.getOrganizationId()).thenReturn(entityOrgId);
        return entity;
    }

    /**
     * Puts an inline-mocked target workflow in the caller's workspace so it clears the
     * cross-workspace guard. Real rows always carry a tenant; an unstubbed mock returns
     * null, which the guard correctly treats as "not mine".
     */
    private WorkflowEntity inCallerWorkspace(WorkflowEntity entity) {
        lenient().when(entity.getTenantId()).thenReturn(TENANT_ID);
        lenient().when(entity.getOrganizationId()).thenReturn(null);
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
            stubActiveRun(run);

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
        @DisplayName("regression: an inputMapping that fails to resolve fails the node instead of sending the PARENT's trigger data")
        void inputMappingFailureDoesNotFireWithParentData() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(
                WORKFLOW_ID, "{{json(core:x.output.text)}}", 60, 5);
            SubWorkflowNode node = createNode(config);
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
                mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any())).thenAnswer(inv -> {
                Map<String, Object> in = inv.getArgument(0);
                if (String.valueOf(in.values()).contains("json(")) {
                    throw new IllegalArgumentException("json() could not parse");
                }
                return new HashMap<>(in);
            });
            node.setTemplateAdapter(adapter);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            stubActiveRun(createMockRun(RunStatus.WAITING_TRIGGER));

            NodeExecutionResult execResult = node.execute(context);

            assertFalse(execResult.isSuccess());
            assertTrue(execResult.errorMessage().orElse("").contains("json() could not parse"),
                execResult.errorMessage().orElse(""));
            verify(reusableTriggerService, never()).executeTriggerInternal(any(), anyString(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyMap());
        }

        @Test
        @DisplayName("reports what the child received beside the mapping expression")
        @SuppressWarnings("unchecked")
        void reportsResolvedInputMapping() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(
                WORKFLOW_ID, "{{core:build.output.payload}}", 60, 5);
            SubWorkflowNode node = createNode(config);
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
                mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(TemplateResolutionStubs.resolving(
                    Map.of("{{core:build.output.payload}}", Map.of("order", 42))));
            node.setTemplateAdapter(adapter);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap())).thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess(), String.valueOf(execResult.errorMessage()));
            Map<String, Object> params = (Map<String, Object>) execResult.output().get("resolved_params");
            assertEquals("{{core:build.output.payload}}", params.get("inputMapping"));
            assertEquals(Map.of("order", 42), params.get("inputMappingResolved"));
        }

        @Test
        @DisplayName("regression: a {{...}} timeoutSeconds is resolved and reported; it used to drop the whole subWorkflow config")
        @SuppressWarnings("unchecked")
        void templatedTimeoutIsResolved() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 300, 5);
            SubWorkflowNode node = createNode(config);
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
                mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(TemplateResolutionStubs.resolving(Map.of("{{core:x.output.secs}}", "42")));
            node.setTemplateAdapter(adapter);
            node.setDeferredScalars(Map.of("subWorkflow", Map.of("timeoutSeconds", "{{core:x.output.secs}}")));

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap())).thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess(), String.valueOf(execResult.errorMessage()));
            Map<String, Object> params = (Map<String, Object>) execResult.output().get("resolved_params");
            assertEquals(42, params.get("timeoutSeconds"));
        }

        @Test
        @DisplayName("a {{...}} maxDepth resolving to a non-number fails before firing, naming the config")
        void templatedMaxDepthNotANumberFails() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 300, 5);
            SubWorkflowNode node = createNode(config);
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
                mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(TemplateResolutionStubs.resolving(Map.of("{{core:x.output.depth}}", "deep")));
            node.setTemplateAdapter(adapter);
            node.setDeferredScalars(Map.of("subWorkflow", Map.of("maxDepth", "{{core:x.output.depth}}")));

            NodeExecutionResult execResult = node.execute(context);

            assertFalse(execResult.isSuccess());
            assertTrue(execResult.errorMessage().orElse("").contains("subWorkflow"), execResult.errorMessage().orElse(""));
            verify(reusableTriggerService, never()).executeTriggerInternal(any(), anyString(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyMap());
        }

        @Test
        @DisplayName("a {{$vars.payload}} inputMapping is handed to the child but its values are withheld in Params")
        @SuppressWarnings("unchecked")
        void workspaceVariableInputMappingIsDescribedNotPrinted() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, "{{$vars.payload}}", 60, 5);
            SubWorkflowNode node = createNode(config);
            com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapter =
                mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(TemplateResolutionStubs.resolving(
                    Map.of("{{$vars.payload}}", Map.of("apiToken", "s3cr3t-value"))));
            node.setTemplateAdapter(adapter);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap())).thenReturn(createSuccessTriggerResult(1));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess(), String.valueOf(execResult.errorMessage()));
            verify(reusableTriggerService).executeTriggerInternal(eq(run), anyString(), any(),
                argThat(input -> input instanceof Map<?, ?> m && "s3cr3t-value".equals(m.get("apiToken"))),
                eq(true), anyMap());
            Map<String, Object> params = (Map<String, Object>) execResult.output().get("resolved_params");
            assertFalse(String.valueOf(params.get("inputMappingResolved")).contains("s3cr3t-value"),
                "described by shape, never printed: " + params.get("inputMappingResolved"));
        }

        @Test
        @DisplayName("Should include mandatory metadata in output")
        void shouldIncludeMandatoryMetadata() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);

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
            stubActiveRun(run);

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
            stubActiveRun(run);

            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));

            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            // Pin-vs-no-pin scoping now lives in the resolver (covered by
            // ProductionRunResolverTest.ResolveActiveRunTests). What this node owns is
            // asking the resolver and never touching the raw queries again.
            verify(productionRunResolver).resolveActiveRun(eq(entity), eq(SubWorkflowNode.ACTIVE_STATUSES));
            verify(workflowRunRepository, never()).findFirstByWorkflowIdAndPlanVersionAndStatusInOrderByStartedAtDesc(
                any(), any(), anyCollection());
            verify(workflowRunRepository, never()).findFirstByWorkflowIdAndStatusInOrderByStartedAtDesc(
                any(), anyCollection());
        }

        @Test
        @DisplayName("Asks for exactly WAITING_TRIGGER + RUNNING + PAUSED - never the wider non-terminal set")
        void requestsItsOwnNarrowStatusSet() {
            SubWorkflowNode node = createNode(new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5));
            // Build the entity BEFORE opening when(): createMockEntity() stubs internally,
            // and nesting that inside an unfinished when() trips Mockito.
            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            stubNoActiveRun();

            node.execute(context);

            // Asserts the PRODUCTION constant, not a copy of it in the test: a widening
            // of SubWorkflowNode.ACTIVE_STATUSES fails here.
            assertEquals(List.of(RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED),
                SubWorkflowNode.ACTIVE_STATUSES);
            verify(productionRunResolver).resolveActiveRun(eq(entity), eq(List.of(RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED)));
            // A blocking call must not be handed a run parked on an approval or a wait
            // timer: it would only burn the parent's timeout. That is why this lane is
            // narrower than the error lane's NON_TERMINAL_STATUSES.
            assertFalse(SubWorkflowNode.ACTIVE_STATUSES.contains(RunStatus.AWAITING_SIGNAL));
            assertFalse(SubWorkflowNode.ACTIVE_STATUSES.contains(RunStatus.COMPLETED));
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

            stubNoActiveRun();

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            String msg = execResult.errorMessage().orElse("");
            // With genuinely nothing alive, the plain "start it first" guidance is right.
            // The richer per-cause explanations (parked on a signal, transient status,
            // version mismatch) are covered by the describeMissingActiveRun tests below.
            assertTrue(msg.contains("No active run found"), msg);
            assertTrue(msg.contains("Start the workflow first"), msg);
            // REGRESSION (merge 223793a13 dropped this): the branch must name the action that
            // creates a run and say this node never creates one. Without both, an agent reading
            // "start the workflow first" has no way to know HOW, and the sibling branches
            // (parked / transient / version mismatch) all name their remedy.
            assertTrue(msg.contains("workflow(action='execute', id='" + WORKFLOW_ID + "')"), msg);
            assertTrue(msg.contains("never creates a run"), msg);
            // Unpinned: no version argument may be suggested, there is nothing to pin to.
            assertFalse(msg.contains("version="), msg);
        }

        @Test
        @DisplayName("Should name both versions when the pinned lookup misses but an active run exists at another version")
        void shouldNameBothVersionsWhenPinnedLookupMissesButActiveRunExists() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity(5); // workflow pinned to v5
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            // The pinned lookup misses: no active run stamped at v5 ...
            stubNoActiveRun();
            // ... but a perfectly live WAITING_TRIGGER run exists at v4.
            WorkflowRunEntity mismatched = createMockRun(RunStatus.WAITING_TRIGGER, 4);
            when(workflowRunRepository.findFirstProductionRunByWorkflowIdAndStatusIn(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(mismatched));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            String message = execResult.errorMessage().orElse("");
            // Pre-fix this said "Start the workflow first." even though the run was already started,
            // which sent the caller chasing a run that existed all along.
            assertFalse(message.contains("Start the workflow first"),
                "Should not tell the caller to start a run that already exists. Actual message: " + message);
            assertTrue(message.contains("pinned to version 5"), "Should name the pinned version: " + message);
            assertTrue(message.contains("plan version 4"), "Should name the run's version: " + message);
            assertTrue(message.contains(RUN_ID_PUBLIC), "Should name the run that is active: " + message);
            // The remedy is the part the agent acts on, so pin it explicitly: exact callable syntax,
            // guarded by the condition that makes re-pinning the right move, and stating the
            // consequence (it redirects every production trigger).
            assertTrue(message.contains("workflow(action='execute', id='" + WORKFLOW_ID + "', version=5)"),
                "Should give the callable way to get a run at the pinned version: " + message);
            assertTrue(message.contains("workflow(action='pin', workflow_id='" + WORKFLOW_ID + "', version=4)"),
                "Should give the exact pin call: " + message);
            assertTrue(message.contains("only if that version is the one you want in production"),
                "Should gate the destructive remedy on intent: " + message);
            assertTrue(message.contains("clones cannot be pinned"),
                "Should warn that the pin remedy dead-ends on a cloned run: " + message);
            assertTrue(message.contains("redirects every production trigger"),
                "Should state the consequence of re-pinning: " + message);
        }

        @Test
        @DisplayName("Should explain a run parked on a blocking node instead of claiming none exists")
        void shouldExplainRunParkedOnBlockingNode() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            // AWAITING_SIGNAL is non-terminal but is NOT in the node's ACTIVE_STATUSES, so the
            // lookup misses a perfectly alive child parked on an approval/interface/wait node.
            //
            // Match on the STATUS SET rather than on invocation order: the property under test is
            // precisely that the diagnostic probe queries a wider set than the lookup. An
            // order-coupled stub would keep passing if a future change made findActiveRun
            // short-circuit, i.e. it would pass for the wrong reason.
            WorkflowRunEntity parked = createMockRun(RunStatus.AWAITING_SIGNAL, 3);
            stubNoActiveRun();
            when(workflowRunRepository.findFirstProductionRunByWorkflowIdAndStatusIn(
            eq(UUID.fromString(WORKFLOW_ID)), anyList()))
                .thenReturn(Optional.of(parked));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            String message = execResult.errorMessage().orElse("");
            assertFalse(message.contains("Start the workflow first"),
                "A run parked on a signal exists; telling the caller to start one is wrong: " + message);
            assertTrue(message.contains("parked on a blocking node"),
                "Should explain why the live run is ineligible: " + message);
            // The remedy must name callable actions, not describe the intent in prose.
            assertTrue(message.contains("resolve_approval") && message.contains("continue_interface"),
                "Should name the actions that unblock the run: " + message);
        }

        @Test
        @DisplayName("Diagnostic probe should query every non-terminal status, derived from RunStatus")
        void probeShouldQueryEveryNonTerminalStatus() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            // Selection misses (that is what makes the node reach the diagnostic probe)...
            stubNoActiveRun();
            // ...and the probe itself finds nothing either.
            when(workflowRunRepository.findFirstProductionRunByWorkflowIdAndStatusIn(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.empty());

            node.execute(context);

            // The probe's status set is derived from RunStatus.isTerminal() so that adding a status
            // to the enum cannot silently leave it out. Pin that contract against the enum itself:
            // a hardcoded list here would drift in exactly the way the derivation exists to prevent.
            ArgumentCaptor<Collection<RunStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
            verify(workflowRunRepository, atLeastOnce())
                .findFirstProductionRunByWorkflowIdAndStatusIn(
                    eq(UUID.fromString(WORKFLOW_ID)), statuses.capture());

            Set<RunStatus> expected = Arrays.stream(RunStatus.values())
                .filter(status -> !status.isTerminal())
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(statuses.getAllValues().stream().anyMatch(captured -> Set.copyOf(captured).equals(expected)),
                "One lookup should use exactly the non-terminal statuses " + expected
                    + ", captured: " + statuses.getAllValues());
        }

        @Test
        @DisplayName("Should treat a not-yet-started run as transient rather than as a blocking node")
        void shouldTreatPendingRunAsTransient() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            // PENDING is the entity's default status, so this is a run created moments ago that has
            // not started yet. It is ALSO absent from ACTIVE_STATUSES, and it must not be described
            // as parked on a blocking node: there is no signal to resolve, only a state to leave.
            WorkflowRunEntity starting = createMockRun(RunStatus.PENDING, 2);
            stubNoActiveRun();
            when(workflowRunRepository.findFirstProductionRunByWorkflowIdAndStatusIn(
            eq(UUID.fromString(WORKFLOW_ID)), anyList()))
                .thenReturn(Optional.of(starting));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            String message = execResult.errorMessage().orElse("");
            assertFalse(message.contains("parked on a blocking node"),
                "A PENDING run has no blocking node to resolve: " + message);
            assertFalse(message.contains("Start the workflow first"),
                "The run exists, telling the caller to start one is wrong: " + message);
            assertTrue(message.contains("transient state"),
                "Should describe PENDING as transient: " + message);
        }

        @Test
        @DisplayName("Should explain a run with no plan version instead of claiming none exists")
        void shouldExplainRunWithNoPlanVersion() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity(5); // pinned to v5
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            stubNoActiveRun();

            // getPlanVersion() must be stubbed to null EXPLICITLY: createMockRun skips the stub for a
            // null version, and Mockito's default answer returns 0 (not null) for an Integer, which
            // would silently exercise the version-mismatch branch instead of this one.
            WorkflowRunEntity unstamped = createMockRun(RunStatus.WAITING_TRIGGER, null);
            lenient().when(unstamped.getPlanVersion()).thenReturn(null);
            when(workflowRunRepository.findFirstProductionRunByWorkflowIdAndStatusIn(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.of(unstamped));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            String message = execResult.errorMessage().orElse("");
            assertFalse(message.contains("version null"),
                "Must never render a null plan version into the message: " + message);
            assertTrue(message.contains("carries no plan version"),
                "Should explain the run is unstamped: " + message);
            assertTrue(message.contains("pinned version 5"), "Should still name the pin: " + message);
        }

        /**
         * Guard, not a regression test: this branch produced the same text before the change.
         *
         * <p>It pins that widening the diagnostic probe did NOT change the genuinely-no-run case:
         * when nothing non-terminal exists, the caller must still be told to start the workflow,
         * because here that advice is correct. The other new branches all assert the absence of
         * that sentence, so without this test nothing pins its presence where it belongs.
         */
        @Test
        @DisplayName("Should keep the plain message when the workflow is pinned and no run is active at all")
        void shouldKeepPlainMessageWhenPinnedAndNoRunActiveAtAll() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity(5);
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            stubNoActiveRun();
            when(workflowRunRepository.findFirstProductionRunByWorkflowIdAndStatusIn(
                eq(UUID.fromString(WORKFLOW_ID)), anyCollection())).thenReturn(Optional.empty());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            String msg = execResult.errorMessage().orElse("");
            assertTrue(msg.contains("Start the workflow first"),
                "With genuinely no active run the original guidance is still the right one");
            // Pinned: the suggested execute call must carry the pinned version, otherwise the
            // agent starts a run at the wrong version and the next call fails on the mismatch
            // branch instead. Same idiom as startAtPin in the version-mismatch branch.
            assertTrue(msg.contains("workflow(action='execute', id='" + WORKFLOW_ID + "', version=5)"), msg);
            assertTrue(msg.contains("never creates a run"), msg);
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
            stubActiveRun(run);

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
            WorkflowEntity entity = inCallerWorkspace(mock(WorkflowEntity.class));
            Map<String, Object> planMap = new HashMap<>();
            planMap.put("id", WORKFLOW_ID);
            planMap.put("name", "Test");
            planMap.put("triggers", List.of(Map.of("id", "t1", "type", "workflow", "label", "OnComplete")));
            planMap.put("steps", List.of());
            planMap.put("edges", List.of());
            when(entity.getPlan()).thenReturn(planMap);
            // No getPinnedVersion() stub: the node no longer reads the pin, the resolver does.
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);

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
            stubActiveRun(run);

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
            stubActiveRun(run);

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
            stubActiveRun(run);

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
            stubActiveRun(run);

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
            node.setProductionRunResolver(productionRunResolver);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("ReusableTriggerService not injected"));
        }

        @Test
        @DisplayName("Should fail when workflow plan is null")
        void shouldFailWhenWorkflowPlanIsNull() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = inCallerWorkspace(mock(WorkflowEntity.class));
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
            stubActiveRun(run);

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
            stubActiveRun(run);

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
            stubActiveRun(run);

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
            WorkflowEntity entity = inCallerWorkspace(mock(WorkflowEntity.class));
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
            stubActiveRun(run);

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

            WorkflowEntity entity = inCallerWorkspace(mock(WorkflowEntity.class));
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
            stubActiveRun(run);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No fireable trigger"));
        }

        @Test
        @DisplayName("Should resolve TriggerType from plan trigger type")
        void shouldResolveTriggerTypeFromPlan() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = inCallerWorkspace(mock(WorkflowEntity.class));
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
            stubActiveRun(run);

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
            stubActiveRun(run);

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
            stubActiveRun(run);

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
        @DisplayName("Collects epoch outputs OUTSIDE the sub-run monitor, so a collect never blocks another fire")
        void collectsEpochOutputsOutsideTheSubRunMonitor() throws Exception {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC)).thenReturn(Optional.of(run));

            // Thread A parks inside collectEpochOutputs; thread B must still be able to enter the
            // fire. Pre-change both lived in the same synchronized block, so B could not start
            // until A finished collecting and firstCollectSawSecondFire stayed false forever
            // (the latch below would time out).
            CountDownLatch secondFireStarted = new CountDownLatch(1);
            CountDownLatch firstCollectEntered = new CountDownLatch(1);
            AtomicInteger fireCount = new AtomicInteger();
            AtomicBoolean firstCollectSawSecondFire = new AtomicBoolean(false);

            when(reusableTriggerService.executeTriggerInternal(
                any(WorkflowRunEntity.class), anyString(), any(), any(), eq(true), anyMap()))
                .thenAnswer(invocation -> {
                    int fire = fireCount.incrementAndGet();
                    if (fire == 2) {
                        secondFireStarted.countDown();
                    }
                    return createSuccessTriggerResult(fire);
                });

            // Epoch isolation is the whole safety argument for reading outside the monitor, so
            // record which epoch each collect actually asked for rather than accepting anyInt().
            List<Integer> collectedEpochs = Collections.synchronizedList(new ArrayList<>());
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(eq(RUN_ID_PUBLIC), anyInt()))
                .thenAnswer(invocation -> {
                    collectedEpochs.add(invocation.getArgument(1));
                    if (firstCollectEntered.getCount() > 0) {
                        firstCollectEntered.countDown();
                        // Park the FIRST collect and see whether the other fire can proceed.
                        firstCollectSawSecondFire.set(secondFireStarted.await(5, TimeUnit.SECONDS));
                    }
                    return List.<WorkflowStepDataRepository.EpochOutputProjection>of();
                });

            // A dedicated 2-thread pool, NOT the common ForkJoinPool: thread A parks on a plain
            // CountDownLatch inside the mock, which is not a ManagedBlocker, so FJP does not spawn a
            // compensating worker. On a 2-core host commonPool parallelism is 1 and B would never
            // get a thread, failing this test on correct code.
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CompletableFuture<NodeExecutionResult> first =
                    CompletableFuture.supplyAsync(() -> node.execute(context), pool);
                assertTrue(firstCollectEntered.await(5, TimeUnit.SECONDS),
                    "First execution should reach the output-collection phase");
                CompletableFuture<NodeExecutionResult> second =
                    CompletableFuture.supplyAsync(() -> node.execute(context), pool);

                assertTrue(first.get(10, TimeUnit.SECONDS).isSuccess());
                assertTrue(second.get(10, TimeUnit.SECONDS).isSuccess());
            } finally {
                pool.shutdownNow();
            }
            assertTrue(firstCollectSawSecondFire.get(),
                "A fire must be able to start while another execution is collecting its epoch outputs; "
                    + "if this fails, collectEpochOutputs is back inside the sub-run monitor");
            // Each execution must read the epoch ITS OWN fire returned. Without this, a bug making
            // both collects read the same epoch would still pass the concurrency assertion above,
            // while silently breaking the epoch-keyed isolation that makes the unlocked read safe.
            assertEquals(List.of(1, 2), collectedEpochs.stream().sorted().toList(),
                "Each execution should collect its own fire's epoch, got " + collectedEpochs);
        }

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
            stubActiveRun(staleRun);
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
            stubActiveRun(run);

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
            node.setProductionRunResolver(productionRunResolver);
            // Do NOT set stepOutputService or workflowStepDataRepository

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);

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
            stubActiveRun(run);

            // Simulate slow trigger execution
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenAnswer(invocation -> {
                    Thread.sleep(3000); // 3 seconds > 1 second timeout
                    return createSuccessTriggerResult(1);
                });

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            // Wording owned by TimeoutHonestyTests: the point is that it names the budget and
            // does not imply the child was stopped.
            assertTrue(execResult.errorMessage().orElse("").contains("did not finish within"));
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
            stubActiveRun(run);

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
            stubActiveRun(run);

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
                .productionRunResolver(productionRunResolver)
                .stepOutputService(stepOutputService)
                .workflowStepDataRepository(workflowStepDataRepository)
                .build();
            node.acceptServices(registry);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);

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

    // ===============================================================
    // execute() - cross-workspace guard
    // ===============================================================

    /**
     * The target workflow id is runtime-resolvable (it goes through the template adapter),
     * so without a scope guard a crafted template could point this node at any workflow row
     * in the database: it would fire that workflow's run and read its step outputs back into
     * the caller's output. Same guard shape as the other parent-to-child dispatch services.
     */
    @Nested
    @DisplayName("Cross-workspace guard")
    class CrossWorkspaceGuardTests {

        private ExecutionContext contextInOrg(String orgId) {
            return context.withOrganization(orgId, "MEMBER");
        }

        private NodeExecutionResult executeAgainst(ExecutionContext ctx, WorkflowEntity target) {
            SubWorkflowNode node = createNode(new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5));
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(target));
            return node.execute(ctx);
        }

        @Test
        @DisplayName("Resolver not injected → explicit failure, never a silent bypass")
        void resolverNotInjectedFailsExplicitly() {
            SubWorkflowNode node = new SubWorkflowNode(NODE_ID, new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5));
            node.setWorkflowRepository(workflowRepository);
            node.setWorkflowRunRepository(workflowRunRepository);
            // deliberately NO setProductionRunResolver
            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("ProductionRunResolver not injected"));
            // The old code silently fell back to an unfiltered repository lookup here.
            verifyNoInteractions(reusableTriggerService);
        }

        @Test
        @DisplayName("Target owned by ANOTHER tenant (both personal) is refused and never fired")
        void foreignTenantIsRefused() {
            WorkflowEntity foreign = createMockEntity(null, "tenant-somebody-else", null);

            NodeExecutionResult execResult = executeAgainst(context, foreign);

            assertTrue(execResult.isFailure());
            // Reported as not-found, not as forbidden: the caller must not learn that a
            // workflow with this id exists in another workspace.
            assertTrue(execResult.errorMessage().orElse("").contains("Workflow not found"));
            verifyNoInteractions(reusableTriggerService);
            // The strongest guarantee: the refusal happens BEFORE run resolution.
            verifyNoInteractions(productionRunResolver);
            verifyNoInteractions(workflowRunRepository);
        }

        // A caller with no org reaching an org-tagged target it OWNS is covered by
        // degradedContextWithoutOrgFallsBackToTenantOwnership: that is allowed on
        // purpose, because post-V263 a null caller org means a degraded context, not a
        // genuine personal workspace, and refusing it would break working sub-workflow
        // calls. Cross-TENANT is still refused, which is what this guard is for.

        @Test
        @DisplayName("Org-scope caller cannot reach ANOTHER TENANT's target in a different org")
        void differentOrgAndDifferentTenantIsRefused() {
            WorkflowEntity foreign = createMockEntity(null, "tenant-somebody-else", "org-99");

            NodeExecutionResult execResult = executeAgainst(contextInOrg("org-42"), foreign);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("Workflow not found"));
            verifyNoInteractions(reusableTriggerService);
            // The strongest guarantee: the refusal happens BEFORE run resolution.
            verifyNoInteractions(productionRunResolver);
        }

        @Test
        @DisplayName("DOCUMENTED TOLERANCE: same owner in a different org IS allowed")
        void sameOwnerAcrossOwnOrgsIsRefusedWhenCallerOrgIsKnown() {
            // The tolerance applies ONLY when the caller's org is unknown. With a known
            // org the strict rule wins: a run executing in org-42 must not reach the
            // caller's own workflow parked in org-99, which is exactly the isolation
            // isInStrictScope exists to keep.
            WorkflowEntity myOtherOrg = createMockEntity(null, TENANT_ID, "org-99");

            NodeExecutionResult execResult = executeAgainst(contextInOrg("org-42"), myOtherOrg);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("Workflow not found"));
            verifyNoInteractions(productionRunResolver);
        }

        @Test
        @DisplayName("...but with NO caller org, that same own-workflow target IS reachable")
        void sameOwnerAcrossOwnOrgsIsAllowedWhenCallerOrgIsUnknown() {
            WorkflowEntity myOtherOrg = createMockEntity(null, TENANT_ID, "org-99");
            stubNoActiveRun();

            // Degraded run: no org to match on, so ownership is the only rule left, and
            // refusing here would break every sub-workflow call from that run.
            NodeExecutionResult execResult = executeAgainst(context, myOtherOrg);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No active run found"),
                "guard must let it through to run resolution");
        }

        @Test
        @DisplayName("Same org → allowed, resolution proceeds past the guard")
        void sameOrgIsAllowed() {
            WorkflowEntity sameOrg = createMockEntity(null, "tenant-other-member", "org-42");
            stubNoActiveRun();

            NodeExecutionResult execResult = executeAgainst(contextInOrg("org-42"), sameOrg);

            // Guard passed: it got as far as run resolution (a member of the same org may
            // call it, even though a different member owns the row).
            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No active run found"));
        }

        @Test
        @DisplayName("Caller context with NO org falls back to tenant ownership - same tenant is allowed")
        void degradedContextWithoutOrgFallsBackToTenantOwnership() {
            // workflows.organization_id is NOT NULL since V263, but the ExecutionContext
            // org degrades to null on several lookup-failure paths. A strict org-vs-org
            // compare would then refuse EVERY sub-workflow call from that run with a
            // misleading "Workflow not found" - a call that worked before this guard.
            WorkflowEntity orgTagged = createMockEntity(null, TENANT_ID, "org-42");
            stubNoActiveRun();

            NodeExecutionResult execResult = executeAgainst(context, orgTagged);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No active run found"),
                "guard must let it through to run resolution, not refuse it");
        }

        @Test
        @DisplayName("Caller context with NO org still refuses ANOTHER tenant - the property the guard exists for")
        void degradedContextStillRefusesForeignTenant() {
            WorkflowEntity foreign = createMockEntity(null, "tenant-somebody-else", "org-42");

            NodeExecutionResult execResult = executeAgainst(context, foreign);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("Workflow not found"));
            verifyNoInteractions(reusableTriggerService);
            // The strongest guarantee: the refusal happens BEFORE run resolution.
            verifyNoInteractions(productionRunResolver);
        }

        @Test
        @DisplayName("Blank-string caller org is treated as absent, not as an org that matches nothing")
        void blankCallerOrgIsTreatedAsAbsent() {
            // Target owned by SOMEONE ELSE in org-42. If a blank caller org were treated
            // as a real org value, crossResourceMatches("  ", "org-42") would refuse it;
            // if it were treated as absent-but-then-owner-matched, it would also refuse
            // (different owner). Only "blank = absent, and absent means the org is
            // unknown" can be told apart here - so assert the refusal AND that a same-
            // owner target with the same blank org is allowed (next assertion block).
            WorkflowEntity someoneElsesInOrg = createMockEntity(null, "tenant-other-member", "org-42");

            NodeExecutionResult refused = executeAgainst(
                context.withOrganization("  ", null), someoneElsesInOrg);

            assertTrue(refused.isFailure());
            assertTrue(refused.errorMessage().orElse("").contains("Workflow not found"));
            verifyNoInteractions(productionRunResolver);
        }

        @Test
        @DisplayName("Blank caller org still reaches the caller's OWN workflow (degraded, not dead)")
        void blankCallerOrgStillReachesOwnWorkflow() {
            WorkflowEntity mine = createMockEntity(null, TENANT_ID, "org-42");
            stubNoActiveRun();

            NodeExecutionResult execResult = executeAgainst(context.withOrganization("  ", null), mine);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No active run found"),
                "guard must let an owned target through even with no usable caller org");
        }

        @Test
        @DisplayName("Same personal workspace → allowed, resolution proceeds past the guard")
        void samePersonalWorkspaceIsAllowed() {
            WorkflowEntity mine = createMockEntity(null, TENANT_ID, null);
            stubNoActiveRun();

            NodeExecutionResult execResult = executeAgainst(context, mine);

            assertTrue(execResult.isFailure());
            assertTrue(execResult.errorMessage().orElse("").contains("No active run found"));
        }
    }

    // ===============================================================
    // Waiting for the child epoch to close
    // ===============================================================

    /**
     * The bug these cover: the fire returning is not the child finishing. A node inside the child
     * that yields (a wait past 3s, a user approval, an interface awaiting __continue, or an agent
     * on the async queue) defers the rest of that DAG, and the node used to read the step rows
     * present at that instant and publish them under success=true.
     *
     * <p>Note what is NOT simulated here: a step row in a non-terminal state. No such row exists
     * in this system, which is precisely why the node has to ask the engine instead of inspecting
     * the child's data. The scenarios below drive the real predicate,
     * {@link ReusableTriggerService#isEpochStillOpen}.
     */
    @Nested
    @DisplayName("execute() - Waits for the child epoch to close")
    class ChildEpochWaitTests {

        @Mock
        private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher redisPublisher;

        private SubWorkflowNode nodeWithTimeout(int timeoutSeconds) {
            SubWorkflowNode node = createNode(new Core.SubWorkflowConfig(WORKFLOW_ID, null, timeoutSeconds, 5));
            node.setWorkflowRedisPublisher(redisPublisher);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            // any(), not eq(run): the node re-reads the freshest child row inside the stripe lock,
            // so a test that stubs that read would otherwise silently miss this stub.
            when(reusableTriggerService.executeTriggerInternal(
                any(WorkflowRunEntity.class), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));
            return node;
        }

        @Test
        @DisplayName("An agent in the child defers its epoch; the node waits and then succeeds with the FULL outputs")
        void shouldWaitForADeferredChildEpochAndThenSucceed() {
            // An agent node goes to the async queue BY DEFAULT (scaling.agent.queue.enabled=true),
            // registers as pending and yields, so this is the ordinary shape of a called workflow,
            // not an edge case. Pre-fix it produced a green node holding a prefix of the epoch.
            SubWorkflowNode node = nodeWithTimeout(60);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenReturn(true, true, false);
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess(), "a child that finishes inside the budget is a success");
            assertEquals(true, execResult.output().get("success"));
            verify(reusableTriggerService, times(3)).isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1);
        }

        @Test
        @DisplayName("Outputs are read only AFTER the epoch closed, never while it is still open")
        void shouldNotReadOutputsBeforeTheEpochCloses() {
            // This ordering IS the bug. Reading first is what shipped 4 of 11 steps as a success.
            SubWorkflowNode node = nodeWithTimeout(60);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenReturn(true, false);
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            node.execute(context);

            InOrder order = inOrder(reusableTriggerService, workflowStepDataRepository);
            order.verify(reusableTriggerService, times(2)).isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1);
            order.verify(workflowStepDataRepository).findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1);
        }

        @Test
        @DisplayName("A child that already finished is not made to wait at all")
        void shouldNotWaitWhenTheEpochIsAlreadyClosed() {
            SubWorkflowNode node = nodeWithTimeout(60);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(false);
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess());
            verify(reusableTriggerService, times(1)).isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1);
        }

        @Test
        @DisplayName("A child still unfinished when the budget runs out fails, and says nothing was stopped")
        void shouldFailWhenTheBudgetRunsOutWithTheChildUnfinished() {
            // The honest answer for a child parked on a person: no timeout can resolve an approval.
            SubWorkflowNode node = nodeWithTimeout(1);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isFailure());
            String message = execResult.errorMessage().orElse("");
            assertTrue(message.contains("NOT stopped") && message.contains("still executing"),
                "must not imply the child was cancelled: " + message);
            assertTrue(message.contains(RUN_ID_PUBLIC), "must name the run that can be stopped: " + message);
            assertTrue(message.contains("__continue") && message.contains("approval"),
                "must name the causes no timeout can fix: " + message);
        }

        @Test
        @DisplayName("Never publishes the child's partial outputs when it gave up on it")
        void shouldNotCollectOutputsWhenTheChildNeverFinished() {
            SubWorkflowNode node = nodeWithTimeout(1);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);

            node.execute(context);

            verify(workflowStepDataRepository, never())
                .findCompletedOutputRefsByRunIdAndEpoch(anyString(), anyInt());
        }

        @Test
        @DisplayName("A child run that goes terminal ends the wait immediately instead of burning the budget")
        void shouldStopWaitingWhenTheChildRunGoesTerminal() {
            // A cancelled or failed child will never close that epoch. Waiting out a 300s budget
            // would only delay the same answer. Pre-fix this case reported SUCCESS on the partial
            // epoch of a cancelled run, which is worse than the bug being fixed.
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);
            when(workflowRunRepository.findStatusByRunIdPublic(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(RunStatus.CANCELLED));

            long startedAt = System.currentTimeMillis();
            NodeExecutionResult execResult = node.execute(context);
            long elapsed = System.currentTimeMillis() - startedAt;

            assertTrue(execResult.isFailure());
            assertTrue(elapsed < 10_000, "must not sit on the full 300s budget, took " + elapsed + "ms");
            verify(workflowStepDataRepository, never())
                .findCompletedOutputRefsByRunIdAndEpoch(anyString(), anyInt());
        }

        @Test
        @DisplayName("WAITING_TRIGGER is not terminal: an ordinary resting child is still waited for")
        void shouldKeepWaitingWhileTheChildRunRestsBetweenFires() {
            // A reusable run sits in WAITING_TRIGGER between fires, which is where essentially
            // every healthy child is when this check runs. Treating any non-null status as an
            // ending would abandon the wait on all of them.
            //
            // The stub chain must be long enough to actually TRIP the status check: it fires on
            // the Nth poll, so a two-value chain would leave this branch unexecuted and the test
            // would pass without testing anything.
            SubWorkflowNode node = nodeWithTimeout(60);
            Boolean[] openUntilTheCheckFires =
                new Boolean[SubWorkflowNode.TERMINAL_CHECK_EVERY_N_POLLS];
            java.util.Arrays.fill(openUntilTheCheckFires, Boolean.TRUE);
            openUntilTheCheckFires[openUntilTheCheckFires.length - 1] = Boolean.FALSE;
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenReturn(Boolean.TRUE, openUntilTheCheckFires);
            // The branch reads the STATUS projection, not the entity: stubbing the entity lookup
            // would leave the scenario unwired to the code under test.
            when(workflowRunRepository.findStatusByRunIdPublic(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(RunStatus.WAITING_TRIGGER));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            assertTrue(node.execute(context).isSuccess(),
                "a child resting in WAITING_TRIGGER has not ended, so the wait must continue");
            verify(workflowRunRepository, atLeastOnce()).findStatusByRunIdPublic(RUN_ID_PUBLIC);
        }

        @Test
        @DisplayName("A stop on this run abandons the wait instead of parking a worker")
        void shouldAbandonTheWaitWhenThisRunIsCancelled() {
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);
            when(redisPublisher.isAgentCancelSignalSet("run-1")).thenReturn(true);

            long startedAt = System.currentTimeMillis();
            NodeExecutionResult execResult = node.execute(context);
            long elapsed = System.currentTimeMillis() - startedAt;

            assertTrue(execResult.isFailure());
            assertTrue(elapsed < 10_000, "a stop must be responsive, took " + elapsed + "ms");
            // Without this the test would pass on any early failure, including a budget timeout.
            String message = execResult.errorMessage().orElse("");
            assertTrue(message.contains("This run was stopped"), message);
            assertFalse(message.contains("did not finish within"),
                "the budget was not spent, so it must not claim it was: " + message);
        }

        @Test
        @DisplayName("An unreadable epoch check fails OPEN: the run proceeds exactly as before the check existed")
        void shouldProceedWhenTheEpochCheckThrows() {
            // Criterion: the fix must not become a new way for a healthy run to fail. A DB or
            // Redis blip is not evidence that the child is unfinished.
            SubWorkflowNode node = nodeWithTimeout(60);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenThrow(new IllegalStateException("redis down"));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess(), "an unreadable check must not fail the run");
            assertEquals(true, execResult.output().get("success"));
        }

        @Test
        @DisplayName("Asks about the DAG key the fire actually ran under, not the node's own copy")
        void shouldAskAboutTheDagKeyCarriedByTheResult() {
            // A key that misses reports "nothing pending", so the whole guard would go silently
            // inert rather than fail loudly. The result carries the string the engine indexed by.
            SubWorkflowNode node = createNode(new Core.SubWorkflowConfig(WORKFLOW_ID, null, 60, 5));
            node.setWorkflowRedisPublisher(redisPublisher);
            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(TriggerExecutionResult.success(
                    RUN_ID_PUBLIC, "trigger:some_other_dag", TriggerType.MANUAL, Set.of(), 4));
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:some_other_dag", 4))
                .thenReturn(false);
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 4))
                .thenReturn(List.of());

            assertTrue(node.execute(context).isSuccess());

            verify(reusableTriggerService).isEpochStillOpen(RUN_ID_PUBLIC, "trigger:some_other_dag", 4);
        }

        @Test
        @DisplayName("Clears the parent pointer on every exit, including the one where it gave up")
        void shouldAlwaysClearTheParentPointer() {
            // The pointer is scoped to the wait by design. Leaving it behind on a SHARED reusable
            // child run would let a later stop of THIS run abort an unrelated fire of that child.
            SubWorkflowNode node = nodeWithTimeout(1);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);

            assertTrue(node.execute(context).isFailure());

            verify(redisPublisher).registerSubWorkflowParent(eq(RUN_ID_PUBLIC), anyString());
            verify(redisPublisher).clearSubWorkflowParent(RUN_ID_PUBLIC);
        }

        @Test
        @DisplayName("A closed epoch's completed rows are returned as a success")
        void shouldReturnCompletedRowsOfAClosedEpoch() {
            // Named for what it actually does. It cannot reproduce the failed-then-retried shape,
            // because the only query this node uses returns COMPLETED rows by definition, so the
            // extra FAILED row a retry leaves behind is never visible here. That is precisely why
            // the node must take the verdict from the fire (which reports a failed EPOCH as a
            // failed fire) and never re-derive it from row statuses.
            SubWorkflowNode node = nodeWithTimeout(60);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(false);
            WorkflowStepDataRepository.EpochOutputProjection failedThenRetried = mock(
                WorkflowStepDataRepository.EpochOutputProjection.class);
            when(failedThenRetried.getStepAlias()).thenReturn("mcp:flaky_step");
            UUID storageId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            when(failedThenRetried.getOutputStorageId()).thenReturn(storageId);
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of(failedThenRetried));
            when(stepOutputService.loadRawOutput(storageId, TENANT_ID))
                .thenReturn(Map.of("ok", true));

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess(), "a retried-then-successful child is a success");
            assertEquals(true, execResult.output().get("success"));
        }
    }

    @Nested
    @DisplayName("execute() - Timeout does not stop the child")
    class TimeoutHonestyTests {

        @Test
        @DisplayName("A fire that outlives the budget reports the child as still running")
        void shouldNotClaimTheChildWasStoppedOnFireTimeout() {
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 1, 5);
            SubWorkflowNode node = createNode(config);

            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);

            CountDownLatch release = new CountDownLatch(1);
            when(reusableTriggerService.executeTriggerInternal(
                eq(run), anyString(), any(), any(), eq(true), anyMap()))
                .thenAnswer(invocation -> {
                    release.await(30, TimeUnit.SECONDS);
                    return createSuccessTriggerResult(1);
                });

            try {
                NodeExecutionResult execResult = node.execute(context);

                assertTrue(execResult.isFailure());
                String message = execResult.errorMessage().orElse("");
                assertTrue(message.contains("did not finish within 1 second"), message);
                assertTrue(message.contains("NOT stopped") && message.contains("still executing"),
                    "the child keeps running after a timeout and the message must say so: " + message);
                assertTrue(message.contains(RUN_ID_PUBLIC), message);
            } finally {
                release.countDown();
            }
        }
    }

    @Nested
    @DisplayName("execute() - Wait mechanics")
    class WaitMechanicsTests {

        @Mock
        private com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher redisPublisher;

        private SubWorkflowNode nodeWithTimeout(int timeoutSeconds) {
            SubWorkflowNode node = createNode(new Core.SubWorkflowConfig(WORKFLOW_ID, null, timeoutSeconds, 5));
            node.setWorkflowRedisPublisher(redisPublisher);
            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            when(reusableTriggerService.executeTriggerInternal(
                any(WorkflowRunEntity.class), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(createSuccessTriggerResult(1));
            return node;
        }

        @Test
        @DisplayName("The fire gets the REMAINING budget, so one call cannot spend the budget twice")
        void shouldGiveTheFireOnlyTheRemainingBudget() throws Exception {
            // Two calls against the SAME child run serialize on the node's per-sub-run lock, so
            // the second one starts its fire with most of its budget already gone. Its deadline
            // was set before the lock wait, which is the whole point: one timeoutSeconds covers
            // waiting for the lock, firing, and waiting for the epoch.
            //
            // Sized so exactly one caller can afford the fire, with margin for a loaded machine.
            // Budget 6s, fire 4s: the winner gets ~5s left (the remaining-seconds conversion
            // truncates down) and fits; the loser reaches the lock 4s in, has ~2s left, and times
            // out. The two only swap verdicts if they start more than ~2s apart, which is the
            // tolerance. Hand the fire a fresh timeoutSeconds instead and BOTH succeed, which is
            // the bug: a single node would run for close to twice its configured budget.
            Core.SubWorkflowConfig config = new Core.SubWorkflowConfig(WORKFLOW_ID, null, 6, 5);
            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            when(reusableTriggerService.executeTriggerInternal(
                any(WorkflowRunEntity.class), anyString(), any(), any(), eq(true), anyMap()))
                .thenAnswer(invocation -> {
                    Thread.sleep(4_000);
                    return createSuccessTriggerResult(1);
                });
            lenient().when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenReturn(false);
            lenient().when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            SubWorkflowNode first = createNode(config);
            SubWorkflowNode second = createNode(config);
            first.setWorkflowRedisPublisher(redisPublisher);
            second.setWorkflowRedisPublisher(redisPublisher);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch bothReady = new CountDownLatch(2);
                List<java.util.concurrent.Future<NodeExecutionResult>> runs = List.of(
                    pool.submit(() -> { bothReady.countDown(); bothReady.await(5, TimeUnit.SECONDS); return first.execute(context); }),
                    pool.submit(() -> { bothReady.countDown(); bothReady.await(5, TimeUnit.SECONDS); return second.execute(context); }));

                List<NodeExecutionResult> results = new ArrayList<>();
                for (java.util.concurrent.Future<NodeExecutionResult> r : runs) {
                    results.add(r.get(60, TimeUnit.SECONDS));
                }
                List<NodeExecutionResult> failed = results.stream()
                    .filter(NodeExecutionResult::isFailure).toList();

                assertEquals(1, failed.size(),
                    "the second caller waited out most of its budget on the lock, so its fire must "
                    + "get only what is LEFT; a fresh budget there lets both succeed and lets a "
                    + "single node run for nearly twice its setting");
                assertEquals(1, results.stream().filter(NodeExecutionResult::isSuccess).count());
                // Prove it failed for the RIGHT reason: a bare count would also be satisfied by a
                // missed stub or an NPE inside execute().
                String failureMessage = failed.get(0).errorMessage().orElse("");
                assertTrue(failureMessage.contains("did not finish within"), failureMessage);
                assertTrue(failureMessage.contains(RUN_ID_PUBLIC), failureMessage);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("A child that reached a SUCCESS status is never described as still executing")
        void shouldNotClaimAStillRunningChildWhenTheChildCompleted() {
            // COMPLETED is terminal too. The status read and the epoch read are not atomic, so a
            // child that finished between them must not be reported with the "it is still
            // executing and can still perform external actions" wording, which would be false.
            SubWorkflowNode node = nodeWithTimeout(300);
            // Polls keep it open until the terminal check trips, and the re-read that follows
            // sees it closed. That is the race this branch exists to absorb. Sized from the
            // constant so a cadence change fails here rather than quietly changing the scenario.
            Boolean[] openUntilTheCheckFires =
                new Boolean[SubWorkflowNode.TERMINAL_CHECK_EVERY_N_POLLS];
            java.util.Arrays.fill(openUntilTheCheckFires, Boolean.TRUE);
            openUntilTheCheckFires[openUntilTheCheckFires.length - 1] = Boolean.FALSE;
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenReturn(Boolean.TRUE, openUntilTheCheckFires);
            when(workflowRunRepository.findStatusByRunIdPublic(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(RunStatus.COMPLETED));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            NodeExecutionResult execResult = node.execute(context);

            assertTrue(execResult.isSuccess(),
                "the re-read showed the epoch closed, so this is a success, not a false alarm");
        }

        @Test
        @DisplayName("A FAILED child ends the wait rather than burning the whole budget")
        void shouldStopWaitingWhenTheChildRunFailed() {
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);
            when(workflowRunRepository.findStatusByRunIdPublic(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(RunStatus.FAILED));

            long startedAt = System.currentTimeMillis();
            NodeExecutionResult execResult = node.execute(context);
            long elapsed = System.currentTimeMillis() - startedAt;

            assertTrue(execResult.isFailure());
            assertTrue(elapsed < 20_000, "must not sit on the full 300s budget, took " + elapsed + "ms");
        }

        @Test
        @DisplayName("Reads the child status by scalar projection, never by re-loading the entity")
        void shouldReadChildStatusWithoutTheEntityCache() {
            // The child run row was already loaded earlier in this same call, so an entity lookup
            // can be served from Hibernate's L1 cache and report the status as of that first load.
            // This branch exists to observe a CHANGE, so a cached answer would make it dead code.
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);
            when(workflowRunRepository.findStatusByRunIdPublic(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(RunStatus.CANCELLED));

            node.execute(context);

            verify(workflowRunRepository, atLeastOnce()).findStatusByRunIdPublic(RUN_ID_PUBLIC);
        }

        @Test
        @DisplayName("An unreadable child status keeps the wait going instead of ending it")
        void shouldKeepWaitingWhenTheChildStatusCannotBeRead() {
            SubWorkflowNode node = nodeWithTimeout(60);
            // Sized from the constant, like the other cadence-dependent tests, so a change to the
            // polling period fails here instead of quietly changing the scenario.
            Boolean[] openUntilTheCheckFires =
                new Boolean[SubWorkflowNode.TERMINAL_CHECK_EVERY_N_POLLS];
            java.util.Arrays.fill(openUntilTheCheckFires, Boolean.TRUE);
            openUntilTheCheckFires[openUntilTheCheckFires.length - 1] = Boolean.FALSE;
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenReturn(Boolean.TRUE, openUntilTheCheckFires);
            when(workflowRunRepository.findStatusByRunIdPublic(RUN_ID_PUBLIC))
                .thenThrow(new IllegalStateException("db blip"));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            assertTrue(node.execute(context).isSuccess(),
                "a failed status read is not evidence the child stopped");
        }

        @Test
        @DisplayName("The child status is polled on a slower cadence than the epoch itself")
        void shouldNotReadTheChildStatusOnEveryPoll() {
            // It is a second DB round trip whose answer changes at most once per wait, and the
            // deadline already bounds a missed transition.
            SubWorkflowNode node = nodeWithTimeout(60);
            // Derived from the constant, so changing the cadence FAILS this test instead of
            // silently changing what it means.
            Boolean[] openUntilJustBeforeTheCheck =
                new Boolean[SubWorkflowNode.TERMINAL_CHECK_EVERY_N_POLLS - 1];
            java.util.Arrays.fill(openUntilJustBeforeTheCheck, Boolean.TRUE);
            openUntilJustBeforeTheCheck[openUntilJustBeforeTheCheck.length - 1] = Boolean.FALSE;
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenReturn(Boolean.TRUE, openUntilJustBeforeTheCheck);
            lenient().when(workflowRunRepository.findStatusByRunIdPublic(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(RunStatus.RUNNING));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            node.execute(context);

            verify(reusableTriggerService, times(SubWorkflowNode.TERMINAL_CHECK_EVERY_N_POLLS))
                .isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1);
            verify(workflowRunRepository, never()).findStatusByRunIdPublic(RUN_ID_PUBLIC);
        }

        @Test
        @DisplayName("The wait compensates the pool instead of consuming one of its workers")
        void compensatedSleepShouldNotConsumeAPoolWorker() throws Exception {
            // Why this tests compensatedSleep directly rather than driving a node: execute() first
            // blocks on CompletableFuture.get, which is ITSELF a compensating block, so the pool
            // has already grown a spare worker by the time the poll loop sleeps. A node-level test
            // therefore passes whether or not the sleep compensates, and proves nothing.
            //
            // execute() is dispatched onto ForkJoinPool.commonPool by every concurrent path (fork
            // branches, split items, any cycle with more than one ready node), and that pool's
            // parallelism is availableProcessors - 1, which is ONE on a 2-vCPU pod. A worker
            // consumed by an uncompensated sleep stalls every other dispatch on the pod for the
            // whole budget, including the child's own resume, which is what the wait depends on.
            ForkJoinPool singleWorker = new ForkJoinPool(1);
            try {
                CountDownLatch sleeping = new CountDownLatch(1);
                CountDownLatch otherTaskRan = new CountDownLatch(1);

                ForkJoinTask<Boolean> sleeper = singleWorker.submit(() -> {
                    sleeping.countDown();
                    return SubWorkflowNode.compensatedSleep(3_000);
                });
                assertTrue(sleeping.await(10, TimeUnit.SECONDS), "the sleeping task should have started");
                // Give the sleep a moment to actually enter the block before probing the pool.
                Thread.sleep(300);

                singleWorker.execute(otherTaskRan::countDown);

                assertTrue(otherTaskRan.await(2, TimeUnit.SECONDS),
                    "the pool's only worker is consumed by the wait: an uncompensated block here "
                    + "stalls every other dispatch on the pod, including the child's own resume, "
                    + "which turns the wait into a self-inflicted timeout");
                assertTrue(sleeper.get(10, TimeUnit.SECONDS), "the sleep should complete normally");
            } finally {
                singleWorker.shutdownNow();
            }
        }

        @Test
        @DisplayName("Refuses to wait inside a transaction rather than pinning a DB connection")
        void shouldNotWaitWhileATransactionIsOpen() {
            // Stepping a workflow one node at a time runs this node inside a read-write
            // @Transactional method. Sleeping there holds a pooled connection and an open session
            // for the whole budget. Failing fast keeps the correctness guarantee (never publish a
            // partial epoch) without that cost.
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);

            org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
            long startedAt;
            NodeExecutionResult execResult;
            try {
                startedAt = System.currentTimeMillis();
                execResult = node.execute(context);
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
            }
            long elapsed = System.currentTimeMillis() - startedAt;

            assertTrue(execResult.isFailure());
            assertTrue(elapsed < 5_000, "must not block inside a transaction, took " + elapsed + "ms");
            String message = execResult.errorMessage().orElse("");
            assertTrue(message.contains("cannot wait"), message);
            // It must NOT claim a budget was spent: none was.
            assertFalse(message.contains("did not finish within"), message);
            verify(workflowStepDataRepository, never())
                .findCompletedOutputRefsByRunIdAndEpoch(anyString(), anyInt());
        }

        @Test
        @DisplayName("A step-by-step run does not wait even when no transaction is visible on this thread")
        void shouldNotWaitOnAStepByStepRunAcrossAThreadHop() {
            // THE hop case, and the reason the thread-local check is not enough on its own.
            // Stepping a workflow holds an open read-write transaction across the whole node call,
            // but the node is not always reached on that thread: a split fans its items onto the
            // common pool, and a node-level timeout policy hands the body to its own executor.
            // Across either hop isActualTransactionActive() reads FALSE while the caller is still
            // blocked on the join holding the transaction open, so polling there would pin a
            // pooled connection for the whole budget anyway.
            //
            // The run's execution mode travels with the run rather than the thread, so it still
            // answers correctly after a hop. No transaction is set on this test's thread, which is
            // exactly the post-hop situation.
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);
            WorkflowRunEntity steppedRun = mock(WorkflowRunEntity.class);
            lenient().when(steppedRun.getExecutionMode())
                .thenReturn(com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.STEP_BY_STEP);
            lenient().when(workflowRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(steppedRun));

            long startedAt = System.currentTimeMillis();
            NodeExecutionResult execResult = node.execute(context);
            long elapsed = System.currentTimeMillis() - startedAt;

            assertTrue(execResult.isFailure());
            assertTrue(elapsed < 5_000,
                "a stepped run must not be polled in: it holds a DB transaction open, took " + elapsed + "ms");
            assertTrue(execResult.errorMessage().orElse("").contains("cannot wait"),
                execResult.errorMessage().orElse(""));
            // The correctness guarantee still held: no partial epoch was published.
            verify(workflowStepDataRepository, never())
                .findCompletedOutputRefsByRunIdAndEpoch(anyString(), anyInt());
        }

        @Test
        @DisplayName("An AUTOMATIC run is waited for normally, so the guard is not over-broad")
        void shouldStillWaitOnAnAutomaticRun() {
            // The mode check must not accidentally disable the wait for ordinary runs, which are
            // the whole point of the change.
            SubWorkflowNode node = nodeWithTimeout(60);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1))
                .thenReturn(true, false);
            WorkflowRunEntity automaticRun = mock(WorkflowRunEntity.class);
            lenient().when(automaticRun.getExecutionMode())
                .thenReturn(com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.AUTOMATIC);
            lenient().when(workflowRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(automaticRun));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            assertTrue(node.execute(context).isSuccess());
            verify(reusableTriggerService, times(2)).isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1);
        }

        @Test
        @DisplayName("A finished child inside a transaction is still a normal success")
        void shouldStillSucceedInsideATransactionWhenTheChildIsDone() {
            // The single check always runs; only the LOOP is refused. An ordinary sub-workflow
            // call stepped by hand must keep working exactly as before.
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(false);
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, 1))
                .thenReturn(List.of());

            org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
            try {
                assertTrue(node.execute(context).isSuccess());
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
            }
        }

        @Test
        @DisplayName("Each way the wait ends gets its own message, none of them borrowed")
        void shouldTellTheTruthForEachWayTheWaitEnds() {
            // One shared wording is how an agent gets told to stop a run that already stopped.
            String ended = SubWorkflowNode.waitFailureMessage(
                SubWorkflowNode.WaitOutcome.CHILD_ENDED, 300, RUN_ID_PUBLIC);
            String stopped = SubWorkflowNode.waitFailureMessage(
                SubWorkflowNode.WaitOutcome.RUN_STOPPED, 300, RUN_ID_PUBLIC);
            String spent = SubWorkflowNode.waitFailureMessage(
                SubWorkflowNode.WaitOutcome.BUDGET_SPENT, 300, RUN_ID_PUBLIC);

            // A child that ENDED is not "still executing", and no budget was spent on it.
            assertFalse(ended.contains("still executing"), ended);
            assertFalse(ended.contains("did not finish within"), ended);
            assertTrue(ended.contains("ended before finishing"), ended);

            // OUR run stopped: the child may well still be running, and no budget was spent.
            assertFalse(stopped.contains("did not finish within"), stopped);
            assertTrue(stopped.contains("This run was stopped"), stopped);

            // Only the budget case may claim the budget.
            assertTrue(spent.contains("did not finish within 300 seconds"), spent);
            assertTrue(spent.contains("NOT stopped"), spent);

            assertNotEquals(ended, stopped);
            assertNotEquals(ended, spent);
            assertNotEquals(stopped, spent);
        }

        @Test
        @DisplayName("A terminal child's message points at that run, not at a phantom running one")
        void shouldDescribeATerminalChildAsEnded() {
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);
            when(workflowRunRepository.findStatusByRunIdPublic(RUN_ID_PUBLIC))
                .thenReturn(Optional.of(RunStatus.CANCELLED));

            String message = node.execute(context).errorMessage().orElse("");

            assertTrue(message.contains("ended before finishing"), message);
            assertFalse(message.contains("still executing"),
                "the child is cancelled; warning about future side effects is false: " + message);
        }

        @Test
        @DisplayName("An epoch of -1 (queued fire) is not waited on")
        void shouldNotWaitForAnUnknownEpoch() {
            // A fire dispatched to the queue reports epoch -1. There is no epoch to ask about, and
            // reporting it open would park the node for the whole budget on an unanswerable question.
            SubWorkflowNode node = createNode(new Core.SubWorkflowConfig(WORKFLOW_ID, null, 300, 5));
            node.setWorkflowRedisPublisher(redisPublisher);
            WorkflowEntity entity = createMockEntity();
            when(workflowRepository.findById(UUID.fromString(WORKFLOW_ID))).thenReturn(Optional.of(entity));
            WorkflowRunEntity run = createMockRun(RunStatus.WAITING_TRIGGER);
            stubActiveRun(run);
            when(reusableTriggerService.executeTriggerInternal(
                any(WorkflowRunEntity.class), anyString(), any(), any(), eq(true), anyMap()))
                .thenReturn(TriggerExecutionResult.accepted(RUN_ID_PUBLIC, "trigger:start", TriggerType.MANUAL));
            when(workflowStepDataRepository.findCompletedOutputRefsByRunIdAndEpoch(RUN_ID_PUBLIC, -1))
                .thenReturn(List.of());

            long startedAt = System.currentTimeMillis();
            assertTrue(node.execute(context).isSuccess());
            assertTrue(System.currentTimeMillis() - startedAt < 5_000, "must not wait on epoch -1");

            verify(reusableTriggerService, never()).isEpochStillOpen(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("SleepBlocker carries its remainder across an early wake-up")
        void sleepBlockerShouldCarryItsRemainder() throws Exception {
            // A ManagedBlocker may be resumed BEFORE its time. If block() treated one call as
            // having served the whole duration, an interrupted sleep would report itself finished,
            // the backoff would collapse and the poll loop would spin.
            //
            // So the early exit has to be forced: sleeping the full duration and then checking
            // isReleasable() proves nothing, because a mutation that simply zeroes the remainder
            // passes that too.
            SubWorkflowNode.SleepBlocker blocker =
                new SubWorkflowNode.SleepBlocker(TimeUnit.SECONDS.toNanos(5));
            assertFalse(blocker.isReleasable(), "a fresh blocker has time left to serve");

            AtomicBoolean interruptedEarly = new AtomicBoolean(false);
            AtomicBoolean releasableAfterEarlyExit = new AtomicBoolean(true);
            Thread sleeper = new Thread(() -> {
                try {
                    blocker.block();
                } catch (InterruptedException e) {
                    interruptedEarly.set(true);
                }
                releasableAfterEarlyExit.set(blocker.isReleasable());
            });
            sleeper.start();
            Thread.sleep(200);
            sleeper.interrupt();
            sleeper.join(10_000);

            assertTrue(interruptedEarly.get(), "the blocker should have been woken early");
            assertFalse(releasableAfterEarlyExit.get(),
                "it served ~0.2s of a 5s sleep, so it still owes time; reporting itself releasable "
                + "here is what would collapse the backoff into a spin");
        }

        @Test
        @DisplayName("An interrupt while waiting restores the flag and does not claim a timeout")
        void shouldRestoreTheInterruptFlagAndNotClaimATimeout() throws Exception {
            SubWorkflowNode node = nodeWithTimeout(300);
            when(reusableTriggerService.isEpochStillOpen(RUN_ID_PUBLIC, "trigger:start", 1)).thenReturn(true);

            AtomicReference<NodeExecutionResult> result = new AtomicReference<>();
            AtomicBoolean flagWasSet = new AtomicBoolean(false);
            CountDownLatch started = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                started.countDown();
                result.set(node.execute(context));
                flagWasSet.set(Thread.currentThread().isInterrupted());
            });
            worker.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(400);
            worker.interrupt();
            worker.join(15_000);

            assertNotNull(result.get(), "the node should have returned rather than hanging");
            assertTrue(result.get().isFailure());
            assertTrue(flagWasSet.get(), "the interrupt flag must be restored for the caller");
            String message = result.get().errorMessage().orElse("");
            // Wording unique to the WAIT's interrupt path. The node also has an outer
            // InterruptedException handler whose message merely contains "interrupted", so a
            // looser assertion could not tell the two apart.
            assertTrue(message.contains("was interrupted before it finished"), message);
            assertTrue(message.contains("may still be running"), message);
            assertFalse(message.contains("did not finish within"),
                "no budget was spent, so it must not claim one: " + message);
        }
    }

    // ===============================================================
    // The timeout ceiling (enforced, not just documented)
    // ===============================================================

    @Nested
    @DisplayName("timeoutSeconds ceiling")
    class TimeoutCeilingTests {

        @Test
        @DisplayName("A value above the ceiling is reduced to it")
        void shouldClampAnOversizedTimeout() {
            // The node now genuinely parks a pooled worker for this long, so the value has to be
            // bounded. Before the wait existed it bounded almost nothing and was never enforced.
            assertEquals(Core.SubWorkflowConfig.MAX_TIMEOUT_SECONDS,
                new Core.SubWorkflowConfig(WORKFLOW_ID, null, 86_400, 5).timeoutSeconds());
        }

        @Test
        @DisplayName("The ceiling itself is allowed, not clamped off by one")
        void shouldAllowExactlyTheCeiling() {
            assertEquals(Core.SubWorkflowConfig.MAX_TIMEOUT_SECONDS,
                new Core.SubWorkflowConfig(WORKFLOW_ID, null,
                    Core.SubWorkflowConfig.MAX_TIMEOUT_SECONDS, 5).timeoutSeconds());
        }

        @Test
        @DisplayName("Zero and negative still fall back to the default, not to the ceiling")
        void shouldKeepTheZeroAndNegativeDefault() {
            // The new Math.min sits next to the pre-existing default. A careless combination would
            // turn "unset" into "the maximum", which is a 25-minute wait nobody asked for.
            assertEquals(300, new Core.SubWorkflowConfig(WORKFLOW_ID, null, 0, 5).timeoutSeconds());
            assertEquals(300, new Core.SubWorkflowConfig(WORKFLOW_ID, null, -5, 5).timeoutSeconds());
        }

        @Test
        @DisplayName("A small value is untouched")
        void shouldLeaveASmallTimeoutAlone() {
            assertEquals(1, new Core.SubWorkflowConfig(WORKFLOW_ID, null, 1, 5).timeoutSeconds());
            assertEquals(600, new Core.SubWorkflowConfig(WORKFLOW_ID, null, 600, 5).timeoutSeconds());
        }

        @Test
        @DisplayName("An already-saved plan carrying an oversized value is clamped when it is read")
        void shouldClampWhenDeserialisingAnExistingPlan() throws Exception {
            // This is the enforcement point that matters for workflows saved BEFORE the ceiling
            // existed: they reach the engine through Jackson, not through the node creator, so
            // the compact constructor is the only thing standing between them and a 24h wait.
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            Core.SubWorkflowConfig restored = mapper.readValue(
                "{\"workflowId\":\"" + WORKFLOW_ID + "\",\"timeoutSeconds\":86400,\"maxDepth\":5}",
                Core.SubWorkflowConfig.class);

            assertEquals(Core.SubWorkflowConfig.MAX_TIMEOUT_SECONDS, restored.timeoutSeconds());
        }

        @Test
        @DisplayName("A node built from an oversized config carries the clamped value, not the requested one")
        void shouldBuildTheNodeWithTheClampedBudget() {
            // Named for what it checks: the record the node holds. It does not time a wait, so it
            // is a wiring check, not a runtime one.
            SubWorkflowNode node = createNode(new Core.SubWorkflowConfig(WORKFLOW_ID, null, 86_400, 5));

            assertEquals(Core.SubWorkflowConfig.MAX_TIMEOUT_SECONDS,
                node.getSubWorkflowConfig().timeoutSeconds());
        }
    }

}
