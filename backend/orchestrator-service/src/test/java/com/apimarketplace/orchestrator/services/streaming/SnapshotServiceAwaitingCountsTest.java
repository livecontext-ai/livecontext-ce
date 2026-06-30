package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the invariant that {@link SnapshotService#computeAwaitingSignalCounts}
 * only counts BLOCKING signals.
 *
 * <p>Regression: non-blocking interface signals (no {@code __continue} in the
 * actionMapping) used to inflate {@code statusCounts.awaitingSignal} → the
 * frontend {@code deriveStatusFromCounts} preferred {@code awaiting_signal}
 * over the backend-reported {@code completed} status → the yellow pause icon
 * flashed briefly on passive interface nodes. Filtering on {@code isBlocking}
 * aligns the per-item signal-based count with the epoch-based baseline, which
 * is already blocking-only (see UnifiedSignalService.registerSignal:179).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotService.computeAwaitingSignalCounts")
class SnapshotServiceAwaitingCountsTest {

    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    private SnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        // Phase B.1 (archi-refoundation 2026-05-04)
        snapshotService = new SnapshotService(
                stateSnapshotService, streamingService, runningNodeTracker, workflowEpochService,
                runRepository, 60L, 1800L);
    }

    private static SignalWaitEntity signal(String nodeId, boolean blocking, SignalType type) {
        SignalWaitEntity s = new SignalWaitEntity();
        s.setNodeId(nodeId);
        s.setBlocking(blocking);
        s.setSignalType(type);
        return s;
    }

    @Test
    @DisplayName("Non-blocking interface signal produces count=0 (no awaiting flash)")
    void nonBlockingInterfaceExcluded() {
        StateSnapshot snapshot = StateSnapshot.empty();
        List<SignalWaitEntity> activeSignals = List.of(
                signal("interface:my_screen", false, SignalType.INTERFACE_SIGNAL));

        Map<String, Integer> counts = snapshotService.computeAwaitingSignalCounts(snapshot, activeSignals);

        assertThat(counts).doesNotContainKey("interface:my_screen");
    }

    @Test
    @DisplayName("Blocking interface split-context with 5 per-item signals counts to 5")
    void blockingSplitContextCountsPerItem() {
        StateSnapshot snapshot = StateSnapshot.empty();
        List<SignalWaitEntity> activeSignals = List.of(
                signal("interface:wizard", true, SignalType.INTERFACE_SIGNAL),
                signal("interface:wizard", true, SignalType.INTERFACE_SIGNAL),
                signal("interface:wizard", true, SignalType.INTERFACE_SIGNAL),
                signal("interface:wizard", true, SignalType.INTERFACE_SIGNAL),
                signal("interface:wizard", true, SignalType.INTERFACE_SIGNAL));

        Map<String, Integer> counts = snapshotService.computeAwaitingSignalCounts(snapshot, activeSignals);

        assertThat(counts).containsEntry("interface:wizard", 5);
    }

    @Test
    @DisplayName("Mixed blocking + non-blocking on same nodeId returns blocking count only")
    void mixedBlockingAndNonBlockingFiltersNonBlocking() {
        StateSnapshot snapshot = StateSnapshot.empty();
        // Edge case: a node with 2 blocking per-item signals AND 3 non-blocking ones
        // (e.g. leftover PENDING rows from an earlier spawn that auto-completed).
        // Only the blocking ones should count toward awaiting_signal.
        List<SignalWaitEntity> activeSignals = List.of(
                signal("interface:mixed", true, SignalType.INTERFACE_SIGNAL),
                signal("interface:mixed", true, SignalType.INTERFACE_SIGNAL),
                signal("interface:mixed", false, SignalType.INTERFACE_SIGNAL),
                signal("interface:mixed", false, SignalType.INTERFACE_SIGNAL),
                signal("interface:mixed", false, SignalType.INTERFACE_SIGNAL));

        Map<String, Integer> counts = snapshotService.computeAwaitingSignalCounts(snapshot, activeSignals);

        assertThat(counts).containsEntry("interface:mixed", 2);
    }

    @Test
    @DisplayName("Other blocking signal types (user approval, wait timer) still count")
    void otherBlockingSignalTypesCounted() {
        StateSnapshot snapshot = StateSnapshot.empty();
        List<SignalWaitEntity> activeSignals = List.of(
                signal("core:approval", true, SignalType.USER_APPROVAL),
                signal("core:timer", true, SignalType.WAIT_TIMER),
                signal("core:webhook", true, SignalType.WEBHOOK_WAIT));

        Map<String, Integer> counts = snapshotService.computeAwaitingSignalCounts(snapshot, activeSignals);

        assertThat(counts)
                .containsEntry("core:approval", 1)
                .containsEntry("core:timer", 1)
                .containsEntry("core:webhook", 1);
    }

    @Test
    @DisplayName("Empty active signals list yields empty count map")
    void emptyActiveSignalsYieldsEmptyCounts() {
        StateSnapshot snapshot = StateSnapshot.empty();

        Map<String, Integer> counts = snapshotService.computeAwaitingSignalCounts(snapshot, List.of());

        assertThat(counts).isEmpty();
    }
}
