package com.apimarketplace.orchestrator.controllers.interfaces;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the signal selection logic in InterfaceActionController.fireAction().
 *
 * Verifies that when multiple INTERFACE_SIGNAL entities exist for the same nodeId
 * across different epochs, the controller picks the one with the highest epoch
 * (i.e., max(Comparator.comparingInt(SignalWaitEntity::getEpoch))).
 *
 * This was a bug fix: previously findFirst() was used, which returned an arbitrary
 * signal depending on list order, sometimes picking a stale epoch's signal.
 */
@ExtendWith(MockitoExtension.class)
class InterfaceActionControllerSignalSelectionTest {

    private static final String RUN_ID = "run-123";
    private static final String NODE_ID = "interface:my_form";
    private static final String USER_ID = "user-1";

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
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(USER_ID);
        lenient().when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    // ========================================================================
    // Helper to build a SignalWaitEntity with the fields used by the controller
    // ========================================================================

    private static SignalWaitEntity buildSignal(Long id, String nodeId, SignalType type, int epoch,
                                                 Map<String, Object> signalConfig) {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(id);
        entity.setRunId(RUN_ID);
        entity.setNodeId(nodeId);
        entity.setSignalType(type);
        entity.setEpoch(epoch);
        entity.setSignalConfig(signalConfig);
        entity.setStatus(SignalWaitEntity.SignalWaitStatus.PENDING);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private static Map<String, Object> configWithAction(String actionKey, String target) {
        return Map.of("actionMapping", Map.of(actionKey, target));
    }

    private Map<String, Object> fireBody(String actionKey) {
        return Map.of("actionKey", actionKey, "data", Map.of());
    }

    // ========================================================================
    // Test 1: Latest epoch signal is selected when multiple exist
    // ========================================================================

    @Test
    @DisplayName("Selects the signal with the highest epoch when multiple exist for the same nodeId")
    void selectsLatestEpochSignal_whenMultipleExist() {
        // Signals in reverse order to prove it's not just picking first or last in list
        SignalWaitEntity epoch0 = buildSignal(1L, NODE_ID, SignalType.INTERFACE_SIGNAL, 0,
                configWithAction("submit", "trigger:process"));
        SignalWaitEntity epoch2 = buildSignal(2L, NODE_ID, SignalType.INTERFACE_SIGNAL, 2,
                configWithAction("submit", "trigger:process_v2"));
        SignalWaitEntity epoch1 = buildSignal(3L, NODE_ID, SignalType.INTERFACE_SIGNAL, 1,
                configWithAction("submit", "trigger:process_v1"));

        // Return them in a scrambled order - epoch 0, then 2, then 1
        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(epoch0, epoch2, epoch1));

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("submit"), USER_ID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // The target from epoch2's config should be used (trigger:process_v2)
        assertEquals("trigger:process_v2", response.getBody().get("targetKey"));
        assertEquals("fired", response.getBody().get("status"));
    }

    // ========================================================================
    // Test 2: Single signal selection works correctly
    // ========================================================================

