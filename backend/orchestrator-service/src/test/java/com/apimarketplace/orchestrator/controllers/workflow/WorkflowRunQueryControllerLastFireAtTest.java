package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowRunSummary;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunSummaryProjection;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@code lastFireAt} wiring on
 * {@link WorkflowRunQueryController}.
 *
 * <p>Bug context: for reusable trigger runs (schedule, webhook, datasource,
 * chat, manual) a single run row is reused across many fires. {@code startedAt}
 * is the run's birth time and never advances, so the run history panel
 * displayed "3d ago" for a workflow firing every hour. Fix exposes the
 * {@code started_at} of the most recent epoch header as {@code lastFireAt} on
 * the run summary DTO; the panel falls back to {@code startedAt} when null.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunQueryController - lastFireAt wiring")
class WorkflowRunQueryControllerLastFireAtTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private WorkflowRunStatusService workflowRunStatusService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private StorageService storageService;
    @Mock private WorkflowEpochRepository workflowEpochRepository;

    private WorkflowRunQueryController controller;

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String RUN_ID_PUBLIC = "run-fire-1";
    private static final String OWNER = "tenant-owner";

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

    private WorkflowRunSummaryProjection projection(String runIdPublic, Instant startedAt) {
        return new WorkflowRunSummaryProjection() {
            @Override public UUID getId() { return RUN_UUID; }
            @Override public String getRunIdPublic() { return runIdPublic; }
            @Override public String getTenantId() { return OWNER; }
            @Override public RunStatus getStatus() { return RunStatus.RUNNING; }
            @Override public ExecutionMode getExecutionMode() { return ExecutionMode.AUTOMATIC; }
            @Override public Instant getStartedAt() { return startedAt; }
            @Override public Instant getEndedAt() { return null; }
            @Override public Long getDurationMs() { return null; }
            @Override public Integer getTotalNodes() { return 5; }
            @Override public Integer getPlanVersion() { return 7; }
            @Override public Map<String, Object> getMetadata() { return Map.of(); }
        };
    }

    private WorkflowRunEntity entityWith(Instant startedAt) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID_PUBLIC);
        run.setTenantId(OWNER);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(startedAt);
        return run;
    }

    @Test
    @DisplayName("listRuns threads latest epoch started_at into WorkflowRunSummary.lastFireAt - reusable trigger 'last execution' fix")
    @SuppressWarnings("unchecked")
    void listRunsThreadsLastFireAt() {
        Instant runBirth = Instant.now().minusSeconds(3 * 86_400);   // 3 days ago - startedAt
        Instant lastFire = Instant.now().minusSeconds(3_600);        // 1 hour ago - most recent epoch

        WorkflowRunSummaryProjection p = projection(RUN_ID_PUBLIC, runBirth);
        Page<WorkflowRunSummaryProjection> page = new PageImpl<>(List.of(p), Pageable.ofSize(15), 1);

        when(workflowRunRepository.findRunSummariesByWorkflowIdInScope(
                eq(WORKFLOW_ID), eq(OWNER), any(), any(Pageable.class)))
                .thenReturn(page);
        when(workflowEpochRepository.getMaxEpochByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, 75));
        when(workflowEpochRepository.getLatestEpochStartedAtByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, lastFire));

        ResponseEntity<List<WorkflowRunSummary>> response =
                (ResponseEntity<List<WorkflowRunSummary>>) (ResponseEntity<?>)
                        controller.listRuns(WORKFLOW_ID, 15, 0, OWNER, null);

        List<WorkflowRunSummary> body = response.getBody();
        assertThat(body).hasSize(1);
        WorkflowRunSummary summary = body.get(0);
        assertThat(summary.startedAt()).isEqualTo(runBirth);          // run birth preserved
        assertThat(summary.lastFireAt()).isEqualTo(lastFire);         // last epoch fire exposed
        assertThat(summary.currentEpoch()).isEqualTo(75);
    }

    @Test
    @DisplayName("listRuns leaves lastFireAt null for runs with no fired epoch (single-shot)")
    @SuppressWarnings("unchecked")
    void listRunsLeavesLastFireAtNullWhenNoEpochsFired() {
        Instant runBirth = Instant.now().minusSeconds(60);

        WorkflowRunSummaryProjection p = projection(RUN_ID_PUBLIC, runBirth);
        Page<WorkflowRunSummaryProjection> page = new PageImpl<>(List.of(p), Pageable.ofSize(15), 1);

        when(workflowRunRepository.findRunSummariesByWorkflowIdInScope(
                eq(WORKFLOW_ID), eq(OWNER), any(), any(Pageable.class)))
                .thenReturn(page);
        when(workflowEpochRepository.getMaxEpochByRunIds(anyList())).thenReturn(Map.of());
        when(workflowEpochRepository.getLatestEpochStartedAtByRunIds(anyList())).thenReturn(Map.of());

        ResponseEntity<List<WorkflowRunSummary>> response =
                (ResponseEntity<List<WorkflowRunSummary>>) (ResponseEntity<?>)
                        controller.listRuns(WORKFLOW_ID, 15, 0, OWNER, null);

        WorkflowRunSummary summary = response.getBody().get(0);
        assertThat(summary.startedAt()).isEqualTo(runBirth);
        // No epoch fired → null. Frontend falls back to startedAt for display.
        assertThat(summary.lastFireAt()).isNull();
        assertThat(summary.currentEpoch()).isNull();
    }

    @Test
    @DisplayName("getPinnedRun threads lastFireAt - production card footer reflects most recent epoch")
    void getPinnedRunThreadsLastFireAt() {
        Instant runBirth = Instant.now().minusSeconds(3 * 86_400);
        Instant lastFire = Instant.now().minusSeconds(3_600);
        WorkflowRunEntity entity = entityWith(runBirth);

        ProductionRunResolver.Resolution resolution = new ProductionRunResolver.Resolution(
                Optional.of(entity), ProductionRunResolver.Outcome.FOUND, "wf");
        when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(resolution);
        when(workflowEpochRepository.getMaxEpochByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, 75));
        when(workflowEpochRepository.getLatestEpochStartedAtByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, lastFire));

        ResponseEntity<?> response = controller.getPinnedRun(WORKFLOW_ID);
        WorkflowRunSummary summary = (WorkflowRunSummary) response.getBody();
        assertThat(summary).isNotNull();
        assertThat(summary.startedAt()).isEqualTo(runBirth);
        assertThat(summary.lastFireAt()).isEqualTo(lastFire);
    }

    @Test
    @DisplayName("getLatestRun threads lastFireAt - same fix applies to single-row latest endpoint")
    void getLatestRunThreadsLastFireAt() {
        Instant runBirth = Instant.now().minusSeconds(2 * 86_400);
        Instant lastFire = Instant.now().minusSeconds(1_800);

        WorkflowRunSummaryProjection p = projection(RUN_ID_PUBLIC, runBirth);
        Page<WorkflowRunSummaryProjection> page = new PageImpl<>(List.of(p), Pageable.ofSize(1), 1);

        when(workflowRunRepository.findRunSummariesByWorkflowIdInScope(
                eq(WORKFLOW_ID), eq(OWNER), any(), any(Pageable.class)))
                .thenReturn(page);
        when(workflowEpochRepository.getMaxEpochByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, 12));
        when(workflowEpochRepository.getLatestEpochStartedAtByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, lastFire));

        ResponseEntity<?> response = controller.getLatestRun(WORKFLOW_ID, OWNER, null);
        WorkflowRunSummary summary = (WorkflowRunSummary) response.getBody();
        assertThat(summary).isNotNull();
        assertThat(summary.startedAt()).isEqualTo(runBirth);
        assertThat(summary.lastFireAt()).isEqualTo(lastFire);
    }

    @Test
    @DisplayName("getRunByPublicId threads lastFireAt via mapRunWithEpochLookup")
    void getRunByPublicIdThreadsLastFireAt() {
        Instant runBirth = Instant.now().minusSeconds(2 * 86_400);
        Instant lastFire = Instant.now().minusSeconds(1_200);
        WorkflowRunEntity entity = entityWith(runBirth);

        when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC)).thenReturn(Optional.of(entity));
        when(workflowEpochRepository.getMaxEpochByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, 5));
        when(workflowEpochRepository.getLatestEpochStartedAtByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, lastFire));

        ResponseEntity<?> response = controller.getRunByPublicId(RUN_ID_PUBLIC, OWNER, null);
        WorkflowRunSummary summary = (WorkflowRunSummary) response.getBody();
        assertThat(summary).isNotNull();
        assertThat(summary.startedAt()).isEqualTo(runBirth);
        assertThat(summary.lastFireAt()).isEqualTo(lastFire);
    }

    @Test
    @DisplayName("getApplicationRun threads lastFireAt via mapRunWithEpochLookup")
    void getApplicationRunThreadsLastFireAt() {
        Instant runBirth = Instant.now().minusSeconds(86_400);
        Instant lastFire = Instant.now().minusSeconds(600);
        WorkflowRunEntity entity = entityWith(runBirth);

        String publicationId = "pub-1";
        when(workflowRunRepository
                .findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                        eq(WORKFLOW_ID), eq("application"), eq(publicationId)))
                .thenReturn(Optional.of(entity));
        when(workflowEpochRepository.getMaxEpochByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, 3));
        when(workflowEpochRepository.getLatestEpochStartedAtByRunIds(anyList()))
                .thenReturn(Map.of(RUN_ID_PUBLIC, lastFire));

        ResponseEntity<?> response = controller.getApplicationRun(WORKFLOW_ID, publicationId);
        WorkflowRunSummary summary = (WorkflowRunSummary) response.getBody();
        assertThat(summary).isNotNull();
        assertThat(summary.startedAt()).isEqualTo(runBirth);
        assertThat(summary.lastFireAt()).isEqualTo(lastFire);
    }
}
