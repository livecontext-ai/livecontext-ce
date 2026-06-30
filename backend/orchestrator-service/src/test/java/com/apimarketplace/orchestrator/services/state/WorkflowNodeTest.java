package com.apimarketplace.orchestrator.services.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowNode")
class WorkflowNodeTest {

    @Nested
    @DisplayName("Builder and construction")
    class BuilderTests {

        @Test
        @DisplayName("Should build with required fields")
        void shouldBuildWithRequiredFields() {
            NodeId id = NodeId.step("my_step");
            WorkflowNode node = WorkflowNode.builder(id, WorkflowNode.NodeType.MCP).build();

            assertEquals(id, node.id());
            assertEquals(WorkflowNode.NodeType.MCP, node.type());
            assertTrue(node.predecessors().isEmpty());
            assertTrue(node.successors().isEmpty());
        }

        @Test
        @DisplayName("Should throw for null id")
        void shouldThrowForNullId() {
            assertThrows(NullPointerException.class, () ->
                WorkflowNode.builder(null, WorkflowNode.NodeType.MCP).build()
            );
        }

        @Test
        @DisplayName("Should throw for null type")
        void shouldThrowForNullType() {
            assertThrows(NullPointerException.class, () ->
                WorkflowNode.builder(NodeId.step("step1"), null).build()
            );
        }

        @Test
        @DisplayName("Should build with predecessors and successors")
        void shouldBuildWithRelationships() {
            NodeId id = NodeId.step("step2");
            NodeId pred = NodeId.trigger("start");
            NodeId succ = NodeId.step("step3");

            WorkflowNode node = WorkflowNode.builder(id, WorkflowNode.NodeType.MCP)
                .addPredecessor(pred)
                .addSuccessor(succ)
                .build();

            assertEquals(1, node.predecessors().size());
            assertEquals(pred, node.predecessors().get(0));
            assertEquals(1, node.successors().size());
            assertEquals(succ, node.successors().get(0));
        }

        @Test
        @DisplayName("Should build with predecessors list")
        void shouldBuildWithPredecessorsList() {
            NodeId id = NodeId.step("step3");

            WorkflowNode node = WorkflowNode.builder(id, WorkflowNode.NodeType.MCP)
                .predecessors(List.of(NodeId.step("step1"), NodeId.step("step2")))
                .build();

            assertEquals(2, node.predecessors().size());
        }

        @Test
        @DisplayName("Should build with merge strategy")
        void shouldBuildWithMergeStrategy() {
            NodeId id = NodeId.step("merge_point");

            WorkflowNode node = WorkflowNode.builder(id, WorkflowNode.NodeType.MERGE)
                .mergeStrategy(WorkflowNode.MergeStrategy.ALL)
                .build();

            assertEquals(WorkflowNode.MergeStrategy.ALL, node.mergeStrategy());
        }

        @Test
        @DisplayName("Should build loop node with body and post-loop decision")
        void shouldBuildLoopNode() {
            NodeId id = NodeId.loop("for_each");
            NodeId bodyNode = NodeId.step("process");
            NodeId postLoop = NodeId.decision("check");

            WorkflowNode node = WorkflowNode.builder(id, WorkflowNode.NodeType.LOOP)
                .loopBody(List.of(bodyNode))
                .postLoopDecision(postLoop)
                .build();

            assertEquals(1, node.loopBody().size());
            assertEquals(bodyNode, node.loopBody().get(0));
            assertEquals(postLoop, node.postLoopDecision());
        }

        @Test
        @DisplayName("Should build with port successors")
        void shouldBuildWithPortSuccessors() {
            NodeId id = NodeId.decision("check");
            NodeId ifTarget = NodeId.step("process");
            NodeId elseTarget = NodeId.step("fallback");

            WorkflowNode node = WorkflowNode.builder(id, WorkflowNode.NodeType.DECISION)
                .addPortSuccessor("if", ifTarget)
                .addPortSuccessor("else", elseTarget)
                .build();

            assertEquals(ifTarget, node.getSuccessorForPort("if"));
            assertEquals(elseTarget, node.getSuccessorForPort("else"));
            assertTrue(node.hasPortSuccessors());
        }

        @Test
        @DisplayName("Should skip null predecessor")
        void shouldSkipNullPredecessor() {
            WorkflowNode node = WorkflowNode.builder(NodeId.step("step1"), WorkflowNode.NodeType.MCP)
                .addPredecessor(null)
                .build();

            assertTrue(node.predecessors().isEmpty());
        }

        @Test
        @DisplayName("Should skip null successor")
        void shouldSkipNullSuccessor() {
            WorkflowNode node = WorkflowNode.builder(NodeId.step("step1"), WorkflowNode.NodeType.MCP)
                .addSuccessor(null)
                .build();

            assertTrue(node.successors().isEmpty());
        }

        @Test
        @DisplayName("getNodeType() on builder should return type")
        void getNodeTypeShouldReturnType() {
            WorkflowNode.Builder builder = WorkflowNode.builder(
                NodeId.step("step1"), WorkflowNode.NodeType.MCP
            );

            assertEquals(WorkflowNode.NodeType.MCP, builder.getNodeType());
        }
    }

