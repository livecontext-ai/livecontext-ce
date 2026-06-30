package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowRunController#getEpochSignals}.
 * Tests the epoch-specific signal query endpoint used for epoch state viewing on canvas.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - Epoch Signals")
class WorkflowRunControllerEpochSignalsTest {

    @Mock
    private SignalWaitRepository signalWaitRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @InjectMocks
    private WorkflowRunController controller;

    private static final String RUN_ID = "run-test-123";
    private static final String TENANT_ID = "tenant-A";

    @BeforeEach
    void wireOwnerCheck() {
        // The controller verifies the run belongs to the caller's tenant before
        // returning signals. Stub a matching owner so the auth check passes for
        // every test in the happy / error / coverage groups; cross-tenant + 401
        // are exercised in the dedicated TenantGuard nested class.
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    // ===== Helpers =====

    private SignalWaitEntity buildSignal(String nodeId, SignalType type, SignalWaitStatus status,
                                         String itemId, int epoch) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(1L);
        entity.setRunId(RUN_ID);
        entity.setNodeId(nodeId);
        entity.setSignalType(type);
        entity.setStatus(status);
        entity.setItemId(itemId);
        entity.setEpoch(epoch);
        entity.setCreatedAt(Instant.parse("2026-02-26T10:00:00Z"));
        return entity;
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("returns active signals for given epoch")
        void returnsActiveSignalsForEpoch() {
            SignalWaitEntity approval = buildSignal("core:user_approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.PENDING, "default", 2);
            approval.setExpiresAt(Instant.parse("2026-02-27T10:00:00Z"));

            SignalWaitEntity interfaceSignal = buildSignal("interface:form_1", SignalType.INTERFACE_SIGNAL,
                    SignalWaitStatus.PENDING, "default", 2);

            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 2))
                    .thenReturn(List.of(approval, interfaceSignal));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 2, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertThat(body).hasSize(2);

            // First signal: USER_APPROVAL
            Map<String, Object> first = body.get(0);
            assertThat(first.get("nodeId")).isEqualTo("core:user_approval");
            assertThat(first.get("signalType")).isEqualTo("USER_APPROVAL");
            assertThat(first.get("status")).isEqualTo("PENDING");
            assertThat(first.get("itemId")).isEqualTo("default");
            assertThat(first.get("createdAt")).isNotNull();
            assertThat(first.get("expiresAt")).isNotNull();

            // Second signal: INTERFACE_SIGNAL (no expiresAt)
            Map<String, Object> second = body.get(1);
            assertThat(second.get("nodeId")).isEqualTo("interface:form_1");
            assertThat(second.get("signalType")).isEqualTo("INTERFACE_SIGNAL");
            assertThat(second.get("status")).isEqualTo("PENDING");
            assertThat(second).doesNotContainKey("expiresAt");
        }

