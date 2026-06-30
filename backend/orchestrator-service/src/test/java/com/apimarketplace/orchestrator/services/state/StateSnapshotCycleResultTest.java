package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StateSnapshotService#deriveCycleStatus(boolean, boolean)} - the cycle
 * outcome classification that drives the run badge / lastCycleResult once an SBS epoch rests at
 * WAITING_TRIGGER. A mix of a failure AND a real (non-trigger) completion must read PARTIAL_SUCCESS,
 * not a plain FAILED.
 */
@DisplayName("StateSnapshotService.deriveCycleStatus")
class StateSnapshotCycleResultTest {

    @Test
    @DisplayName("failure + a real (non-trigger) completion -> PARTIAL_SUCCESS")
    void mixIsPartialSuccess() {
        assertThat(StateSnapshotService.deriveCycleStatus(true, true)).isEqualTo(RunStatus.PARTIAL_SUCCESS);
    }

    @Test
    @DisplayName("failure with no real completion -> FAILED (the trigger alone must not make it partial)")
    void allFailedIsFailed() {
        assertThat(StateSnapshotService.deriveCycleStatus(true, false)).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("no failures -> COMPLETED, whether or not there were non-trigger completions")
    void noFailuresIsCompleted() {
        assertThat(StateSnapshotService.deriveCycleStatus(false, true)).isEqualTo(RunStatus.COMPLETED);
        assertThat(StateSnapshotService.deriveCycleStatus(false, false)).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("partial success is distinct from a plain failure")
    void partialIsDistinctFromFailed() {
        assertThat(StateSnapshotService.deriveCycleStatus(true, true))
                .isNotEqualTo(StateSnapshotService.deriveCycleStatus(true, false));
    }
}
