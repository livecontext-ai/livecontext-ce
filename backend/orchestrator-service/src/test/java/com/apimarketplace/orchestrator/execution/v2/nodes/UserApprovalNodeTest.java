package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserApprovalNode")
class UserApprovalNodeTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-04T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    @Mock private UnifiedSignalService signalService;
    @Mock private ExecutionContext context;
    @Mock private com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter templateAdapter;

    private UserApprovalNode approvalNode;
    private ExecutionNode approvedTarget;
    private ExecutionNode rejectedTarget;
    private ExecutionNode timeoutTarget;

    @BeforeEach
    void setUp() {
        approvalNode = UserApprovalNode.builder()
            .nodeId("core:manager_approval")

            .approverRoles(List.of("manager"))
            .requiredApprovals(1)
            .timeoutMs(86400000L)
            .build();

        // Create target nodes for each port using WaitNode as a simple concrete ExecutionNode
        approvedTarget = new WaitNode("mcp:deploy", 0);
        rejectedTarget = new WaitNode("mcp:notify_rejection", 0);
        timeoutTarget = new WaitNode("core:escalate", 0);

        approvalNode.addPortTarget("approved", approvedTarget);
        approvalNode.addPortTarget("rejected", rejectedTarget);
        approvalNode.addPortTarget("timeout", timeoutTarget);
    }

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("Should register signal and return AWAITING_SIGNAL")
        void shouldRegisterSignalAndYield() {
            approvalNode.setSignalService(signalService);
            approvalNode.setClock(FIXED_CLOCK);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = approvalNode.execute(context);

            assertTrue(result.isAwaitingSignal());
            assertEquals(NodeStatus.AWAITING_SIGNAL, result.status());
            assertEquals("USER_APPROVAL", result.output().get("signal_type"));

            verify(signalService).registerSignal(
                eq("run-1"), eq("0"), eq("core:manager_approval"),
                isNull(), eq(0),
                eq(SignalType.USER_APPROVAL), any(Map.class), isNull(), isNull());
        }

        @Test
        @DisplayName("regression: split context persists the current item as the signal's splitItemData (approver sees WHAT they approve)")
        void persistsSplitItemContextAsSignalSplitItemData() {
            approvalNode.setSignalService(signalService);
            approvalNode.setClock(FIXED_CLOCK);

            com.apimarketplace.orchestrator.execution.v2.state.ExecutionState state =
                mock(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.class);
            when(state.getGlobalData("item")).thenReturn(java.util.Optional.of("Order Beta"));
            when(state.getGlobalData("index")).thenReturn(java.util.Optional.of(1));
            when(context.state()).thenReturn(state);
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("1");

            approvalNode.execute(context);

            org.mockito.ArgumentCaptor<Map<String, Object>> splitItemData =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(signalService).registerSignal(
                eq("run-1"), eq("1"), eq("core:manager_approval"),
                isNull(), eq(0),
                eq(SignalType.USER_APPROVAL), any(Map.class), splitItemData.capture(), isNull());
            assertEquals("Order Beta", splitItemData.getValue().get("current_item"));
            assertEquals(1, splitItemData.getValue().get("current_index"));
        }

        @Test
        @DisplayName("an oversized split item is capped to a truncated display preview (payload stays bounded)")
        void capsOversizedSplitItemToTruncatedPreview() {
            approvalNode.setSignalService(signalService);
            approvalNode.setClock(FIXED_CLOCK);

            String hugeItem = "x".repeat(SignalContextResolver.MAX_ITEM_CONTEXT_JSON_CHARS + 100);
            com.apimarketplace.orchestrator.execution.v2.state.ExecutionState state =
                mock(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.class);
            when(state.getGlobalData("item")).thenReturn(java.util.Optional.of(hugeItem));
            when(state.getGlobalData("index")).thenReturn(java.util.Optional.of(0));
            when(context.state()).thenReturn(state);
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            approvalNode.execute(context);

            org.mockito.ArgumentCaptor<Map<String, Object>> splitItemData =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(signalService).registerSignal(
                any(), any(), any(), isNull(), eq(0),
                eq(SignalType.USER_APPROVAL), any(Map.class), splitItemData.capture(), isNull());
            Object capped = splitItemData.getValue().get("current_item");
            assertInstanceOf(Map.class, capped);
            assertEquals(true, ((Map<?, ?>) capped).get("_truncated"));
            assertTrue(String.valueOf(((Map<?, ?>) capped).get("preview")).length() <= 1024);
        }

        @Test
        @DisplayName("outside a split context the signal keeps a null splitItemData")
        void registersNullSplitItemDataOutsideSplitContext() {
            approvalNode.setSignalService(signalService);
            approvalNode.setClock(FIXED_CLOCK);

            com.apimarketplace.orchestrator.execution.v2.state.ExecutionState state =
                mock(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.class);
            when(state.getGlobalData("item")).thenReturn(java.util.Optional.empty());
            when(context.state()).thenReturn(state);
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            approvalNode.execute(context);

            verify(signalService).registerSignal(
                eq("run-1"), eq("0"), eq("core:manager_approval"),
                isNull(), eq(0),
                eq(SignalType.USER_APPROVAL), any(Map.class), isNull(), isNull());
        }

        @Test
        @DisplayName("resolves contextTemplate at yield and passes it as the signal's approvalContext")
        void passesResolvedApprovalContext() {
            UserApprovalNode node = UserApprovalNode.builder()
                .nodeId("core:manager_approval")
                .approverRoles(List.of("manager"))
                .requiredApprovals(1)
                .timeoutMs(86400000L)
                .contextTemplate("Approve refund of {{amount}}?")
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);
            node.setTemplateAdapter(templateAdapter);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(templateAdapter.evaluateTemplate("Approve refund of {{amount}}?", context))
                .thenReturn("Approve refund of 120 EUR?");

            node.execute(context);

            verify(signalService).registerSignal(
                eq("run-1"), eq("0"), eq("core:manager_approval"),
                isNull(), eq(0),
                eq(SignalType.USER_APPROVAL), any(Map.class), isNull(), eq("Approve refund of 120 EUR?"));
        }

        @Test
        @DisplayName("SOFT-REQUIRED: contextTemplate set but no template adapter -> approvalContext null and the node still yields")
        void contextTemplateWithoutAdapterStillYields() {
            UserApprovalNode node = UserApprovalNode.builder()
                .nodeId("core:manager_approval")
                .requiredApprovals(1)
                .timeoutMs(0)
                .contextTemplate("Approve {{x}}?")
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);
            // intentionally no setTemplateAdapter

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            verify(signalService).registerSignal(
                any(), any(), any(), isNull(), eq(0),
                eq(SignalType.USER_APPROVAL), any(Map.class), isNull(), isNull());
        }

        @Test
        @DisplayName("Should return failure when signalService is null")
        void shouldFailWhenNoSignalService() {
            // Do NOT set signal service

            NodeExecutionResult result = approvalNode.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent());
            assertNotNull(result.output().get("resolved_params"));
            assertEquals(List.of("manager"), result.output().get("approver_roles"));
            assertEquals(1, result.output().get("required_approvals"));
        }

        @Test
        @DisplayName("Should include approval metadata in result")
        void shouldIncludeMetadata() {
            approvalNode.setSignalService(signalService);
            approvalNode.setClock(FIXED_CLOCK);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = approvalNode.execute(context);

            assertNotNull(result.metadata());
            assertEquals("WAIT_TIMER".replace("WAIT_TIMER", "USER_APPROVAL"),
                result.metadata().get("signal_type"));
            assertEquals(List.of("manager"), result.metadata().get("approver_roles"));
            assertEquals(1, result.metadata().get("required_approvals"));
        }
    }

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return approved port targets when selected_port is 'approved'")
        void shouldReturnApprovedTargets() {
            NodeExecutionResult result = NodeExecutionResult.success(
                "core:manager_approval",
                Map.of("selected_port", "approved"));

            List<ExecutionNode> nextNodes = approvalNode.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:deploy", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return rejected port targets when selected_port is 'rejected'")
        void shouldReturnRejectedTargets() {
            NodeExecutionResult result = NodeExecutionResult.success(
                "core:manager_approval",
                Map.of("selected_port", "rejected"));

            List<ExecutionNode> nextNodes = approvalNode.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:notify_rejection", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should return timeout port targets when selected_port is 'timeout'")
        void shouldReturnTimeoutTargets() {
            NodeExecutionResult result = NodeExecutionResult.success(
                "core:manager_approval",
                Map.of("selected_port", "timeout"));

            List<ExecutionNode> nextNodes = approvalNode.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("core:escalate", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Should fall back to timeout port when no selected_port")
        void shouldFallBackToTimeout() {
            NodeExecutionResult result = NodeExecutionResult.success(
                "core:manager_approval", Map.of());

            List<ExecutionNode> nextNodes = approvalNode.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("core:escalate", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("regression: cancelled approval does not fall back to timeout branch")
        void cancelledApprovalDoesNotFallBackToTimeoutBranch() {
            NodeExecutionResult result = NodeExecutionResult.success(
                "core:manager_approval", Map.of("resolution", "CANCELLED"));

            List<ExecutionNode> nextNodes = approvalNode.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for failure result")
        void shouldReturnEmptyForFailure() {
            NodeExecutionResult result = NodeExecutionResult.failure("core:manager_approval", "error");

            List<ExecutionNode> nextNodes = approvalNode.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for null result")
        void shouldReturnEmptyForNull() {
            List<ExecutionNode> nextNodes = approvalNode.getNextNodes(null);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for unknown port")
        void shouldReturnEmptyForUnknownPort() {
            // Remove timeout port so fallback is also empty
            UserApprovalNode nodeNoTimeout = UserApprovalNode.builder()
                .nodeId("core:simple_approval")

                .requiredApprovals(1)
                .timeoutMs(0)
                .build();
            nodeNoTimeout.addPortTarget("approved", approvedTarget);
            nodeNoTimeout.addPortTarget("rejected", rejectedTarget);

            NodeExecutionResult result = NodeExecutionResult.success(
                "core:simple_approval",
                Map.of("selected_port", "nonexistent"));

            // No "timeout" fallback configured, and "nonexistent" not in ports
            List<ExecutionNode> nextNodes = nodeNoTimeout.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    @Nested
    @DisplayName("getSkippedChildNodes()")
    class GetSkippedChildNodesTests {

        @Test
        @DisplayName("Should return rejected + timeout when approved is selected")
        void shouldSkipNonApprovedPorts() {
            NodeExecutionResult result = NodeExecutionResult.success(
                "core:manager_approval",
                Map.of("selected_port", "approved"));

            List<ExecutionNode> skipped = approvalNode.getSkippedChildNodes(result);

            assertEquals(2, skipped.size());
            List<String> skippedIds = skipped.stream().map(ExecutionNode::getNodeId).toList();
            assertTrue(skippedIds.contains("mcp:notify_rejection"));
            assertTrue(skippedIds.contains("core:escalate"));
            assertFalse(skippedIds.contains("mcp:deploy"));
        }

        @Test
        @DisplayName("Should return approved + timeout when rejected is selected")
        void shouldSkipNonRejectedPorts() {
            NodeExecutionResult result = NodeExecutionResult.success(
                "core:manager_approval",
                Map.of("selected_port", "rejected"));

            List<ExecutionNode> skipped = approvalNode.getSkippedChildNodes(result);

            assertEquals(2, skipped.size());
            List<String> skippedIds = skipped.stream().map(ExecutionNode::getNodeId).toList();
            assertTrue(skippedIds.contains("mcp:deploy"));
            assertTrue(skippedIds.contains("core:escalate"));
        }

        @Test
        @DisplayName("Should return all port targets when no port selected (null result)")
        void shouldReturnAllWhenNullResult() {
            List<ExecutionNode> skipped = approvalNode.getSkippedChildNodes(null);

            assertEquals(3, skipped.size());
        }

        @Test
        @DisplayName("Should return all port targets when no selected_port in output")
        void shouldReturnAllWhenNoSelectedPort() {
            NodeExecutionResult result = NodeExecutionResult.success(
                "core:manager_approval", Map.of());

            List<ExecutionNode> skipped = approvalNode.getSkippedChildNodes(result);

            assertEquals(3, skipped.size());
        }
    }

    @Nested
    @DisplayName("getAllPortTargetNodes()")
    class GetAllPortTargetNodesTests {

        @Test
        @DisplayName("Should return all port target nodes")
        void shouldReturnAllTargets() {
            List<ExecutionNode> all = approvalNode.getAllPortTargetNodes();

            assertEquals(3, all.size());
            List<String> allIds = all.stream().map(ExecutionNode::getNodeId).toList();
            assertTrue(allIds.contains("mcp:deploy"));
            assertTrue(allIds.contains("mcp:notify_rejection"));
            assertTrue(allIds.contains("core:escalate"));
        }

        @Test
        @DisplayName("Should return empty list when no port targets")
        void shouldReturnEmptyWhenNoPorts() {
            UserApprovalNode emptyNode = UserApprovalNode.builder()
                .nodeId("core:empty_approval")

                .requiredApprovals(1)
                .timeoutMs(0)
                .build();

            assertTrue(emptyNode.getAllPortTargetNodes().isEmpty());
        }
    }

    @Nested
    @DisplayName("getSuccessors()")
    class GetSuccessorsTests {

        @Test
        @DisplayName("getSuccessors() should return same as getAllPortTargetNodes()")
        void successorsShouldMatchAllPortTargets() {
            List<ExecutionNode> successors = approvalNode.getSuccessors();
            List<ExecutionNode> allTargets = approvalNode.getAllPortTargetNodes();

            assertEquals(allTargets.size(), successors.size());
            assertTrue(successors.containsAll(allTargets));
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build node with all properties")
        void shouldBuildWithAllProperties() {
            UserApprovalNode node = UserApprovalNode.builder()
                .nodeId("core:approval_test")

                .approverRoles(List.of("admin", "manager"))
                .requiredApprovals(2)
                .timeoutMs(3600000L)
                .contextTemplate("Review {{trigger:form.output.summary}}")
                .build();

            assertEquals("core:approval_test", node.getNodeId());
            assertEquals(List.of("admin", "manager"), node.getApproverRoles());
            assertEquals(2, node.getRequiredApprovals());
            assertEquals(3600000L, node.getTimeoutMs());
            assertEquals("Review {{trigger:form.output.summary}}", node.getContextTemplate());
            assertEquals(NodeType.APPROVAL, node.getType());
        }

        @Test
        @DisplayName("Should enforce minimum requiredApprovals of 1")
        void shouldEnforceMinimumApprovals() {
            UserApprovalNode node = UserApprovalNode.builder()
                .nodeId("core:min_approval")
                .requiredApprovals(0) // should be clamped to 1
                .build();

            assertEquals(1, node.getRequiredApprovals());
        }

        @Test
        @DisplayName("Should handle null approver roles")
        void shouldHandleNullApproverRoles() {
            UserApprovalNode node = UserApprovalNode.builder()
                .nodeId("core:null_roles")
                .approverRoles(null)
                .build();

            assertNotNull(node.getApproverRoles());
            assertTrue(node.getApproverRoles().isEmpty());
        }
    }
}
