package com.apimarketplace.publication.service;

import com.apimarketplace.common.publication.ShowcaseCaptureContract;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient.RunRestriction;
import com.apimarketplace.publication.config.OrchestratorInternalClient.RunRestrictionAnswer;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-066 (Google Limited Use), serve-time defence: a showcase snapshot from a run holding Gmail or
 * Google Drive data is never served to marketplace visitors, including snapshots stored before the
 * capture learned to withhold them. And the converse: a showcase whose run never read a mailbox is
 * not withheld (or, worse, permanently replaced) because its PLAN names Gmail.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShowcaseRestrictionGuard - LC-066 serve-time showcase guard")
class ShowcaseRestrictionGuardTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;

    private ShowcaseRestrictionGuard guard() {
        return new ShowcaseRestrictionGuard(publicationRepository, orchestratorClient);
    }

    private static WorkflowPublicationEntity publicationWith(Map<String, Object> snapshot) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setShowcaseSnapshot(snapshot);
        return pub;
    }

    /** A snapshot as captured before the restriction check existed: neither marker nor stamp. */
    private static Map<String, Object> legacySnapshot(Map<String, Object> runState) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", 1);
        snapshot.put("capturedAt", "2026-09-01T10:00:00Z");
        snapshot.put("sourceRunId", "run_legacy");
        snapshot.put("_sourceTenantId", "tenant-owner");
        snapshot.put("runState", runState);
        snapshot.put("interfaceRenders", Map.of("iface-1", Map.of("items", List.of(Map.of("data", Map.of("subject", "Hi"))))));
        return snapshot;
    }

    /**
     * The run state of a workflow whose plan holds a Gmail node (on a branch that was skipped) and
     * an agent with Gmail granted but unused: the plan names Gmail, nothing the run produced does.
     */
    private static Map<String, Object> planNamingGmailButNotRun() {
        return Map.of(
                "plan", Map.of(
                        "mcps", List.of(Map.of("id", "mcp:read_inbox", "iconSlug", "gmail", "apiSlug", "gmail")),
                        "agents", List.of(Map.of("id", "agent:helper", "tools", List.of(
                                Map.of("apiSlug", "gmail", "toolSlug", "list_messages"),
                                Map.of("apiSlug", "google_drive", "toolSlug", "list_files"))))),
                "steps", List.of(
                        Map.of("stepAlias", "read_inbox", "status", "SKIPPED"),
                        Map.of("stepAlias", "fetch_weather", "status", "COMPLETED",
                                "output", Map.of("temperature", 21, "metadata", Map.of("iconSlug", "openweather")))));
    }

    /** A run state whose step output came from Gmail (named by the integration, not tagged). */
    private static Map<String, Object> stepOutputFromGmail() {
        return Map.of("steps", List.of(Map.of(
                "stepAlias", "list_mail",
                "output", Map.of("messages", List.of(Map.of("subject", "Payroll")),
                        "metadata", Map.of("iconSlug", "gmail")))));
    }

    /** The legacy snapshots above are captured at 2026-09-01T10:00Z; this run was restricted a month earlier. */
    private static final java.time.Instant RESTRICTED_BEFORE_CAPTURE = java.time.Instant.parse("2026-08-01T00:00:00Z");

    /** The orchestrator's answer; a RESTRICTED run was restricted before the legacy capture. */
    private static RunRestrictionAnswer answer(RunRestriction status) {
        return new RunRestrictionAnswer(status, status == RunRestriction.RESTRICTED ? RESTRICTED_BEFORE_CAPTURE : null);
    }

    private void stubReload(WorkflowPublicationEntity pub, Map<String, Object> stored) {
        WorkflowPublicationEntity fresh = new WorkflowPublicationEntity();
        fresh.setId(pub.getId());
        fresh.setShowcaseSnapshot(stored);
        when(publicationRepository.findById(pub.getId())).thenReturn(Optional.of(fresh));
    }

    private Map<String, Object> savedSnapshot() {
        ArgumentCaptor<WorkflowPublicationEntity> saved = ArgumentCaptor.forClass(WorkflowPublicationEntity.class);
        verify(publicationRepository).save(saved.capture());
        return saved.getValue().getShowcaseSnapshot();
    }

    @Test
    @DisplayName("LC-066: a snapshot the capture marked withheld is never served, with no lookup")
    void withheldMarkerIsHonoured() {
        WorkflowPublicationEntity pub = publicationWith(Map.of(
                "version", 1, "sourceRunId", "run_gmail",
                ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA));

        assertThat(guard().withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
        verifyNoInteractions(orchestratorClient, publicationRepository);
    }

    @Test
    @DisplayName("LC-066: a snapshot stamped checked at capture is served, with no lookup")
    void checkedSnapshotIsServed() {
        WorkflowPublicationEntity pub = publicationWith(Map.of(
                "version", 1, "sourceRunId", "run_plain", ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true));

        assertThat(guard().withheldReason(pub)).isNull();
        verifyNoInteractions(orchestratorClient, publicationRepository);
    }

    @Test
    @DisplayName("LC-066: no snapshot at all is not this guard's answer (the caller reports the missing snapshot)")
    void missingSnapshotIsNotWithheld() {
        assertThat(guard().withheldReason(publicationWith(null))).isNull();
        verifyNoInteractions(orchestratorClient, publicationRepository);
    }

    @Test
    @DisplayName("LC-066 regression: a plan naming Gmail (skipped node, granted-but-unused agent tool) with a clean run is served and stamped, never replaced")
    void planOnlyGmailMentionWithCleanRunIsServedAndStamped() {
        Map<String, Object> snapshot = legacySnapshot(planNamingGmailButNotRun());
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.NOT_RESTRICTED));

        assertThat(guard().withheldReason(pub)).isNull();

        // The orchestrator was asked: the plan's mention decided nothing.
        verify(orchestratorClient).runRestrictionAnswer("run_legacy");
        assertThat(savedSnapshot())
                .containsEntry(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true)
                .containsKeys("runState", "interfaceRenders")
                .doesNotContainKey(ShowcaseCaptureContract.WITHHELD_KEY);
    }

    @Test
    @DisplayName("LC-066 regression: a plan naming Gmail is not judged even when the run is gone (only produced sections are)")
    void planOnlyGmailMentionWithDeletedRunIsServed() {
        Map<String, Object> snapshot = legacySnapshot(planNamingGmailButNotRun());
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.RUN_GONE));

        assertThat(guard().withheldReason(pub)).isNull();
        assertThat(savedSnapshot()).containsEntry(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true);
    }

    @Test
    @DisplayName("LC-066: an explicit RESTRICTED tag in a produced section withholds and replaces the snapshot, with no lookup")
    void explicitTagInOutputIsWithheldWithoutLookup() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        snapshot.put("aggregatedSteps", Map.of("all", List.of(Map.of(
                "alias", "summarise", "output", Map.of("__dataSensitivity__", "RESTRICTED")))));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);

        assertThat(guard().withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);

        verify(orchestratorClient, never()).runRestrictionAnswer(anyString());
        assertThat(savedSnapshot())
                .containsEntry(ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA)
                .containsEntry("sourceRunId", "run_legacy")
                .doesNotContainKeys("runState", "interfaceRenders", "aggregatedSteps");
        // The request that judged it answers from the verdict too.
        assertThat(pub.getShowcaseSnapshot()).doesNotContainKey("runState");
    }

    @Test
    @DisplayName("LC-066: a run-state step output tagged RESTRICTED withholds too (a produced section, unlike the plan)")
    void explicitTagInRunStateStepOutputIsWithheld() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of(Map.of(
                "stepAlias", "summarise", "output", Map.of("text", "x", "__dataSensitivity__", "RESTRICTED")))));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);

        assertThat(guard().withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
        verify(orchestratorClient, never()).runRestrictionAnswer(anyString());
    }

    @Test
    @DisplayName("LC-066: a legacy snapshot whose source run the orchestrator reports restricted is withheld and replaced")
    void legacySnapshotOfRestrictedRunIsWithheld() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.RESTRICTED));

        ShowcaseRestrictionGuard guard = guard();
        assertThat(guard.withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
        // Persisted on the instance: a second read does not ask again.
        assertThat(guard.withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);

        verify(orchestratorClient).runRestrictionAnswer("run_legacy");
        assertThat(savedSnapshot()).containsEntry(
                ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
    }

    @Test
    @DisplayName("LC-066: the orchestrator's answer decides over an untagged Gmail mention in an output while the run exists")
    void existingCleanRunDecidesOverUntaggedOutputMention() {
        Map<String, Object> snapshot = legacySnapshot(stepOutputFromGmail());
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.NOT_RESTRICTED));

        assertThat(guard().withheldReason(pub)).isNull();
        assertThat(savedSnapshot()).containsEntry(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true);
    }

    @Test
    @DisplayName("LC-066: when the source run is gone, an output naming Gmail withholds and replaces the snapshot")
    void deletedRunWithGmailOutputIsWithheld() {
        Map<String, Object> snapshot = legacySnapshot(stepOutputFromGmail());
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.RUN_GONE));

        assertThat(guard().withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
        assertThat(savedSnapshot())
                .containsEntry(ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA)
                .doesNotContainKeys("runState", "interfaceRenders");
    }

    @Test
    @DisplayName("LC-066: when the source run is gone, an interface render naming Drive withholds too")
    void deletedRunWithDriveRenderIsWithheld() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        snapshot.put("interfaceRenders", Map.of("iface-1", Map.of("items", List.of(Map.of(
                "data", Map.of("file", "q3.pdf", "metadata", Map.of("apiName", "Google Drive")))))));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.RUN_GONE));

        assertThat(guard().withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
    }

    @Test
    @DisplayName("LC-066: a clean legacy snapshot is served as before and stamped checked, so it is asked once")
    void cleanLegacySnapshotIsServedAndStamped() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of(Map.of("stepAlias", "fetch_weather"))));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.NOT_RESTRICTED));

        ShowcaseRestrictionGuard guard = guard();
        assertThat(guard.withheldReason(pub)).isNull();
        assertThat(guard.withheldReason(pub)).isNull();

        verify(orchestratorClient).runRestrictionAnswer("run_legacy");
        assertThat(savedSnapshot())
                .containsEntry(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true)
                .containsKeys("runState", "interfaceRenders");
    }

    @Test
    @DisplayName("LC-066 regression: while the orchestrator cannot answer, nothing is persisted, nothing is served, and the request asks once")
    void unverifiableLegacySnapshotIsHiddenNotPersistedAndAskedOncePerRequest() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.UNKNOWN));

        ShowcaseRestrictionGuard guard = guard();
        // showcaseUnavailable, then servableSnapshot: the same loaded instance, one question.
        assertThat(guard.withheldReason(pub)).isEqualTo(ShowcaseRestrictionGuard.UNVERIFIED);
        assertThat(guard.isWithheld(pub)).isTrue();

        verify(orchestratorClient, times(1)).runRestrictionAnswer("run_legacy");
        verify(publicationRepository, never()).save(any());
        assertThat(pub.getShowcaseSnapshot()).containsKey("runState").doesNotContainKey(
                ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY);

        // The next request loads the row again and asks again.
        WorkflowPublicationEntity nextLoad = publicationWith(snapshot);
        assertThat(guard.withheldReason(nextLoad)).isEqualTo(ShowcaseRestrictionGuard.UNVERIFIED);
        verify(orchestratorClient, times(2)).runRestrictionAnswer("run_legacy");
    }

    @Test
    @DisplayName("LC-066: a verdict is not written over a snapshot a concurrent republish replaced")
    void verdictDoesNotOverwriteANewerSnapshot() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        Map<String, Object> republished = new LinkedHashMap<>(snapshot);
        republished.put("capturedAt", "2026-10-02T09:00:00Z");
        stubReload(pub, republished);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.RESTRICTED));

        assertThat(guard().withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);

        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("LC-066: the sweep verifies every unchecked snapshot, so a listing nobody opens is cleaned too")
    void sweepVerifiesUncheckedSnapshots() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        when(publicationRepository.findIdsWithUncheckedShowcaseSnapshot(any(), anyInt())).thenReturn(List.of(pub.getId()));
        when(publicationRepository.findById(pub.getId())).thenReturn(Optional.of(pub));
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.RESTRICTED));

        guard().sweepUncheckedSnapshots();

        verify(publicationRepository).save(pub);
        assertThat(pub.getShowcaseSnapshot())
                .containsEntry(ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA)
                .doesNotContainKey("interfaceRenders");
    }

    @Test
    @DisplayName("LC-066 perf: once a sweep pass leaves nothing to retry, later passes do not scan the table again")
    void sweepStopsAfterACompletePass() {
        when(publicationRepository.findIdsWithUncheckedShowcaseSnapshot(any(), anyInt())).thenReturn(List.of());

        ShowcaseRestrictionGuard guard = guard();
        guard.sweepUncheckedSnapshots();
        guard.sweepUncheckedSnapshots();
        guard.sweepUncheckedSnapshots();

        verify(publicationRepository, times(1)).findIdsWithUncheckedShowcaseSnapshot(any(), anyInt());
    }

    @Test
    @DisplayName("LC-066: a sweep pass that leaves a snapshot unverified keeps the sweep running")
    void sweepKeepsRunningWhileSnapshotsStayUnverified() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        when(publicationRepository.findIdsWithUncheckedShowcaseSnapshot(any(), anyInt())).thenReturn(List.of(pub.getId()));
        when(publicationRepository.findById(pub.getId())).thenAnswer(inv -> Optional.of(publicationWith(snapshot)));
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(answer(RunRestriction.UNKNOWN));

        ShowcaseRestrictionGuard guard = guard();
        guard.sweepUncheckedSnapshots();
        guard.sweepUncheckedSnapshots();

        verify(publicationRepository, times(2)).findIdsWithUncheckedShowcaseSnapshot(any(), anyInt());
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("LC-066 r5-3: a run restricted only AFTER the legacy capture keeps its clean preview (served and stamped)")
    void runRestrictedAfterTheCaptureKeepsItsPreview() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(new RunRestrictionAnswer(
                RunRestriction.RESTRICTED, java.time.Instant.parse("2026-09-15T00:00:00Z")));

        assertThat(guard().withheldReason(pub)).isNull();
        assertThat(savedSnapshot())
                .containsEntry(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true)
                .containsKeys("runState", "interfaceRenders");
    }

    @Test
    @DisplayName("LC-066 r5-3: a restriction a few minutes after capturedAt (inside the capture window) still withholds")
    void restrictionInsideTheCaptureWindowWithholds() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        stubReload(pub, snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(new RunRestrictionAnswer(
                RunRestriction.RESTRICTED, java.time.Instant.parse("2026-09-01T10:03:00Z")));

        assertThat(guard().withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
        assertThat(savedSnapshot()).containsEntry(
                ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
    }

    @Test
    @DisplayName("LC-066 r5-3: restricted, but since when unknown (old orchestrator): not served, nothing persisted, asked again")
    void restrictedWithUnknownMomentIsUnverifiedAndNotPersisted() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy"))
                .thenReturn(new RunRestrictionAnswer(RunRestriction.RESTRICTED, null));

        ShowcaseRestrictionGuard guard = guard();
        assertThat(guard.withheldReason(pub)).isEqualTo(ShowcaseRestrictionGuard.UNVERIFIED);
        assertThat(guard.withheldReason(publicationWith(snapshot))).isEqualTo(ShowcaseRestrictionGuard.UNVERIFIED);

        verify(publicationRepository, never()).save(any());
        verify(orchestratorClient, times(2)).runRestrictionAnswer("run_legacy");
    }

    @Test
    @DisplayName("LC-066 r5-3: a restricted run with a legacy snapshot that cannot be dated is withheld, as before")
    void undatableSnapshotOfRestrictedRunIsWithheld() {
        Map<String, Object> snapshot = legacySnapshot(Map.of("steps", List.of()));
        snapshot.remove("capturedAt");
        WorkflowPublicationEntity pub = publicationWith(snapshot);
        when(orchestratorClient.runRestrictionAnswer("run_legacy")).thenReturn(new RunRestrictionAnswer(
                RunRestriction.RESTRICTED, java.time.Instant.parse("2026-12-01T00:00:00Z")));
        when(publicationRepository.findById(pub.getId())).thenReturn(Optional.empty());

        assertThat(guard().withheldReason(pub)).isEqualTo(ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
    }

    @Test
    @DisplayName("LC-066 r5-5: the sweep walks the table in keyset batches until a short one")
    void sweepWalksKeysetBatches() {
        List<UUID> fullBatch = new java.util.ArrayList<>();
        for (int i = 0; i < ShowcaseRestrictionGuard.SWEEP_BATCH; i++) {
            fullBatch.add(new UUID(0L, i + 1L));
        }
        UUID last = fullBatch.get(fullBatch.size() - 1);
        when(publicationRepository.findIdsWithUncheckedShowcaseSnapshot(new UUID(0L, 0L), ShowcaseRestrictionGuard.SWEEP_BATCH))
                .thenReturn(fullBatch);
        when(publicationRepository.findIdsWithUncheckedShowcaseSnapshot(last, ShowcaseRestrictionGuard.SWEEP_BATCH))
                .thenReturn(List.of());
        when(publicationRepository.findById(any())).thenReturn(Optional.empty());

        guard().sweepUncheckedSnapshots();

        verify(publicationRepository).findIdsWithUncheckedShowcaseSnapshot(new UUID(0L, 0L), ShowcaseRestrictionGuard.SWEEP_BATCH);
        verify(publicationRepository).findIdsWithUncheckedShowcaseSnapshot(last, ShowcaseRestrictionGuard.SWEEP_BATCH);
    }

    @Test
    @DisplayName("LC-066: the produced sections exclude the plan; the tag scan finds explicit tags only, the integration scan Drive names too")
    void contentScan() {
        Map<String, Object> snapshot = legacySnapshot(planNamingGmailButNotRun());
        List<Object> produced = ShowcaseRestrictionGuard.producedSections(snapshot);
        assertThat(ShowcaseRestrictionGuard.anyMapMatches(produced,
                ShowcaseRestrictionGuard::namesRestrictedIntegration)).isFalse();

        List<Object> tagged = List.of(Map.of("a", List.of(Map.of("b", Map.of("__dataSensitivity__", "RESTRICTED")))));
        assertThat(ShowcaseRestrictionGuard.anyMapMatches(tagged, ShowcaseRestrictionGuard::carriesRestrictedTag)).isTrue();

        List<Object> named = List.of(Map.of("files", List.of(Map.of("apiSlug", "google_drive"))));
        assertThat(ShowcaseRestrictionGuard.anyMapMatches(named, ShowcaseRestrictionGuard::carriesRestrictedTag)).isFalse();
        assertThat(ShowcaseRestrictionGuard.anyMapMatches(named, ShowcaseRestrictionGuard::namesRestrictedIntegration)).isTrue();
        assertThat(ShowcaseRestrictionGuard.anyMapMatches(
                List.of(Map.of("apiSlug", "slack", "text", "my gmail address")),
                ShowcaseRestrictionGuard::namesRestrictedIntegration)).isFalse();
    }
}
