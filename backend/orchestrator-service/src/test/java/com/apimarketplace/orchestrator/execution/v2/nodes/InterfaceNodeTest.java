package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ResolvedTemplateSnapshot;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceScreenshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InterfaceNode.
 * InterfaceNode represents a UI interface in the DAG execution.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceNode")
class InterfaceNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private UnifiedSignalService mockSignalService;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("data", "value");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create InterfaceNode with all properties")
        void shouldCreateInterfaceNodeWithAllProperties() {
            Map<String, String> actionMapping = Map.of("#btn", "trigger:submit");
            InterfaceNode node = new InterfaceNode("interface:my_form", "uuid-123", actionMapping, false);

            assertEquals("interface:my_form", node.getNodeId());
            assertEquals(NodeType.INTERFACE, node.getType());
            assertEquals("uuid-123", node.getInterfaceId());
            assertEquals(actionMapping, node.getActionMapping());
        }

        @Test
        @DisplayName("Should handle null action mapping")
        void shouldHandleNullActionMapping() {
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", null, false);

            assertTrue(node.getActionMapping().isEmpty());
        }
    }

    @Nested
    @DisplayName("execute() - No action_mapping (auto-advance, non-blocking)")
    class NoActionMappingTests {

        @Test
        @DisplayName("Should return SUCCESS without action_mapping (auto-advance)")
        void shouldReturnSuccessWithoutActionMapping() {
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", Map.of(), false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isAwaitingSignal());
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("interface:form", result.nodeId());
        }

        @Test
        @DisplayName("Should include interface metadata in output without action_mapping")
        void shouldIncludeMetadataInOutputWithoutActionMapping() {
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", Map.of(), false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertEquals("uuid-123", result.output().get("interface_id"));
        }

        @Test
        @DisplayName("Should register INTERFACE_SIGNAL even without action_mapping")
        void shouldRegisterSignalWithoutActionMapping() {
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", Map.of(), false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            node.execute(context);

            verify(mockSignalService).registerSignal(
                eq("run-1"),
                eq("item-1"),
                eq("interface:form"),
                any(),
                anyInt(),
                eq(SignalType.INTERFACE_SIGNAL),
                any(),
                isNull()
            );
        }
    }

    @Nested
    @DisplayName("execute() - With action_mapping (no __continue, auto-advance)")
    class WithActionMappingTests {

        @Test
        @DisplayName("Should return SUCCESS with action_mapping but no __continue (auto-advance)")
        void shouldReturnSuccessWithoutContinue() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isAwaitingSignal());
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("interface:form", result.nodeId());
        }

        @Test
        @DisplayName("Should include interface metadata in output")
        void shouldIncludeMetadataInOutput() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertEquals("uuid-123", result.output().get("interface_id"));
            assertEquals(actionMapping, result.output().get("action_mapping"));
        }

        @Test
        @DisplayName("Should register INTERFACE_SIGNAL when signal service available")
        void shouldRegisterInterfaceSignal() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            node.execute(context);

            verify(mockSignalService).registerSignal(
                eq("run-1"),
                eq("item-1"),
                eq("interface:form"),
                any(),
                anyInt(),
                eq(SignalType.INTERFACE_SIGNAL),
                any(),
                isNull()
            );
        }

        @Test
        @DisplayName("Should not fail when signal service is null and no __continue (non-blocking)")
        void shouldNotFailWhenSignalServiceNull() {
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", Map.of(), false);
            // Don't inject signal service

            NodeExecutionResult result = node.execute(context);

            // No __continue → auto-advance → SUCCESS
            assertFalse(result.isAwaitingSignal());
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("Should return FAILURE when signal service is null and __continue present (blocking)")
        void shouldFailWhenSignalServiceNullAndBlockingInterface() {
            Map<String, String> actionMapping = Map.of("#save", "__continue");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            // Don't inject signal service - simulates misconfigured node

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertEquals(NodeStatus.FAILED, result.status());
            assertEquals("interface:form", result.nodeId());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("Signal service not available"));
            assertNotNull(result.output().get("resolved_params"));
            assertEquals("uuid-123", result.output().get("interface_id"));
        }

        @Test
        @DisplayName("Should catch exception from registerSignal and return FAILURE")
        void shouldCatchRegisterSignalException() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            doThrow(new RuntimeException("DB connection failed"))
                .when(mockSignalService).registerSignal(any(), any(), any(), any(), anyInt(), any(), any(), any());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertEquals(NodeStatus.FAILED, result.status());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("DB connection failed"));
        }

        @Test
        @DisplayName("Should catch exception from registerSignal on blocking interface and return FAILURE")
        void shouldCatchRegisterSignalExceptionOnBlockingInterface() {
            Map<String, String> actionMapping = Map.of("#save", "__continue");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            doThrow(new RuntimeException("Signal registration timeout"))
                .when(mockSignalService).registerSignal(any(), any(), any(), any(), anyInt(), any(), any(), any());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertEquals(NodeStatus.FAILED, result.status());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("Signal registration timeout"));
        }
    }

    @Nested
    @DisplayName("execute() - With __continue in action_mapping (blocking)")
    class WithContinueActionMappingTests {

        @Test
        @DisplayName("Should return AWAITING_SIGNAL when __continue present")
        void shouldReturnAwaitingSignalWithContinue() {
            Map<String, String> actionMapping = Map.of("#save", "__continue", "#cancel", "trigger:cancel");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isAwaitingSignal());
            assertEquals("interface:form", result.nodeId());
        }

        @Test
        @DisplayName("Should include interface metadata in awaiting output")
        void shouldIncludeMetadataInAwaitingOutput() {
            Map<String, String> actionMapping = Map.of("#save", "__continue");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertEquals("uuid-123", result.metadata().get("interface_id"));
            assertEquals(actionMapping, result.metadata().get("action_mapping"));
        }

        @Test
        @DisplayName("Should register blocking INTERFACE_SIGNAL")
        void shouldRegisterBlockingInterfaceSignal() {
            Map<String, String> actionMapping = Map.of("#save", "__continue");
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", actionMapping, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            node.acceptServices(registry);

            node.execute(context);

            verify(mockSignalService).registerSignal(
                eq("run-1"),
                eq("item-1"),
                eq("interface:form"),
                any(),
                anyInt(),
                eq(SignalType.INTERFACE_SIGNAL),
                any(),
                isNull()
            );
        }
    }

    @Nested
    @DisplayName("isBranchingNode()")
    class BranchingTests {

        @Test
        @DisplayName("Should not be a branching node")
        void shouldNotBeBranchingNode() {
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", Map.of(), false);

            assertFalse(node.isBranchingNode());
        }
    }

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", Map.of(), false);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.success("interface:form", Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            InterfaceNode node = new InterfaceNode("interface:form", "uuid-123", Map.of(), false);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("interface:form", "Error");
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }
    }

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }

    @Nested
    @DisplayName("execute() - generateScreenshot toggle")
    class ScreenshotCaptureTests {

        private static final String INTERFACE_UUID = "11111111-2222-3333-4444-555555555555";

        @Test
        @DisplayName("Toggle off → no screenshot field emitted; screenshot service not invoked")
        void toggleOffOmitsScreenshotField() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("screenshot"), "screenshot must be absent when toggle off");
            verifyNoInteractions(mockScreenshotService);
        }

        @Test
        @DisplayName("Toggle on + service returns FileRef → screenshot field carries that FileRef")
        void toggleOnWithSuccessfulCaptureEmitsScreenshotField() {
            FileRef captured = FileRef.of("tenant-1/wf/run-1/interface:form/interface_screenshot_epoch_0.png",
                "interface_screenshot_epoch_0.png", "image/png", 1024L);
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.capture(eq("tenant-1"), eq("run-1"), anyInt(), anyInt(), any(),
                eq("interface:form"), eq(UUID.fromString(INTERFACE_UUID))))
                .thenReturn(Optional.of(captured));
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertEquals(captured, result.output().get("screenshot"));
        }

        @Test
        @DisplayName("Toggle on + capture returns empty → screenshot field stays absent and workflow continues normally")
        void toggleOnWithCaptureFailureContinuesWithoutScreenshot() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.capture(any(), any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Optional.empty());
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("screenshot"));
            assertEquals(NodeStatus.COMPLETED, result.status(), "capture failure must NOT fail the node");
        }

        @Test
        @DisplayName("Toggle on + capture throws → screenshot absent, workflow continues (regression guard for continue-on-failure)")
        void toggleOnWithCaptureExceptionContinuesWithoutScreenshot() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.capture(any(), any(), anyInt(), anyInt(), any(), any(), any()))
                .thenThrow(new RuntimeException("sidecar exploded"));
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("screenshot"));
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("Toggle on + interfaceId is not a UUID → screenshot service is not called (defensive parse)")
        void toggleOnWithInvalidInterfaceIdSkipsCapture() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            InterfaceNode node = new InterfaceNode("interface:form", "not-a-uuid", Map.of(), false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("screenshot"));
            verify(mockScreenshotService, never()).capture(any(), any(), anyInt(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("Toggle on + screenshot service not wired (null) → no NPE, no screenshot field")
        void toggleOnWithNullServiceDoesNotThrow() {
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(null);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("screenshot"));
            assertEquals(NodeStatus.COMPLETED, result.status());
        }
    }

    @Nested
    @DisplayName("execute() - generatePdf toggle")
    class PdfCaptureTests {

        private static final String INTERFACE_UUID = "11111111-2222-3333-4444-555555555555";

        /** 9-arg ctor: generateScreenshot=false, exposeRenderedSource=false, generatePdf as given. */
        private InterfaceNode pdfNode(String interfaceId, boolean generatePdf, String format, boolean landscape) {
            return new InterfaceNode("interface:form", interfaceId, Map.of(), false,
                false, false, generatePdf, format, landscape);
        }

        @Test
        @DisplayName("Toggle off → no pdf field emitted; capturePdf not invoked")
        void toggleOffOmitsPdfField() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            InterfaceNode node = pdfNode(INTERFACE_UUID, false, "A4", false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("pdf"), "pdf must be absent when toggle off");
            verify(mockScreenshotService, never())
                .capturePdf(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Toggle on + service returns FileRef → pdf field carries it AND format/landscape are forwarded")
        void toggleOnWithSuccessfulRenderEmitsPdfFieldAndForwardsOptions() {
            FileRef captured = FileRef.of("tenant-1/wf/run-1/interface:form/form_pdf_epoch_0_spawn_0.pdf",
                "form_pdf_epoch_0_spawn_0.pdf", "application/pdf", 2048L);
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.capturePdf(eq("tenant-1"), eq("run-1"), anyInt(), anyInt(), any(),
                eq("interface:form"), eq(UUID.fromString(INTERFACE_UUID)), eq("Letter"), eq(true)))
                .thenReturn(Optional.of(captured));
            InterfaceNode node = pdfNode(INTERFACE_UUID, true, "Letter", true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertEquals(captured, result.output().get("pdf"));
            // The node MUST forward the configured page options, not hard-code A4/portrait.
            verify(mockScreenshotService).capturePdf(eq("tenant-1"), eq("run-1"), anyInt(), anyInt(), any(),
                eq("interface:form"), eq(UUID.fromString(INTERFACE_UUID)), eq("Letter"), eq(true));
        }

        @Test
        @DisplayName("Toggle on + render returns empty → pdf field absent, workflow continues (COMPLETED)")
        void toggleOnWithRenderFailureContinuesWithoutPdf() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.capturePdf(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(Optional.empty());
            InterfaceNode node = pdfNode(INTERFACE_UUID, true, "A4", false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("pdf"));
            assertEquals(NodeStatus.COMPLETED, result.status(), "render failure must NOT fail the node");
        }

        @Test
        @DisplayName("Toggle on + capturePdf throws → pdf absent, workflow continues (continue-on-failure guard)")
        void toggleOnWithRenderExceptionContinuesWithoutPdf() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.capturePdf(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("sidecar exploded"));
            InterfaceNode node = pdfNode(INTERFACE_UUID, true, "A4", false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("pdf"));
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("Toggle on + interfaceId is not a UUID → capturePdf is not called (defensive parse)")
        void toggleOnWithInvalidInterfaceIdSkipsRender() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            InterfaceNode node = pdfNode("not-a-uuid", true, "A4", false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("pdf"));
            verify(mockScreenshotService, never())
                .capturePdf(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Toggle on + screenshot service not wired (null) → no NPE, no pdf field")
        void toggleOnWithNullServiceDoesNotThrow() {
            InterfaceNode node = pdfNode(INTERFACE_UUID, true, "A4", false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(null);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("pdf"));
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("resolved_params surfaces generatePdf + pdfFormat + pdfLandscape (inspector visibility)")
        @SuppressWarnings("unchecked")
        void resolvedParamsSurfacesPdfConfig() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.capturePdf(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(Optional.empty());
            InterfaceNode node = pdfNode(INTERFACE_UUID, true, "Letter", true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertNotNull(resolved, "resolved_params must be present for the inspector panel");
            assertEquals(true, resolved.get("generatePdf"));
            assertEquals("Letter", resolved.get("pdfFormat"));
            assertEquals(true, resolved.get("pdfLandscape"));
        }
    }

    @Nested
    @DisplayName("execute() - generateVideo toggle")
    class VideoCaptureTests {

        private static final String INTERFACE_UUID = "11111111-2222-3333-4444-555555555555";

        /** 12-arg ctor: only the video toggles vary; screenshot/PDF/rendered-source all off. */
        private InterfaceNode videoNode(String interfaceId, boolean generateVideo,
                                        String preset, Integer maxDurationSeconds) {
            return new InterfaceNode("interface:form", interfaceId, Map.of(), false,
                false, false, false, null, false,
                generateVideo, preset, maxDurationSeconds);
        }

        @Test
        @DisplayName("Toggle off → no video field emitted; captureVideo not invoked")
        void toggleOffOmitsVideoField() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            InterfaceNode node = videoNode(INTERFACE_UUID, false, "vertical", 30);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("video"), "video must be absent when toggle off");
            verify(mockScreenshotService, never())
                .captureVideo(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Toggle on + service returns FileRef → video field carries it AND preset/duration are forwarded")
        void toggleOnWithSuccessfulRecordingEmitsVideoFieldAndForwardsOptions() {
            FileRef recorded = FileRef.of("tenant-1/wf/run-1/interface:form/form_video_epoch_0_spawn_0.mp4",
                "form_video_epoch_0_spawn_0.mp4", "video/mp4", 4096L);
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.captureVideo(eq("tenant-1"), eq("run-1"), anyInt(), anyInt(), any(),
                eq("interface:form"), eq(UUID.fromString(INTERFACE_UUID)), eq("square"), eq(45), isNull(), isNull()))
                .thenReturn(Optional.of(recorded));
            InterfaceNode node = videoNode(INTERFACE_UUID, true, "square", 45);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertEquals(recorded, result.output().get("video"));
            // The node MUST forward the configured capture options, not hard-code vertical/30s.
            verify(mockScreenshotService).captureVideo(eq("tenant-1"), eq("run-1"), anyInt(), anyInt(), any(),
                eq("interface:form"), eq(UUID.fromString(INTERFACE_UUID)), eq("square"), eq(45), isNull(), isNull());
        }

        @Test
        @DisplayName("Toggle on + recording returns empty → video field absent, workflow continues (COMPLETED)")
        void toggleOnWithRecordingFailureContinuesWithoutVideo() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.captureVideo(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
            InterfaceNode node = videoNode(INTERFACE_UUID, true, "vertical", 30);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("video"));
            assertEquals(NodeStatus.COMPLETED, result.status(), "recording failure must NOT fail the node");
        }

        @Test
        @DisplayName("Toggle on + captureVideo throws → video absent, workflow continues (continue-on-failure guard)")
        void toggleOnWithRecordingExceptionContinuesWithoutVideo() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.captureVideo(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("sidecar exploded"));
            InterfaceNode node = videoNode(INTERFACE_UUID, true, "vertical", 30);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("video"));
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("Toggle on + interfaceId is not a UUID → captureVideo is not called (defensive parse)")
        void toggleOnWithInvalidInterfaceIdSkipsRecording() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            InterfaceNode node = videoNode("not-a-uuid", true, "vertical", 30);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("video"));
            verify(mockScreenshotService, never())
                .captureVideo(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Toggle on + screenshot service not wired (null) → no NPE, no video field")
        void toggleOnWithNullServiceDoesNotThrow() {
            InterfaceNode node = videoNode(INTERFACE_UUID, true, "vertical", 30);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(null);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("video"));
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("resolved_params surfaces generateVideo + videoPreset + videoMaxDurationSeconds (inspector visibility)")
        @SuppressWarnings("unchecked")
        void resolvedParamsSurfacesVideoConfig() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.captureVideo(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
            InterfaceNode node = videoNode(INTERFACE_UUID, true, "horizontal", 60);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertNotNull(resolved, "resolved_params must be present for the inspector panel");
            assertEquals(true, resolved.get("generateVideo"));
            assertEquals("horizontal", resolved.get("videoPreset"));
            assertEquals(60, resolved.get("videoMaxDurationSeconds"));
        }

        @Test
        @DisplayName("videoMode + videoFps from the 14-arg constructor are forwarded to the capture service")
        void videoModeAndFpsForwarded() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.captureVideo(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false,
                false, false, false, null, false,
                true, "vertical", 20, "live", 60);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            node.execute(context);

            verify(mockScreenshotService).captureVideo(any(), any(), anyInt(), anyInt(), any(),
                eq("interface:form"), eq(UUID.fromString(INTERFACE_UUID)),
                eq("vertical"), eq(20), eq("live"), eq(60));
            assertEquals("live", node.getVideoMode());
            assertEquals(60, node.getVideoFps());
        }

        @Test
        @DisplayName("Back-compat 9-arg constructor leaves video OFF (no video field, no capture call)")
        void nineArgConstructorDefaultsVideoOff() {
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false,
                false, false, false, null, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(node.isGenerateVideo());
            assertFalse(result.output().containsKey("video"));
            verify(mockScreenshotService, never())
                .captureVideo(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("execute() - the node no longer carries a display format")
    class NoNodeLevelFormat {

        private static final String INTERFACE_UUID = "11111111-2222-3333-4444-555555555555";

        private InterfaceNode node(boolean generateScreenshot, boolean generateVideo, String videoPreset) {
            return new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false,
                generateScreenshot, false, false, null, false,
                generateVideo, videoPreset, null, null, null);
        }

        private ServiceRegistry registryWith(InterfaceScreenshotService screenshotService) {
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getInterfaceScreenshotService()).thenReturn(screenshotService);
            return registry;
        }

        @Test
        @DisplayName("capture is called with no format argument - the shape comes from the interface")
        void captureTakesNoFormat() {
            // The screenshot service resolves the format from the render snapshot, so the node
            // must not (and cannot) pass one: it does not know the interface's shape.
            InterfaceScreenshotService screenshotService = mock(InterfaceScreenshotService.class);
            when(screenshotService.capture(any(), any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Optional.empty());

            InterfaceNode node = node(true, false, null);
            node.acceptServices(registryWith(screenshotService));
            node.execute(context);

            verify(screenshotService).capture(any(), any(), anyInt(), anyInt(), any(), any(),
                eq(UUID.fromString(INTERFACE_UUID)));
        }

        @Test
        @DisplayName("captureVideo forwards only the explicit videoPreset override")
        void captureVideoForwardsOnlyThePreset() {
            InterfaceScreenshotService screenshotService = mock(InterfaceScreenshotService.class);
            when(screenshotService.captureVideo(any(), any(), anyInt(), anyInt(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(Optional.empty());

            InterfaceNode node = node(false, true, "horizontal");
            node.acceptServices(registryWith(screenshotService));
            node.execute(context);

            verify(screenshotService).captureVideo(any(), any(), anyInt(), anyInt(), any(), any(),
                eq(UUID.fromString(INTERFACE_UUID)), eq("horizontal"), any(), any(), any());
        }

        @Test
        @DisplayName("resolved_params exposes no format / formatWidth / formatHeight")
        @SuppressWarnings("unchecked")
        void resolvedParamsHaveNoFormat() {
            // The inspector used to show the node's format; it cannot any more without fetching
            // the interface, and advertising a stale value would be worse than showing none.
            InterfaceNode node = node(false, false, null);
            node.acceptServices(registryWith(null));

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertFalse(resolved.containsKey("format"));
            assertFalse(resolved.containsKey("formatWidth"));
            assertFalse(resolved.containsKey("formatHeight"));
        }
    }

    @Nested
    @DisplayName("execute() - exposeRenderedSource toggle")
    class RenderedSourceExposureTests {

        private static final String INTERFACE_UUID = "11111111-2222-3333-4444-555555555555";

        private ResolvedTemplateSnapshot snapshotWith(String html, String css, String js) {
            return new ResolvedTemplateSnapshot(html, css, js, Map.of());
        }

        @Test
        @DisplayName("Toggle off → no rendered_* fields; render service not invoked")
        void toggleOffOmitsRenderedFields() {
            InterfaceRenderService mockRenderService = mock(InterfaceRenderService.class);
            // 6-arg ctor with both toggles OFF
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, false, false);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(mockRenderService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("rendered_html"));
            assertFalse(result.output().containsKey("rendered_css"));
            assertFalse(result.output().containsKey("rendered_js"));
            verifyNoInteractions(mockRenderService);
        }

        @Test
        @DisplayName("Toggle on + snapshot has all 3 parts → all 3 string fields emitted (HTML carries the iframe-resolved view)")
        void toggleOnWithFullSnapshotEmitsAllThreeFields() {
            InterfaceRenderService mockRenderService = mock(InterfaceRenderService.class);
            when(mockRenderService.resolveTemplateSnapshot(eq(UUID.fromString(INTERFACE_UUID)),
                eq("run-1"), eq("tenant-1"), anyInt()))
                .thenReturn(Optional.of(snapshotWith("<h1>Welcome Alice</h1>", "h1{color:red}", "console.log('hi')")));
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(mockRenderService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            // rendered_html carries the substituted output (no {{var}} placeholders) because
            // resolveTemplateSnapshot already applied InterfaceTemplateDefaults.apply().
            assertEquals("<h1>Welcome Alice</h1>", result.output().get("rendered_html"));
            assertEquals("h1{color:red}", result.output().get("rendered_css"));
            assertEquals("console.log('hi')", result.output().get("rendered_js"));
        }

        @Test
        @DisplayName("Toggle on + snapshot has null CSS/JS → only rendered_html emitted (no-CSS, no-JS interface)")
        void toggleOnWithNullCssAndJsEmitsOnlyHtml() {
            InterfaceRenderService mockRenderService = mock(InterfaceRenderService.class);
            when(mockRenderService.resolveTemplateSnapshot(any(UUID.class), anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(snapshotWith("<p>plain</p>", null, null)));
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(mockRenderService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertEquals("<p>plain</p>", result.output().get("rendered_html"));
            assertFalse(result.output().containsKey("rendered_css"),
                "null cssTemplate must not surface as empty-string output");
            assertFalse(result.output().containsKey("rendered_js"),
                "null jsTemplate must not surface as empty-string output");
        }

        @Test
        @DisplayName("Toggle on + resolveTemplateSnapshot returns empty (interface has no html template) → no rendered_* fields")
        void toggleOnWithEmptySnapshotEmitsNothing() {
            InterfaceRenderService mockRenderService = mock(InterfaceRenderService.class);
            when(mockRenderService.resolveTemplateSnapshot(any(UUID.class), anyString(), anyString(), anyInt()))
                .thenReturn(Optional.empty());
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(mockRenderService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("rendered_html"));
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("Toggle on + resolveTemplateSnapshot throws → all rendered_* fields absent, workflow continues (continue-on-failure regression guard)")
        void toggleOnWithRenderExceptionContinuesWithoutFields() {
            InterfaceRenderService mockRenderService = mock(InterfaceRenderService.class);
            when(mockRenderService.resolveTemplateSnapshot(any(UUID.class), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("DB exploded"));
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(mockRenderService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("rendered_html"));
            assertFalse(result.output().containsKey("rendered_css"));
            assertFalse(result.output().containsKey("rendered_js"));
            assertEquals(NodeStatus.COMPLETED, result.status(),
                "render failure must NOT fail the node");
        }

        @Test
        @DisplayName("Toggle on + interfaceId is not a UUID → render service not called (defensive parse)")
        void toggleOnWithInvalidInterfaceIdSkipsRender() {
            InterfaceRenderService mockRenderService = mock(InterfaceRenderService.class);
            InterfaceNode node = new InterfaceNode("interface:form", "not-a-uuid", Map.of(), false, false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(mockRenderService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("rendered_html"));
            verify(mockRenderService, never()).resolveTemplateSnapshot(any(UUID.class),
                anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Toggle on + render service not wired (null) → no NPE, no rendered_* fields")
        void toggleOnWithNullServiceDoesNotThrow() {
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(null);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.output().containsKey("rendered_html"));
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("Field exceeding 256KB cap → truncated to first 256KB; workflow continues; other fields untouched")
        void toggleOnWithOversizedHtmlTruncatesAtCap() {
            // Repeat a 1-char string to overshoot the cap by exactly 1 char.
            String oversized = "a".repeat(InterfaceNode.MAX_RENDERED_FIELD_CHARS + 1);
            InterfaceRenderService mockRenderService = mock(InterfaceRenderService.class);
            when(mockRenderService.resolveTemplateSnapshot(any(UUID.class), anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(snapshotWith(oversized, "h1{}", "var x=1")));
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, false, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(mockRenderService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            String htmlOut = (String) result.output().get("rendered_html");
            assertNotNull(htmlOut);
            assertEquals(InterfaceNode.MAX_RENDERED_FIELD_CHARS, htmlOut.length(),
                "Oversized rendered_html must be truncated to exactly MAX_RENDERED_FIELD_CHARS");
            assertEquals("h1{}", result.output().get("rendered_css"),
                "css field below the cap must pass through untouched");
            assertEquals("var x=1", result.output().get("rendered_js"),
                "js field below the cap must pass through untouched");
            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        @Test
        @DisplayName("Both toggles ON → screenshot AND rendered_* coexist independently in output")
        void bothTogglesOnCoexistIndependently() {
            FileRef captured = FileRef.of("tenant-1/wf/run-1/interface:form/snap.png",
                "snap.png", "image/png", 256L);
            InterfaceScreenshotService mockScreenshotService = mock(InterfaceScreenshotService.class);
            when(mockScreenshotService.capture(any(), any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Optional.of(captured));
            InterfaceRenderService mockRenderService = mock(InterfaceRenderService.class);
            when(mockRenderService.resolveTemplateSnapshot(any(UUID.class), anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(snapshotWith("<h1>both</h1>", null, null)));
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false, true, true);
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceScreenshotService()).thenReturn(mockScreenshotService);
            when(registry.getInterfaceRenderService()).thenReturn(mockRenderService);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            assertEquals(captured, result.output().get("screenshot"));
            assertEquals("<h1>both</h1>", result.output().get("rendered_html"));
        }
    }

    /**
     * What the run says about the mapping between the workflow's data and the page.
     *
     * <p>It said nothing at all, on any path. An interface that renders an empty screen
     * is the most common thing a reader opens this panel for, and the documented cause -
     * a mapping resolving to a wrapper object, so the page reads one level too deep -
     * left no trace anywhere: no error, no failed node, an empty page and a green run.
     */
    @Nested
    @DisplayName("execute() - variable mapping reporting")
    class VariableMappingReportingTests {

        private static final String INTERFACE_UUID = "11111111-2222-3333-4444-555555555555";

        private InterfaceNode nodeWithMapping(Map<String, String> mapping) {
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false);
            node.setVariableMapping(mapping);
            return node;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> variablesOf(NodeExecutionResult result) {
            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            return (Map<String, Object>) resolved.get("variableMapping");
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> variable(NodeExecutionResult result, String name) {
            return (Map<String, Object>) variablesOf(result).get(name);
        }

        private InterfaceNode wire(InterfaceNode node, InterfaceRenderService renderService) {
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(renderService);
            node.acceptServices(registry);
            return node;
        }

        @Test
        @DisplayName("the double-`result` wrapper is visible in the panel: the expression, and the object it resolved to")
        void namesTheWrapperTheMappingResolvedTo() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            // What {{core:normalize.output}} gives back: the code node's own output wrapped
            // under `result`, so the page's __RESOLVED_DATA__.result.listings finds nothing.
            when(renderService.resolveVariablesForReporting(any(), eq("run-1"), eq("tenant-1"),
                anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of("result", Map.of("result", Map.of("listings", List.of()))));
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("result", "{{core:normalize.output}}")), renderService);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> variable = variable(result, "result");
            assertEquals("{{core:normalize.output}}", variable.get("expression"));
            assertEquals("Map(keys=[result])", variable.get("resolved"));
            assertEquals("resolved", variable.get("status"));
        }

        @Test
        @DisplayName("a variable that resolved to nothing is reported unresolved, which is what an empty screen means")
        void reportsAnUnresolvedVariable() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of());
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}")), renderService);

            Map<String, Object> variable = variable(node.execute(context), "rows");

            assertEquals("{{core:fetch.output.items}}", variable.get("expression"));
            assertNull(variable.get("resolved"));
            assertEquals("unresolved", variable.get("status"));
        }

        @Test
        @DisplayName("a collection is described by its size: this map is persisted on every step row")
        void describesRatherThanCopiesTheValue() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of("rows", List.of(Map.of("id", 1), Map.of("id", 2))));
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}")), renderService);

            assertEquals("List(size=2)", variable(node.execute(context), "rows").get("resolved"));
        }

        @Test
        @DisplayName("with no render service the mapping is still reported, marked not_evaluated rather than left blank")
        void reportsTheMappingWithoutItsValuesWhenNothingCanResolveThem() {
            InterfaceNode node = nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}"));
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(null);
            node.acceptServices(registry);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> variable = variable(result, "rows");
            assertEquals("{{core:fetch.output.items}}", variable.get("expression"));
            assertEquals("not_evaluated", variable.get("status"));
            // A blank would read as a measurement that came back empty. The reason the
            // measurement did not happen is reported too.
            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("no render service is wired", resolved.get("variableMappingError"));
        }

        @Test
        @DisplayName("a resolution that throws never fails the node, and says why it has no values")
        void survivesAFailedResolution() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("storage unavailable"));
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}")), renderService);

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("not_evaluated", variable(result, "rows").get("status"));
            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("storage unavailable", resolved.get("variableMappingError"));
        }

        @Test
        @DisplayName("a parking interface RECORDS its report on the signal, which is the only way it ever reaches a step row")
        @SuppressWarnings("unchecked")
        void recordsItsReportOnTheSignal() {
            // The wiring nothing else can catch. A blocking interface yields, and a yield
            // persists no step row: the row it gets is written when the signal resolves.
            // Building a correct report and not handing it to the signal leaves the node's
            // own tests green over a panel that shows none of it - which is exactly what
            // shipped until this call existed.
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of("rows", List.of(1, 2)));
            InterfaceNode node = new InterfaceNode(
                "interface:form", INTERFACE_UUID, Map.of("#next", "__continue"), false);
            node.setVariableMapping(Map.of("rows", "{{core:fetch.output.items}}"));
            wire(node, renderService);

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.AWAITING_SIGNAL, result.status());
            ArgumentCaptor<Map<String, Object>> recorded = ArgumentCaptor.forClass(Map.class);
            verify(mockSignalService).recordReportedParams(any(), recorded.capture());
            assertNotNull(recorded.getValue().get("variableMapping"),
                "the mapping must travel with the signal, or the paused run reports nothing");
        }

        @Test
        @DisplayName("the blocking path and the auto-advancing one hand their sinks the SAME map")
        @SuppressWarnings("unchecked")
        void bothPathsReportTheSameThing() {
            // Two producers, one report. The signal path goes through
            // UnifiedSignalService.recordReportedParams, which applies ReportedParams.forReport;
            // the auto-advance path writes the output map itself. While only the first was
            // gated the two diverged on the same node: a variable an author called `token`
            // read one way when the interface parked and another when it advanced, and
            // nothing compared them. A variable named for a credential is the case that
            // separates the two gates, so it is the one asserted here.
            Map<String, String> mapping = Map.of(
                "token", "{{core:auth.output.session_label}}",
                "title", "{{core:prepare.output.title}}");
            Map<String, Object> resolvedVariables = Map.of("token", "svc-account", "title", "Hello");

            InterfaceRenderService blockingRender = mock(InterfaceRenderService.class);
            when(blockingRender.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(resolvedVariables);
            InterfaceNode blocking = new InterfaceNode(
                "interface:form", INTERFACE_UUID, Map.of("#next", "__continue"), false);
            blocking.setVariableMapping(mapping);
            wire(blocking, blockingRender);

            InterfaceRenderService advancingRender = mock(InterfaceRenderService.class);
            when(advancingRender.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(resolvedVariables);
            InterfaceNode advancing = new InterfaceNode(
                "interface:form", INTERFACE_UUID, Map.of(), false);
            advancing.setVariableMapping(mapping);
            wire(advancing, advancingRender);

            NodeExecutionResult blocked = blocking.execute(context);
            NodeExecutionResult advanced = advancing.execute(context);

            assertEquals(NodeStatus.AWAITING_SIGNAL, blocked.status());
            assertEquals(NodeStatus.COMPLETED, advanced.status());

            // Both nodes record on their signal - an interface registers one whether it
            // parks or not - so the first captured map is the parking one.
            //
            // What this pins is the PRODUCER half: the two paths hand their sinks the same
            // map. The signal service is a mock here, so the production gate does not run;
            // applying forReport below is this test standing in for it, which is why the
            // DisplayName no longer claims otherwise. That the real service gates what it is
            // handed is pinned where it lives, by UnifiedSignalServiceTest.redactsTheParams.
            ArgumentCaptor<Map<String, Object>> recorded = ArgumentCaptor.forClass(Map.class);
            verify(mockSignalService, times(2)).recordReportedParams(any(), recorded.capture());
            Map<String, Object> onTheSignal = ReportedParams.forReport(recorded.getAllValues().get(0));
            Map<String, Object> onTheRow = (Map<String, Object>) advanced.output().get("resolved_params");

            assertEquals(onTheSignal.get("variableMapping"), onTheRow.get("variableMapping"),
                "a paused interface and an advancing one must describe the same mapping");
            assertNotNull(((Map<String, Object>) onTheRow.get("variableMapping")).get("token"),
                "and a variable named for a credential must survive both, or the panel it "
                    + "is opened for says nothing");
        }

        @Test
        @DisplayName("an oversized mapping is bounded on the auto-advancing path too, not only on the signal")
        @SuppressWarnings("unchecked")
        void boundsTheReportOnTheAutoAdvancingPath() {
            // The half of the parity above that a reader can measure. The signal path has
            // been bounded since recordReportedParams existed; the auto-advance path wrote
            // its map straight into the output, so an interface with a large mapping copied
            // the whole thing onto a step row - per item of every split it sits in.
            Map<String, String> mapping = new java.util.LinkedHashMap<>();
            Map<String, Object> resolvedVariables = new java.util.LinkedHashMap<>();
            for (int i = 0; i < 400; i++) {
                mapping.put("field" + i, "{{core:prepare.output.field" + i + "}}");
                resolvedVariables.put("field" + i, "x".repeat(200));
            }
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(resolvedVariables);
            InterfaceNode node = new InterfaceNode("interface:form", INTERFACE_UUID, Map.of(), false);
            node.setVariableMapping(mapping);
            wire(node, renderService);

            Map<String, Object> reported =
                (Map<String, Object>) node.execute(context).output().get("resolved_params");

            // 20 000 chars: the gate's own map budget plus room for the marker. The raw
            // mapping is an order of magnitude past it.
            assertTrue(reported.toString().length() < 20_000,
                "a step row must not carry the whole mapping: " + reported.toString().length());
            assertNotNull(((Map<String, Object>) reported.get("variableMapping")).get(ReportedParams.TRUNCATED),
                "and the cut must be stated rather than silent, beside the variables that fit");
            assertNotNull(((Map<String, Object>) reported.get("variableMapping")).get("field0"),
                "the variables that DO fit keep their wiring: a bounded mapping is not an absent one");
        }

        @Test
        @DisplayName("a FAILING interface reports its mapping through the gate too: an unwrapped report reads one level too deep")
        @SuppressWarnings("unchecked")
        void reportsThroughTheGateOnTheFailurePath() {
            // The report travels to the gate inside a `PreGated` wrapper, and the gate is
            // what unwraps it. A failure path that skipped the gate therefore persisted
            // `{"variableMapping": {"value": {...}}}` - an extra level on the very path this
            // report exists to make readable, and `{{interface:x.input.variableMapping.<name>}}`
            // resolving to nothing. A failed interface is precisely when a reader opens this.
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of("rows", List.of(1, 2)));
            InterfaceNode node = new InterfaceNode(
                "interface:form", INTERFACE_UUID, Map.of("#next", "__continue"), false);
            node.setVariableMapping(Map.of("rows", "{{core:fetch.output.items}}"));
            ServiceRegistry registry = mock(ServiceRegistry.class);
            when(registry.getSignalService()).thenReturn(mockSignalService);
            when(registry.getInterfaceRenderService()).thenReturn(renderService);
            node.acceptServices(registry);
            doThrow(new IllegalStateException("signal registration timeout"))
                .when(mockSignalService).registerSignal(any(), any(), any(), any(), anyInt(), any(), any(), any());

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.FAILED, result.status());
            Map<String, Object> reported = (Map<String, Object>) result.output().get("resolved_params");
            Map<String, Object> mapping = (Map<String, Object>) reported.get("variableMapping");
            assertNotNull(mapping, "a failed interface still says what it was wired to");
            assertNull(mapping.get("value"),
                "no wrapper level: the gate unwraps the report, and a path that skips it does not");
            assertNotNull(mapping.get("rows"),
                "the author's own variable is addressable directly under variableMapping");
        }

        @Test
        @DisplayName("an interface that parks on __continue reports its mapping too: that row is the one a paused run is read from")
        void reportsOnTheAwaitingSignalPath() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of("title", "Hello"));
            InterfaceNode node = new InterfaceNode(
                "interface:form", INTERFACE_UUID, Map.of("#next", "__continue"), false);
            node.setVariableMapping(Map.of("title", "{{core:prepare.output.title}}"));
            wire(node, renderService);

            NodeExecutionResult result = node.execute(context);

            assertEquals(NodeStatus.AWAITING_SIGNAL, result.status());
            assertEquals("\"Hello\"", variable(result, "title").get("resolved"));
        }

        @Test
        @DisplayName("a static interface declares no mapping, and none is invented for it")
        void reportsNothingWhenThereIsNoMapping() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            InterfaceNode node = wire(nodeWithMapping(Map.of()), renderService);

            NodeExecutionResult result = node.execute(context);

            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertFalse(resolved.containsKey("variableMapping"));
            assertFalse(resolved.containsKey("variableMappingError"));
            verifyNoInteractions(renderService);
        }

        @Test
        @DisplayName("the resolution is asked for THIS item's coordinates: epoch, spawn and item index, none of them each other")
        void resolvesForTheItemBeingExecuted() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of());
            // Three DIFFERENT non-zero values. With the run's defaults they are all 0, and a
            // test on those cannot tell the three arguments apart, nor the node's resolved
            // epoch from the raw context one.
            ExecutionContext itemContext = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-7", 7,
                "trigger:webhook", 5, 2, Map.of(), mockPlan);
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}")), renderService);

            node.execute(itemContext);

            verify(renderService).resolveVariablesForReporting(
                eq(Map.of("rows", "{{core:fetch.output.items}}")),
                eq("run-1"), eq("tenant-1"),
                eq(5), eq(2), eq(7));
        }

        @Test
        @DisplayName("the node's OWN epoch wins over the context's, the way the signal it registers uses it")
        void resolvesForTheNodesResolvedEpoch() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of());
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}")), renderService);
            node.setEpoch(9);

            node.execute(context);

            // Reporting an epoch the node is not registering its signal for would describe
            // another epoch's data beside this row.
            verify(renderService).resolveVariablesForReporting(
                any(), eq("run-1"), eq("tenant-1"), eq(9), anyInt(), anyInt());
        }

        @Test
        @DisplayName("a paged collection reports its TRUE size, not the page the render loaded")
        void reportsTheTrueSizeRatherThanThePage() {
            // The resolution loads one row and states the real count beside it. Describing
            // what came back would report a 4 812-row variable as holding one, which is the
            // "a size that is not the size" defect this report exists to remove.
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of(
                    "rows", List.of(Map.of("id", 1)),
                    "rows__total", 4812,
                    "rows__truncated", true));
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}")), renderService);

            assertEquals("List(size=4812)", variable(node.execute(context), "rows").get("resolved"));
        }

        @Test
        @DisplayName("a total that belongs to something else is ignored rather than stamped onto a scalar")
        void ignoresATotalThatIsNotACollectionSize() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of("title", "Hello", "title__total", 99));
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("title", "{{core:prepare.output.title}}")), renderService);

            assertEquals("\"Hello\"", variable(node.execute(context), "title").get("resolved"));
        }

        @Test
        @DisplayName("a variable DROPPED by the render's byte budget is not reported as one that held nothing")
        void tellsADroppedVariableFromAnEmptyOne() {
            // The render stops resolving once its cumulative byte budget is exhausted and
            // flags the MAP, not the variable. Reading the variable's own absence would call
            // that "unresolved", which this report defines as "it held nothing" - the
            // opposite of "it held too much to measure", and the two want opposite fixes.
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of(
                    "first", List.of(1, 2),
                    "__resolved_variables_truncated", true));
            InterfaceNode node = wire(nodeWithMapping(new java.util.LinkedHashMap<>(Map.of(
                "first", "{{core:a.output.items}}",
                "second", "{{core:b.output.items}}"))), renderService);

            NodeExecutionResult result = node.execute(context);

            assertEquals("resolved", variable(result, "first").get("status"));
            assertEquals("not_evaluated", variable(result, "second").get("status"),
                "a variable the budget dropped was never read, so it cannot be called empty");
            @SuppressWarnings("unchecked")
            Map<String, Object> resolved =
                (Map<String, Object>) result.output().get("resolved_params");
            assertTrue(String.valueOf(resolved.get("variableMappingError")).contains("byte budget"),
                "and the reason is reported where the reader is, not only in a log");
        }

        @Test
        @DisplayName("a resolution that answers nothing without throwing still says why it has no values")
        void reportsAReasonWhenTheResolutionAnswersNull() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(null);
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}")), renderService);

            NodeExecutionResult result = node.execute(context);

            assertEquals("not_evaluated", variable(result, "rows").get("status"));
            @SuppressWarnings("unchecked")
            Map<String, Object> resolved =
                (Map<String, Object>) result.output().get("resolved_params");
            // not_evaluated without a reason contradicts both the doc and the agent help,
            // which define the status as "nothing measured it; the error says why".
            assertNotNull(resolved.get("variableMappingError"));
        }

        @Test
        @DisplayName("a resolution failure message is shortened before it lands on the step row")
        void shortensTheFailureReason() {
            InterfaceRenderService renderService = mock(InterfaceRenderService.class);
            when(renderService.resolveVariablesForReporting(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("z".repeat(5000)));
            InterfaceNode node = wire(
                nodeWithMapping(Map.of("rows", "{{core:fetch.output.items}}")), renderService);

            @SuppressWarnings("unchecked")
            Map<String, Object> resolved =
                (Map<String, Object>) node.execute(context).output().get("resolved_params");
            String reported = String.valueOf(resolved.get("variableMappingError"));
            assertTrue(reported.length() < 300, "a stack-trace-sized message must not be persisted whole");
            assertTrue(reported.contains("5000 chars"), "and the reader must be told what was cut");
        }
    }
}
