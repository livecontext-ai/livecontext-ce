package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.common.storage.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant-isolation tests for the run-list / run-detail endpoints on
 * {@link WorkflowRunQueryController}. Each endpoint MUST 401 on a
 * missing X-User-ID header and 404 on cross-tenant access. Regressions
 * here would re-open the marketplace-preview leak where a publisher's
 * authenticated session walked away with another tenant's run data.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunQueryController - Tenant Guard")
class WorkflowRunQueryControllerTenantGuardTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private WorkflowRunStatusService workflowRunStatusService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    @Mock private StorageService storageService;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowEpochRepository workflowEpochRepository;

    private WorkflowRunQueryController controller;

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String RUN_ID_PUBLIC = "run-public-tenant-1";
    private static final String OWNER = "tenant-owner";
    private static final String INTRUDER = "tenant-intruder";

    @BeforeEach
    void setUp() {
        controller = new WorkflowRunQueryController(
                workflowRunRepository,
                workflowStepDataRepository,
                workflowRunStatusService,
                workflowEpochService,
                productionRunResolver,
                storageService,
                new ObjectMapper(),
                workflowEpochRepository,
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.ApplicationRunVersionBatchService.class)
        );
    }

    private WorkflowRunEntity ownedRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID_PUBLIC);
        run.setTenantId(OWNER);
        run.setStatus(RunStatus.COMPLETED);
        run.setStartedAt(Instant.now());
        return run;
    }

    @Nested
    @DisplayName("listRuns")
    class ListRuns {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.listRuns(WORKFLOW_ID, 15, 0, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(workflowRunRepository, never())
                    .findRunSummariesByWorkflowIdAndTenantId(any(), any(), any());
        }

        @Test
        @DisplayName("query is tenant-scoped - passes caller's tenantId")
        void queryIsTenantScoped() {
            // Returning empty page is fine; we only assert the tenant param made it through.
            when(workflowRunRepository.findRunSummariesByWorkflowIdInScope(
                    eq(WORKFLOW_ID), eq(OWNER), any(), any(Pageable.class)))
                    .thenReturn(org.springframework.data.domain.Page.empty());
            ResponseEntity<?> response = controller.listRuns(WORKFLOW_ID, 15, 0, OWNER, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(workflowRunRepository)
                    .findRunSummariesByWorkflowIdInScope(eq(WORKFLOW_ID), eq(OWNER), any(), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("getLatestRun")
    class GetLatestRun {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.getLatestRun(WORKFLOW_ID, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("getRunByPublicId")
    class GetRunByPublicId {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.getRunByPublicId(RUN_ID_PUBLIC, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(workflowRunRepository, never()).findByRunIdPublic(RUN_ID_PUBLIC);
        }

        @Test
        @DisplayName("404 on cross-tenant access")
        void notFoundOnCrossTenant() {
            when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC))
                    .thenReturn(Optional.of(ownedRun()));
            ResponseEntity<?> response = controller.getRunByPublicId(RUN_ID_PUBLIC, INTRUDER, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("listSteps")
    class ListSteps {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.listSteps(RUN_UUID, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(workflowStepDataRepository, never()).findByWorkflowRunIdOrderByIdAsc(RUN_UUID);
        }

        @Test
        @DisplayName("404 on cross-tenant access")
        void notFoundOnCrossTenant() {
            when(workflowRunRepository.findById(RUN_UUID)).thenReturn(Optional.of(ownedRun()));
            ResponseEntity<?> response = controller.listSteps(RUN_UUID, INTRUDER, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(workflowStepDataRepository, never()).findByWorkflowRunIdOrderByIdAsc(RUN_UUID);
        }
    }

    @Nested
    @DisplayName("listStepsPaged")
    class ListStepsPaged {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.listStepsPaged(RUN_UUID, "alias", 0, 1, null, null, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("404 on cross-tenant access")
        void notFoundOnCrossTenant() {
            when(workflowRunRepository.findById(RUN_UUID)).thenReturn(Optional.of(ownedRun()));
            ResponseEntity<?> response = controller.listStepsPaged(RUN_UUID, "alias", 0, 1, null, null, INTRUDER, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("getStatusCounts")
    class GetStatusCounts {
        @Test
        @DisplayName("401 when X-User-ID is missing")
        void unauthorizedWhenHeaderMissing() {
            ResponseEntity<?> response = controller.getStatusCounts(RUN_ID_PUBLIC, null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("404 on cross-tenant access")
        void notFoundOnCrossTenant() {
            when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC))
                    .thenReturn(Optional.of(ownedRun()));
            ResponseEntity<?> response = controller.getStatusCounts(RUN_ID_PUBLIC, INTRUDER, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
