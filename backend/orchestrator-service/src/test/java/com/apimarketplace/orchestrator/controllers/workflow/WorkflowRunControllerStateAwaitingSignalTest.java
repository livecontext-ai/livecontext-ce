package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowRunController#getRunState}: the awaiting-signal /
 * running split in the REST {@code /state} payload.
 *
 * <p>Regression: {@code StateReconstructor} folds awaiting-signal nodes (approval /
 * wait / interface) INTO {@code runningStepIds}. On the WS snapshot path
 * ({@code SnapshotService}) the running set is clean and a separate
 * {@code awaitingSignalStepIds} is emitted, so the frontend paints those nodes amber
 * "awaiting". The REST {@code /state} path used to leak them into {@code runningStepIds}
 * with NO {@code awaitingSignalStepIds} field, so on hydration/reconnect an approval node
 * rendered blue "running" forever instead of amber. This pins the fix: {@code /state} now
 * mirrors the WS snapshot (clean running + separate awaiting set).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - /state awaiting-signal vs running split")
class WorkflowRunControllerStateAwaitingSignalTest {

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private WorkflowResumeService resumeService;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private WorkflowEpochService workflowEpochService;

    @InjectMocks
    private WorkflowRunController controller;

    private static final String RUN_ID = "run-state-awaiting";
    private static final String TENANT_ID = "tenant-A";

    @BeforeEach
    void wireOwnerCheckAndSnapshot() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        lenient().when(workflowEpochService.listEpochTimestamps(RUN_ID)).thenReturn(List.of());
    }

    private WorkflowRunState stateWithRunning(Set<String> runningStepIds) {
        return new WorkflowRunState(
                RUN_ID, "wf-1", RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                Instant.now(), null, Map.of(), List.of(), List.of(),
                Set.of(), Set.of(), Set.of(), Set.of(),
                new HashSet<>(runningStepIds), Map.of(), List.of());
    }

    private StateSnapshot snapshotWithAwaiting(Set<String> awaiting) {
        // Real StateSnapshot: getAwaitingSignalNodeIds() is a lazy-computed flatten over
        // dags/epochs (not a simple getter), so mocking it is brittle - build the real thing.
        StateSnapshot snap = StateSnapshot.empty()
                .withDagState("trigger:start", DagState.initial().advanceEpoch(1));
        for (String nodeId : awaiting) {
            snap = snap.markNodeAwaitingSignal("trigger:start", nodeId, 1);
        }
        return snap;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> field(ResponseEntity<?> response, String key) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Object v = body.get(key);
        return v == null ? null : new HashSet<>((java.util.Collection<String>) v);
    }

    @Test
    @DisplayName("awaiting node is excluded from runningStepIds and surfaced in awaitingSignalStepIds")
    void awaitingNodeSplitOutOfRunning() {
        // StateReconstructor folded core:approve (awaiting) into running alongside a real running node.
        when(resumeService.reconstructStateForApi(RUN_ID))
                .thenReturn(stateWithRunning(Set.of("agent:echo", "core:approve")));
        when(stateSnapshotService.getSnapshot(RUN_ID))
                .thenReturn(snapshotWithAwaiting(Set.of("core:approve")));

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Clean running: the real running node stays, the awaiting one is gone (would be blue otherwise).
        assertThat(field(response, "runningStepIds")).containsExactly("agent:echo");
        // Separate awaiting set the frontend keys amber off of.
        assertThat(field(response, "awaitingSignalStepIds")).containsExactly("core:approve");
    }

    @Test
    @DisplayName("no awaiting signals: running is unchanged and awaitingSignalStepIds is empty")
    void noAwaitingIsNoOp() {
        when(resumeService.reconstructStateForApi(RUN_ID))
                .thenReturn(stateWithRunning(Set.of("agent:echo")));
        when(stateSnapshotService.getSnapshot(RUN_ID))
                .thenReturn(snapshotWithAwaiting(Set.of()));

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(field(response, "runningStepIds")).containsExactly("agent:echo");
        assertThat(field(response, "awaitingSignalStepIds")).isEmpty();
    }

    @Test
    @DisplayName("full=true path (reconstructState) gets the same split - it feeds the E2E OutputVerifier")
    void fullPathAlsoSplits() {
        // full=true routes through reconstructState (not ...ForApi); the split runs on shared
        // code after that branch, so this consumer must get clean running + awaiting too.
        when(resumeService.reconstructState(RUN_ID))
                .thenReturn(stateWithRunning(Set.of("agent:echo", "core:approve")));
        when(stateSnapshotService.getSnapshot(RUN_ID))
                .thenReturn(snapshotWithAwaiting(Set.of("core:approve")));

        ResponseEntity<?> response = controller.getRunState(RUN_ID, true, TENANT_ID, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(field(response, "runningStepIds")).containsExactly("agent:echo");
        assertThat(field(response, "awaitingSignalStepIds")).containsExactly("core:approve");
    }

    @Test
    @DisplayName("multiple awaiting + running nodes: only the awaiting ones move, running keeps the rest")
    void multipleNodesSplitCorrectly() {
        when(resumeService.reconstructStateForApi(RUN_ID))
                .thenReturn(stateWithRunning(Set.of("mcp:a", "mcp:b", "core:approve", "core:wait")));
        when(stateSnapshotService.getSnapshot(RUN_ID))
                .thenReturn(snapshotWithAwaiting(Set.of("core:approve", "core:wait")));

        ResponseEntity<?> response = controller.getRunState(RUN_ID, false, TENANT_ID, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(field(response, "runningStepIds")).containsExactlyInAnyOrder("mcp:a", "mcp:b");
        assertThat(field(response, "awaitingSignalStepIds"))
                .containsExactlyInAnyOrder("core:approve", "core:wait");
    }
}
