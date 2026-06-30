package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowRunController#stopWorkflow(String)} HTTP-status mapping.
 *
 * <p>Regression: previously {@code IllegalStateException} from a non-stoppable run was
 * mapped to a generic 400 (or worse, a default 500 with no error body). The fix maps it
 * to HTTP 409 Conflict with a structured body so the frontend can display a proper toast.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - stopWorkflow HTTP mapping")
class WorkflowRunControllerStopTest {

    @Mock
    private WorkflowResumeService resumeService;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @InjectMocks
    private WorkflowRunController controller;

    private static final String RUN_ID = "run_<id>";
    private static final String TENANT = "user-stop-1";

    private WorkflowRunEntity runEntityWith(RunStatus status) {
        WorkflowRunEntity entity = mock(WorkflowRunEntity.class);
        lenient().when(entity.getStatus()).thenReturn(status);
        // Scope guard mirror: stopWorkflow now requires the run to belong to the caller.
        lenient().when(entity.getTenantId()).thenReturn(TENANT);
        return entity;
    }

    @Nested
    @DisplayName("Conflict path - 409 with structured body")
    class ConflictPath {

        @Test
        @DisplayName("Maps IllegalStateException to HTTP 409 with currentStatus in body")
        void illegalStateMapsTo409WithCurrentStatus() {
            WorkflowRunEntity pendingRun = runEntityWith(RunStatus.PENDING);
            // First findByRunIdPublic is the scope-guard pre-check; second is the
            // post-stop status read-back. Stub both with the same in-scope run.
            when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.of(pendingRun));
            doThrow(new IllegalStateException("Cannot stop workflow in status: PENDING. Must be RUNNING or PAUSED."))
                    .when(resumeService).stopWorkflow(RUN_ID);

            ResponseEntity<?> response = controller.stopWorkflow(RUN_ID, TENANT, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("error")).asString().contains("Cannot stop workflow");
            assertThat(body.get("currentStatus")).isEqualTo("pending");
            assertThat(body.get("runId")).isEqualTo(RUN_ID);
        }

        @Test
        @DisplayName("Missing run → 404 via scope guard (no service call)")
        void missingRunReturns404ViaScopeGuard() {
            // PR15/V209 scope guard: when the run cannot be found for the caller,
            // we fail closed with 404 *before* invoking the resume service. Replaces
            // the previous "fall back to currentStatus=unknown" semantics, which
            // would have leaked existence information across tenants.
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.stopWorkflow(RUN_ID, TENANT, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(resumeService, never()).stopWorkflow(RUN_ID);
        }
    }

    @Nested
    @DisplayName("Happy path - 200 with post-stop status")
    class HappyPath {

        @Test
        @DisplayName("Returns 200 with status=waiting_trigger after stopping a RUNNING run")
        void stopsRunningRunReturnsWaitingTrigger() {
            WorkflowRunEntity waitingRun = runEntityWith(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.of(waitingRun));
            // resumeService.stopWorkflow succeeds (no throw)
            doNothing().when(resumeService).stopWorkflow(RUN_ID);

            ResponseEntity<?> response = controller.stopWorkflow(RUN_ID, TENANT, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("success")).isEqualTo(true);
            assertThat(body.get("status")).isEqualTo("waiting_trigger");
            assertThat(body.get("runId")).isEqualTo(RUN_ID);
        }

        @Test
        @DisplayName("Returns 200 with status=failed when stopping an already-FAILED run (idempotent)")
        void stopOnAlreadyFailedRunReturns200WithFailedStatus() {
            WorkflowRunEntity failedRun = runEntityWith(RunStatus.FAILED);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                    .thenReturn(Optional.of(failedRun));
            // Idempotent: service does cleanup, doesn't throw, run stays FAILED
            doNothing().when(resumeService).stopWorkflow(RUN_ID);

            ResponseEntity<?> response = controller.stopWorkflow(RUN_ID, TENANT, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("success")).isEqualTo(true);
            // Critical: report the actual current status, not a fake waiting_trigger
            assertThat(body.get("status")).isEqualTo("failed");
        }
    }

    @Nested
    @DisplayName("Not-found and server-error paths")
    class OtherPaths {

        @Test
        @DisplayName("Maps IllegalArgumentException to HTTP 404")
        void illegalArgumentMapsTo404() {
            // In-scope run exists; service still throws IllegalArgumentException
            // (e.g. inconsistent state lookup) - surface as 404.
            WorkflowRunEntity inScope = runEntityWith(RunStatus.RUNNING);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(inScope));
            doThrow(new IllegalArgumentException("Run not found: " + RUN_ID))
                    .when(resumeService).stopWorkflow(RUN_ID);

            ResponseEntity<?> response = controller.stopWorkflow(RUN_ID, TENANT, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Maps unexpected RuntimeException to HTTP 500 with error message")
        void unexpectedExceptionMapsTo500() {
            WorkflowRunEntity inScope = runEntityWith(RunStatus.RUNNING);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(inScope));
            doThrow(new RuntimeException("DB connection lost"))
                    .when(resumeService).stopWorkflow(RUN_ID);

            ResponseEntity<?> response = controller.stopWorkflow(RUN_ID, TENANT, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("error")).asString().contains("Failed to stop workflow");
        }
    }
}
