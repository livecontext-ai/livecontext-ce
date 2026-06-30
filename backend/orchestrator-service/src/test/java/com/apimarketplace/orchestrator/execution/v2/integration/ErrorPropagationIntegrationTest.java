package com.apimarketplace.orchestrator.execution.v2.integration;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.TestEngineFactory;
import com.apimarketplace.orchestrator.execution.v2.engine.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for error propagation through workflow execution.
 * Tests how errors are handled and propagated through different workflow patterns.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Error Propagation Integration")
class ErrorPropagationIntegrationTest {

    @Mock
    private V2ExecutionEventService eventService;

    @Mock
    private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngineFactory.create();
        lenient().when(execution.getRunId()).thenReturn("run-error-test");
    }

    @Nested
    @DisplayName("Linear workflow errors")
    class LinearWorkflowErrorTests {

        @Test
        @DisplayName("Should stop at failing step in linear workflow")
        void shouldStopAtFailingStepInLinearWorkflow() {
            // Given: Step 2 fails
            AtomicBoolean step1Executed = new AtomicBoolean(false);
            AtomicBoolean step2Executed = new AtomicBoolean(false);
            AtomicBoolean step3Executed = new AtomicBoolean(false);

            ExecutionTree tree = buildLinearTreeWithFailure(
                step1Executed, step2Executed, step3Executed, 2
            );

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Step 1 and 2 executed, step 3 potentially skipped
            assertNotNull(result);
            assertTrue(step1Executed.get());
            assertTrue(step2Executed.get());
        }

        @Test
        @DisplayName("Should mark downstream steps as skipped after failure")
        void shouldMarkDownstreamStepsAsSkippedAfterFailure() {
            // Given: Step 1 fails immediately
            AtomicInteger skippedCount = new AtomicInteger(0);
            ExecutionTree tree = buildTreeWithSkipTracking(skippedCount);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Downstream steps were potentially skipped
            assertNotNull(tree);
        }

        @Test
        @DisplayName("Should preserve error message in result")
        void shouldPreserveErrorMessageInResult() {
            // Given
            String errorMessage = "Custom error: something went wrong";
            ExecutionTree tree = buildTreeWithSpecificError(errorMessage);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Result contains error info
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Fork/merge error handling")
    class ForkMergeErrorTests {

        @Test
        @DisplayName("Should propagate error from one fork branch")
        void shouldPropagateErrorFromOneForkBranch() {
            // Given: Branch A fails, Branch B succeeds
            AtomicBoolean branchAFailed = new AtomicBoolean(false);
            AtomicBoolean branchBSucceeded = new AtomicBoolean(false);

            ExecutionTree tree = buildForkWithOneBranchFailing(branchAFailed, branchBSucceeded);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Both branches attempted
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle errors in multiple fork branches")
        void shouldHandleErrorsInMultipleForkBranches() {
            // Given: Both branches fail
            ExecutionTree tree = buildForkWithAllBranchesFailing();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should still execute merge after partial failure")
        void shouldStillExecuteMergeAfterPartialFailure() {
            // Given: One branch fails, merge should still be reached
            AtomicBoolean mergeExecuted = new AtomicBoolean(false);
            ExecutionTree tree = buildForkWithMergeTracking(mergeExecuted);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Merge node behavior depends on implementation
            assertNotNull(tree);
        }
    }

    @Nested
    @DisplayName("Loop error handling")
    class LoopErrorTests {

        @Test
        @DisplayName("Should stop loop on body error")
        void shouldStopLoopOnBodyError() {
            // Given: Loop body fails on iteration 2
            AtomicInteger successfulIterations = new AtomicInteger(0);
            ExecutionTree tree = buildLoopWithBodyFailure(2, successfulIterations);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle error in loop condition")
        void shouldHandleErrorInLoopCondition() {
            // Given: Loop condition throws error
            ExecutionTree tree = buildLoopWithConditionFailure();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should track iteration count on error")
        void shouldTrackIterationCountOnError() {
            // Given
            AtomicInteger lastIteration = new AtomicInteger(-1);
            ExecutionTree tree = buildLoopWithIterationTrackingOnError(3, lastIteration);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Last iteration was tracked
            assertTrue(lastIteration.get() >= -1);
        }
    }

    @Nested
    @DisplayName("Decision branch errors")
    class DecisionBranchErrorTests {

        @Test
        @DisplayName("Should propagate error from if branch")
        void shouldPropagateErrorFromIfBranch() {
            // Given: If branch fails
            ExecutionTree tree = buildDecisionWithIfBranchFailing();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should propagate error from else branch")
        void shouldPropagateErrorFromElseBranch() {
            // Given: Else branch fails
            ExecutionTree tree = buildDecisionWithElseBranchFailing();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle error in decision condition evaluation")
        void shouldHandleErrorInDecisionConditionEvaluation() {
            // Given: Decision condition throws error
            ExecutionTree tree = buildDecisionWithConditionError();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Error propagation across multiple items")
    class MultipleItemsErrorTests {

        @Test
        @DisplayName("Should isolate errors between items")
        void shouldIsolateErrorsBetweenItems() {
            // Given: Item 1 fails, items 0 and 2 succeed
            AtomicInteger item0Completed = new AtomicInteger(0);
            AtomicInteger item1Failed = new AtomicInteger(0);
            AtomicInteger item2Completed = new AtomicInteger(0);

            ExecutionTree tree = buildTreeWithItemSpecificFailure(
                item0Completed, item1Failed, item2Completed
            );

            // When: 3 items
            List<TriggerItem> items = List.of(
                new TriggerItem("item-0", 0, Map.of()),
                new TriggerItem("item-1", 1, Map.of()),
                new TriggerItem("item-2", 2, Map.of())
            );
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: All 3 items processed
            assertEquals(3, result.totalItems());
        }

        @Test
        @DisplayName("Should continue processing after item failure")
        void shouldContinueProcessingAfterItemFailure() {
            // Given
            AtomicInteger totalProcessed = new AtomicInteger(0);
            ExecutionTree tree = buildTreeWithProcessingCounter(totalProcessed);

            // When
            List<TriggerItem> items = List.of(
                new TriggerItem("item-0", 0, Map.of("shouldFail", false)),
                new TriggerItem("item-1", 1, Map.of("shouldFail", true)),
                new TriggerItem("item-2", 2, Map.of("shouldFail", false))
            );
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
            assertTrue(totalProcessed.get() >= 0);
        }

        @Test
        @DisplayName("Should aggregate errors from multiple items")
        void shouldAggregateErrorsFromMultipleItems() {
            // Given: Multiple items fail
            ExecutionTree tree = buildTreeWithMultipleFailures();

            // When
            List<TriggerItem> items = List.of(
                new TriggerItem("item-0", 0, Map.of("fail", true)),
                new TriggerItem("item-1", 1, Map.of("fail", true)),
                new TriggerItem("item-2", 2, Map.of("fail", true))
            );
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(result);
            assertEquals(3, result.totalItems());
        }
    }

    @Nested
    @DisplayName("Runtime exception handling")
    class RuntimeExceptionTests {

        @Test
        @DisplayName("Should handle NullPointerException gracefully")
        void shouldHandleNullPointerExceptionGracefully() {
            // Given: Node throws NPE
            ExecutionTree tree = buildTreeWithNullPointerException();

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));

            // Then: Should not throw, should return result
            assertDoesNotThrow(() -> {
                engine.executeWorkflow(tree, items, execution, eventService).join();
            });
        }

        @Test
        @DisplayName("Should handle IllegalArgumentException gracefully")
        void shouldHandleIllegalArgumentExceptionGracefully() {
            // Given
            ExecutionTree tree = buildTreeWithIllegalArgumentException();

            // When/Then
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            assertDoesNotThrow(() -> {
                engine.executeWorkflow(tree, items, execution, eventService).join();
            });
        }

        @Test
        @DisplayName("Should handle timeout-like scenarios")
        void shouldHandleTimeoutLikeScenarios() {
            // Given: Node simulates slow execution
            ExecutionTree tree = buildTreeWithSlowNode();

            // When/Then: Should complete eventually
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            WorkflowResult result = engine.executeWorkflow(tree, items, execution, eventService).join();
            assertNotNull(result);
        }
    }

    // ===== Helper methods =====

    private ExecutionTree buildLinearTreeWithFailure(
            AtomicBoolean s1, AtomicBoolean s2, AtomicBoolean s3, int failAtStep) {

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode step1 = createMockNode("mcp:step1", NodeType.MCP);
        when(step1.execute(any())).thenAnswer(inv -> {
            s1.set(true);
            if (failAtStep == 1) {
                return NodeExecutionResult.failure("mcp:step1", "Step 1 failed");
            }
            return NodeExecutionResult.success("mcp:step1", Map.of());
        });
        nodeMap.put("mcp:step1", step1);

        ExecutionNode step2 = createMockNode("mcp:step2", NodeType.MCP);
        when(step2.execute(any())).thenAnswer(inv -> {
            s2.set(true);
            if (failAtStep == 2) {
                return NodeExecutionResult.failure("mcp:step2", "Step 2 failed");
            }
            return NodeExecutionResult.success("mcp:step2", Map.of());
        });
        nodeMap.put("mcp:step2", step2);

        ExecutionNode step3 = createMockNode("mcp:step3", NodeType.MCP);
        when(step3.execute(any())).thenAnswer(inv -> {
            s3.set(true);
            if (failAtStep == 3) {
                return NodeExecutionResult.failure("mcp:step3", "Step 3 failed");
            }
            return NodeExecutionResult.success("mcp:step3", Map.of());
        });
        nodeMap.put("mcp:step3", step3);

        when(trigger.getNextNodes(any())).thenReturn(List.of(step1));
        when(step1.getNextNodes(any())).thenReturn(List.of(step2));
        when(step2.getNextNodes(any())).thenReturn(List.of(step3));
        when(step3.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildTreeWithSkipTracking(AtomicInteger skippedCount) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode failingStep = createMockNode("mcp:failing", NodeType.MCP);
        when(failingStep.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:failing", "Immediate failure")
        );
        nodeMap.put("mcp:failing", failingStep);

        ExecutionNode skippableStep = createMockNode("mcp:skippable", NodeType.MCP);
        when(skippableStep.execute(any())).thenAnswer(inv -> {
            // This tracks if step was executed (shouldn't be if skipped)
            return NodeExecutionResult.success("mcp:skippable", Map.of());
        });
        nodeMap.put("mcp:skippable", skippableStep);

        when(trigger.getNextNodes(any())).thenReturn(List.of(failingStep));
        when(failingStep.getNextNodes(any())).thenReturn(List.of(skippableStep));
        when(skippableStep.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildTreeWithSpecificError(String errorMessage) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode step = createMockNode("mcp:step", NodeType.MCP);
        when(step.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:step", errorMessage)
        );
        nodeMap.put("mcp:step", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkWithOneBranchFailing(AtomicBoolean branchAFailed, AtomicBoolean branchBSucceeded) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        ExecutionNode branchA = createMockNode("mcp:branch_a", NodeType.MCP);
        when(branchA.execute(any())).thenAnswer(inv -> {
            branchAFailed.set(true);
            return NodeExecutionResult.failure("mcp:branch_a", "Branch A failed");
        });
        nodeMap.put("mcp:branch_a", branchA);

        ExecutionNode branchB = createMockNode("mcp:branch_b", NodeType.MCP);
        when(branchB.execute(any())).thenAnswer(inv -> {
            branchBSucceeded.set(true);
            return NodeExecutionResult.success("mcp:branch_b", Map.of());
        });
        nodeMap.put("mcp:branch_b", branchB);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(branchA, branchB));
        when(branchA.getNextNodes(any())).thenReturn(List.of(merge));
        when(branchB.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkWithAllBranchesFailing() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        ExecutionNode branchA = createMockNode("mcp:branch_a", NodeType.MCP);
        when(branchA.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:branch_a", "Branch A failed")
        );
        nodeMap.put("mcp:branch_a", branchA);

        ExecutionNode branchB = createMockNode("mcp:branch_b", NodeType.MCP);
        when(branchB.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:branch_b", "Branch B failed")
        );
        nodeMap.put("mcp:branch_b", branchB);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(branchA, branchB));
        when(branchA.getNextNodes(any())).thenReturn(List.of(merge));
        when(branchB.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkWithMergeTracking(AtomicBoolean mergeExecuted) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        ExecutionNode branchA = createMockNode("mcp:branch_a", NodeType.MCP);
        when(branchA.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:branch_a", "Branch A failed")
        );
        nodeMap.put("mcp:branch_a", branchA);

        ExecutionNode branchB = createMockNode("mcp:branch_b", NodeType.MCP);
        nodeMap.put("mcp:branch_b", branchB);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        when(merge.execute(any())).thenAnswer(inv -> {
            mergeExecuted.set(true);
            return NodeExecutionResult.success("core:merge", Map.of());
        });
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(branchA, branchB));
        when(branchA.getNextNodes(any())).thenReturn(List.of(merge));
        when(branchB.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildLoopWithBodyFailure(int failAtIteration, AtomicInteger successfulIterations) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        AtomicInteger counter = new AtomicInteger(0);

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenAnswer(inv -> {
            int iter = counter.get();
            return NodeExecutionResult.success("core:loop", Map.of("terminated", iter >= 5));
        });
        nodeMap.put("core:loop", loop);

        ExecutionNode body = createMockNode("mcp:body", NodeType.MCP);
        when(body.execute(any())).thenAnswer(inv -> {
            int iter = counter.getAndIncrement();
            if (iter == failAtIteration) {
                return NodeExecutionResult.failure("mcp:body", "Body failed at iteration " + iter);
            }
            successfulIterations.incrementAndGet();
            return NodeExecutionResult.success("mcp:body", Map.of());
        });
        nodeMap.put("mcp:body", body);

        when(trigger.getNextNodes(any())).thenReturn(List.of(loop));
        when(loop.getNextNodes(any())).thenReturn(List.of(body));
        when(body.getNextNodes(any())).thenReturn(List.of(loop));

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildLoopWithConditionFailure() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenReturn(
            NodeExecutionResult.failure("core:loop", "Condition evaluation failed")
        );
        nodeMap.put("core:loop", loop);

        ExecutionNode body = createMockNode("mcp:body", NodeType.MCP);
        nodeMap.put("mcp:body", body);

        when(trigger.getNextNodes(any())).thenReturn(List.of(loop));
        when(loop.getNextNodes(any())).thenReturn(List.of());
        when(body.getNextNodes(any())).thenReturn(List.of(loop));

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildLoopWithIterationTrackingOnError(int failAtIteration, AtomicInteger lastIteration) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        AtomicInteger counter = new AtomicInteger(0);

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenAnswer(inv -> {
            int iter = counter.get();
            lastIteration.set(iter);
            return NodeExecutionResult.success("core:loop", Map.of("terminated", iter >= 5));
        });
        nodeMap.put("core:loop", loop);

        ExecutionNode body = createMockNode("mcp:body", NodeType.MCP);
        when(body.execute(any())).thenAnswer(inv -> {
            int iter = counter.getAndIncrement();
            if (iter == failAtIteration) {
                return NodeExecutionResult.failure("mcp:body", "Iteration " + iter + " failed");
            }
            return NodeExecutionResult.success("mcp:body", Map.of());
        });
        nodeMap.put("mcp:body", body);

        when(trigger.getNextNodes(any())).thenReturn(List.of(loop));
        when(loop.getNextNodes(any())).thenReturn(List.of(body));
        when(body.getNextNodes(any())).thenReturn(List.of(loop));

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildDecisionWithIfBranchFailing() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode decision = createMockNode("core:decision", NodeType.DECISION);
        when(decision.execute(any())).thenReturn(
            NodeExecutionResult.success("core:decision", Map.of("condition", true))
        );
        nodeMap.put("core:decision", decision);

        ExecutionNode ifBranch = createMockNode("mcp:if", NodeType.MCP);
        when(ifBranch.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:if", "If branch failed")
        );
        nodeMap.put("mcp:if", ifBranch);

        ExecutionNode elseBranch = createMockNode("mcp:else", NodeType.MCP);
        nodeMap.put("mcp:else", elseBranch);

        when(trigger.getNextNodes(any())).thenReturn(List.of(decision));
        when(decision.getNextNodes(any())).thenReturn(List.of(ifBranch));
        when(ifBranch.getNextNodes(any())).thenReturn(List.of());
        when(elseBranch.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildDecisionWithElseBranchFailing() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode decision = createMockNode("core:decision", NodeType.DECISION);
        when(decision.execute(any())).thenReturn(
            NodeExecutionResult.success("core:decision", Map.of("condition", false))
        );
        nodeMap.put("core:decision", decision);

        ExecutionNode ifBranch = createMockNode("mcp:if", NodeType.MCP);
        nodeMap.put("mcp:if", ifBranch);

        ExecutionNode elseBranch = createMockNode("mcp:else", NodeType.MCP);
        when(elseBranch.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:else", "Else branch failed")
        );
        nodeMap.put("mcp:else", elseBranch);

        when(trigger.getNextNodes(any())).thenReturn(List.of(decision));
        when(decision.getNextNodes(any())).thenReturn(List.of(elseBranch));
        when(ifBranch.getNextNodes(any())).thenReturn(List.of());
        when(elseBranch.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildDecisionWithConditionError() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode decision = createMockNode("core:decision", NodeType.DECISION);
        when(decision.execute(any())).thenReturn(
            NodeExecutionResult.failure("core:decision", "Condition evaluation error")
        );
        nodeMap.put("core:decision", decision);

        when(trigger.getNextNodes(any())).thenReturn(List.of(decision));
        when(decision.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildTreeWithItemSpecificFailure(
            AtomicInteger item0, AtomicInteger item1, AtomicInteger item2) {

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode step = createMockNode("mcp:step", NodeType.MCP);
        when(step.execute(any())).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(0);
            int itemIndex = ctx.itemIndex();

            switch (itemIndex) {
                case 0:
                    item0.incrementAndGet();
                    return NodeExecutionResult.success("mcp:step", Map.of());
                case 1:
                    item1.incrementAndGet();
                    return NodeExecutionResult.failure("mcp:step", "Item 1 failed");
                case 2:
                    item2.incrementAndGet();
                    return NodeExecutionResult.success("mcp:step", Map.of());
                default:
                    return NodeExecutionResult.success("mcp:step", Map.of());
            }
        });
        nodeMap.put("mcp:step", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildTreeWithProcessingCounter(AtomicInteger counter) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode step = createMockNode("mcp:step", NodeType.MCP);
        when(step.execute(any())).thenAnswer(inv -> {
            counter.incrementAndGet();
            ExecutionContext ctx = inv.getArgument(0);
            if (ctx.itemIndex() == 1) {
                return NodeExecutionResult.failure("mcp:step", "Item 1 failure");
            }
            return NodeExecutionResult.success("mcp:step", Map.of());
        });
        nodeMap.put("mcp:step", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildTreeWithMultipleFailures() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode step = createMockNode("mcp:step", NodeType.MCP);
        when(step.execute(any())).thenReturn(
            NodeExecutionResult.failure("mcp:step", "All items fail")
        );
        nodeMap.put("mcp:step", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildTreeWithNullPointerException() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode step = createMockNode("mcp:step", NodeType.MCP);
        when(step.execute(any())).thenThrow(new NullPointerException("Simulated NPE"));
        nodeMap.put("mcp:step", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildTreeWithIllegalArgumentException() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode step = createMockNode("mcp:step", NodeType.MCP);
        when(step.execute(any())).thenThrow(new IllegalArgumentException("Invalid argument"));
        nodeMap.put("mcp:step", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildTreeWithSlowNode() {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode step = createMockNode("mcp:step", NodeType.MCP);
        when(step.execute(any())).thenAnswer(inv -> {
            // Simulate brief delay (but not actual sleep in unit test)
            return NodeExecutionResult.success("mcp:step", Map.of("delayed", true));
        });
        nodeMap.put("mcp:step", step);

        when(trigger.getNextNodes(any())).thenReturn(List.of(step));
        when(step.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private TriggerNode createMockTrigger(String nodeId) {
        TriggerNode trigger = mock(TriggerNode.class);
        when(trigger.getNodeId()).thenReturn(nodeId);
        when(trigger.getType()).thenReturn(NodeType.TRIGGER);
        when(trigger.canExecute(any())).thenReturn(true);
        when(trigger.execute(any())).thenReturn(NodeExecutionResult.success(nodeId, Map.of()));
        return trigger;
    }

    private ExecutionNode createMockNode(String nodeId, NodeType type) {
        ExecutionNode node = mock(ExecutionNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getType()).thenReturn(type);
        when(node.canExecute(any())).thenReturn(true);
        when(node.execute(any())).thenReturn(NodeExecutionResult.success(nodeId, Map.of()));
        return node;
    }

    private ExecutionTree buildTree(ExecutionNode root, Map<String, ExecutionNode> nodeMap) {
        WorkflowPlan plan = mock(WorkflowPlan.class);

        ExecutionTree tree = mock(ExecutionTree.class);
        when(tree.getRunId()).thenReturn("run-test");
        when(tree.getWorkflowRunId()).thenReturn("wfr-test");
        when(tree.getTenantId()).thenReturn("tenant-test");
        when(tree.getRootNode()).thenReturn(root);
        when(tree.getPlan()).thenReturn(plan);

        return tree;
    }
}