    @Test
    @DisplayName("Works correctly when only one signal exists")
    void selectsSingleSignal() {
        SignalWaitEntity onlySignal = buildSignal(10L, NODE_ID, SignalType.INTERFACE_SIGNAL, 5,
                configWithAction("click", "trigger:handle_click"));

        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(onlySignal));

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("click"), USER_ID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("trigger:handle_click", response.getBody().get("targetKey"));
    }

    // ========================================================================
    // Test 3: No signal found returns 404
    // ========================================================================

    @Test
    @DisplayName("Returns 404 when no matching INTERFACE_SIGNAL exists for the nodeId")
    void returns404_whenNoMatchingSignal() {
        // Empty active signals
        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("submit"), USER_ID, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    @DisplayName("Returns 404 when active signals exist but none match the requested nodeId")
    void returns404_whenSignalsExistButNoneMatchNodeId() {
        SignalWaitEntity otherNode = buildSignal(1L, "interface:other_form", SignalType.INTERFACE_SIGNAL, 0,
                configWithAction("submit", "trigger:other"));

        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(otherNode));

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("submit"), USER_ID, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ========================================================================
    // Test 4: Signals from different nodes are filtered correctly
    // ========================================================================

    @Test
    @DisplayName("Filters by nodeId correctly when signals for multiple nodes exist")
    void filtersSignalsByNodeId() {
        // Signal for a different node with a HIGHER epoch - should NOT be selected
        SignalWaitEntity otherNodeHighEpoch = buildSignal(1L, "interface:other_form", SignalType.INTERFACE_SIGNAL, 99,
                configWithAction("submit", "trigger:wrong_target"));

        // Signal for the target node with a lower epoch
        SignalWaitEntity targetNodeSignal = buildSignal(2L, NODE_ID, SignalType.INTERFACE_SIGNAL, 3,
                configWithAction("submit", "trigger:correct_target"));

        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(otherNodeHighEpoch, targetNodeSignal));

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("submit"), USER_ID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // Must pick the signal for NODE_ID, not the other node's higher-epoch signal
        assertEquals("trigger:correct_target", response.getBody().get("targetKey"));
    }

    @Test
    @DisplayName("Selects highest epoch among signals for the same nodeId, ignoring other nodes")
    void selectsHighestEpochForCorrectNode_ignoringOtherNodes() {
        SignalWaitEntity otherNode = buildSignal(1L, "interface:dashboard", SignalType.INTERFACE_SIGNAL, 10,
                configWithAction("submit", "trigger:dashboard_action"));

        SignalWaitEntity targetEpoch1 = buildSignal(2L, NODE_ID, SignalType.INTERFACE_SIGNAL, 1,
                configWithAction("submit", "trigger:old_action"));
        SignalWaitEntity targetEpoch4 = buildSignal(3L, NODE_ID, SignalType.INTERFACE_SIGNAL, 4,
                configWithAction("submit", "trigger:latest_action"));
        SignalWaitEntity targetEpoch2 = buildSignal(4L, NODE_ID, SignalType.INTERFACE_SIGNAL, 2,
                configWithAction("submit", "trigger:mid_action"));

        when(signalService.getActiveSignals(RUN_ID))
                .thenReturn(List.of(otherNode, targetEpoch1, targetEpoch4, targetEpoch2));

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("submit"), USER_ID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("trigger:latest_action", response.getBody().get("targetKey"));
    }

    // ========================================================================
    // Test 5: Signals of different types are filtered
    // ========================================================================

    @Test
    @DisplayName("Ignores non-INTERFACE_SIGNAL types even if they match the nodeId")
    void filtersOutNonInterfaceSignalTypes() {
        // Same nodeId but different signal types - should be ignored
        SignalWaitEntity timerSignal = buildSignal(1L, NODE_ID, SignalType.WAIT_TIMER, 5, Map.of());
        SignalWaitEntity approvalSignal = buildSignal(2L, NODE_ID, SignalType.USER_APPROVAL, 5, Map.of());
        SignalWaitEntity webhookSignal = buildSignal(3L, NODE_ID, SignalType.WEBHOOK_WAIT, 5, Map.of());

        // The only INTERFACE_SIGNAL, with a lower epoch
        SignalWaitEntity interfaceSignal = buildSignal(4L, NODE_ID, SignalType.INTERFACE_SIGNAL, 2,
                configWithAction("submit", "trigger:correct"));

        when(signalService.getActiveSignals(RUN_ID))
                .thenReturn(List.of(timerSignal, approvalSignal, webhookSignal, interfaceSignal));

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("submit"), USER_ID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("trigger:correct", response.getBody().get("targetKey"));
    }

    @Test
    @DisplayName("Returns 404 when nodeId has active signals but none are INTERFACE_SIGNAL type")
    void returns404_whenOnlyNonInterfaceSignalTypesExist() {
        SignalWaitEntity timerSignal = buildSignal(1L, NODE_ID, SignalType.WAIT_TIMER, 5, Map.of());
        SignalWaitEntity approvalSignal = buildSignal(2L, NODE_ID, SignalType.USER_APPROVAL, 3, Map.of());

        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of(timerSignal, approvalSignal));

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("submit"), USER_ID, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("Selects highest epoch INTERFACE_SIGNAL when mixed with other signal types at higher epochs")
    void selectsHighestEpochInterfaceSignal_ignoringOtherTypesAtHigherEpochs() {
        // A WAIT_TIMER at epoch 10 for the same node - must be ignored
        SignalWaitEntity timerHighEpoch = buildSignal(1L, NODE_ID, SignalType.WAIT_TIMER, 10, Map.of());

        // INTERFACE_SIGNALs at epoch 2 and 5
        SignalWaitEntity interfaceEpoch2 = buildSignal(2L, NODE_ID, SignalType.INTERFACE_SIGNAL, 2,
                configWithAction("submit", "trigger:old"));
        SignalWaitEntity interfaceEpoch5 = buildSignal(3L, NODE_ID, SignalType.INTERFACE_SIGNAL, 5,
                configWithAction("submit", "trigger:latest"));

        when(signalService.getActiveSignals(RUN_ID))
                .thenReturn(List.of(timerHighEpoch, interfaceEpoch2, interfaceEpoch5));

        ResponseEntity<Map<String, Object>> response = controller.fireAction(RUN_ID, NODE_ID, fireBody("submit"), USER_ID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // Must pick epoch 5 INTERFACE_SIGNAL, not epoch 10 WAIT_TIMER
        assertEquals("trigger:latest", response.getBody().get("targetKey"));
    }
}
