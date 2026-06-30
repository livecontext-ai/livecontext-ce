package com.apimarketplace.orchestrator.controllers.interfaces;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Wiring regression for the V5 Activity-tab interface touch path.
 *
 * <p>Pins two contracts that the rest of the test suite does not exercise:
 * <ol>
 *   <li><b>Invocation</b>: {@code fireAction} must trigger
 *       {@code interfaceClient.touchUpdatedAt(interfaceId)} (fire-and-forget on
 *       the default ForkJoinPool) when {@code signalConfig.interfaceId} is a
 *       valid UUID. Without this, the bell's Activity tab would silently
 *       stay frozen on an interface's last config edit timestamp.</li>
 *   <li><b>Swallow</b>: an exception thrown by the client must NOT propagate
 *       to the user's fireAction response - the touch is best-effort, the
 *       action persistence and signal handling are unaffected.</li>
 * </ol>
 *
 * <p>Uses Mockito {@code timeout()} to handle the
 * {@code CompletableFuture.runAsync} hand-off without an Awaitility dep.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceActionController - async touch wiring (V5 Activity-tab extension)")
class InterfaceActionControllerAsyncTouchTest {

    private static final UUID INTERFACE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String RUN_ID = "run-async-touch";
    private static final String NODE_ID = "interface:my_form";
    private static final String USER_ID = "user-touch";

    @Mock private UnifiedSignalService signalService;
    @Mock private InterfaceActionService interfaceActionService;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private InterfaceClient interfaceClient;

    private InterfaceActionController controller;

    @BeforeEach
    void setUp() {
        controller = new InterfaceActionController(signalService, interfaceActionService, runRepository, interfaceClient);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(USER_ID);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        SignalWaitEntity signal = new SignalWaitEntity();
        signal.setId(99L);
        signal.setRunId(RUN_ID);
        signal.setNodeId(NODE_ID);
        signal.setSignalType(SignalType.INTERFACE_SIGNAL);
        signal.setStatus(SignalWaitEntity.SignalWaitStatus.PENDING);
        signal.setSignalConfig(Map.of(
            "type", "INTERFACE_SIGNAL",
            "interfaceId", INTERFACE_ID.toString(),
            "actionMapping", Map.of("#submit", "trigger:form_submit")
        ));
        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(signal));
    }

    @Test
    @DisplayName("fireAction invokes interfaceClient.touchUpdatedAt(id) asynchronously")
    void wiringContract_fireActionTriggersTouch() {
        Map<String, Object> body = Map.of("actionKey", "#submit", "data", Map.of("name", "John"));

        ResponseEntity<Map<String, Object>> response =
            controller.fireAction(RUN_ID, NODE_ID, body, USER_ID, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // CompletableFuture.runAsync hand-off - use Mockito timeout(500ms) to
        // wait for the async invocation without flakiness.
        verify(interfaceClient, timeout(500)).touchUpdatedAt(eq(INTERFACE_ID));
    }

    @Test
    @DisplayName("Touch failure is swallowed: fireAction still returns 200 when client throws")
    void swallowContract_clientThrowDoesNotBreakFireAction() {
        // The try/catch INSIDE the runAsync lambda must catch this - without it,
        // the exception would go to the default UncaughtExceptionHandler and
        // disappear from app logs (V4 audit 2 F6 + audit 3 F6 mandated the
        // explicit try/catch). The user-visible fireAction response is unaffected
        // because the touch is fire-and-forget.
        doThrow(new RuntimeException("interface-service unreachable"))
            .when(interfaceClient).touchUpdatedAt(any(UUID.class));

        Map<String, Object> body = Map.of("actionKey", "#submit", "data", Map.of());

        ResponseEntity<Map<String, Object>> response =
            controller.fireAction(RUN_ID, NODE_ID, body, USER_ID, null);

        assertThat(response.getStatusCode())
            .as("touch failure must NOT propagate to the user - fireAction stays 200")
            .isEqualTo(HttpStatus.OK);
        // Wait briefly to ensure the async lambda actually ran (and threw + swallowed).
        verify(interfaceClient, timeout(500)).touchUpdatedAt(eq(INTERFACE_ID));
    }

    @Test
    @DisplayName("Malformed interfaceId in signalConfig is silently skipped (no touch fired)")
    void defensiveContract_malformedInterfaceIdSkips() {
        SignalWaitEntity badSignal = new SignalWaitEntity();
        badSignal.setId(100L);
        badSignal.setRunId(RUN_ID);
        badSignal.setNodeId(NODE_ID);
        badSignal.setSignalType(SignalType.INTERFACE_SIGNAL);
        badSignal.setStatus(SignalWaitEntity.SignalWaitStatus.PENDING);
        badSignal.setSignalConfig(Map.of(
            "type", "INTERFACE_SIGNAL",
            "interfaceId", "not-a-uuid",
            "actionMapping", Map.of("#submit", "trigger:x")
        ));
        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(badSignal));

        Map<String, Object> body = Map.of("actionKey", "#submit", "data", Map.of());

        ResponseEntity<Map<String, Object>> response =
            controller.fireAction(RUN_ID, NODE_ID, body, USER_ID, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Wait a bit then verify NO touch call happened (defensive UUID.fromString catch).
        verify(interfaceClient, after(200).never()).touchUpdatedAt(any(UUID.class));
    }
}