    @Nested
    @DisplayName("Convenience type checks")
    class TypeCheckTests {

        @Test
        @DisplayName("isTrigger() should return true for TRIGGER type")
        void isTriggerShouldWork() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.trigger("start"), WorkflowNode.NodeType.TRIGGER
            ).build();

            assertTrue(node.isTrigger());
            assertFalse(node.isStep());
        }

        @Test
        @DisplayName("isStep() should return true for MCP type")
        void isStepShouldWork() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.step("step1"), WorkflowNode.NodeType.MCP
            ).build();

            assertTrue(node.isStep());
        }

        @Test
        @DisplayName("isDecision() should return true for DECISION type")
        void isDecisionShouldWork() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.decision("check"), WorkflowNode.NodeType.DECISION
            ).build();

            assertTrue(node.isDecision());
        }

        @Test
        @DisplayName("isLoop() should return true for LOOP type")
        void isLoopShouldWork() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.loop("for_each"), WorkflowNode.NodeType.LOOP
            ).build();

            assertTrue(node.isLoop());
        }

        @Test
        @DisplayName("isAgent() should return true for AGENT type")
        void isAgentShouldWork() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.agent("assistant"), WorkflowNode.NodeType.AGENT
            ).build();

            assertTrue(node.isAgent());
        }

        @Test
        @DisplayName("isFork() should return true for FORK type")
        void isForkShouldWork() {
            WorkflowNode node = WorkflowNode.builder(
                new NodeId("core", "parallel"), WorkflowNode.NodeType.FORK
            ).build();

            assertTrue(node.isFork());
        }

        @Test
        @DisplayName("isMerge() should return true for MERGE type")
        void isMergeShouldWork() {
            WorkflowNode node = WorkflowNode.builder(
                new NodeId("core", "wait_all"), WorkflowNode.NodeType.MERGE
            ).build();

            assertTrue(node.isMerge());
        }
    }

    @Nested
    @DisplayName("isMergeNode()")
    class IsMergeNodeTests {

        @Test
        @DisplayName("Should return true for multiple predecessors")
        void shouldReturnTrueForMultiplePredecessors() {
            WorkflowNode node = WorkflowNode.builder(NodeId.step("final"), WorkflowNode.NodeType.MCP)
                .addPredecessor(NodeId.step("step1"))
                .addPredecessor(NodeId.step("step2"))
                .build();

            assertTrue(node.isMergeNode());
        }

        @Test
        @DisplayName("Should return false for single predecessor")
        void shouldReturnFalseForSinglePredecessor() {
            WorkflowNode node = WorkflowNode.builder(NodeId.step("step2"), WorkflowNode.NodeType.MCP)
                .addPredecessor(NodeId.step("step1"))
                .build();

            assertFalse(node.isMergeNode());
        }
    }

    @Nested
    @DisplayName("hasPostLoopDecision()")
    class HasPostLoopDecisionTests {

        @Test
        @DisplayName("Should return true for loop with post-decision")
        void shouldReturnTrueForLoopWithDecision() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.loop("for_each"), WorkflowNode.NodeType.LOOP
            ).postLoopDecision(NodeId.decision("check")).build();

            assertTrue(node.hasPostLoopDecision());
        }

        @Test
        @DisplayName("Should return false for loop without post-decision")
        void shouldReturnFalseForLoopWithoutDecision() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.loop("for_each"), WorkflowNode.NodeType.LOOP
            ).build();

            assertFalse(node.hasPostLoopDecision());
        }

        @Test
        @DisplayName("Should return false for non-loop node")
        void shouldReturnFalseForNonLoop() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.step("step1"), WorkflowNode.NodeType.MCP
            ).postLoopDecision(NodeId.decision("check")).build();

            assertFalse(node.hasPostLoopDecision());
        }
    }

    @Nested
    @DisplayName("isExecutable()")
    class IsExecutableTests {

        @Test
        @DisplayName("Should return true for TRIGGER, MCP, AGENT")
        void shouldReturnTrueForExecutableTypes() {
            assertTrue(createNode(WorkflowNode.NodeType.TRIGGER).isExecutable());
            assertTrue(createNode(WorkflowNode.NodeType.MCP).isExecutable());
            assertTrue(createNode(WorkflowNode.NodeType.AGENT).isExecutable());
        }

        @Test
        @DisplayName("Should return false for control flow types")
        void shouldReturnFalseForControlFlow() {
            assertFalse(createNode(WorkflowNode.NodeType.DECISION).isExecutable());
            assertFalse(createNode(WorkflowNode.NodeType.LOOP).isExecutable());
            assertFalse(createNode(WorkflowNode.NodeType.FORK).isExecutable());
        }
    }

    @Nested
    @DisplayName("isControlFlow()")
    class IsControlFlowTests {

        @Test
        @DisplayName("Should return true for DECISION, LOOP, FORK")
        void shouldReturnTrueForControlFlow() {
            assertTrue(createNode(WorkflowNode.NodeType.DECISION).isControlFlow());
            assertTrue(createNode(WorkflowNode.NodeType.LOOP).isControlFlow());
            assertTrue(createNode(WorkflowNode.NodeType.FORK).isControlFlow());
        }

        @Test
        @DisplayName("Should return false for executable types")
        void shouldReturnFalseForExecutable() {
            assertFalse(createNode(WorkflowNode.NodeType.MCP).isControlFlow());
            assertFalse(createNode(WorkflowNode.NodeType.TRIGGER).isControlFlow());
        }
    }

    @Nested
    @DisplayName("isEntryPoint() and isExitPoint()")
    class EntryExitPointTests {

        @Test
        @DisplayName("isEntryPoint() should return true for no predecessors")
        void isEntryPointShouldReturnTrue() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.trigger("start"), WorkflowNode.NodeType.TRIGGER
            ).build();

            assertTrue(node.isEntryPoint());
        }

        @Test
        @DisplayName("isExitPoint() should return true for no successors")
        void isExitPointShouldReturnTrue() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.step("final"), WorkflowNode.NodeType.MCP
            ).build();

            assertTrue(node.isExitPoint());
        }
    }

    @Nested
    @DisplayName("Port successors")
    class PortSuccessorsTests {

        @Test
        @DisplayName("getSuccessorForPort() should return null for non-existent port")
        void shouldReturnNullForMissingPort() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.decision("check"), WorkflowNode.NodeType.DECISION
            ).build();

            assertNull(node.getSuccessorForPort("if"));
        }

        @Test
        @DisplayName("hasPortSuccessors() should return false when no ports")
        void shouldReturnFalseWhenNoPorts() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.step("step1"), WorkflowNode.NodeType.MCP
            ).build();

            assertFalse(node.hasPortSuccessors());
        }

        @Test
        @DisplayName("Should handle portSuccessors map on builder")
        void shouldHandlePortSuccessorsMap() {
            NodeId ifTarget = NodeId.step("if_step");
            NodeId elseTarget = NodeId.step("else_step");

            WorkflowNode node = WorkflowNode.builder(
                NodeId.decision("check"), WorkflowNode.NodeType.DECISION
            ).portSuccessors(Map.of("if", ifTarget, "else", elseTarget)).build();

            assertEquals(ifTarget, node.getSuccessorForPort("if"));
            assertEquals(elseTarget, node.getSuccessorForPort("else"));
        }
    }

    @Nested
    @DisplayName("Equality and toString")
    class EqualityTests {

        @Test
        @DisplayName("Nodes with same id should be equal")
        void nodesSameIdShouldBeEqual() {
            NodeId id = NodeId.step("step1");
            WorkflowNode a = WorkflowNode.builder(id, WorkflowNode.NodeType.MCP).build();
            WorkflowNode b = WorkflowNode.builder(id, WorkflowNode.NodeType.MCP).build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("Nodes with different ids should not be equal")
        void nodesDifferentIdsShouldNotBeEqual() {
            WorkflowNode a = WorkflowNode.builder(NodeId.step("step1"), WorkflowNode.NodeType.MCP).build();
            WorkflowNode b = WorkflowNode.builder(NodeId.step("step2"), WorkflowNode.NodeType.MCP).build();

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("toString() should contain node id and type")
        void toStringShouldContainInfo() {
            WorkflowNode node = WorkflowNode.builder(
                NodeId.step("step1"), WorkflowNode.NodeType.MCP
            ).build();

            String str = node.toString();
            assertTrue(str.contains("mcp:step1"));
            assertTrue(str.contains("MCP"));
        }
    }

    @Nested
    @DisplayName("NodeType enum")
    class NodeTypeEnumTests {

        @Test
        @DisplayName("Should have all expected types")
        void shouldHaveAllTypes() {
            assertEquals(8, WorkflowNode.NodeType.values().length);
            assertNotNull(WorkflowNode.NodeType.valueOf("TRIGGER"));
            assertNotNull(WorkflowNode.NodeType.valueOf("MCP"));
            assertNotNull(WorkflowNode.NodeType.valueOf("DECISION"));
            assertNotNull(WorkflowNode.NodeType.valueOf("LOOP"));
            assertNotNull(WorkflowNode.NodeType.valueOf("AGENT"));
            assertNotNull(WorkflowNode.NodeType.valueOf("FORK"));
            assertNotNull(WorkflowNode.NodeType.valueOf("MERGE"));
            assertNotNull(WorkflowNode.NodeType.valueOf("AGGREGATE"));
        }
    }

    @Nested
    @DisplayName("MergeStrategy enum")
    class MergeStrategyEnumTests {

        @Test
        @DisplayName("Should have ANY and ALL strategies")
        void shouldHaveExpectedStrategies() {
            assertEquals(2, WorkflowNode.MergeStrategy.values().length);
            assertNotNull(WorkflowNode.MergeStrategy.valueOf("ANY"));
            assertNotNull(WorkflowNode.MergeStrategy.valueOf("ALL"));
        }
    }

    // Helper
    private WorkflowNode createNode(WorkflowNode.NodeType type) {
        return WorkflowNode.builder(new NodeId("test", "node"), type).build();
    }
}
