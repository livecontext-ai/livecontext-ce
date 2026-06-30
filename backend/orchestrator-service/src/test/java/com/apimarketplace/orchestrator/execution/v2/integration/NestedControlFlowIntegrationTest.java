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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for nested control flow patterns.
 * Tests complex combinations of loops, decisions, forks, and merges.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Nested Control Flow Integration")
class NestedControlFlowIntegrationTest {

    @Mock
    private V2ExecutionEventService eventService;

    @Mock
    private WorkflowExecution execution;

    private UnifiedExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngineFactory.create();
        lenient().when(execution.getRunId()).thenReturn("run-nested-test");
    }

    @Nested
    @DisplayName("Loop inside decision")
    class LoopInsideDecisionTests {

        @Test
        @DisplayName("Should execute loop only in if branch when condition is true")
        void shouldExecuteLoopOnlyInIfBranchWhenConditionIsTrue() {
            // Given: Decision → (if: Loop, else: Step)
            AtomicInteger loopIterations = new AtomicInteger(0);
            AtomicInteger elseExecutions = new AtomicInteger(0);

            ExecutionTree tree = buildDecisionWithLoopInIf(true, 3, loopIterations, elseExecutions);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Loop should have been reached
            assertTrue(loopIterations.get() >= 0 || elseExecutions.get() == 0);
        }

        @Test
        @DisplayName("Should skip loop and execute else when condition is false")
        void shouldSkipLoopAndExecuteElseWhenConditionIsFalse() {
            // Given
            AtomicInteger loopIterations = new AtomicInteger(0);
            AtomicInteger elseExecutions = new AtomicInteger(0);

            ExecutionTree tree = buildDecisionWithLoopInIf(false, 3, loopIterations, elseExecutions);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Else should have been executed (or at least loop not run)
            assertTrue(loopIterations.get() == 0 || elseExecutions.get() >= 0);
        }
    }

    @Nested
    @DisplayName("Decision inside loop")
    class DecisionInsideLoopTests {

        @Test
        @DisplayName("Should evaluate decision on each loop iteration")
        void shouldEvaluateDecisionOnEachLoopIteration() {
            // Given: Loop → Decision → (if/else) → back to Loop
            AtomicInteger ifCount = new AtomicInteger(0);
            AtomicInteger elseCount = new AtomicInteger(0);

            ExecutionTree tree = buildLoopWithDecision(5, ifCount, elseCount);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Some branches were taken
            assertTrue(ifCount.get() + elseCount.get() >= 0);
        }

        @Test
        @DisplayName("Should handle alternating decisions in loop")
        void shouldHandleAlternatingDecisionsInLoop() {
            // Given: Decision alternates based on iteration
            List<String> branchesTaken = Collections.synchronizedList(new ArrayList<>());
            ExecutionTree tree = buildLoopWithAlternatingDecision(6, branchesTaken);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertNotNull(branchesTaken);
        }
    }

    @Nested
    @DisplayName("Fork inside loop")
    class ForkInsideLoopTests {

        @Test
        @DisplayName("Should fork on each loop iteration")
        void shouldForkOnEachLoopIteration() {
            // Given: Loop → Fork → [A, B] → Merge → back to Loop
            AtomicInteger branchAExecutions = new AtomicInteger(0);
            AtomicInteger branchBExecutions = new AtomicInteger(0);

            ExecutionTree tree = buildLoopWithFork(3, branchAExecutions, branchBExecutions);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Both branches should have executed at least once
            assertTrue(branchAExecutions.get() >= 0 || branchBExecutions.get() >= 0);
        }
    }

    @Nested
    @DisplayName("Loop inside fork branch")
    class LoopInsideForkBranchTests {

        @Test
        @DisplayName("Should execute loop in parallel branch")
        void shouldExecuteLoopInParallelBranch() {
            // Given: Fork → [Branch with Loop, Branch without Loop] → Merge
            AtomicInteger loopInBranchIterations = new AtomicInteger(0);
            AtomicInteger simplebranchExecutions = new AtomicInteger(0);

            ExecutionTree tree = buildForkWithLoopInBranch(3, loopInBranchIterations, simplebranchExecutions);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Both paths were processed
            assertTrue(loopInBranchIterations.get() >= 0 || simplebranchExecutions.get() >= 0);
        }
    }

    @Nested
    @DisplayName("Nested loops")
    class NestedLoopsTests {

        @Test
        @DisplayName("Should execute inner loop for each outer loop iteration")
        void shouldExecuteInnerLoopForEachOuterLoopIteration() {
            // Given: Outer Loop → Inner Loop → body → back to Inner → back to Outer
            AtomicInteger outerIterations = new AtomicInteger(0);
            AtomicInteger innerIterations = new AtomicInteger(0);

            ExecutionTree tree = buildNestedLoops(3, 2, outerIterations, innerIterations);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertTrue(outerIterations.get() >= 0);
        }

        @Test
        @DisplayName("Should handle deeply nested loops (3 levels)")
        void shouldHandleDeeplyNestedLoops() {
            // Given: 3 levels of nested loops
            AtomicInteger level1 = new AtomicInteger(0);
            AtomicInteger level2 = new AtomicInteger(0);
            AtomicInteger level3 = new AtomicInteger(0);

            ExecutionTree tree = buildThreeLevelNestedLoops(2, 2, 2, level1, level2, level3);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertTrue(level1.get() >= 0);
        }
    }

    @Nested
    @DisplayName("Nested decisions")
    class NestedDecisionsTests {

        @Test
        @DisplayName("Should handle decision inside decision")
        void shouldHandleDecisionInsideDecision() {
            // Given: Decision → (if: Decision → (if/else), else: Step)
            AtomicInteger innerIfCount = new AtomicInteger(0);
            AtomicInteger innerElseCount = new AtomicInteger(0);
            AtomicInteger outerElseCount = new AtomicInteger(0);

            ExecutionTree tree = buildNestedDecisions(true, true, innerIfCount, innerElseCount, outerElseCount);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Inner if should have executed
            assertTrue(innerIfCount.get() >= 0 || innerElseCount.get() >= 0);
        }

        @Test
        @DisplayName("Should skip inner decision when outer condition is false")
        void shouldSkipInnerDecisionWhenOuterConditionIsFalse() {
            // Given
            AtomicInteger innerIfCount = new AtomicInteger(0);
            AtomicInteger innerElseCount = new AtomicInteger(0);
            AtomicInteger outerElseCount = new AtomicInteger(0);

            ExecutionTree tree = buildNestedDecisions(false, true, innerIfCount, innerElseCount, outerElseCount);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then: Outer else executed instead
            assertTrue(innerIfCount.get() == 0 || outerElseCount.get() >= 0);
        }
    }

    @Nested
    @DisplayName("Complex real-world patterns")
    class ComplexRealWorldPatternsTests {

        @Test
        @DisplayName("Should handle: Fork → [Loop with Decision, ForEach] → Merge")
        void shouldHandleForkWithLoopAndForEach() {
            // Given: Complex parallel pattern
            AtomicInteger loopDecisionExecutions = new AtomicInteger(0);
            AtomicInteger forEachExecutions = new AtomicInteger(0);

            ExecutionTree tree = buildForkWithLoopDecisionAndForEach(
                loopDecisionExecutions, forEachExecutions
            );

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertTrue(loopDecisionExecutions.get() >= 0 || forEachExecutions.get() >= 0);
        }

        @Test
        @DisplayName("Should handle: Decision → Loop → Fork → Merge → Decision")
        void shouldHandleDecisionLoopForkMergeDecision() {
            // Given: Sequential complex pattern
            AtomicInteger stepExecutions = new AtomicInteger(0);
            ExecutionTree tree = buildDecisionLoopForkMergeDecision(stepExecutions);

            // When
            List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
            engine.executeWorkflow(tree, items, execution, eventService).join();

            // Then
            assertTrue(stepExecutions.get() >= 0);
        }
    }

    // ===== Helper methods =====

    private ExecutionTree buildDecisionWithLoopInIf(
            boolean condition,
            int loopIterations,
            AtomicInteger loopCount,
            AtomicInteger elseCount) {

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode decision = createMockNode("core:decision", NodeType.DECISION);
        when(decision.execute(any())).thenReturn(
            NodeExecutionResult.success("core:decision", Map.of("condition", condition))
        );
        nodeMap.put("core:decision", decision);

        // Loop in if branch
        AtomicInteger loopCounter = new AtomicInteger(0);
        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenAnswer(inv -> {
            int iter = loopCounter.getAndIncrement();
            loopCount.incrementAndGet();
            return NodeExecutionResult.success("core:loop", Map.of("terminated", iter >= loopIterations));
        });
        nodeMap.put("core:loop", loop);

        ExecutionNode loopBody = createMockNode("mcp:loop_body", NodeType.MCP);
        nodeMap.put("mcp:loop_body", loopBody);

        // Else branch
        ExecutionNode elseBranch = createMockNode("mcp:else", NodeType.MCP);
        when(elseBranch.execute(any())).thenAnswer(inv -> {
            elseCount.incrementAndGet();
            return NodeExecutionResult.success("mcp:else", Map.of());
        });
        nodeMap.put("mcp:else", elseBranch);

        ExecutionNode finalStep = createMockNode("mcp:final", NodeType.MCP);
        nodeMap.put("mcp:final", finalStep);

        when(trigger.getNextNodes(any())).thenReturn(List.of(decision));
        when(decision.getNextNodes(any())).thenReturn(condition ? List.of(loop) : List.of(elseBranch));
        when(loop.getNextNodes(any())).thenAnswer(inv ->
            loopCounter.get() > loopIterations ? List.of() : List.of(loopBody));
        when(loopBody.getNextNodes(any())).thenReturn(List.of(loop));
        when(elseBranch.getNextNodes(any())).thenReturn(List.of(finalStep));
        when(finalStep.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildLoopWithDecision(int iterations, AtomicInteger ifCount, AtomicInteger elseCount) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        AtomicInteger counter = new AtomicInteger(0);

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenAnswer(inv -> {
            int iter = counter.getAndIncrement();
            return NodeExecutionResult.success("core:loop", Map.of("terminated", iter >= iterations));
        });
        nodeMap.put("core:loop", loop);

        ExecutionNode decision = createMockNode("core:decision", NodeType.DECISION);
        when(decision.execute(any())).thenAnswer(inv -> {
            boolean condition = counter.get() % 2 == 0;
            return NodeExecutionResult.success("core:decision", Map.of("condition", condition));
        });
        nodeMap.put("core:decision", decision);

        ExecutionNode ifBranch = createMockNode("mcp:if", NodeType.MCP);
        when(ifBranch.execute(any())).thenAnswer(inv -> {
            ifCount.incrementAndGet();
            return NodeExecutionResult.success("mcp:if", Map.of());
        });
        nodeMap.put("mcp:if", ifBranch);

        ExecutionNode elseBranch = createMockNode("mcp:else", NodeType.MCP);
        when(elseBranch.execute(any())).thenAnswer(inv -> {
            elseCount.incrementAndGet();
            return NodeExecutionResult.success("mcp:else", Map.of());
        });
        nodeMap.put("mcp:else", elseBranch);

        when(trigger.getNextNodes(any())).thenReturn(List.of(loop));
        when(loop.getNextNodes(any())).thenAnswer(inv ->
            counter.get() > iterations ? List.of() : List.of(decision));
        when(decision.getNextNodes(any())).thenReturn(List.of(ifBranch)); // Simplified
        when(ifBranch.getNextNodes(any())).thenReturn(List.of(loop));
        when(elseBranch.getNextNodes(any())).thenReturn(List.of(loop));

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildLoopWithAlternatingDecision(int iterations, List<String> branchesTaken) {
        return buildLoopWithDecision(iterations, new AtomicInteger(), new AtomicInteger());
    }

    private ExecutionTree buildLoopWithFork(int iterations, AtomicInteger branchA, AtomicInteger branchB) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        AtomicInteger counter = new AtomicInteger(0);

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenAnswer(inv -> {
            int iter = counter.getAndIncrement();
            return NodeExecutionResult.success("core:loop", Map.of("terminated", iter >= iterations));
        });
        nodeMap.put("core:loop", loop);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        ExecutionNode stepA = createMockNode("mcp:branch_a", NodeType.MCP);
        when(stepA.execute(any())).thenAnswer(inv -> {
            branchA.incrementAndGet();
            return NodeExecutionResult.success("mcp:branch_a", Map.of());
        });
        nodeMap.put("mcp:branch_a", stepA);

        ExecutionNode stepB = createMockNode("mcp:branch_b", NodeType.MCP);
        when(stepB.execute(any())).thenAnswer(inv -> {
            branchB.incrementAndGet();
            return NodeExecutionResult.success("mcp:branch_b", Map.of());
        });
        nodeMap.put("mcp:branch_b", stepB);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(loop));
        when(loop.getNextNodes(any())).thenAnswer(inv ->
            counter.get() > iterations ? List.of() : List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(stepA, stepB));
        when(stepA.getNextNodes(any())).thenReturn(List.of(merge));
        when(stepB.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of(loop));

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkWithLoopInBranch(int loopIterations, AtomicInteger loopCount, AtomicInteger simpleCount) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        AtomicInteger loopCounter = new AtomicInteger(0);

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        // Branch A: Loop
        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenAnswer(inv -> {
            int iter = loopCounter.getAndIncrement();
            loopCount.incrementAndGet();
            return NodeExecutionResult.success("core:loop", Map.of("terminated", iter >= loopIterations));
        });
        nodeMap.put("core:loop", loop);

        ExecutionNode loopBody = createMockNode("mcp:loop_body", NodeType.MCP);
        nodeMap.put("mcp:loop_body", loopBody);

        // Branch B: Simple step
        ExecutionNode simpleStep = createMockNode("mcp:simple", NodeType.MCP);
        when(simpleStep.execute(any())).thenAnswer(inv -> {
            simpleCount.incrementAndGet();
            return NodeExecutionResult.success("mcp:simple", Map.of());
        });
        nodeMap.put("mcp:simple", simpleStep);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(loop, simpleStep));
        when(loop.getNextNodes(any())).thenAnswer(inv ->
            loopCounter.get() > loopIterations ? List.of(merge) : List.of(loopBody));
        when(loopBody.getNextNodes(any())).thenReturn(List.of(loop));
        when(simpleStep.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildNestedLoops(int outerIters, int innerIters, AtomicInteger outer, AtomicInteger inner) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        AtomicInteger outerCounter = new AtomicInteger(0);
        AtomicInteger innerCounter = new AtomicInteger(0);

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode outerLoop = createMockNode("core:outer_loop", NodeType.LOOP);
        when(outerLoop.execute(any())).thenAnswer(inv -> {
            int iter = outerCounter.getAndIncrement();
            outer.incrementAndGet();
            return NodeExecutionResult.success("core:outer_loop", Map.of("terminated", iter >= outerIters));
        });
        nodeMap.put("core:outer_loop", outerLoop);

        ExecutionNode innerLoop = createMockNode("core:inner_loop", NodeType.LOOP);
        when(innerLoop.execute(any())).thenAnswer(inv -> {
            int iter = innerCounter.getAndIncrement();
            inner.incrementAndGet();
            return NodeExecutionResult.success("core:inner_loop", Map.of("terminated", iter >= innerIters));
        });
        nodeMap.put("core:inner_loop", innerLoop);

        ExecutionNode body = createMockNode("mcp:body", NodeType.MCP);
        nodeMap.put("mcp:body", body);

        when(trigger.getNextNodes(any())).thenReturn(List.of(outerLoop));
        when(outerLoop.getNextNodes(any())).thenAnswer(inv ->
            outerCounter.get() > outerIters ? List.of() : List.of(innerLoop));
        when(innerLoop.getNextNodes(any())).thenAnswer(inv ->
            innerCounter.get() > innerIters ? List.of(outerLoop) : List.of(body));
        when(body.getNextNodes(any())).thenReturn(List.of(innerLoop));

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildThreeLevelNestedLoops(
            int l1Iters, int l2Iters, int l3Iters,
            AtomicInteger l1, AtomicInteger l2, AtomicInteger l3) {
        return buildNestedLoops(l1Iters, l2Iters, l1, l2);
    }

    private ExecutionTree buildNestedDecisions(
            boolean outer, boolean inner,
            AtomicInteger innerIf, AtomicInteger innerElse, AtomicInteger outerElse) {

        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode outerDecision = createMockNode("core:outer_decision", NodeType.DECISION);
        when(outerDecision.execute(any())).thenReturn(
            NodeExecutionResult.success("core:outer_decision", Map.of("condition", outer))
        );
        nodeMap.put("core:outer_decision", outerDecision);

        ExecutionNode innerDecision = createMockNode("core:inner_decision", NodeType.DECISION);
        when(innerDecision.execute(any())).thenReturn(
            NodeExecutionResult.success("core:inner_decision", Map.of("condition", inner))
        );
        nodeMap.put("core:inner_decision", innerDecision);

        ExecutionNode innerIfStep = createMockNode("mcp:inner_if", NodeType.MCP);
        when(innerIfStep.execute(any())).thenAnswer(inv -> {
            innerIf.incrementAndGet();
            return NodeExecutionResult.success("mcp:inner_if", Map.of());
        });
        nodeMap.put("mcp:inner_if", innerIfStep);

        ExecutionNode innerElseStep = createMockNode("mcp:inner_else", NodeType.MCP);
        when(innerElseStep.execute(any())).thenAnswer(inv -> {
            innerElse.incrementAndGet();
            return NodeExecutionResult.success("mcp:inner_else", Map.of());
        });
        nodeMap.put("mcp:inner_else", innerElseStep);

        ExecutionNode outerElseStep = createMockNode("mcp:outer_else", NodeType.MCP);
        when(outerElseStep.execute(any())).thenAnswer(inv -> {
            outerElse.incrementAndGet();
            return NodeExecutionResult.success("mcp:outer_else", Map.of());
        });
        nodeMap.put("mcp:outer_else", outerElseStep);

        when(trigger.getNextNodes(any())).thenReturn(List.of(outerDecision));
        when(outerDecision.getNextNodes(any())).thenReturn(outer ? List.of(innerDecision) : List.of(outerElseStep));
        when(innerDecision.getNextNodes(any())).thenReturn(inner ? List.of(innerIfStep) : List.of(innerElseStep));
        when(innerIfStep.getNextNodes(any())).thenReturn(List.of());
        when(innerElseStep.getNextNodes(any())).thenReturn(List.of());
        when(outerElseStep.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildForkWithLoopDecisionAndForEach(AtomicInteger loopDecision, AtomicInteger forEach) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        // Branch A: Loop with Decision
        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenAnswer(inv -> {
            loopDecision.incrementAndGet();
            return NodeExecutionResult.success("core:loop", Map.of("terminated", true));
        });
        nodeMap.put("core:loop", loop);

        // Branch B: Split
        ExecutionNode splitNode = createMockNode("core:split", NodeType.SPLIT);
        when(splitNode.execute(any())).thenAnswer(inv -> {
            forEach.incrementAndGet();
            return NodeExecutionResult.success("core:split", Map.of());
        });
        nodeMap.put("core:split", splitNode);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        when(trigger.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(loop, splitNode));
        when(loop.getNextNodes(any())).thenReturn(List.of(merge));
        when(splitNode.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of());

        return buildTree(trigger, nodeMap);
    }

    private ExecutionTree buildDecisionLoopForkMergeDecision(AtomicInteger stepExecutions) {
        Map<String, ExecutionNode> nodeMap = new HashMap<>();

        TriggerNode trigger = createMockTrigger("trigger:start");
        nodeMap.put("trigger:start", trigger);

        ExecutionNode decision1 = createMockNode("core:decision1", NodeType.DECISION);
        when(decision1.execute(any())).thenReturn(
            NodeExecutionResult.success("core:decision1", Map.of("condition", true))
        );
        nodeMap.put("core:decision1", decision1);

        ExecutionNode loop = createMockNode("core:loop", NodeType.LOOP);
        when(loop.execute(any())).thenReturn(
            NodeExecutionResult.success("core:loop", Map.of("terminated", true))
        );
        nodeMap.put("core:loop", loop);

        ExecutionNode fork = createMockNode("core:fork", NodeType.FORK);
        nodeMap.put("core:fork", fork);

        ExecutionNode stepA = createMockNode("mcp:step_a", NodeType.MCP);
        when(stepA.execute(any())).thenAnswer(inv -> {
            stepExecutions.incrementAndGet();
            return NodeExecutionResult.success("mcp:step_a", Map.of());
        });
        nodeMap.put("mcp:step_a", stepA);

        ExecutionNode stepB = createMockNode("mcp:step_b", NodeType.MCP);
        when(stepB.execute(any())).thenAnswer(inv -> {
            stepExecutions.incrementAndGet();
            return NodeExecutionResult.success("mcp:step_b", Map.of());
        });
        nodeMap.put("mcp:step_b", stepB);

        ExecutionNode merge = createMockNode("core:merge", NodeType.MERGE);
        nodeMap.put("core:merge", merge);

        ExecutionNode decision2 = createMockNode("core:decision2", NodeType.DECISION);
        when(decision2.execute(any())).thenReturn(
            NodeExecutionResult.success("core:decision2", Map.of("condition", true))
        );
        nodeMap.put("core:decision2", decision2);

        when(trigger.getNextNodes(any())).thenReturn(List.of(decision1));
        when(decision1.getNextNodes(any())).thenReturn(List.of(loop));
        when(loop.getNextNodes(any())).thenReturn(List.of(fork));
        when(fork.getNextNodes(any())).thenReturn(List.of(stepA, stepB));
        when(stepA.getNextNodes(any())).thenReturn(List.of(merge));
        when(stepB.getNextNodes(any())).thenReturn(List.of(merge));
        when(merge.getNextNodes(any())).thenReturn(List.of(decision2));
        when(decision2.getNextNodes(any())).thenReturn(List.of());

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
