package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowSignalController}.
 * Focuses on the bulk resolve-all endpoint and its interaction with
 * the existing signal resolution flow.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowSignalController")
class WorkflowSignalControllerTest {

    @Mock private UnifiedSignalService signalService;
    @Mock private SignalResumeService signalResumeService;
    @Mock private WorkflowRunRepository runRepository;

    private WorkflowSignalController controller;

    private static final String RUN_ID = "run-bulk-test-1";
    private static final String NODE_ID = "core:user_approval";
    private static final String USER_ID = "user-42";

    @BeforeEach
    void setUp() {
        controller = new WorkflowSignalController(signalService, signalResumeService, runRepository);
        // Audit 2026-05-16 round-3: signal endpoints now scope-guard via the
        // run-repository. Stub a tenant-matching run for the happy path; the
        // dedicated cross-tenant tests override this stub.
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(USER_ID);
        lenient().when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private SignalWaitEntity buildSignal(long id, String nodeId, SignalType type, int epoch) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(id);
        entity.setRunId(RUN_ID);
        entity.setNodeId(nodeId);
        entity.setSignalType(type);
        entity.setStatus(SignalWaitStatus.PENDING);
        entity.setItemId("main");
        entity.setEpoch(epoch);
        entity.setCreatedAt(Instant.parse("2026-03-04T10:00:00Z"));
        return entity;
    }

