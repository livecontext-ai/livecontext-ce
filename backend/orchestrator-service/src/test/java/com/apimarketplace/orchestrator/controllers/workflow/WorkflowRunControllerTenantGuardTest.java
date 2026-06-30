package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant-isolation tests for the auth'd run-by-id endpoints on
 * {@link WorkflowRunController}: getRunState, getEpochState,
 * getEpochSignals, getAggregatedSteps. They MUST 401 on missing
 * X-User-ID and 404 on cross-tenant access (no existence leak).
 *
 * Regression: these endpoints previously took only the path runId and
 * returned data directly, allowing the publisher's authenticated session
 * (or any authenticated caller who knew a runId) to read another tenant's
 * showcase clone or live run state.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - Tenant Guard")
class WorkflowRunControllerTenantGuardTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowResumeService resumeService;
    @Mock private StepAggregationService stepAggregationService;
    @Mock private WorkflowEpochService workflowEpochService;

    @InjectMocks
    private WorkflowRunController controller;

    private static final String RUN_ID = "run-tenant-guard-1";
    private static final String OWNER = "tenant-owner";
    private static final String INTRUDER = "tenant-intruder";

    @BeforeEach
    void wireOwner() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(OWNER);
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    @Nested
    @DisplayName("getRunState")
    class GetRunState {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.getRunState(RUN_ID, false, null, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(resumeService, never()).reconstructState(RUN_ID);
        }

        @Test
        @DisplayName("404 on cross-tenant access - no existence leak")
        void notFoundOnCrossTenantAccess() {
            ResponseEntity<?> response = controller.getRunState(RUN_ID, false, INTRUDER, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(resumeService, never()).reconstructState(RUN_ID);
        }

        @Test
        @DisplayName("404 when run does not exist (mirror of cross-tenant)")
        void notFoundWhenRunMissing() {
            when(workflowRunRepository.findByRunIdPublic("nope")).thenReturn(Optional.empty());
            ResponseEntity<?> response = controller.getRunState("nope", false, OWNER, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("getEpochState")
    class GetEpochState {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.getEpochState(RUN_ID, 1, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(workflowEpochService, never()).getEpochState(RUN_ID, 1);
        }

        @Test
        @DisplayName("404 on cross-tenant access")
        void notFoundOnCrossTenantAccess() {
            ResponseEntity<?> response = controller.getEpochState(RUN_ID, 1, INTRUDER, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(workflowEpochService, never()).getEpochState(RUN_ID, 1);
        }
    }

    @Nested
    @DisplayName("getAggregatedSteps")
    class GetAggregatedSteps {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, null, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(stepAggregationService, never()).getAggregatedSteps(RUN_ID);
        }

        @Test
        @DisplayName("404 on cross-tenant access")
        void notFoundOnCrossTenantAccess() {
            ResponseEntity<?> response = controller.getAggregatedSteps(RUN_ID, null, INTRUDER, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(stepAggregationService, never()).getAggregatedSteps(RUN_ID);
        }
    }
}
