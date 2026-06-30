package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the 2026-05-13 InspectorPanel "max 100 results" audit finding.
 * Before the fix the controller hard-clamped {@code safeSize} to 100 even when
 * the frontend asked for more - the inspector silently truncated input/output/
 * params columns on workflows with thousands of items per stepAlias (Daily
 * Email Digest: 3490 rows on {@code parse_headers}). The fix raises the cap to
 * 500 so the frontend's useInfiniteQuery pages cleanly without the silent
 * client-side ceiling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunQueryController.listStepsPaged - page-size cap")
class WorkflowRunQueryControllerPageSizeCapTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private WorkflowRunStatusService workflowRunStatusService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private StorageService storageService;
    @Mock private WorkflowEpochRepository workflowEpochRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowRunQueryController controller;

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String TENANT_ID = "tenant-cap";
    private static final String STEP_ALIAS = "mcp:my_step";
    private static final String NORMALIZED_ALIAS = "mcp_my_step";

    @BeforeEach
    void setUp() {
        controller = new WorkflowRunQueryController(
                workflowRunRepository,
                workflowStepDataRepository,
                workflowRunStatusService,
                workflowEpochService,
                productionRunResolver,
                storageService,
                objectMapper,
                workflowEpochRepository,
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.services.ApplicationRunVersionBatchService.class)
        );
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(TENANT_ID);
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }

    private static Page<WorkflowStepDataEntity> emptyPage() {
        return new PageImpl<>(List.of(), Pageable.ofSize(1), 0);
    }

    @Test
    @DisplayName("size=500 reaches the repo unchanged - frontend can pull a full page in one round-trip")
    void sizeFiveHundredPassesThrough() {
        when(workflowStepDataRepository.findByWorkflowRunIdAndStepAliasPagedFiltered(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), any()))
            .thenReturn(emptyPage());

        ResponseEntity<?> response = controller.listStepsPaged(
                RUN_ID, STEP_ALIAS, 0, 500, null, null, TENANT_ID, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(workflowStepDataRepository).findByWorkflowRunIdAndStepAliasPagedFiltered(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), pageable.capture());
        assertThat(pageable.getValue().getPageSize())
            .as("size=500 must survive the safeSize clamp - pre-fix this was silently truncated to 100")
            .isEqualTo(500);
    }

    @Test
    @DisplayName("size=10_000 (oversize) clamps to the 500 cap - defense against an abusive single request")
    void oversizeClampsToFiveHundred() {
        when(workflowStepDataRepository.findByWorkflowRunIdAndStepAliasPagedFiltered(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), any()))
            .thenReturn(emptyPage());

        ResponseEntity<?> response = controller.listStepsPaged(
                RUN_ID, STEP_ALIAS, 0, 10_000, null, null, TENANT_ID, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(workflowStepDataRepository).findByWorkflowRunIdAndStepAliasPagedFiltered(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), pageable.capture());
        assertThat(pageable.getValue().getPageSize())
            .as("Oversize requests must still cap at 500 - bounds the per-request heap footprint")
            .isEqualTo(500);
    }

    @Test
    @DisplayName("size=0 / negative is clamped UP to at least 1 - Spring's Pageable rejects size<1")
    void zeroSizeClampedToOne() {
        when(workflowStepDataRepository.findByWorkflowRunIdAndStepAliasPagedFiltered(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), any()))
            .thenReturn(emptyPage());

        ResponseEntity<?> response = controller.listStepsPaged(
                RUN_ID, STEP_ALIAS, 0, 0, null, null, TENANT_ID, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(workflowStepDataRepository).findByWorkflowRunIdAndStepAliasPagedFiltered(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }
}
