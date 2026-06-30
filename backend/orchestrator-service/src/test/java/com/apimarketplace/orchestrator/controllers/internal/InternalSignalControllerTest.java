package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalSignalController")
class InternalSignalControllerTest {

    @Mock
    private UnifiedSignalService signalService;

    @Mock
    private SignalWaitRepository signalWaitRepository;

    @Mock
    private WorkflowRunRepository runRepository;

    private InternalSignalController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalSignalController(signalService, signalWaitRepository, runRepository);

        // Default: signal 7 belongs to run-1, which lives in org-1 owned by user-1.
        lenient().when(signalWaitRepository.findEpochInfoById(7L)).thenReturn(Optional.of(
                new SignalWaitRepository.EpochInfo("run-1", "trigger:start", 1, Instant.EPOCH)));
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("user-1");
        run.setOrganizationId("org-1");
        lenient().when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
        lenient().when(signalService.resolveSignal(anyLong(), any(), anyMap(), anyString()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("resolves a signal for a caller in the run's workspace")
    void resolvesInScopeSignal() {
        ResponseEntity<Map<String, Object>> response = controller.resolveSignal(
                7L, "user-1", "org-1", Map.of("resolution", "APPROVED"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "resolved");
        verify(signalService).resolveSignal(eq(7L), eq(SignalResolution.APPROVED), anyMap(), eq("user-1"));
    }

    @Test
    @DisplayName("regression: 404 when the caller's workspace does not match the signal's run (forged WS signal.resolve)")
    void rejectsForeignOrgSignal() {
        // Bug class: the internal WS path had no run-scope guard (the public twin
        // WorkflowSignalController guards every resolve with isRunInScope) - a
        // forged signalId could approve another workspace's pending approval.
        ResponseEntity<Map<String, Object>> response = controller.resolveSignal(
                7L, "user-1", "some-other-org", Map.of("resolution", "APPROVED"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(signalService, never()).resolveSignal(anyLong(), any(), anyMap(), anyString());
    }

    @Test
    @DisplayName("404 when the signal does not exist")
    void rejectsUnknownSignal() {
        when(signalWaitRepository.findEpochInfoById(99L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.resolveSignal(
                99L, "user-1", "org-1", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(signalService, never()).resolveSignal(anyLong(), any(), anyMap(), anyString());
    }

    @Test
    @DisplayName("401 when X-User-ID is missing")
    void rejectsMissingUser() {
        ResponseEntity<Map<String, Object>> response = controller.resolveSignal(
                7L, null, "org-1", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(signalService, never()).resolveSignal(anyLong(), any(), anyMap(), anyString());
    }

    @Test
    @DisplayName("401 when X-User-ID is blank")
    void rejectsBlankUser() {
        ResponseEntity<Map<String, Object>> response = controller.resolveSignal(
                7L, "  ", "org-1", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(signalService, never()).resolveSignal(anyLong(), any(), anyMap(), anyString());
    }

    @Test
    @DisplayName("400 on an invalid resolution value (in-scope caller)")
    void rejectsInvalidResolution() {
        ResponseEntity<Map<String, Object>> response = controller.resolveSignal(
                7L, "user-1", "org-1", Map.of("resolution", "MAYBE"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("reports already_resolved when the service declines to resolve")
    void reportsAlreadyResolved() {
        when(signalService.resolveSignal(anyLong(), any(), anyMap(), anyString())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.resolveSignal(
                7L, "user-1", "org-1", Map.of("resolution", "REJECTED"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "already_resolved");
    }
}
