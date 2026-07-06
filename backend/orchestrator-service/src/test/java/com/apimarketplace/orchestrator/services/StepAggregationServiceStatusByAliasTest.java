package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.AggregatedStepProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StepAggregationService#getAggregatedStatusByAlias} and
 * {@link StepAggregationService#toRunStatus} - the spawn-aware per-alias status feed the
 * all-epochs {@code /state} reconstruction uses to fix the "skipped wins over success" bug.
 *
 * <p>The underlying query {@code getAggregatedStepsByRunId} is spawn-aware (latest spawn per
 * coordinate), so a rerun-superseded COMPLETED row is already excluded before it reaches this
 * service - these tests exercise the count folding + success-over-skipped precedence.
 */
@ExtendWith(MockitoExtension.class)
class StepAggregationServiceStatusByAliasTest {

    @Mock
    private WorkflowStepDataRepository workflowStepDataRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @InjectMocks
    private StepAggregationService service;

    @BeforeEach
    void setUp() {
        // no-op: @InjectMocks wires the @Autowired fields
    }

    // ---- toRunStatus mapping -------------------------------------------------

    @Test
    @DisplayName("toRunStatus maps aggregate strings to RunStatus (error -> FAILED, partial_success -> PARTIAL_SUCCESS)")
    void toRunStatusMapping() {
        assertEquals(RunStatus.COMPLETED, StepAggregationService.toRunStatus("completed"));
        assertEquals(RunStatus.COMPLETED, StepAggregationService.toRunStatus("success"));
        assertEquals(RunStatus.FAILED, StepAggregationService.toRunStatus("error"));
        assertEquals(RunStatus.FAILED, StepAggregationService.toRunStatus("failed"));
        assertEquals(RunStatus.PARTIAL_SUCCESS, StepAggregationService.toRunStatus("partial_success"));
        assertEquals(RunStatus.SKIPPED, StepAggregationService.toRunStatus("skipped"));
        assertEquals(RunStatus.RUNNING, StepAggregationService.toRunStatus("running"));
        assertEquals(RunStatus.AWAITING_SIGNAL, StepAggregationService.toRunStatus("awaiting_signal"));
        assertEquals(RunStatus.PENDING, StepAggregationService.toRunStatus("pending"));
        assertEquals(RunStatus.PENDING, StepAggregationService.toRunStatus(null));
        assertEquals(RunStatus.PENDING, StepAggregationService.toRunStatus("  "));
    }

    // ---- getAggregatedStatusByAlias ------------------------------------------

    @Test
    @DisplayName("multi-epoch success-with-skips folds to COMPLETED (3 completed + 8 skipped -> COMPLETED)")
    void multiEpochSuccessFoldsToCompleted() {
        when(workflowStepDataRepository.getAggregatedStepsByRunId("run-1")).thenReturn(List.of(
                row("fetch", "COMPLETED", 3L),
                row("fetch", "SKIPPED", 8L)
        ));

        Map<String, RunStatus> statuses = service.getAggregatedStatusByAlias("run-1");

        assertEquals(RunStatus.COMPLETED, statuses.get("fetch"),
                "success must win over skipped in the accumulated multi-epoch view");
    }

    @Test
    @DisplayName("all-skipped alias folds to SKIPPED (never-taken branch stays grey)")
    void allSkippedFoldsToSkipped() {
        when(workflowStepDataRepository.getAggregatedStepsByRunId("run-1")).thenReturn(List.of(
                row("never", "SKIPPED", 11L)
        ));

        Map<String, RunStatus> statuses = service.getAggregatedStatusByAlias("run-1");

        assertEquals(RunStatus.SKIPPED, statuses.get("never"));
    }

    @Test
    @DisplayName("mixed success + failure folds to PARTIAL_SUCCESS")
    void mixedSuccessFailureFoldsToPartial() {
        when(workflowStepDataRepository.getAggregatedStepsByRunId("run-1")).thenReturn(List.of(
                row("call", "COMPLETED", 2L),
                row("call", "FAILED", 1L),
                row("call", "SKIPPED", 5L)
        ));

        Map<String, RunStatus> statuses = service.getAggregatedStatusByAlias("run-1");

        assertEquals(RunStatus.PARTIAL_SUCCESS, statuses.get("call"));
    }

    @Test
    @DisplayName("all-failed folds to FAILED even with skips")
    void allFailedFoldsToFailed() {
        when(workflowStepDataRepository.getAggregatedStepsByRunId("run-1")).thenReturn(List.of(
                row("call", "FAILED", 4L),
                row("call", "SKIPPED", 2L)
        ));

        Map<String, RunStatus> statuses = service.getAggregatedStatusByAlias("run-1");

        assertEquals(RunStatus.FAILED, statuses.get("call"));
    }

    @Test
    @DisplayName("empty result -> empty map (no rows for run)")
    void emptyResultYieldsEmptyMap() {
        when(workflowStepDataRepository.getAggregatedStepsByRunId("run-1")).thenReturn(List.of());

        assertTrue(service.getAggregatedStatusByAlias("run-1").isEmpty());
    }

    private static AggregatedStepProjection row(String alias, String status, Long count) {
        return new AggregatedStepProjection() {
            @Override public String getStepAlias() { return alias; }
            @Override public String getStatus() { return status; }
            @Override public Long getCount() { return count; }
            @Override public String getToolId() { return null; }
            @Override public Object getMinStartTime() { return null; }
            @Override public Object getMaxEndTime() { return null; }
            @Override public Long getSumExecutionTimeMs() { return null; }
        };
    }
}
