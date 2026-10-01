package com.apimarketplace.orchestrator.services.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.StorageSkeletonService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the security scrub on the showcase snapshot. The marketplace
 * surface is anonymous, so steps[].inputData must be dropped and any key
 * matching a credential hint inside steps[].output must be redacted before
 * the JSONB is exposed.
 *
 * <p>Tests the static scrub helpers directly via reflection - they are
 * package-private intentionally so production callers go through
 * {@link ShowcaseSnapshotBuilder#capture}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShowcaseSnapshotBuilder - credential scrub")
class ShowcaseSnapshotBuilderScrubTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowResumeService workflowResumeService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private StepAggregationService stepAggregationService;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private InterfaceRenderService interfaceRenderService;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private StorageSkeletonService storageSkeletonService;

    @Test
    @DisplayName("scrubMap redacts values whose key matches a credential hint, recursively")
    void scrubMapRedactsCredentials() throws Exception {
        Method scrub = ShowcaseSnapshotBuilder.class.getDeclaredMethod("scrubMap", Object.class);
        scrub.setAccessible(true);

        Map<String, Object> input = Map.of(
                "result", "ok",
                "auth_token", "Bearer xyz",
                "nested", Map.of("api_key", "sk_xxx", "harmless", 42),
                "list", List.of(Map.of("password", "secret-pass", "name", "alice"))
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) scrub.invoke(null, input);
        assertThat(out.get("result")).isEqualTo("ok");
        assertThat(out.get("auth_token")).isEqualTo("[redacted]");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) out.get("nested");
        assertThat(nested.get("api_key")).isEqualTo("[redacted]");
        assertThat(nested.get("harmless")).isEqualTo(42);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("list");
        assertThat(list.get(0).get("password")).isEqualTo("[redacted]");
        assertThat(list.get(0).get("name")).isEqualTo("alice");
    }

    @Test
    @DisplayName("looksSensitive flags well-known credential key names case-insensitively")
    void looksSensitiveFlagsCommonKeys() {
        for (String good : List.of("password", "Bearer", "X-Api-Key", "refresh_token", "client_secret",
                                    "apiKey", "privateKey", "accessToken", "credentials")) {
            assertThat(ShowcaseSnapshotBuilder.looksSensitive(good)).as("should flag %s", good).isTrue();
        }
        for (String safe : List.of("name", "email_address", "title", "url", "count",
                                    "maxTokens", "tokenLimit", "totalTokens", "sessionId")) {
            assertThat(ShowcaseSnapshotBuilder.looksSensitive(safe)).as("should NOT flag %s", safe).isFalse();
        }
    }

    @Test
    @DisplayName("capture rejects personal caller for org run owned by same tenant")
    void captureRejectsPersonalCallerForOwnedOrgRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic("run-org");
        run.setTenantId("tenant-owner");
        run.setOrganizationId("org-acme");
        when(workflowRunRepository.findByRunIdPublic("run-org")).thenReturn(Optional.of(run));

        ShowcaseSnapshotBuilder builder = new ShowcaseSnapshotBuilder(
                workflowRunRepository,
                workflowResumeService,
                stateSnapshotService,
                workflowEpochService,
                stepAggregationService,
                signalWaitRepository,
                interfaceRenderService,
                interfaceClient,
                workflowStepDataRepository,
                storageSkeletonService,
                new ObjectMapper());

        ShowcaseSnapshotBuilder.CaptureOutcome outcome = builder.captureWithOutcome("run-org", "tenant-owner", null, null);

        assertThat(outcome.snapshot()).isEmpty();
        assertThat(outcome.epochNotFound()).as("a scope mismatch is never reported as a missing epoch").isFalse();
        verify(interfaceClient, never()).getSnapshotsForRun(run.getId(), "tenant-owner", null);
    }

    @Test
    @DisplayName("capture forwards organization scope when listing interface run snapshots")
    void captureForwardsOrganizationScopeToInterfaceSnapshots() {
        UUID workflowRunUuid = UUID.randomUUID();
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", workflowRunUuid);
        run.setRunIdPublic("run-org");
        run.setTenantId("tenant-owner");
        run.setOrganizationId("org-acme");
        when(workflowRunRepository.findByRunIdPublic("run-org")).thenReturn(Optional.of(run));
        when(workflowResumeService.reconstructStateForApi("run-org"))
                .thenReturn(new WorkflowRunState(
                        "run-org",
                        "workflow-1",
                        RunStatus.COMPLETED,
                        ExecutionMode.AUTOMATIC,
                        Instant.EPOCH,
                        null,
                        Map.of(),
                        List.of(),
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of()));
        DagState dag = new DagState(2, 0, 1, Map.of(
                2, EpochState.fresh()
        ), Set.of());
        when(stateSnapshotService.getSnapshot("run-org"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:start", dag));
        when(workflowEpochService.listEpochTimestamps("run-org")).thenReturn(List.of());
        when(interfaceClient.getSnapshotsForRun(workflowRunUuid, "tenant-caller", "org-acme"))
                .thenReturn(List.of());

        ShowcaseSnapshotBuilder builder = new ShowcaseSnapshotBuilder(
                workflowRunRepository,
                workflowResumeService,
                stateSnapshotService,
                workflowEpochService,
                stepAggregationService,
                signalWaitRepository,
                interfaceRenderService,
                interfaceClient,
                workflowStepDataRepository,
                storageSkeletonService,
                new ObjectMapper());

        Optional<Map<String, Object>> snapshot = builder.capture("run-org", "tenant-caller", "org-acme", 2);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get()).containsEntry("sourceEpoch", 2);
        verify(interfaceClient).getSnapshotsForRun(workflowRunUuid, "tenant-caller", "org-acme");
    }

    @Test
    @DisplayName("epoch-filtered capture builds runState steps from epoch aggregation, not latest flat output")
    void epochFilteredCaptureUsesEpochAggregationInsteadOfLatestFlatOutput() {
        UUID workflowRunUuid = UUID.randomUUID();
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", workflowRunUuid);
        run.setRunIdPublic("run-epochs");
        run.setTenantId("tenant-owner");
        run.setOrganizationId("org-acme");
        when(workflowRunRepository.findByRunIdPublic("run-epochs")).thenReturn(Optional.of(run));

        WorkflowRunState.StepState latestFlatStep = step("node-a", RunStatus.FAILED);
        when(workflowResumeService.reconstructStateForApi("run-epochs"))
                .thenReturn(new WorkflowRunState(
                        "run-epochs",
                        "workflow-1",
                        RunStatus.COMPLETED,
                        ExecutionMode.AUTOMATIC,
                        Instant.EPOCH,
                        null,
                        Map.of(),
                        List.of(latestFlatStep),
                        List.of(new WorkflowRunState.EdgeState("trigger:start", "node-a", RunStatus.COMPLETED, 9, 0, 9)),
                        Set.of(),
                        Set.of(),
                        Set.of("node-a"),
                        Set.of(),
                        Map.of()));
        DagState dag = new DagState(2, 0, 2, Map.of(
                1, new EpochState(Set.of("node-a"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                        Map.of(), Map.of(), Map.of(), Instant.EPOCH),
                2, new EpochState(Set.of(), Set.of("node-a"), Set.of(), Set.of(), Set.of(), Set.of(),
                        Map.of(), Map.of(), Map.of(), Instant.EPOCH)
        ), Set.of());
        when(stateSnapshotService.getSnapshot("run-epochs"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:start", dag));
        Instant epochStart = Instant.parse("2026-05-26T10:00:00Z");
        Instant epochEnd = Instant.parse("2026-05-26T10:00:03Z");
        when(stepAggregationService.getAggregatedSteps("run-epochs", 2))
                .thenReturn(Optional.of(List.of(new StepAggregationService.AggregatedStep(
                        "node-a",
                        "error",
                        "tool",
                        epochStart,
                        epochEnd,
                        Map.of("failed", 1),
                        123L
                ))));
        when(workflowEpochService.listEpochTimestamps("run-epochs")).thenReturn(List.of());
        when(interfaceClient.getSnapshotsForRun(workflowRunUuid, "tenant-caller", "org-acme"))
                .thenReturn(List.of());

        ShowcaseSnapshotBuilder builder = new ShowcaseSnapshotBuilder(
                workflowRunRepository,
                workflowResumeService,
                stateSnapshotService,
                workflowEpochService,
                stepAggregationService,
                signalWaitRepository,
                interfaceRenderService,
                interfaceClient,
                workflowStepDataRepository,
                storageSkeletonService,
                new ObjectMapper());

        Optional<Map<String, Object>> snapshot = builder.capture("run-epochs", "tenant-caller", "org-acme", 2);

        assertThat(snapshot).isPresent();
        // The tenant that OWNS the run, which here is not the caller. publication-service
        // uses it to tell a file the publication may legitimately copy from one a publisher
        // merely named in a hand-written value, so the capture has to state it: nobody
        // downstream can work it out, and inferring it from the path being checked is what
        // the guard exists to avoid.
        assertThat(snapshot.get()).containsEntry("_sourceTenantId", "tenant-owner");
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) snapshot.get().get("runState");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) runState.get("steps");
        assertThat(steps).extracting(step -> step.get("stepId")).containsExactly("node-a");
        assertThat(steps.getFirst()).containsEntry("output", null);
        assertThat(steps.getFirst()).containsEntry("startTime", epochStart);
        assertThat(steps.getFirst()).containsEntry("endTime", epochEnd);
        assertThat(steps.getFirst()).containsEntry("executionTimeMs", 123L);
        assertThat(steps.getFirst()).containsEntry("status", RunStatus.FAILED);
        assertThat((Set<String>) runState.get("failedStepIds")).containsExactly("node-a");
        @SuppressWarnings("unchecked")
        Map<String, Integer> statusCounts = (Map<String, Integer>) steps.getFirst().get("statusCounts");
        assertThat(statusCounts).containsEntry("failed", 1);
    }

    @Test
    @DisplayName("renumbering a showcase epoch keeps its measured execution window")
    void renumberedEpochKeepsItsWorkDuration() {
        // Publishing a single epoch renumbers it to 1. Only the NUMBER is meant to
        // change: the previous version rebuilt the row field by field through
        // reflection, so a field it did not know about (workDurationMs, the only one
        // saying how long the epoch executed) vanished and the showcase fell back to
        // the header span, which counts the epoch's idle tail (32h42m on prod).
        UUID workflowRunUuid = UUID.randomUUID();
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", workflowRunUuid);
        run.setRunIdPublic("run-renumber");
        run.setTenantId("tenant-owner");
        run.setOrganizationId("org-acme");
        when(workflowRunRepository.findByRunIdPublic("run-renumber")).thenReturn(Optional.of(run));
        when(workflowResumeService.reconstructStateForApi("run-renumber"))
                .thenReturn(new WorkflowRunState(
                        "run-renumber",
                        "workflow-1",
                        RunStatus.COMPLETED,
                        ExecutionMode.AUTOMATIC,
                        Instant.EPOCH,
                        null,
                        Map.of(),
                        List.of(),
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of()));

        DagState dag = new DagState(7, 0, 7, Map.of(7, EpochState.fresh()), Set.of());
        when(stateSnapshotService.getSnapshot("run-renumber"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:start", dag));
        when(stepAggregationService.getAggregatedSteps("run-renumber", 7))
                .thenReturn(Optional.of(List.of()));
        when(workflowEpochService.listEpochTimestamps("run-renumber")).thenReturn(List.of(
                new WorkflowEpochRepository.EpochTimestampRow(
                        6, "2026-08-01T09:00:00Z", "2026-08-01T09:00:04Z", 4_000L),
                new WorkflowEpochRepository.EpochTimestampRow(
                        7, "2026-08-02T03:18:00Z", "2026-08-02T12:00:00Z", 12_000L)));
        when(interfaceClient.getSnapshotsForRun(workflowRunUuid, "tenant-caller", "org-acme"))
                .thenReturn(List.of());

        ShowcaseSnapshotBuilder builder = new ShowcaseSnapshotBuilder(
                workflowRunRepository,
                workflowResumeService,
                stateSnapshotService,
                workflowEpochService,
                stepAggregationService,
                signalWaitRepository,
                interfaceRenderService,
                interfaceClient,
                workflowStepDataRepository,
                storageSkeletonService,
                new ObjectMapper());

        Optional<Map<String, Object>> snapshot = builder.capture("run-renumber", "tenant-caller", "org-acme", 7);

        assertThat(snapshot).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) snapshot.get().get("runState");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> epochs = (List<Map<String, Object>>) runState.get("epochTimestamps");
        // Only the published epoch survives, renumbered, with its window intact. Its
        // header span is 8h42m; the figure that must travel is the 12s of work.
        assertThat(epochs).hasSize(1);
        assertThat(epochs.getFirst()).containsEntry("epoch", 1);
        assertThat(epochs.getFirst()).containsEntry("workDurationMs", 12_000L);
        assertThat(epochs.getFirst()).containsEntry("startedAt", "2026-08-02T03:18:00Z");
        assertThat(epochs.getFirst()).containsEntry("endedAt", "2026-08-02T12:00:00Z");
    }

    @Test
    @DisplayName("capture rejects selected showcase epoch when the run has no such epoch")
    void captureRejectsMissingSelectedEpoch() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic("run-invalid-epoch");
        run.setTenantId("tenant-owner");
        run.setOrganizationId("org-acme");
        when(workflowRunRepository.findByRunIdPublic("run-invalid-epoch")).thenReturn(Optional.of(run));
        DagState dag = new DagState(1, 0, 1, Map.of(
                1, EpochState.fresh()
        ), Set.of());
        when(stateSnapshotService.getSnapshot("run-invalid-epoch"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:start", dag));
        when(workflowEpochService.listEpochTimestamps("run-invalid-epoch")).thenReturn(List.of());
        when(stepAggregationService.getAggregatedSteps("run-invalid-epoch", 99))
                .thenReturn(Optional.of(List.of()));

        ShowcaseSnapshotBuilder builder = new ShowcaseSnapshotBuilder(
                workflowRunRepository,
                workflowResumeService,
                stateSnapshotService,
                workflowEpochService,
                stepAggregationService,
                signalWaitRepository,
                interfaceRenderService,
                interfaceClient,
                workflowStepDataRepository,
                storageSkeletonService,
                new ObjectMapper());

        ShowcaseSnapshotBuilder.CaptureOutcome outcome =
                builder.captureWithOutcome("run-invalid-epoch", "tenant-caller", "org-acme", 99);

        assertThat(outcome.snapshot()).isEmpty();
        // Bug A7: the one miss the publisher can correct is named, so publication-service can answer
        // "that epoch is not in the run" instead of a 500 "empty payload".
        assertThat(outcome.epochNotFound()).isTrue();
        verify(workflowResumeService, never()).reconstructStateForApi("run-invalid-epoch");
    }

    @Test
    @DisplayName("A failing epoch probe is a server error, never EPOCH_NOT_FOUND (a DB hiccup must not say 'pick another epoch')")
    void failingEpochProbeIsNotReportedAsMissingEpoch() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic("run-probe-down");
        run.setTenantId("tenant-owner");
        run.setOrganizationId("org-acme");
        when(workflowRunRepository.findByRunIdPublic("run-probe-down")).thenReturn(Optional.of(run));
        DagState dag = new DagState(1, 0, 1, Map.of(1, EpochState.fresh()), Set.of());
        when(stateSnapshotService.getSnapshot("run-probe-down"))
                .thenReturn(StateSnapshot.empty().withDagState("trigger:start", dag));
        when(workflowEpochService.listEpochTimestamps("run-probe-down"))
                .thenThrow(new RuntimeException("connection reset"));
        when(stepAggregationService.getAggregatedSteps("run-probe-down", 99)).thenReturn(Optional.of(List.of()));

        ShowcaseSnapshotBuilder builder = new ShowcaseSnapshotBuilder(
                workflowRunRepository, workflowResumeService, stateSnapshotService, workflowEpochService,
                stepAggregationService, signalWaitRepository, interfaceRenderService, interfaceClient,
                workflowStepDataRepository, storageSkeletonService, new ObjectMapper());

        assertThatThrownBy(() -> builder.captureWithOutcome("run-probe-down", "tenant-caller", "org-acme", 99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("epoch probe failed");
    }

    private static WorkflowRunState.StepState step(String nodeId, RunStatus status) {
        return new WorkflowRunState.StepState(
                nodeId,
                nodeId,
                "tool",
                status,
                Map.of("apiKey", "secret"),
                Map.of("result", nodeId),
                null,
                null,
                200,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                1,
                Set.of(),
                false,
                Map.of("COMPLETED", 2));
    }
}
