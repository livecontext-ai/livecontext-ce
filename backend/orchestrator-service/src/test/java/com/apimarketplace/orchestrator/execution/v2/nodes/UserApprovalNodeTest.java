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
        @DisplayName("the resolved approval context is surfaced in the node output too (visible in awaiting params, referenceable downstream)")
        void includesResolvedApprovalContextInAwaitingOutput() {
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

            NodeExecutionResult result = node.execute(context);

            assertEquals("Approve refund of 120 EUR?", result.output().get("approval_context"));
        }

        @Test
        @DisplayName("no resolved context -> the node output omits approval_context (soft-required, never a blank key)")
        void omitsApprovalContextInOutputWhenUnresolved() {
            UserApprovalNode node = UserApprovalNode.builder()
                .nodeId("core:manager_approval")
                .requiredApprovals(1)
                .timeoutMs(0)
                .contextTemplate("Approve {{x}}?")
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);
            // intentionally no setTemplateAdapter -> approvalContext resolves to null

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("approval_context"));
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
    @DisplayName("execute() - external-channel delegation")
    class DelegationTests {

        private UserApprovalNode buildDelegatedNode(com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation delegation) {
            UserApprovalNode node = UserApprovalNode.builder()
                .nodeId("core:manager_approval")
                .approverRoles(List.of("manager"))
                .requiredApprovals(1)
                .timeoutMs(86400000L)
                .delegation(delegation)
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);
            return node;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> capturedSignalConfig() {
            org.mockito.ArgumentCaptor<Map<String, Object>> configCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(signalService).registerSignal(
                any(), any(), any(), isNull(), eq(0),
                eq(SignalType.USER_APPROVAL), configCaptor.capture(), any(), any());
            return configCaptor.getValue();
        }

        @Test
        @DisplayName("delegation configured: the signal config embeds the resolved delegation block (chatId + message resolved at yield)")
        void embedsResolvedDelegationBlockInSignalConfig() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "{{trigger:start.output.chat_id}}",
                    "Approve order {{trigger:start.output.id}}?", "", List.of("777"), null, null));
            node.setTemplateAdapter(templateAdapter);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(templateAdapter.evaluateTemplate("{{trigger:start.output.chat_id}}", context))
                .thenReturn("123456");
            when(templateAdapter.evaluateTemplate("Approve order {{trigger:start.output.id}}?", context))
                .thenReturn("Approve order 99?");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            Map<String, Object> config = capturedSignalConfig();
            assertInstanceOf(Map.class, config.get("delegation"));
            Map<String, Object> delegation = (Map<String, Object>) config.get("delegation");
            assertEquals("telegram", delegation.get("channel"));
            assertEquals(42L, delegation.get("credentialId"));
            assertEquals("123456", delegation.get("chatId"));
            assertEquals("Approve order 99?", delegation.get("message"));
            assertEquals(List.of("777"), delegation.get("allowedUserIds"));
        }

        @Test
        @DisplayName("delegation configured: the yield output advertises delegated_channel")
        void outputAdvertisesDelegatedChannel() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "123456", "", "", List.of(), null, null));

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = node.execute(context);

            assertEquals("telegram", result.output().get("delegated_channel"));
        }

        @Test
        @DisplayName("SOFT: a failing chatId template falls back to the raw configured string and the node still yields")
        void chatIdTemplateFailureFallsBackToRawAndStillYields() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "{{bad.expr}}", "", "", List.of(), null, null));
            node.setTemplateAdapter(templateAdapter);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(templateAdapter.evaluateTemplate("{{bad.expr}}", context))
                .thenThrow(new RuntimeException("bad template"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            Map<String, Object> config = capturedSignalConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> delegation = (Map<String, Object>) config.get("delegation");
            assertEquals("{{bad.expr}}", delegation.get("chatId"));
            // blank messageTemplate never resolves: the message key is omitted, not blank
            assertFalse(delegation.containsKey("message"));
        }

        @Test
        @DisplayName("image template resolving to a FileRef Map is embedded AS-IS under delegation.image (no stringification)")
        void imageTemplateResolvingToFileRefMapIsEmbeddedAsIs() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "123456", "",
                    "{{interface:card.output.screenshot}}", List.of(), null, null));
            node.setTemplateAdapter(templateAdapter);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", "tenant-1/wf/run/screenshot.png",
                "name", "screenshot.png",
                "mimeType", "image/png");
            when(templateAdapter.evaluateTemplate("{{interface:card.output.screenshot}}", context))
                .thenReturn(fileRef);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            Map<String, Object> config = capturedSignalConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> delegation = (Map<String, Object>) config.get("delegation");
            // The Map shape must survive verbatim: the catalog's send_photo multipart
            // encoder detects the FileRef by _type and uploads the bytes.
            assertSame(fileRef, delegation.get("image"));
        }

        @Test
        @DisplayName("image template resolving to a String URL is embedded as that string")
        void imageTemplateResolvingToStringUrlIsEmbedded() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "123456", "",
                    "{{mcp:fetch.output.image_url}}", List.of(), null, null));
            node.setTemplateAdapter(templateAdapter);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(templateAdapter.evaluateTemplate("{{mcp:fetch.output.image_url}}", context))
                .thenReturn("https://example.com/img.png");

            node.execute(context);

            Map<String, Object> config = capturedSignalConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> delegation = (Map<String, Object>) config.get("delegation");
            assertEquals("https://example.com/img.png", delegation.get("image"));
        }

        @Test
        @DisplayName("regression: blank imageTemplate -> the delegation block carries NO image key (text-message path unchanged)")
        void blankImageTemplateOmitsImageKey() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "123456", "", "", List.of(), null, null));

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            node.execute(context);

            Map<String, Object> config = capturedSignalConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> delegation = (Map<String, Object>) config.get("delegation");
            assertFalse(delegation.containsKey("image"));
        }

        @Test
        @DisplayName("SOFT: a failing image template omits the image key and the node still yields")
        void imageTemplateFailureOmitsImageAndStillYields() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "123456", "", "{{bad.image.expr}}", List.of(), null, null));
            node.setTemplateAdapter(templateAdapter);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            when(templateAdapter.evaluateTemplate("{{bad.image.expr}}", context))
                .thenThrow(new RuntimeException("bad template"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            Map<String, Object> config = capturedSignalConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> delegation = (Map<String, Object>) config.get("delegation");
            assertFalse(delegation.containsKey("image"));
        }

        @Test
        @DisplayName("custom button labels: approveLabel/rejectLabel are resolved and embedded in the delegation block")
        void embedsResolvedCustomButtonLabels() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "123456", "", "", List.of(),
                    "Approve {{trigger:start.output.kind}}", "❌ Reject"));
            node.setTemplateAdapter(templateAdapter);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");
            // chatId is non-blank so it is resolved through the adapter too (stub it to itself).
            when(templateAdapter.evaluateTemplate("123456", context)).thenReturn("123456");
            when(templateAdapter.evaluateTemplate("Approve {{trigger:start.output.kind}}", context))
                .thenReturn("Approve order");
            when(templateAdapter.evaluateTemplate("❌ Reject", context))
                .thenReturn("❌ Reject");

            node.execute(context);

            Map<String, Object> config = capturedSignalConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> delegation = (Map<String, Object>) config.get("delegation");
            assertEquals("Approve order", delegation.get("approveLabel"));
            assertEquals("❌ Reject", delegation.get("rejectLabel"));
        }

        @Test
        @DisplayName("regression: blank button labels -> the delegation block carries NO approveLabel/rejectLabel keys (defaults preserved)")
        void blankButtonLabelsOmitKeys() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "telegram", 42L, "123456", "", "", List.of(), "", ""));

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            node.execute(context);

            Map<String, Object> config = capturedSignalConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> delegation = (Map<String, Object>) config.get("delegation");
            assertFalse(delegation.containsKey("approveLabel"));
            assertFalse(delegation.containsKey("rejectLabel"));
        }

        @Test
        @DisplayName("regression: no delegation -> signal config has NO delegation key and output has NO delegated_channel")
        void noDelegationLeavesConfigAndOutputUntouched() {
            approvalNode.setSignalService(signalService);
            approvalNode.setClock(FIXED_CLOCK);

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = approvalNode.execute(context);

            assertTrue(result.isAwaitingSignal());
            Map<String, Object> config = capturedSignalConfig();
            assertFalse(config.containsKey("delegation"));
            assertFalse(result.output().containsKey("delegated_channel"));
        }

        @Test
        @DisplayName("a blank-channel delegation (section left unconfigured) is treated as no delegation")
        void blankChannelDelegationTreatedAsAbsent() {
            UserApprovalNode node = buildDelegatedNode(
                new com.apimarketplace.orchestrator.domain.workflow.Core.ApprovalDelegation(
                    "   ", 42L, "123", "msg", "", List.of(), null, null));

            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            assertNull(node.getDelegation());
            Map<String, Object> config = capturedSignalConfig();
            assertFalse(config.containsKey("delegation"));
            assertFalse(result.output().containsKey("delegated_channel"));
        }
    }

    @Nested
    @DisplayName("execute() - continuationMode in signal config")
    class ContinuationModeTests {

        @SuppressWarnings("unchecked")
        private Map<String, Object> capturedSignalConfig() {
            org.mockito.ArgumentCaptor<Map<String, Object>> configCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(signalService).registerSignal(
                any(), any(), any(), isNull(), eq(0),
                eq(SignalType.USER_APPROVAL), configCaptor.capture(), any(), any());
            return configCaptor.getValue();
        }

        private UserApprovalNode buildNode(String continuationMode) {
            UserApprovalNode node = UserApprovalNode.builder()
                .nodeId("core:item_approval")
                .approverRoles(List.of("manager"))
                .requiredApprovals(1)
                .timeoutMs(86400000L)
                .continuationMode(continuationMode)
                .build();
            node.setSignalService(signalService);
            node.setClock(FIXED_CLOCK);
            return node;
        }

        @Test
        @DisplayName("built with continuationMode=per_item: the registered signal config carries continuationMode=per_item")
        void perItemContinuationModeLandsInSignalConfig() {
            UserApprovalNode node = buildNode("per_item");
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            assertEquals("per_item", capturedSignalConfig().get("continuationMode"));
        }

        @Test
        @DisplayName("default build (no continuationMode): the signal config carries the all_items default")
        void defaultBuildRegistersAllItemsContinuationMode() {
            approvalNode.setSignalService(signalService);
            approvalNode.setClock(FIXED_CLOCK);
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            approvalNode.execute(context);

            assertEquals("all_items", capturedSignalConfig().get("continuationMode"));
        }

        @Test
        @DisplayName("an unnormalized continuationMode value is normalized at construction (\"PER_ITEM\" -> per_item)")
        void unnormalizedContinuationModeIsNormalizedAtConstruction() {
            UserApprovalNode node = buildNode("PER_ITEM");
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            node.execute(context);

            assertEquals("per_item", node.getContinuationMode());
            assertEquals("per_item", capturedSignalConfig().get("continuationMode"));
        }

        @Test
        @DisplayName("an unknown continuationMode falls back to all_items in the signal config")
        void unknownContinuationModeFallsBackToAllItems() {
            UserApprovalNode node = buildNode("bogus_mode");
            when(context.runId()).thenReturn("run-1");
            when(context.itemId()).thenReturn("0");

            node.execute(context);

            assertEquals("all_items", capturedSignalConfig().get("continuationMode"));
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
