package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalSbsController")
class InternalSbsControllerTest {

    @Mock
    private V2StepByStepService v2StepByStepService;

    @Mock
    private V2StepByStepScheduler v2StepByStepScheduler;

    @Mock
    private WorkflowResumeService resumeService;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private SnapshotService snapshotService;

    @Mock
    private CreditConsumptionClient creditClient;

    @Mock
    private WorkflowRunRepository runRepository;

    // Use SyncTaskExecutor so async tasks run inline in tests
    private final TaskExecutor sbsExecutor = new SyncTaskExecutor();

    private InternalSbsController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalSbsController(
                v2StepByStepService,
                v2StepByStepScheduler,
                resumeService,
                stateSnapshotService,
                snapshotService,
                creditClient,
                sbsExecutor,
                runRepository
        );
        // Default: allow credits
        lenient().when(creditClient.checkCredits(any())).thenReturn(true);
        // Mock the claim check to succeed by default (prevents 409 early return)
        lenient().when(stateSnapshotService.claimNodeForExecution(anyString(), anyString()))
                .thenReturn(true);
        // Default: run row exists in org-1 - callers passing ("user-1", "org-1")
        // pass the run-scope guard.
        WorkflowRunEntity defaultRun = new WorkflowRunEntity();
        defaultRun.setTenantId("user-1");
        defaultRun.setOrganizationId("org-1");
        defaultRun.setOrganizationRole("OWNER");
        lenient().when(runRepository.findByRunIdPublic(anyString()))
                .thenReturn(java.util.Optional.of(defaultRun));
    }

    @Nested
    @DisplayName("executeNode()")
    class ExecuteNodeTests {

        @Test
        @DisplayName("Should return 402 when user has insufficient credits")
        void shouldReturn402WhenInsufficientCredits() {
            when(creditClient.checkCredits("user-1")).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-1", "mcp:step1", "user-1", "org-1", Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody()).containsEntry("accepted", false);
            assertThat(response.getBody()).containsEntry("error", "INSUFFICIENT_CREDITS");
            verify(v2StepByStepService, never()).executeNode(any(), any(), any());
            verify(stateSnapshotService, never()).claimNodeForExecution(any(), any());
        }

        @Test
        @DisplayName("Should return accepted ack immediately")
        void shouldReturnAcceptedAck() {
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());

            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-1", "mcp:step1", "user-1", "org-1", Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("accepted", true);
            assertThat(response.getBody()).containsEntry("runId", "run-1");
            assertThat(response.getBody()).containsEntry("nodeId", "mcp:step1");
        }

        @Test
        @DisplayName("Should execute node via V2StepByStepService")
        void shouldExecuteNode() {
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());

            controller.executeNode("run-1", "mcp:step1", "user-1", "org-1", Map.of());

            verify(v2StepByStepService).executeNode("run-1", "mcp:step1", "0");
        }

        @Test
        @DisplayName("regression: releases the claim and reconciles status when execution dies before any outcome")
        void releasesClaimWhenExecutionThrowsBeforeOutcome() {
            // Bug: claimNodeForExecution moved the node READY → RUNNING, then the async
            // execution threw (e.g. tree rebuild failure) before the V2 pipeline recorded
            // any outcome - the node stayed neither READY nor resolved and every further
            // click was rejected with NODE_NOT_READY.
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());
            doThrow(new IllegalStateException("tree rebuild failed"))
                    .when(v2StepByStepService).executeNode("run-1", "mcp:step1", "0");
            when(stateSnapshotService.releaseNodeClaimIfUnresolved("run-1", "mcp:step1"))
                    .thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-1", "mcp:step1", "user-1", "org-1", Map.of());

            // Ack already sent; the catch path must release + re-derive status + push snapshot.
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(stateSnapshotService).releaseNodeClaimIfUnresolved("run-1", "mcp:step1");
            verify(stateSnapshotService).reconcileSbsRunStatus("run-1");
            verify(snapshotService).sendSnapshotImmediate("run-1");
        }

        @Test
        @DisplayName("no snapshot/reconcile when the release is a no-op (engine recorded an outcome)")
        void noSnapshotWhenReleaseNoop() {
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());
            doThrow(new IllegalStateException("post-persist failure"))
                    .when(v2StepByStepService).executeNode("run-1", "mcp:step1", "0");
            when(stateSnapshotService.releaseNodeClaimIfUnresolved("run-1", "mcp:step1"))
                    .thenReturn(false);

            controller.executeNode("run-1", "mcp:step1", "user-1", "org-1", Map.of());

            verify(stateSnapshotService).releaseNodeClaimIfUnresolved("run-1", "mcp:step1");
            verify(stateSnapshotService, never()).reconcileSbsRunStatus(anyString());
            verify(snapshotService, never()).sendSnapshotImmediate(anyString());
        }

        @Test
        @DisplayName("a failing release is swallowed (recovery must never escalate)")
        void releaseFailureIsSwallowed() {
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());
            doThrow(new IllegalStateException("execution died"))
                    .when(v2StepByStepService).executeNode("run-1", "mcp:step1", "0");
            when(stateSnapshotService.releaseNodeClaimIfUnresolved("run-1", "mcp:step1"))
                    .thenThrow(new RuntimeException("db down"));

            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-1", "mcp:step1", "user-1", "org-1", Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(snapshotService, never()).sendSnapshotImmediate(anyString());
        }

        @Test
        @DisplayName("regression: binds the RUN's org scope + role on the executor thread (WS path sent none before)")
        void bindsRunOrgScopeOnExecutorThread() {
            // Bug: the gateway WsActionHandler forwarded no X-Organization-ID and the
            // controller bound the (null) header - SBS steps of org-workspace runs
            // executed with a null TenantResolver scope and OrgScopedEntityListener
            // rejected the storage.storage offload of the step output payload.
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setTenantId("user-1");
            run.setOrganizationId("org-of-run");
            run.setOrganizationRole("ADMIN");
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(java.util.Optional.of(run));

            AtomicReference<String> observedOrg = new AtomicReference<>();
            AtomicReference<String> observedRole = new AtomicReference<>();
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());
            doAnswer(invocation -> {
                observedOrg.set(TenantResolver.currentRequestOrganizationId());
                observedRole.set(TenantResolver.currentRequestOrganizationRole());
                return null;
            }).when(v2StepByStepService).executeNode("run-1", "mcp:step1", "0");

            controller.executeNode("run-1", "mcp:step1", "user-1", "org-of-run", Map.of());

            assertThat(observedOrg).hasValue("org-of-run");
            assertThat(observedRole).hasValue("ADMIN");
        }

        @Test
        @DisplayName("binds a null org scope for a legacy personal run (null org, owner caller, no header)")
        void bindsNullOrgScopeForLegacyPersonalRun() {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setTenantId("user-1");
            run.setOrganizationId(null);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(java.util.Optional.of(run));

            AtomicReference<String> observedOrg = new AtomicReference<>("sentinel");
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());
            doAnswer(invocation -> {
                observedOrg.set(TenantResolver.currentRequestOrganizationId());
                return null;
            }).when(v2StepByStepService).executeNode("run-1", "mcp:step1", "0");

            ResponseEntity<Map<String, Object>> response =
                    controller.executeNode("run-1", "mcp:step1", "user-1", null, Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(observedOrg.get()).isNull();
        }

        @Test
        @DisplayName("binds the run org with a null role when the run row carries no organization role")
        void bindsRunOrgWithNullRole() {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setTenantId("user-1");
            run.setOrganizationId("org-of-run");
            run.setOrganizationRole(null);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(java.util.Optional.of(run));

            AtomicReference<String> observedOrg = new AtomicReference<>();
            AtomicReference<String> observedRole = new AtomicReference<>("sentinel");
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());
            doAnswer(invocation -> {
                observedOrg.set(TenantResolver.currentRequestOrganizationId());
                observedRole.set(TenantResolver.currentRequestOrganizationRole());
                return null;
            }).when(v2StepByStepService).executeNode("run-1", "mcp:step1", "0");

            controller.executeNode("run-1", "mcp:step1", "user-1", "org-of-run", Map.of());

            assertThat(observedOrg).hasValue("org-of-run");
            assertThat(observedRole.get()).isNull();
        }

        @Test
        @DisplayName("guard: 404 when the run does not exist (client-supplied runId over WS)")
        void rejectsUnknownRun() {
            when(runRepository.findByRunIdPublic("run-x")).thenReturn(java.util.Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-x", "mcp:step1", "user-1", "org-1", Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).containsEntry("error", "RUN_NOT_FOUND");
            verify(stateSnapshotService, never()).claimNodeForExecution(any(), any());
            verify(v2StepByStepService, never()).executeNode(any(), any(), any());
        }

        @Test
        @DisplayName("guard: 404 when the caller's workspace does not match the run's org (forged/stale sbs.execute)")
        void rejectsForeignOrgRun() {
            // Same mirror as the HTTP twin's guardRunScope: a WS client must not be
            // able to step a run living in a workspace it is not currently in.
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setTenantId("someone-else");
            run.setOrganizationId("org-of-run");
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(java.util.Optional.of(run));

            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-1", "mcp:step1", "user-1", "org-from-stale-session", Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            verify(stateSnapshotService, never()).claimNodeForExecution(any(), any());
            verify(v2StepByStepService, never()).executeNode(any(), any(), any());
        }

        @Test
        @DisplayName("guard: 401 when X-User-ID is missing")
        void rejectsMissingUser() {
            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-1", "mcp:step1", null, "org-1", Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            verify(creditClient, never()).checkCredits(any());
            verify(v2StepByStepService, never()).executeNode(any(), any(), any());
        }

        @Test
        @DisplayName("guard: 401 when X-User-ID is blank")
        void rejectsBlankUser() {
            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-1", "mcp:step1", "  ", "org-1", Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            verify(v2StepByStepService, never()).executeNode(any(), any(), any());
        }

        @Test
        @DisplayName("Should reconcile SBS run status after execution")
        void shouldReconcileRunStatusAfterExecution() {
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());

            controller.executeNode("run-1", "mcp:step1", "user-1", "org-1", Map.of());

            verify(stateSnapshotService).reconcileSbsRunStatus("run-1");
        }

        @Test
        @DisplayName("Should pass itemId from data")
        void shouldPassItemId() {
            controller.executeNode("run-1", "mcp:step1", "user-1", "org-1",
                    Map.of("itemId", "0.1"));

            verify(v2StepByStepService).executeNode("run-1", "mcp:step1", "0.1");
        }

        @Test
        @DisplayName("Should update plan if present in data")
        @SuppressWarnings("unchecked")
        void shouldUpdatePlanIfPresent() {
            Map<String, Object> plan = Map.of("triggers", java.util.List.of());
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("plan", plan);

            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());

            controller.executeNode("run-1", "mcp:step1", "user-1", "org-1", data);

            verify(resumeService).updateRunPlan(eq("run-1"), eq(plan));
        }

        @Test
        @DisplayName("Should handle null data gracefully")
        void shouldHandleNullData() {
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(Set.of());

            ResponseEntity<Map<String, Object>> response = controller.executeNode(
                    "run-1", "mcp:step1", "user-1", "org-1", null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(v2StepByStepService).executeNode("run-1", "mcp:step1", "0");
        }

        @Test
        @DisplayName("Should execute split items when pending")
        void shouldExecuteSplitItems() {
            Set<String> pendingItems = Set.of("0.0", "0.1");
            when(v2StepByStepScheduler.getPendingItemIdsForNode("run-1", "mcp:step1"))
                    .thenReturn(pendingItems);

            controller.executeNode("run-1", "mcp:step1", "user-1", "org-1", Map.of());

            verify(v2StepByStepService).executeSplitItems("run-1", "mcp:step1", pendingItems);
            verify(v2StepByStepService, never()).executeNode(anyString(), anyString(), anyString());
        }
    }
}
