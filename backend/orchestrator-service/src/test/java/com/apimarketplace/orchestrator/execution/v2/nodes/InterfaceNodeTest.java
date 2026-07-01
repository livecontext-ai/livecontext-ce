package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ResolvedTemplateSnapshot;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceScreenshotService;
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
}