        @Test
        @DisplayName("returns CLAIMED signals too")
        void returnsClaimedSignals() {
            SignalWaitEntity claimed = buildSignal("core:approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.CLAIMED, "item-1", 1);

            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 1))
                    .thenReturn(List.of(claimed));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 1, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertThat(body).hasSize(1);
            assertThat(body.get(0).get("status")).isEqualTo("CLAIMED");
        }
    }

    @Nested
    @DisplayName("Empty results")
    class EmptyResults {

        @Test
        @DisplayName("returns empty list when no active signals for epoch")
        void returnsEmptyListWhenNoSignals() {
            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 5))
                    .thenReturn(Collections.emptyList());

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 5, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertThat(body).isEmpty();
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns 500 on repository exception")
        void returns500OnException() {
            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 1))
                    .thenThrow(new RuntimeException("DB connection failed"));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 1, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsKey("error");
            assertThat((String) body.get("error")).contains("DB connection failed");
        }
    }

    @Nested
    @DisplayName("Signal type coverage")
    class SignalTypeCoverage {

        @Test
        @DisplayName("all signal types are correctly serialized")
        void allSignalTypesSerializedCorrectly() {
            SignalWaitEntity timer = buildSignal("core:wait_5s", SignalType.WAIT_TIMER,
                    SignalWaitStatus.PENDING, "default", 3);
            SignalWaitEntity webhook = buildSignal("core:webhook_wait", SignalType.WEBHOOK_WAIT,
                    SignalWaitStatus.PENDING, "default", 3);

            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 3))
                    .thenReturn(List.of(timer, webhook));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 3, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertThat(body).hasSize(2);
            assertThat(body.get(0).get("signalType")).isEqualTo("WAIT_TIMER");
            assertThat(body.get(1).get("signalType")).isEqualTo("WEBHOOK_WAIT");
        }
    }

    @Nested
    @DisplayName("itemContext (split item data)")
    class ItemContext {

        @Test
        @DisplayName("includes itemContext with the persisted splitItemData when present (split context)")
        void includesItemContextWhenSplitItemDataPresent() {
            SignalWaitEntity approval = buildSignal("core:user_approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.PENDING, "1", 0);
            Map<String, Object> itemData = Map.of("name", "Order #42", "amount", 199);
            approval.setSplitItemData(itemData);

            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 0))
                    .thenReturn(List.of(approval));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 0, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertThat(body.get(0).get("itemContext")).isEqualTo(itemData);
        }

        @Test
        @DisplayName("REGRESSION: strips cross-pod restoration keys (splitNodeId/items/...) from itemContext")
        void stripsRestorationKeysFromItemContext() {
            SignalWaitEntity approval = buildSignal("core:user_approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.PENDING, "1", 0);
            approval.setSplitItemData(Map.of(
                "current_item", Map.of("name", "Order #42"),
                "current_index", 1,
                "splitNodeId", "core:split_orders",
                "items", List.of("a", "b", "c"),
                "itemIndex", 1,
                "workflowItemIndex", 0));
            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 0))
                    .thenReturn(List.of(approval));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 0, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            @SuppressWarnings("unchecked")
            Map<String, Object> itemContext = (Map<String, Object>) body.get(0).get("itemContext");
            assertThat(itemContext).containsOnlyKeys("current_item", "current_index");
            assertThat(itemContext).doesNotContainKey("splitNodeId");
            assertThat(itemContext).doesNotContainKey("items");
        }

        @Test
        @DisplayName("omits the itemContext key when splitItemData is null or empty")
        void omitsItemContextWhenSplitItemDataNullOrEmpty() {
            SignalWaitEntity nullData = buildSignal("core:user_approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.PENDING, "default", 0);
            SignalWaitEntity emptyData = buildSignal("core:other_approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.PENDING, "default", 0);
            emptyData.setSplitItemData(Map.of());

            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 0))
                    .thenReturn(List.of(nullData, emptyData));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 0, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertThat(body.get(0)).doesNotContainKey("itemContext");
            assertThat(body.get(1)).doesNotContainKey("itemContext");
        }
    }

    @Nested
    @DisplayName("approvalContext (resolved contextTemplate)")
    class ApprovalContext {

        @Test
        @DisplayName("includes approvalContext (resolved display string) when present")
        void includesApprovalContextWhenPresent() {
            SignalWaitEntity approval = buildSignal("core:user_approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.PENDING, "default", 0);
            approval.setApprovalContext("Approve refund of 120 EUR?");
            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 0))
                    .thenReturn(List.of(approval));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 0, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertThat(body.get(0).get("approvalContext")).isEqualTo("Approve refund of 120 EUR?");
        }

        @Test
        @DisplayName("omits approvalContext when null or blank")
        void omitsApprovalContextWhenNullOrBlank() {
            SignalWaitEntity nullCtx = buildSignal("core:user_approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.PENDING, "default", 0);
            SignalWaitEntity blankCtx = buildSignal("core:other_approval", SignalType.USER_APPROVAL,
                    SignalWaitStatus.PENDING, "default", 0);
            blankCtx.setApprovalContext("   ");
            when(signalWaitRepository.findActiveByRunIdAndEpoch(RUN_ID, 0))
                    .thenReturn(List.of(nullCtx, blankCtx));

            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 0, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertThat(body.get(0)).doesNotContainKey("approvalContext");
            assertThat(body.get(1)).doesNotContainKey("approvalContext");
        }
    }

    @Nested
    @DisplayName("Tenant guard")
    class TenantGuard {

        @Test
        @DisplayName("returns 401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 1, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("returns 404 when caller tenant doesn't match the run owner")
        void notFoundOnCrossTenantAccess() {
            ResponseEntity<?> response = controller.getEpochSignals(RUN_ID, 1, "tenant-OTHER", null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
