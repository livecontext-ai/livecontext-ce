package com.apimarketplace.orchestrator.controllers.interfaces;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InterfaceActionController.
 * Verifies that actions fire without resolving the interface signal.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceActionController")
class InterfaceActionControllerTest {

    @Mock
    private UnifiedSignalService signalService;

    @Mock
    private InterfaceActionService interfaceActionService;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private com.apimarketplace.interfaces.client.InterfaceClient interfaceClient;

    private InterfaceActionController controller;

    @BeforeEach
    void setUp() {
        controller = new InterfaceActionController(signalService, interfaceActionService, runRepository, interfaceClient);
        // Audit 2026-05-17 round-4: fire-action and signal-list are now scoped
        // by caller. Stub a tenant-matching run for the happy-path tests.
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("user-1");
        lenient().when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private SignalWaitEntity createInterfaceSignal(String nodeId, Map<String, String> actionMapping) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(1L);
        entity.setRunId("run-1");
        entity.setNodeId(nodeId);
        entity.setSignalType(SignalType.INTERFACE_SIGNAL);
        entity.setStatus(SignalWaitEntity.SignalWaitStatus.PENDING);
        entity.setSignalConfig(Map.of(
            "type", "INTERFACE_SIGNAL",
            "interfaceId", "uuid-123",
            "actionMapping", actionMapping
        ));
        return entity;
    }

    private SignalWaitEntity createTimerSignal(String nodeId) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(2L);
        entity.setRunId("run-1");
        entity.setNodeId(nodeId);
        entity.setSignalType(SignalType.WAIT_TIMER);
        entity.setStatus(SignalWaitEntity.SignalWaitStatus.PENDING);
        return entity;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // fireAction() - Success cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fireAction() - Success")
    class FireActionSuccessTests {

