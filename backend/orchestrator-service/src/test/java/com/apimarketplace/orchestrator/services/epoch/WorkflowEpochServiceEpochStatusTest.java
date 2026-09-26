package com.apimarketplace.orchestrator.services.epoch;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochTimelineRow;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochTimestampRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Every epoch of the timeline carries its OWN outcome, so the epoch selector can badge
 * epoch 2 as failed while epoch 3 is green. The run-level status can only ever describe
 * the last one.
 *
 * <p>The outcome is read from the epoch's persisted {@link EpochState} - the same
 * {@code failedNodeIds} the run's cycle verdict is computed from. The epoch COUNTER rows
 * are not usable for it, and the two tests that pin why are the point of this file: they
 * are additive across a rerun, and a continue-anyway split records a FAILED count for a
 * node the cycle verdict deliberately keeps out of {@code failedNodeIds}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEpochService - per-epoch outcome status")
class WorkflowEpochServiceEpochStatusTest {

    @Mock private WorkflowEpochRepository repository;
    @Mock private WorkflowStepDataRepository stepDataRepository;

    private WorkflowEpochService service;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        service = new WorkflowEpochService(repository, mapper, stepDataRepository);
    }

    private static EpochState state(Set<String> completed, Set<String> failed, Set<String> skipped) {
        return new EpochState(completed, failed, Set.of(), skipped,
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Instant.parse("2026-08-02T09:00:00Z"));
    }

    /** A closed timeline row carrying the serialized state. */
    private EpochTimelineRow closed(int epoch, EpochState state) {
        return row(epoch, state, false);
    }

    private EpochTimelineRow row(int epoch, EpochState state, boolean active) {
        try {
            return new EpochTimelineRow(epoch, "2026-08-02T09:0" + epoch + ":00Z",
                    active ? null : "2026-08-02T09:0" + epoch + ":30Z", active,
                    state == null ? null : mapper.writeValueAsString(state));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── The windowed timeline ─────────────────────────────────────────────────

    @Test
    @DisplayName("The windowed timeline is oldest first, badged, and measured over ITS epochs only")
    void latestTimestampsAreSortedBadgedAndMeasuredOverTheWindow() {
        org.mockito.Mockito.when(repository.listLatestEpochTimestamps("run-1", 2)).thenReturn(List.of(
                closed(8, state(Set.of("mcp:a"), Set.of("mcp:b"), Set.of())),
                closed(7, state(Set.of("mcp:a"), Set.of(), Set.of()))));
        org.mockito.Mockito.when(stepDataRepository.findEpochWorkWindowsInRange("run-1", 7, 8)).thenReturn(List.of(
                new com.apimarketplace.orchestrator.persistence.EpochWorkWindowProjection(
                        "run-1", 7, Instant.parse("2026-08-02T09:07:00Z"), Instant.parse("2026-08-02T09:07:04Z"))));

        List<EpochTimestampRow> rows = service.listLatestEpochTimestamps("run-1", 2);

        assertThat(rows).extracting(EpochTimestampRow::epoch, EpochTimestampRow::status, EpochTimestampRow::workDurationMs)
                .containsExactly(Tuple.tuple(7, "COMPLETED", 4_000L), Tuple.tuple(8, "FAILED", null));
        // The whole-run aggregate is what this method exists to avoid.
        org.mockito.Mockito.verify(stepDataRepository, org.mockito.Mockito.never()).findEpochWorkWindows(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    @DisplayName("An empty window reads no step rows")
    void emptyWindowReadsNoStepRows() {
        org.mockito.Mockito.when(repository.listLatestEpochTimestamps("run-1", 60)).thenReturn(List.of());

        assertThat(service.listLatestEpochTimestamps("run-1", 60)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(stepDataRepository);
    }

    // ── The verdict itself ────────────────────────────────────────────────────

    @Test
    @DisplayName("A failed node makes the epoch FAILED, even alongside successful work")
    void anyFailedNodeMakesTheEpochFailed() {
        // Binary, matching StateSnapshotService.deriveCycleStatus: an epoch is a cycle and
        // partial_success is a NODE verdict. Answering PARTIAL_SUCCESS here would make the
        // epoch badge contradict the run badge for the same cycle.
        assertThat(WorkflowEpochService.deriveEpochOutcome(
                state(Set.of("trigger:webhook", "mcp:fetch"), Set.of("mcp:save"), Set.of()), false))
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("No failed node and something ran is a completion")
    void noFailedNodeIsCompleted() {
        assertThat(WorkflowEpochService.deriveEpochOutcome(
                state(Set.of("trigger:webhook", "mcp:fetch"), Set.of(), Set.of("core:notify")), false))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("An epoch whose whole downstream was skipped still owes an outcome")
    void skipsAloneStillCount() {
        // An exit branch or an unrouted split: the cycle fired and reached its end, so it
        // reports COMPLETED - the same answer the automatic rearm path records for it.
        assertThat(WorkflowEpochService.deriveEpochOutcome(
                state(Set.of(), Set.of(), Set.of("core:notify")), false))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("An epoch where only the trigger fired is armed, not completed")
    void triggerOnlyEpochHasNoOutcome() {
        // The trigger completes on every fire, so counting it would make "armed"
        // indistinguishable from "ran" and badge an idle run as a success.
        assertThat(WorkflowEpochService.deriveEpochOutcome(
                state(Set.of("trigger:webhook"), Set.of(), Set.of()), false)).isNull();
        assertThat(WorkflowEpochService.deriveEpochOutcome(state(Set.of(), Set.of(), Set.of()), false)).isNull();
        assertThat(WorkflowEpochService.deriveEpochOutcome(null, false)).isNull();
    }

    @Test
    @DisplayName("\"Did it run\" is one predicate, shared with the run's own reconcile")
    void hasExecutedIsSharedWithTheRunReconcile() {
        // The epoch badge and the run status must agree on what counts as having run, or
        // an idle run gets badged as a success on one surface and not the other. Pinned
        // here on the shared predicate itself: the trigger alone is armed, not ran, and
        // a fully-skipped downstream still counts.
        assertThat(StateSnapshotService.hasExecuted(state(Set.of("trigger:webhook"), Set.of(), Set.of()))).isFalse();
        assertThat(StateSnapshotService.hasExecuted(state(Set.of(), Set.of(), Set.of()))).isFalse();
        assertThat(StateSnapshotService.hasExecuted(null)).isFalse();
        assertThat(StateSnapshotService.hasExecuted(state(Set.of("trigger:t", "mcp:a"), Set.of(), Set.of()))).isTrue();
        assertThat(StateSnapshotService.hasExecuted(state(Set.of(), Set.of("mcp:a"), Set.of()))).isTrue();
        assertThat(StateSnapshotService.hasExecuted(state(Set.of(), Set.of(), Set.of("core:notify")))).isTrue();
    }

    @Test
    @DisplayName("An ACTIVE epoch has no outcome, whatever its stored state says")
    void activeEpochHasNoOutcome() {
        // The header holds the state written when the epoch OPENED, so it cannot describe
        // an outcome. Claiming COMPLETED from it would badge a running epoch as finished.
        assertThat(WorkflowEpochService.deriveEpochOutcome(
                state(Set.of("trigger:webhook", "mcp:fetch"), Set.of(), Set.of()), true)).isNull();
    }

    // ── Why the counter rows cannot answer this ───────────────────────────────

    @Test
    @DisplayName("A node rerun to success inside its epoch clears the epoch's failure")
    void rerunToSuccessClearsTheEpochFailure() {
        // Walked through the ACTUAL rerun path rather than hand-building the end state:
        // the rerun removes the node from every tracking set (EpochState.removeNodes) and
        // the re-execution marks it completed. The epoch COUNTER rows are additive and
        // still hold the original FAILED, so deriving from them would badge this epoch red
        // forever, with nothing on screen explaining why.
        EpochState failed = state(Set.of("trigger:webhook"), Set.of("mcp:save"), Set.of());
        assertThat(WorkflowEpochService.deriveEpochOutcome(failed, false)).isEqualTo("FAILED");

        EpochState afterRerun = failed.removeNodes(Set.of("mcp:save")).markNodeCompleted("mcp:save");

        assertThat(afterRerun.getFailedNodeIds()).isEmpty();
        assertThat(WorkflowEpochService.deriveEpochOutcome(afterRerun, false)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("A continue-anyway split partial failure does not fail the epoch")
    void continueAnywaySplitDoesNotFailTheEpoch() {
        // StepCompletionOrchestrator records a FAILED COUNTER row for a split that lost
        // some items while keeping the node OUT of failedNodeIds ("This does NOT change the
        // run verdict"). Reading the counters here would put a red epoch badge next to a
        // green run badge for one and the same cycle.
        EpochState partial = new EpochState(
                Set.of("trigger:webhook", "mcp:fanout"), Set.of(), Set.of("mcp:fanout"), Set.of(),
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Instant.parse("2026-08-02T09:00:00Z"));

        assertThat(partial.getPartialFailedNodeIds()).contains("mcp:fanout");
        assertThat(WorkflowEpochService.deriveEpochOutcome(partial, false)).isEqualTo("COMPLETED");
    }

    // ── The timeline plumbing ─────────────────────────────────────────────────

    @Test
    @DisplayName("Each timeline row gets its own epoch's status, not the run's")
    void timelineRowsCarryTheirOwnStatus() {
        org.mockito.Mockito.when(repository.listEpochTimestamps(anyString())).thenReturn(List.of(
                closed(1, state(Set.of("trigger:t", "mcp:a"), Set.of(), Set.of())),
                closed(2, state(Set.of("trigger:t"), Set.of("mcp:a"), Set.of())),
                row(3, state(Set.of("trigger:t"), Set.of(), Set.of()), true)));

        assertThat(service.listEpochTimestamps("run-A"))
                .extracting(EpochTimestampRow::epoch, EpochTimestampRow::status)
                .containsExactly(
                        Tuple.tuple(1, "COMPLETED"),
                        Tuple.tuple(2, "FAILED"),
                        // Active: the badge is the RUN's business, not this row's.
                        Tuple.tuple(3, null));
    }

    @Test
    @DisplayName("The epoch state never reaches the wire, only the outcome it produced")
    void epochStateStaysServerSide() {
        // The timeline row is a WIRE shape sent to every client. The state JSON is an
        // internal payload (node id sets) and has no business being serialized to them.
        org.mockito.Mockito.when(repository.listEpochTimestamps(anyString())).thenReturn(List.of(
                closed(1, state(Set.of("trigger:t", "mcp:a"), Set.of(), Set.of()))));

        Map<String, Object> wire = service.listEpochTimestamps("run-A").get(0).toWireMap();

        assertThat(wire).containsEntry("status", "COMPLETED");
        assertThat(wire.keySet()).containsExactlyInAnyOrder("epoch", "startedAt", "endedAt", "workDurationMs", "status");
    }

    @Test
    @DisplayName("An unreadable epoch state leaves the row without a status instead of throwing")
    void unreadableStateDegradesToNoStatus() {
        org.mockito.Mockito.when(repository.listEpochTimestamps(anyString())).thenReturn(List.of(
                new EpochTimelineRow(1, "2026-08-02T09:00:00Z", "2026-08-02T09:00:30Z", false, "{not json"),
                new EpochTimelineRow(2, "2026-08-02T09:01:00Z", "2026-08-02T09:01:30Z", false, null)));

        assertThat(service.listEpochTimestamps("run-A"))
                .extracting(EpochTimestampRow::epoch, EpochTimestampRow::status)
                .containsExactly(Tuple.tuple(1, null), Tuple.tuple(2, null));
    }

    @Test
    @DisplayName("The status rides alongside the work window, neither overwrites the other")
    void statusAndWorkDurationCoexist() {
        // Both enrichments hit the same row through separate `with…` copies; a copy that
        // forgot to carry the other field would silently blank it.
        org.mockito.Mockito.when(repository.listEpochTimestamps(anyString())).thenReturn(List.of(
                closed(1, state(Set.of("trigger:t"), Set.of("mcp:a"), Set.of()))));
        org.mockito.Mockito.when(stepDataRepository.findEpochWorkWindows(List.of("run-A"))).thenReturn(List.of(
                new com.apimarketplace.orchestrator.persistence.EpochWorkWindowProjection(
                        "run-A", 1,
                        Instant.parse("2026-08-02T09:00:00Z"),
                        Instant.parse("2026-08-02T09:00:12Z"))));

        EpochTimestampRow row = service.listEpochTimestamps("run-A").get(0);

        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.workDurationMs()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("Renumbering an epoch for a showcase keeps its outcome")
    void renumberingKeepsTheStatus() {
        EpochTimestampRow renumbered = new EpochTimestampRow(37, "2026-08-02T09:00:00Z", null, 12_000L)
                .withStatus("FAILED")
                .withEpoch(1);

        assertThat(renumbered.epoch()).isEqualTo(1);
        assertThat(renumbered.status()).isEqualTo("FAILED");
        assertThat(renumbered.workDurationMs()).isEqualTo(12_000L);
    }
}
