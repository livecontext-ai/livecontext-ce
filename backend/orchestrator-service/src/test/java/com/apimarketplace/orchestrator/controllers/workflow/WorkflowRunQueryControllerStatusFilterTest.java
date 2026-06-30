package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowStepDataSummary;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the {@code status} query parameter on
 * {@code GET /api/workflows/runs/{runId}/steps/paged}. Before the fix the
 * inspector's {@code RunOutputPreview} filtered status client-side after
 * fetching, so {@code totalElements} reflected the unfiltered set and the
 * filter "felt client-side". The fix forwards status to this controller so
 * pagination / counts match the filtered result, just like the existing epoch
 * filter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunQueryController.listStepsPaged - status filter")
class WorkflowRunQueryControllerStatusFilterTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private WorkflowRunStatusService workflowRunStatusService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private StorageService storageService;
    @Mock private WorkflowEpochRepository workflowEpochRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowRunQueryController controller;

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TENANT_ID = "tenant-test";
    private static final String STEP_ALIAS = "mcp:my_step";
    // LabelNormalizer.normalizeLabel converts "mcp:my_step" → "mcp_my_step" (replaces ':' with '_')
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

    private WorkflowStepDataEntity entity(long id, String status, int epoch) {
        WorkflowStepDataEntity e = new WorkflowStepDataEntity();
        e.setId(id);
        e.setStepAlias(STEP_ALIAS);
        e.setStatus(status);
        e.setEpoch(epoch);
        e.setTenantId(TENANT_ID);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<WorkflowStepDataSummary> content(ResponseEntity<?> response) {
        return (List<WorkflowStepDataSummary>) body(response).get("content");
    }

    private static Page<WorkflowStepDataEntity> page(List<WorkflowStepDataEntity> rows) {
        return new PageImpl<>(rows, Pageable.ofSize(rows.isEmpty() ? 1 : rows.size()), rows.size());
    }

    @Test
    @DisplayName("Filters by canonical status BEFORE pagination so totalElements reflects the filtered set")
    void statusFilterAppliesBeforePagination() {
        // After Phase 2: filter pushed into JPQL, repo returns the already-filtered page.
        // Total of 4 rows in the run, 2 match status='completed'. Caller is the
        // status-IN repo method (rawStatuses non-empty for canonical 'completed').
        when(workflowStepDataRepository.findByWorkflowRunIdAndStepAliasAndStatusInPaged(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), any(), any()))
            .thenReturn(page(List.of(entity(1L, "completed", 0), entity(3L, "completed", 0))));

        ResponseEntity<?> response = controller.listStepsPaged(
                RUN_ID, STEP_ALIAS, 0, 50, null, "completed", TENANT_ID, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(body(response)).containsEntry("totalElements", 2L);
        assertThat(content(response)).hasSize(2);
    }

    @Test
    @DisplayName("Canonical 'completed' matches BOTH 'completed' and 'success' raw rows")
    void completedExpansionMatchesSuccessRows() {
        when(workflowStepDataRepository.findByWorkflowRunIdAndStepAliasAndStatusInPaged(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), any(), any()))
            .thenReturn(page(List.of(entity(1L, "completed", 0), entity(2L, "success", 0))));

        ResponseEntity<?> response = controller.listStepsPaged(
                RUN_ID, STEP_ALIAS, 0, 50, null, "completed", TENANT_ID, null);

        assertThat(body(response)).containsEntry("totalElements", 2L);
    }

    @Test
    @DisplayName("Combines status + epoch filters with AND semantics")
    void statusAndEpochCombineWithAnd() {
        when(workflowStepDataRepository.findByWorkflowRunIdAndStepAliasAndStatusInPaged(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(1), any(), any()))
            .thenReturn(page(List.of(entity(2L, "completed", 1), entity(4L, "success", 1))));

        ResponseEntity<?> response = controller.listStepsPaged(
                RUN_ID, STEP_ALIAS, 0, 50, 1, "completed", TENANT_ID, null);

        assertThat(body(response)).containsEntry("totalElements", 2L);
    }

    @Test
    @DisplayName("Treats null/blank status as 'no filter' so every alias-matching row is returned")
    void blankStatusIsNoFilter() {
        // No status filter → repo path WITHOUT IN clause.
        when(workflowStepDataRepository.findByWorkflowRunIdAndStepAliasPagedFiltered(
                eq(RUN_ID), eq(NORMALIZED_ALIAS), eq(null), any()))
            .thenReturn(page(List.of(entity(1L, "completed", 0), entity(2L, "failed", 0))));

        ResponseEntity<?> withNull = controller.listStepsPaged(
                RUN_ID, STEP_ALIAS, 0, 50, null, null, TENANT_ID, null);
        ResponseEntity<?> withBlank = controller.listStepsPaged(
                RUN_ID, STEP_ALIAS, 0, 50, null, "  ", TENANT_ID, null);

        assertThat(body(withNull)).containsEntry("totalElements", 2L);
        assertThat(body(withBlank)).containsEntry("totalElements", 2L);
    }

    @Test
    @DisplayName("Phase 2 regression: listSteps uses lightweight projection - heavy JSONB never materialised under frontend polling")
    void listStepsUsesLightweightProjection() {
        // Pin the OOM mitigation. Before Phase 2, listSteps loaded
        // findByWorkflowRunIdOrderByIdAsc which retained 207 741 ManagedEntityImpl + 798 MB
        // byte[] JSONB on the prod 2026-05-07 12:40 UTC heap dump under frontend polling.
        // Phase 2 switches to findByWorkflowRunIdLightweightAll which nulls the heavy JSONB
        // columns (input_data, metadata, merge_received_branches, merge_skipped_branches).
        when(workflowStepDataRepository.findByWorkflowRunIdLightweightAll(RUN_ID))
            .thenReturn(List.of(entity(1L, "completed", 0)));

        controller.listSteps(RUN_ID, TENANT_ID, null);

        // The legacy heavy-JSONB query must NEVER be used by listSteps anymore.
        org.mockito.Mockito.verify(workflowStepDataRepository, org.mockito.Mockito.never())
            .findByWorkflowRunIdOrderByIdAsc(any(UUID.class));
    }

    @Test
    @DisplayName("Phase 2 regression: filtering happens DB-side - repo receives canonical→raw expansion, not raw filter pass-through")
    void statusFilterPushedToJpqlAsRawList() {
        // Pin the contract that PROVES the OOM-fix: the controller must NOT load the entire
        // run and filter in memory. Previously listStepsPaged called
        // findByWorkflowRunIdOrderByIdAsc(runId) which materialised every row in the run
        // (~17 000 on prod 2026-05-07 12:40 UTC) just to keep one alias subset. The Phase 2
        // fix delegates filter+page to the DB via the IN-clause repo method, and forbids the
        // legacy load-all path.
        when(workflowStepDataRepository.findByWorkflowRunIdAndStepAliasAndStatusInPaged(
                any(UUID.class), any(String.class), any(), any(), any()))
            .thenReturn(page(List.of(entity(1L, "completed", 0))));

        controller.listStepsPaged(RUN_ID, STEP_ALIAS, 0, 50, null, "completed", TENANT_ID, null);

        // Legacy in-memory path must NEVER be invoked from listStepsPaged anymore.
        org.mockito.Mockito.verify(workflowStepDataRepository, org.mockito.Mockito.never())
            .findByWorkflowRunIdOrderByIdAsc(any(UUID.class));
    }
}