        @Test
        @DisplayName("Should fire action and return 200 with result")
        void shouldFireActionSuccessfully() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));

            Map<String, Object> body = Map.of(
                "actionKey", "#submit",
                "data", Map.of("name", "John")
            );

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "fired");
            assertThat(response.getBody()).containsEntry("nodeId", "interface:my_form");
            assertThat(response.getBody()).containsEntry("actionKey", "#submit");
            assertThat(response.getBody()).containsEntry("targetKey", "trigger:form_submit");
        }

        @Test
        @DisplayName("Should NOT resolve the signal - interface stays active")
        void shouldNotResolveSignal() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));

            Map<String, Object> body = Map.of("actionKey", "#submit", "data", Map.of());

            controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            // Signal should NOT be resolved - only getActiveSignals was called
            verify(signalService).getActiveSignals("run-1");
            verifyNoMoreInteractions(signalService);
        }

        @Test
        @DisplayName("Should persist action data when userId is provided")
        void shouldPersistActionData() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));

            Map<String, Object> formData = Map.of("name", "Alice");
            Map<String, Object> body = Map.of("actionKey", "#submit", "data", formData);

            controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            verify(interfaceActionService).persistActionData(
                    eq("run-1"), eq("interface:my_form"), eq("#submit"),
                    eq(formData), eq("user-1"), anyInt(), any(), anyInt()
            );
        }

        @Test
        @DisplayName("Should return 401 and not persist when X-User-ID header is missing")
        void shouldNotPersistWhenNoUserId() {
            // Audit 2026-05-17 round-4: missing X-User-ID short-circuits the
            // controller before signal lookup. No interactions expected with
            // either the signal service or the action persistence service.
            Map<String, Object> body = Map.of("actionKey", "#submit", "data", Map.of());

            controller.fireAction("run-1", "interface:my_form", body, null, null);

            verifyNoInteractions(interfaceActionService);
            verifyNoInteractions(signalService);
        }

        @Test
        @DisplayName("Should pass data through in result")
        void shouldPassDataThrough() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));

            Map<String, Object> formData = Map.of("name", "Alice", "email", "alice@test.com");
            Map<String, Object> body = Map.of("actionKey", "#submit", "data", formData);

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            assertThat(response.getBody()).containsEntry("data", formData);
        }

        @Test
        @DisplayName("Should filter by nodeId when multiple signals exist")
        void shouldFilterByNodeId() {
            SignalWaitEntity formSignal = createInterfaceSignal("interface:my_form",
                Map.of("#submit", "trigger:form_submit"));
            SignalWaitEntity timerSignal = createTimerSignal("core:wait_5s");

            SignalWaitEntity otherInterface = new SignalWaitEntity();
            otherInterface.setId(3L);
            otherInterface.setRunId("run-1");
            otherInterface.setNodeId("interface:other");
            otherInterface.setSignalType(SignalType.INTERFACE_SIGNAL);
            otherInterface.setStatus(SignalWaitEntity.SignalWaitStatus.PENDING);
            otherInterface.setSignalConfig(Map.of(
                "type", "INTERFACE_SIGNAL",
                "interfaceId", "uuid-other",
                "actionMapping", Map.of("#ok", "trigger:confirm")
            ));

            when(signalService.getActiveSignals("run-1"))
                .thenReturn(List.of(formSignal, timerSignal, otherInterface));

            Map<String, Object> body = Map.of("actionKey", "#submit", "data", Map.of());

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("targetKey", "trigger:form_submit");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // fireAction() - Error cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fireAction() - Errors")
    class FireActionErrorTests {

        @Test
        @DisplayName("Should return 400 when actionKey is missing")
        void shouldReturn400WhenActionKeyMissing() {
            Map<String, Object> body = Map.of("data", Map.of());

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error");
        }

        @Test
        @DisplayName("Should return 400 when actionKey is blank")
        void shouldReturn400WhenActionKeyBlank() {
            Map<String, Object> body = Map.of("actionKey", "  ", "data", Map.of());

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Should return 404 when no active signal found for nodeId")
        void shouldReturn404WhenNoSignalFound() {
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of());

            Map<String, Object> body = Map.of("actionKey", "#submit", "data", Map.of());

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:missing", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return 404 when signal exists but is not INTERFACE_SIGNAL type")
        void shouldReturn404WhenSignalIsWrongType() {
            SignalWaitEntity timerSignal = createTimerSignal("interface:form");
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(timerSignal));

            Map<String, Object> body = Map.of("actionKey", "#submit", "data", Map.of());

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return 400 when actionKey has no mapped target")
        void shouldReturn400WhenActionKeyNotMapped() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));

            Map<String, Object> body = Map.of("actionKey", "#unknown_button", "data", Map.of());

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error");
            assertThat(response.getBody()).containsKey("availableActions");
        }

        @Test
        @DisplayName("Should handle missing data field gracefully")
        void shouldHandleMissingDataGracefully() {
            Map<String, String> actionMapping = Map.of("#submit", "trigger:form_submit");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));

            // Body without "data" field
            Map<String, Object> body = Map.of("actionKey", "#submit");

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("data", Map.of());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // fireAction() - __continue (signal resolution)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fireAction() - __continue")
    class FireActionContinueTests {

        @Test
        @DisplayName("Should resolve signal when targetKey is __continue")
        void shouldResolveSignalOnContinue() {
            Map<String, String> actionMapping = Map.of("#done-btn", "__continue");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));
            when(signalService.resolveSignal(eq(1L), eq(SignalResolution.CONTINUE), anyMap(), eq("user-1")))
                .thenReturn(true);

            Map<String, Object> body = Map.of("actionKey", "#done-btn", "data", Map.of("q", "hello"));

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "continued");
            assertThat(response.getBody()).containsEntry("targetKey", "__continue");
            verify(signalService).resolveSignal(eq(1L), eq(SignalResolution.CONTINUE), anyMap(), eq("user-1"));
        }

        @Test
        @DisplayName("Should return already_resolved when signal was already resolved")
        void shouldReturnAlreadyResolvedWhenSignalAlreadyClaimed() {
            Map<String, String> actionMapping = Map.of("#done-btn", "__continue");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));
            when(signalService.resolveSignal(eq(1L), eq(SignalResolution.CONTINUE), anyMap(), eq("user-1")))
                .thenReturn(false);

            Map<String, Object> body = Map.of("actionKey", "#done-btn", "data", Map.of());

            ResponseEntity<Map<String, Object>> response =
                controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "already_resolved");
        }

        @Test
        @DisplayName("Should persist action data before resolving signal on __continue")
        void shouldPersistDataBeforeContinue() {
            Map<String, String> actionMapping = Map.of("#done-btn", "__continue");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actionMapping);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));
            when(signalService.resolveSignal(anyLong(), any(), anyMap(), anyString()))
                .thenReturn(true);

            Map<String, Object> formData = Map.of("name", "Alice");
            Map<String, Object> body = Map.of("actionKey", "#done-btn", "data", formData);

            controller.fireAction("run-1", "interface:my_form", body, "user-1", null);

            // Verify persist is called BEFORE resolveSignal
            var inOrder = inOrder(interfaceActionService, signalService);
            inOrder.verify(interfaceActionService).persistActionData(
                eq("run-1"), eq("interface:my_form"), eq("#done-btn"),
                eq(formData), eq("user-1"), anyInt(), any(), anyInt()
            );
            inOrder.verify(signalService).resolveSignal(anyLong(), any(), anyMap(), anyString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // listInterfaceSignals()
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("listInterfaceSignals()")
    class ListInterfaceSignalsTests {

        @Test
        @DisplayName("Should return empty list when no signals")
        void shouldReturnEmptyListWhenNoSignals() {
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of());

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listInterfaceSignals("run-1", "user-1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }

        @Test
        @DisplayName("Should filter only INTERFACE_SIGNAL signals")
        void shouldFilterOnlyInterfaceSignals() {
            SignalWaitEntity interfaceSignal = createInterfaceSignal("interface:form",
                Map.of("#submit", "trigger:submit"));
            SignalWaitEntity timerSignal = createTimerSignal("core:wait");
            when(signalService.getActiveSignals("run-1"))
                .thenReturn(List.of(interfaceSignal, timerSignal));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listInterfaceSignals("run-1", "user-1", null);

            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0)).containsEntry("nodeId", "interface:form");
        }

        @Test
        @DisplayName("Should include signal config details in response")
        void shouldIncludeSignalConfigDetails() {
            Map<String, String> actions = Map.of("#submit", "trigger:form_submit");
            SignalWaitEntity signal = createInterfaceSignal("interface:my_form", actions);
            when(signalService.getActiveSignals("run-1")).thenReturn(List.of(signal));

            ResponseEntity<List<Map<String, Object>>> response =
                controller.listInterfaceSignals("run-1", "user-1", null);

            Map<String, Object> result = response.getBody().get(0);
            assertThat(result).containsEntry("interfaceId", "uuid-123");
            assertThat(result).containsEntry("nodeId", "interface:my_form");
            assertThat(result).containsEntry("status", "PENDING");
        }
    }
}