    private Map<String, Object> approveBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", "APPROVED");
        return body;
    }

    private Map<String, Object> rejectBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", "REJECTED");
        return body;
    }

    private Map<String, Object> bodyWithComment(String resolution, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", resolution);
        body.put("comment", comment);
        return body;
    }

    // ========================================================================
    // resolve-all: Happy path
    // ========================================================================

    @Nested
    @DisplayName("resolveAllSignals - Happy path")
    class ResolveAllHappyPath {

        @Test
        @DisplayName("Should resolve all USER_APPROVAL signals for node across 3 epochs")
        void shouldResolveAllSignalsAcrossEpochs() {
            // Arrange: 3 pending USER_APPROVAL signals for the same node, different epochs
            SignalWaitEntity sig0 = buildSignal(100L, NODE_ID, SignalType.USER_APPROVAL, 0);
            SignalWaitEntity sig1 = buildSignal(101L, NODE_ID, SignalType.USER_APPROVAL, 1);
            SignalWaitEntity sig2 = buildSignal(102L, NODE_ID, SignalType.USER_APPROVAL, 2);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0, sig1, sig2));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            // Act
            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            // Assert
            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> result = response.getBody();
            assertNotNull(result);
            assertEquals("resolved", result.get("status"));
            assertEquals(3, result.get("count"));
            assertEquals(NODE_ID, result.get("nodeId"));
            assertEquals("APPROVED", result.get("resolution"));
            assertEquals(USER_ID, result.get("resolvedBy"));

            // Verify each signal was resolved
            verify(signalService).resolveSignal(eq(100L), eq(SignalResolution.APPROVED), any(), eq(USER_ID));
            verify(signalService).resolveSignal(eq(101L), eq(SignalResolution.APPROVED), any(), eq(USER_ID));
            verify(signalService).resolveSignal(eq(102L), eq(SignalResolution.APPROVED), any(), eq(USER_ID));

            // Verify resume was called for each
            verify(signalResumeService, times(3)).resumeAfterSignal(any(SignalWaitEntity.class));
        }

        @Test
        @DisplayName("Should resolve single signal when only one epoch pending")
        void shouldResolveSingleSignal() {
            SignalWaitEntity sig = buildSignal(200L, NODE_ID, SignalType.USER_APPROVAL, 5);
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, rejectBody(), USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(1, response.getBody().get("count"));
            assertEquals("REJECTED", response.getBody().get("resolution"));

            verify(signalService).resolveSignal(eq(200L), eq(SignalResolution.REJECTED), any(), eq(USER_ID));
            verify(signalResumeService).resumeAfterSignal(any());
        }

        @Test
        @DisplayName("Should pass comment through to resolution data")
        void shouldPassCommentToResolutionData() {
            SignalWaitEntity sig = buildSignal(300L, NODE_ID, SignalType.USER_APPROVAL, 0);
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            controller.resolveAllSignals(RUN_ID, NODE_ID,
                bodyWithComment("APPROVED", "Bulk approved by admin"), USER_ID, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(signalService).resolveSignal(eq(300L), eq(SignalResolution.APPROVED), captor.capture(), eq(USER_ID));
            assertEquals("Bulk approved by admin", captor.getValue().get("comment"));
        }

        @Test
        @DisplayName("Should return 401 when X-User-ID header is missing (scope guard)")
        void shouldReturn401WhenNoUserId() {
            // Audit 2026-05-16 round-3: signal endpoints now require X-User-ID
            // and guard run-scope via WorkflowControllerHelper.isRunInScope.
            // The pre-guard fallback to a magic "api" actor is removed - anonymous
            // bulk-resolve calls would have let a leaked runId become a
            // cross-tenant approval bypass.
            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), null, null);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            verify(signalService, never()).resolveSignal(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Should resolve in epoch order (lowest first)")
        void shouldResolveInEpochOrder() {
            // Signals returned in random order
            SignalWaitEntity sig2 = buildSignal(102L, NODE_ID, SignalType.USER_APPROVAL, 2);
            SignalWaitEntity sig0 = buildSignal(100L, NODE_ID, SignalType.USER_APPROVAL, 0);
            SignalWaitEntity sig1 = buildSignal(101L, NODE_ID, SignalType.USER_APPROVAL, 1);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig2, sig0, sig1));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            // Verify the order of resumeAfterSignal calls (epoch 0, 1, 2)
            ArgumentCaptor<SignalWaitEntity> captor = ArgumentCaptor.forClass(SignalWaitEntity.class);
            verify(signalResumeService, times(3)).resumeAfterSignal(captor.capture());
            List<SignalWaitEntity> resumed = captor.getAllValues();
            assertEquals(0, resumed.get(0).getEpoch());
            assertEquals(1, resumed.get(1).getEpoch());
            assertEquals(2, resumed.get(2).getEpoch());
        }
    }

    // ========================================================================
    // resolve-all: Filtering
    // ========================================================================

    @Nested
    @DisplayName("resolveAllSignals - Filtering")
    class ResolveAllFiltering {

        @Test
        @DisplayName("Should only resolve USER_APPROVAL signals, not WAIT_TIMER or INTERFACE_SIGNAL")
        void shouldOnlyResolveUserApproval() {
            SignalWaitEntity approval = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            SignalWaitEntity timer = buildSignal(2L, NODE_ID, SignalType.WAIT_TIMER, 0);
            SignalWaitEntity iface = buildSignal(3L, NODE_ID, SignalType.INTERFACE_SIGNAL, 0);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(approval, timer, iface));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            assertEquals(1, response.getBody().get("count"));
            verify(signalService).resolveSignal(eq(1L), any(), any(), any());
            verify(signalService, never()).resolveSignal(eq(2L), any(), any(), any());
            verify(signalService, never()).resolveSignal(eq(3L), any(), any(), any());
        }

        @Test
        @DisplayName("Should only resolve signals matching the given nodeId")
        void shouldOnlyResolveMatchingNodeId() {
            SignalWaitEntity ours = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            SignalWaitEntity other = buildSignal(2L, "core:other_approval", SignalType.USER_APPROVAL, 0);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(ours, other));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            assertEquals(1, response.getBody().get("count"));
            verify(signalService).resolveSignal(eq(1L), any(), any(), any());
            verify(signalService, never()).resolveSignal(eq(2L), any(), any(), any());
        }

        @Test
        @DisplayName("Should filter by both nodeId AND signalType together")
        void shouldFilterByBothNodeIdAndType() {
            // Our node with USER_APPROVAL: should be resolved
            SignalWaitEntity match = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            // Our node with INTERFACE_SIGNAL: should NOT be resolved
            SignalWaitEntity wrongType = buildSignal(2L, NODE_ID, SignalType.INTERFACE_SIGNAL, 0);
            // Different node with USER_APPROVAL: should NOT be resolved
            SignalWaitEntity wrongNode = buildSignal(3L, "core:other", SignalType.USER_APPROVAL, 0);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(match, wrongType, wrongNode));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            assertEquals(1, response.getBody().get("count"));
            verify(signalService, times(1)).resolveSignal(anyLong(), any(), any(), any());
        }
    }

    // ========================================================================
    // resolve-all: Error handling
    // ========================================================================

    @Nested
    @DisplayName("resolveAllSignals - Error handling")
    class ResolveAllErrorHandling {

        @Test
        @DisplayName("Should return 404 when no matching signals found")
        void shouldReturn404WhenNoSignals() {
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of());

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            verify(signalService, never()).resolveSignal(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return 404 when active signals exist but none match nodeId")
        void shouldReturn404WhenNoMatchingNodeId() {
            SignalWaitEntity other = buildSignal(1L, "core:different_node", SignalType.USER_APPROVAL, 0);
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(other));

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }

        @Test
        @DisplayName("Should return 400 when resolution field is missing")
        void shouldReturn400WhenResolutionMissing() {
            Map<String, Object> body = new LinkedHashMap<>();
            // No "resolution" key

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, body, USER_ID, null);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertTrue(response.getBody().get("error").toString().contains("Missing"));
        }

        @Test
        @DisplayName("Should return 400 when resolution value is invalid")
        void shouldReturn400WhenResolutionInvalid() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resolution", "INVALID_VALUE");

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, body, USER_ID, null);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertTrue(response.getBody().get("error").toString().contains("Invalid"));
        }

        @Test
        @DisplayName("Should count only successfully resolved signals (skip already-claimed)")
        void shouldCountOnlySuccessfulResolutions() {
            SignalWaitEntity sig0 = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            SignalWaitEntity sig1 = buildSignal(2L, NODE_ID, SignalType.USER_APPROVAL, 1);
            SignalWaitEntity sig2 = buildSignal(3L, NODE_ID, SignalType.USER_APPROVAL, 2);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0, sig1, sig2));
            // Signal 1 already claimed (race condition)
            when(signalService.resolveSignal(eq(1L), any(), any(), any())).thenReturn(true);
            when(signalService.resolveSignal(eq(2L), any(), any(), any())).thenReturn(false);
            when(signalService.resolveSignal(eq(3L), any(), any(), any())).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(2, response.getBody().get("count"));

            // Resume called only for successfully resolved signals
            verify(signalResumeService, times(2)).resumeAfterSignal(any());
        }

        @Test
        @DisplayName("Should continue resolving remaining signals when one resume fails")
        void shouldContinueWhenResumeFails() {
            SignalWaitEntity sig0 = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            SignalWaitEntity sig1 = buildSignal(2L, NODE_ID, SignalType.USER_APPROVAL, 1);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0, sig1));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            // First resume throws, second succeeds
            doThrow(new RuntimeException("resume failed"))
                .doNothing()
                .when(signalResumeService).resumeAfterSignal(any());

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            // Both signals resolved successfully, even though first resume failed
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(2, response.getBody().get("count"));
            verify(signalResumeService, times(2)).resumeAfterSignal(any());
        }
    }

    // ========================================================================
    // resolve-all: Entity state after resolution
    // ========================================================================

    @Nested
    @DisplayName("resolveAllSignals - Entity state")
    class ResolveAllEntityState {

        @Test
        @DisplayName("Should set resolution, resolvedBy, resolvedAt on each resolved entity before resuming")
        void shouldSetEntityFieldsBeforeResume() {
            SignalWaitEntity sig = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 3);
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            // After resolve, entity should have fields set for resumeAfterSignal
            ArgumentCaptor<SignalWaitEntity> captor = ArgumentCaptor.forClass(SignalWaitEntity.class);
            verify(signalResumeService).resumeAfterSignal(captor.capture());
            SignalWaitEntity resumed = captor.getValue();
            assertEquals(SignalResolution.APPROVED, resumed.getResolution());
            assertEquals(USER_ID, resumed.getResolvedBy());
            assertNotNull(resumed.getResolvedAt());
        }

        @Test
        @DisplayName("Should NOT call resume for signals that failed to resolve")
        void shouldNotResumeFailedResolutions() {
            SignalWaitEntity sig = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(false);

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            assertEquals(0, response.getBody().get("count"));
            verify(signalResumeService, never()).resumeAfterSignal(any());
        }
    }

    // ========================================================================
    // Existing resolve endpoint: regression checks
    // ========================================================================

    @Nested
    @DisplayName("resolveSignal (single) - Regression")
    class ResolveSingleRegression {

        @Test
        @DisplayName("Should pick latest epoch signal when no epoch specified")
        void shouldPickLatestEpochByDefault() {
            SignalWaitEntity sig0 = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            SignalWaitEntity sig2 = buildSignal(3L, NODE_ID, SignalType.USER_APPROVAL, 2);
            SignalWaitEntity sig1 = buildSignal(2L, NODE_ID, SignalType.USER_APPROVAL, 1);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0, sig2, sig1));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            controller.resolveSignal(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            // Should resolve the LATEST epoch (epoch=2, id=3)
            verify(signalService).resolveSignal(eq(3L), any(), any(), any());
            verify(signalService, times(1)).resolveSignal(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Should resolve specific epoch when epoch is in body")
        void shouldResolveSpecificEpoch() {
            SignalWaitEntity sig0 = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            SignalWaitEntity sig1 = buildSignal(2L, NODE_ID, SignalType.USER_APPROVAL, 1);

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0, sig1));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            Map<String, Object> body = approveBody();
            body.put("epoch", 0); // Explicitly request epoch 0

            controller.resolveSignal(RUN_ID, NODE_ID, body, USER_ID, null);

            // Should resolve epoch 0 (id=1), NOT epoch 1
            verify(signalService).resolveSignal(eq(1L), any(), any(), any());
        }

        @Test
        @DisplayName("Should resolve specific split item when itemId is in body")
        void shouldResolveSpecificItemId() {
            SignalWaitEntity sig0 = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "0");
            SignalWaitEntity sig1 = buildSignalWithItemId(2L, NODE_ID, SignalType.USER_APPROVAL, 0, "1");
            SignalWaitEntity sig2 = buildSignalWithItemId(3L, NODE_ID, SignalType.USER_APPROVAL, 0, "2");

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0, sig1, sig2));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            Map<String, Object> body = approveBody();
            body.put("itemId", "1");

            ResponseEntity<Map<String, Object>> response = controller.resolveSignal(RUN_ID, NODE_ID, body, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            // Should resolve only signal id=2 (itemId="1"), not the others
            verify(signalService).resolveSignal(eq(2L), eq(SignalResolution.APPROVED), any(), eq(USER_ID));
            verify(signalService, times(1)).resolveSignal(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return 404 when itemId does not match any active signal")
        void shouldReturn404WhenItemIdNotFound() {
            SignalWaitEntity sig0 = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "0");

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0));

            Map<String, Object> body = approveBody();
            body.put("itemId", "99");

            ResponseEntity<Map<String, Object>> response = controller.resolveSignal(RUN_ID, NODE_ID, body, USER_ID, null);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            verify(signalService, never()).resolveSignal(anyLong(), any(), any(), any());
        }
    }

    // ========================================================================
    // Per-item pending endpoint
    // ========================================================================

    @Nested
    @DisplayName("GET /{nodeId}/pending - list pending signals for a node")
    class ListPendingForNode {

        @Test
        @DisplayName("Should return pending signals with itemId details")
        void shouldReturnPendingSignalsWithItemId() {
            SignalWaitEntity sig0 = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "0");
            SignalWaitEntity sig1 = buildSignalWithItemId(2L, NODE_ID, SignalType.USER_APPROVAL, 0, "1");
            SignalWaitEntity sig2 = buildSignalWithItemId(3L, NODE_ID, SignalType.USER_APPROVAL, 0, "2");

            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID))
                .thenReturn(List.of(sig0, sig1, sig2));

            ResponseEntity<List<Map<String, Object>>> response = controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            List<Map<String, Object>> signals = response.getBody();
            assertNotNull(signals);
            assertEquals(3, signals.size());

            // Each signal should have itemId
            assertEquals("0", signals.get(0).get("itemId"));
            assertEquals("1", signals.get(1).get("itemId"));
            assertEquals("2", signals.get(2).get("itemId"));

            // Each should have epoch and signalType
            assertEquals(0, signals.get(0).get("epoch"));
            assertEquals("USER_APPROVAL", signals.get(0).get("signalType"));
        }

        @Test
        @DisplayName("Should return empty list when no pending signals")
        void shouldReturnEmptyWhenNoPending() {
            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID))
                .thenReturn(List.of());

            ResponseEntity<List<Map<String, Object>>> response = controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(0, response.getBody().size());
        }
    }

    // ========================================================================
    // itemContext (split item data) in signal payloads - regression for
    // "pending-signals payload gives the approver no context in split context"
    // ========================================================================

    @Nested
    @DisplayName("Signal payload itemContext (split item data)")
    class SignalPayloadItemContext {

        @Test
        @DisplayName("Should include itemContext with the persisted splitItemData when present (split context)")
        void shouldIncludeItemContextWhenSplitItemDataPresent() {
            // Arrange: a split-context signal carrying the per-item data persisted at registration
            SignalWaitEntity sig = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "1");
            Map<String, Object> itemData = Map.of("name", "Order #42", "amount", 199);
            sig.setSplitItemData(itemData);
            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID)).thenReturn(List.of(sig));

            // Act
            ResponseEntity<List<Map<String, Object>>> response =
                controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            // Assert
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(itemData, response.getBody().get(0).get("itemContext"));
        }

        @Test
        @DisplayName("REGRESSION: strips cross-pod restoration keys (splitNodeId/items/...) from itemContext - approver sees only the item")
        void shouldStripRestorationKeysFromItemContext() {
            // A split signal blob that ALSO carries the cross-pod restoration keys (as
            // buildSplitItemData now persists). Only the display fields may reach the UI.
            SignalWaitEntity sig = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "1");
            sig.setSplitItemData(Map.of(
                "current_item", Map.of("name", "Order #42"),
                "current_index", 1,
                "splitNodeId", "core:split_orders",
                "items", List.of("a", "b", "c"),
                "itemIndex", 1,
                "workflowItemIndex", 0));
            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID)).thenReturn(List.of(sig));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            @SuppressWarnings("unchecked")
            Map<String, Object> itemContext = (Map<String, Object>) response.getBody().get(0).get("itemContext");
            assertEquals(Map.of("current_item", Map.of("name", "Order #42"), "current_index", 1), itemContext);
            assertFalse(itemContext.containsKey("splitNodeId"), "internal splitNodeId must not reach the UI (the approver-preview regression)");
            assertFalse(itemContext.containsKey("items"), "the full items list must not bloat the signals payload");
        }

        @Test
        @DisplayName("Should omit the itemContext key when splitItemData is null (non-split context)")
        void shouldOmitItemContextWhenSplitItemDataNull() {
            // Arrange: a regular signal - splitItemData is never set
            SignalWaitEntity sig = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID)).thenReturn(List.of(sig));

            // Act
            ResponseEntity<List<Map<String, Object>>> response =
                controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            // Assert
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().get(0).containsKey("itemContext"),
                "itemContext must be omitted when splitItemData is null");
        }

        @Test
        @DisplayName("Should omit the itemContext key when splitItemData is an empty map")
        void shouldOmitItemContextWhenSplitItemDataEmpty() {
            SignalWaitEntity sig = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            sig.setSplitItemData(Map.of());
            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID)).thenReturn(List.of(sig));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().get(0).containsKey("itemContext"),
                "itemContext must be omitted when splitItemData is empty");
        }

        @Test
        @DisplayName("Should include itemContext in the run-level GET /signals list as well")
        void shouldIncludeItemContextInRunLevelList() {
            SignalWaitEntity sig = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "0");
            sig.setSplitItemData(Map.of("item", "alpha"));
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listSignals(RUN_ID, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(Map.of("item", "alpha"), response.getBody().get(0).get("itemContext"));
        }
    }

    // ========================================================================
    // approvalContext (resolved contextTemplate) in signal payloads
    // ========================================================================

    @Nested
    @DisplayName("Signal payload approvalContext (resolved contextTemplate)")
    class SignalPayloadApprovalContext {

        @Test
        @DisplayName("Should include approvalContext (the resolved display string) when present")
        void shouldIncludeApprovalContextWhenPresent() {
            SignalWaitEntity sig = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            sig.setApprovalContext("Approve refund of 120 EUR for x@y?");
            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID)).thenReturn(List.of(sig));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("Approve refund of 120 EUR for x@y?",
                response.getBody().get(0).get("approvalContext"));
        }

        @Test
        @DisplayName("Should omit approvalContext when null (no contextTemplate configured)")
        void shouldOmitApprovalContextWhenNull() {
            SignalWaitEntity sig = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID)).thenReturn(List.of(sig));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().get(0).containsKey("approvalContext"),
                "approvalContext must be omitted when not set");
        }

        @Test
        @DisplayName("Should omit approvalContext when blank")
        void shouldOmitApprovalContextWhenBlank() {
            SignalWaitEntity sig = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            sig.setApprovalContext("   ");
            when(signalService.getActiveSignalsForNode(RUN_ID, NODE_ID)).thenReturn(List.of(sig));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listPendingForNode(RUN_ID, NODE_ID, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().get(0).containsKey("approvalContext"),
                "approvalContext must be omitted when blank");
        }

        @Test
        @DisplayName("Should include approvalContext in the run-level GET /signals list as well")
        void shouldIncludeApprovalContextInRunLevelList() {
            SignalWaitEntity sig = buildSignal(1L, NODE_ID, SignalType.USER_APPROVAL, 0);
            sig.setApprovalContext("Please review");
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listSignals(RUN_ID, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("Please review", response.getBody().get(0).get("approvalContext"));
        }
    }

    // ========================================================================
    // resolve-all: Epoch-scoped (split context)
    // ========================================================================

    @Nested
    @DisplayName("resolveAllSignals - Epoch-scoped (split context)")
    class ResolveAllEpochScoped {

        @Test
        @DisplayName("Should resolve only signals matching the requested epoch")
        void shouldResolveOnlyMatchingEpoch() {
            SignalWaitEntity sig0item0 = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "0");
            SignalWaitEntity sig0item1 = buildSignalWithItemId(2L, NODE_ID, SignalType.USER_APPROVAL, 0, "1");
            SignalWaitEntity sig0item2 = buildSignalWithItemId(3L, NODE_ID, SignalType.USER_APPROVAL, 0, "2");
            SignalWaitEntity sig1item0 = buildSignalWithItemId(4L, NODE_ID, SignalType.USER_APPROVAL, 1, "0");

            when(signalService.getActiveSignals(RUN_ID))
                .thenReturn(List.of(sig0item0, sig0item1, sig0item2, sig1item0));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            Map<String, Object> body = approveBody();
            body.put("epoch", 0);

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, body, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(3, response.getBody().get("count"));

            // Only epoch 0 signals resolved
            verify(signalService).resolveSignal(eq(1L), any(), any(), any());
            verify(signalService).resolveSignal(eq(2L), any(), any(), any());
            verify(signalService).resolveSignal(eq(3L), any(), any(), any());
            // Epoch 1 NOT resolved
            verify(signalService, never()).resolveSignal(eq(4L), any(), any(), any());
        }

        @Test
        @DisplayName("Should resolve all epochs when epoch is not specified")
        void shouldResolveAllEpochsWhenNotSpecified() {
            SignalWaitEntity sig0 = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "0");
            SignalWaitEntity sig1 = buildSignalWithItemId(2L, NODE_ID, SignalType.USER_APPROVAL, 1, "0");

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0, sig1));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            // No epoch in body
            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, approveBody(), USER_ID, null);

            assertEquals(2, response.getBody().get("count"));
            verify(signalService).resolveSignal(eq(1L), any(), any(), any());
            verify(signalService).resolveSignal(eq(2L), any(), any(), any());
        }

        @Test
        @DisplayName("Should treat non-Number epoch as absent (resolve all epochs)")
        void shouldTreatNonNumberEpochAsAbsent() {
            SignalWaitEntity sig0 = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "0");
            SignalWaitEntity sig1 = buildSignalWithItemId(2L, NODE_ID, SignalType.USER_APPROVAL, 1, "0");

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0, sig1));
            when(signalService.resolveSignal(anyLong(), any(), any(), any())).thenReturn(true);

            Map<String, Object> body = approveBody();
            body.put("epoch", "not-a-number"); // String instead of Number

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, body, USER_ID, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(2, response.getBody().get("count")); // Both epochs resolved
        }

        @Test
        @DisplayName("Should return 404 when epoch has no matching signals")
        void shouldReturn404WhenEpochHasNoSignals() {
            SignalWaitEntity sig0 = buildSignalWithItemId(1L, NODE_ID, SignalType.USER_APPROVAL, 0, "0");

            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(sig0));

            Map<String, Object> body = approveBody();
            body.put("epoch", 5); // No signals at epoch 5

            ResponseEntity<Map<String, Object>> response =
                controller.resolveAllSignals(RUN_ID, NODE_ID, body, USER_ID, null);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            verify(signalService, never()).resolveSignal(anyLong(), any(), any(), any());
        }
    }

    // ========================================================================
    // Additional helper
    // ========================================================================

    private SignalWaitEntity buildSignalWithItemId(long id, String nodeId, SignalType type, int epoch, String itemId) {
        SignalWaitEntity entity = buildSignal(id, nodeId, type, epoch);
        entity.setItemId(itemId);
        return entity;
    }
}
