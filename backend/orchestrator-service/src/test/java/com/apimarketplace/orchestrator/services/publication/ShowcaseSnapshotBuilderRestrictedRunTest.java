package com.apimarketplace.orchestrator.services.publication;

import com.apimarketplace.common.publication.ShowcaseCaptureContract;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.StorageSkeletonService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.persistence.StepPayloadService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-066 (Google Limited Use): the marketplace showcase of a listing is a frozen copy of one
 * publisher run, served to anonymous visitors and kept with no retention bound. A run holding
 * Gmail or Google Drive data (RESTRICTED) must never be frozen into it: the capture answers a
 * header-only snapshot marked withheld, so the publication still goes through without a preview.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ShowcaseSnapshotBuilder - LC-066 restricted source run")
class ShowcaseSnapshotBuilderRestrictedRunTest {

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
    @Mock private StepPayloadService stepPayloadService;

    private ShowcaseSnapshotBuilder builder() {
        return new ShowcaseSnapshotBuilder(
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
                new ObjectMapper(),
                stepPayloadService);
    }

    private WorkflowRunEntity run(String runId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic(runId);
        run.setTenantId("tenant-owner");
        when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));
        when(stateSnapshotService.getSnapshot(runId)).thenReturn(StateSnapshot.empty());
        when(workflowResumeService.reconstructStateForApi(runId)).thenReturn(new WorkflowRunState(
                runId, "workflow-1", RunStatus.COMPLETED, ExecutionMode.AUTOMATIC, Instant.EPOCH, null,
                Map.of(), List.of(), List.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of()));
        when(workflowEpochService.listEpochTimestamps(runId)).thenReturn(List.of());
        when(interfaceClient.getSnapshotsForRun(any(), anyString(), any())).thenReturn(List.of());
        return run;
    }

    @Test
    @DisplayName("LC-066: a RESTRICTED run yields a header-only snapshot marked withheld, with nothing the run produced")
    void restrictedRunIsWithheld() {
        run("run-gmail");
        when(stepPayloadService.isRunRestrictedStrict("run-gmail")).thenReturn(true);

        ShowcaseSnapshotBuilder.CaptureOutcome outcome =
                builder().captureWithOutcome("run-gmail", "tenant-owner", null, null);

        assertThat(outcome.epochNotFound()).isFalse();
        Map<String, Object> snapshot = outcome.snapshot().orElseThrow();
        assertThat(snapshot)
                .containsEntry(ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA)
                .containsEntry("sourceRunId", "run-gmail")
                .containsEntry("_sourceTenantId", "tenant-owner")
                .doesNotContainKeys("runState", "aggregatedSteps", "epochSignals", "epochStates",
                        "interfaceRenders", "stepFiles", ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY);
        // Nothing of the run is even read: no state, no render, no file.
        verify(workflowResumeService, never()).reconstructStateForApi(anyString());
        verifyNoInteractions(interfaceRenderService, interfaceClient, workflowStepDataRepository, storageSkeletonService);
    }

    @Test
    @DisplayName("LC-066: a withheld snapshot keeps the chosen epoch so a republish keeps it")
    void restrictedRunKeepsChosenEpoch() {
        run("run-gmail");
        when(workflowEpochService.getEpochHeader("run-gmail", 3))
                .thenReturn(org.mockito.Mockito.mock(com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderRow.class));
        when(stepPayloadService.isRunEpochRestrictedStrict("run-gmail", 3)).thenReturn(true);

        Map<String, Object> snapshot = builder().capture("run-gmail", "tenant-owner", null, 3).orElseThrow();

        assertThat(snapshot)
                .containsEntry(ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA)
                .containsEntry("sourceEpoch", 3);
        verify(interfaceRenderService, never()).render(any(), anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("LC-066 r5-4: a chosen epoch that holds no restricted data is captured even when a LATER epoch made the run restricted")
    void cleanChosenEpochOfALaterRestrictedRunIsCaptured() {
        run("run-later-gmail");
        when(workflowEpochService.getEpochHeader("run-later-gmail", 1))
                .thenReturn(org.mockito.Mockito.mock(com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochHeaderRow.class));
        when(stepPayloadService.isRunRestrictedStrict("run-later-gmail")).thenReturn(true);
        when(stepPayloadService.isRunEpochRestrictedStrict("run-later-gmail", 1)).thenReturn(false);

        Map<String, Object> snapshot = builder().capture("run-later-gmail", "tenant-owner", null, 1).orElseThrow();

        assertThat(snapshot)
                .containsEntry(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true)
                .doesNotContainKey(ShowcaseCaptureContract.WITHHELD_KEY)
                .containsEntry("sourceEpoch", 1);
        verify(stepPayloadService).isRunEpochRestrictedStrict("run-later-gmail", 1);
        verify(stepPayloadService, never()).isRunRestrictedStrict("run-later-gmail");
    }

    @Test
    @DisplayName("LC-066: a run without restricted data is captured as before and stamped as checked")
    void normalRunIsCapturedAndStamped() {
        run("run-plain");
        when(stepPayloadService.isRunRestrictedStrict("run-plain")).thenReturn(false);

        Map<String, Object> snapshot = builder().capture("run-plain", "tenant-owner", null, null).orElseThrow();

        assertThat(snapshot)
                .containsEntry(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true)
                .doesNotContainKey(ShowcaseCaptureContract.WITHHELD_KEY)
                .containsKeys("runState", "aggregatedSteps", "interfaceRenders", "stepFiles");
        verify(workflowResumeService).reconstructStateForApi("run-plain");
    }

    @Test
    @DisplayName("LC-066: when the restriction lookup fails the capture fails, it never freezes a run of unknown class")
    void lookupFailureFailsTheCapture() {
        run("run-unknown");
        when(stepPayloadService.isRunRestrictedStrict("run-unknown"))
                .thenThrow(new IllegalStateException("storage down"));

        assertThatThrownBy(() -> builder().captureWithOutcome("run-unknown", "tenant-owner", null, null))
                .isInstanceOf(IllegalStateException.class);
        verify(workflowResumeService, never()).reconstructStateForApi(anyString());
    }

    @Test
    @DisplayName("LC-066: an out-of-scope run is refused before its class is looked up (no existence oracle)")
    void outOfScopeRunIsNotLookedUp() {
        run("run-other");

        ShowcaseSnapshotBuilder.CaptureOutcome outcome =
                builder().captureWithOutcome("run-other", "tenant-intruder", null, null);

        assertThat(outcome.snapshot()).isEmpty();
        verifyNoInteractions(stepPayloadService);
    }
}
