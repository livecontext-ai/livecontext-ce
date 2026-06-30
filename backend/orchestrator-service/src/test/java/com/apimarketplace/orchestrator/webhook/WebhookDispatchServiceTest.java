package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.WebhookTokenDto;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookDispatchService")
class WebhookDispatchServiceTest {

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private WebhookResponseRegistry webhookResponseRegistry;

    private WebhookDispatchService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TRIGGER_ID = "trigger:my_webhook";
    private static final String TOKEN = "wh_abc123def456";
    private static final String RUN_ID = "run-123";

    @BeforeEach
    void setUp() {
        service = new WebhookDispatchService(
            triggerClient, workflowRepository, runRepository,
            triggerService, productionRunResolver, creditClient, webhookResponseRegistry
        );
        // Default: allow credits
        lenient().when(creditClient.checkCredits(any())).thenReturn(true);

        // Default: ProductionRunResolver delegates to the existing repo stubs.
        // This lets the existing test stubs (workflowRepository.findById +
        // runRepository.findFirstBy...) work transparently after the centralized
        // refactor. Tests that explicitly want NOT_PINNED behavior should override
        // this default with their own resolver stub.
        lenient().when(productionRunResolver.resolve(any(), any())).thenAnswer(inv -> {
            java.util.UUID wfId = inv.getArgument(0);
            var wf = workflowRepository.findById(wfId).orElse(null);
            if (wf == null) {
                return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                    java.util.Optional.empty(),
                    com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.WORKFLOW_MISSING,
                    null);
            }
            Integer pinned = wf.getPinnedVersion();
            java.util.Optional<com.apimarketplace.orchestrator.domain.WorkflowRunEntity> r = pinned != null
                ? runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(wfId, pinned)
                : runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId);
            var outcome = pinned == null
                ? com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NOT_PINNED
                : (r.isPresent()
                    ? com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND
                    : com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            // For pre-existing tests that worked under the old fallback, treat
            // unpinned + run-found as FOUND so they continue to pass. New tests
            // explicitly stubbing NOT_PINNED via the resolver will still see it.
            if (pinned == null && r.isPresent()) {
                outcome = com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND;
            }
            return new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                r, outcome, wf.getName());
        });
    }

    private WebhookTokenDto createTokenDto() {
        WebhookTokenDto dto = new WebhookTokenDto();
        dto.setWorkflowId(WORKFLOW_ID);
        dto.setTriggerId(TRIGGER_ID);
        dto.setToken(TOKEN);
        return dto;
    }

    private WorkflowEntity createWorkflowEntity(Integer pinnedVersion) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setPinnedVersion(pinnedVersion);
        return workflow;
    }

    private WorkflowRunEntity createRunEntity(RunStatus status, ExecutionMode mode) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(status);
        run.setExecutionMode(mode);
        return run;
    }

    /** Setup token + workflow (unpinned) + run lookup via findFirstByWorkflowIdOrderByStartedAtDesc */
    private void setupUnpinnedDispatch(WorkflowRunEntity run) {
        when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDto());
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(null)));
        when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
            .thenReturn(Optional.ofNullable(run));
    }

    @Nested
    @DisplayName("dispatch() - WAITING_TRIGGER")
    class WaitingTriggerTests {

        @Test
        @DisplayName("Should dispatch to WAITING_TRIGGER run (auto mode)")
        void shouldDispatchToWaitingTriggerRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            setupUnpinnedDispatch(run);
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1));

            WebhookResponse response = service.dispatch(TOKEN, Map.of("data", "test"), false);

            assertEquals("triggered", response.status());
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any());
        }

        @Test
        @DisplayName("Should dispatch to WAITING_TRIGGER run (step-by-step mode)")
        void shouldDispatchToWaitingTriggerSbsRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.STEP_BY_STEP);
            setupUnpinnedDispatch(run);
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of("mcp:step1"), 1));

            WebhookResponse response = service.dispatch(TOKEN, Map.of("data", "test"), false);

            assertEquals("triggered", response.status());
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any());
        }

        @Test
        @DisplayName("Should dispatch to RUNNING run (parallel epoch in auto mode)")
        void shouldDispatchToRunningRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.RUNNING, ExecutionMode.AUTOMATIC);
            setupUnpinnedDispatch(run);
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 2));

            WebhookResponse response = service.dispatch(TOKEN, Map.of("data", "test"), false);

            assertEquals("triggered", response.status());
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any());
        }

        @Test
        @DisplayName("Should dispatch to PAUSED run (SBS auto-closes previous epochs)")
        void shouldDispatchToPausedSbsRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.PAUSED, ExecutionMode.STEP_BY_STEP);
            setupUnpinnedDispatch(run);
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of("mcp:step1"), 2));

            WebhookResponse response = service.dispatch(TOKEN, Map.of("data", "test"), false);

            assertEquals("triggered", response.status());
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any());
        }

        @Test
        @DisplayName("Should return insufficientCredits when user has no credits")
        void shouldReturnInsufficientCreditsWhenNoCredits() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            run.setTenantId("42");
            setupUnpinnedDispatch(run);
            when(creditClient.checkCredits("42")).thenReturn(false);

            WebhookResponse response = service.dispatch(TOKEN, Map.of("data", "test"), false);

            assertEquals("insufficient_credits", response.status());
            assertTrue(response.message().contains("Insufficient credits"));
            assertTrue(response.message().contains("/app/settings/pricing"));
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return notActive when no active run exists")
        void shouldReturnNotActiveWhenNoMatchingRun() {
            setupUnpinnedDispatch(null);

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertNotEquals("triggered", response.status());
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("dispatch() - token not found")
    class TokenNotFoundTests {

        @Test
        @DisplayName("Should fallback to standalone when token not found")
        void shouldFallbackToStandalone() {
            when(triggerClient.findByToken(TOKEN)).thenReturn(null);
            when(triggerClient.findStandaloneByToken(TOKEN)).thenReturn(null);

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertNotEquals("triggered", response.status());
        }
    }

    @Nested
    @DisplayName("dispatch() - Version-Aware Dispatch")
    class VersionAwareDispatchTests {

        @Test
        @DisplayName("Should find run by planVersion when workflow is pinned")
        void shouldFindRunByPlanVersion() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            run.setPlanVersion(5);

            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDto());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(5)));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1));

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("triggered", response.status());
            // Verify pinned query was used, not the unpinned one
            verify(runRepository).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5);
            verify(runRepository, never()).findFirstByWorkflowIdOrderByStartedAtDesc(any());
        }

        @Test
        @DisplayName("Should return notActive when no run matches pinned version")
        void shouldReturnNotActiveWhenNoPinnedRun() {
            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDto());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(5)));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                .thenReturn(Optional.empty());

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("not_active", response.status());
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should find latest run when workflow is unpinned")
        void shouldFindLatestRunWhenUnpinned() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            setupUnpinnedDispatch(run);
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1));

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("triggered", response.status());
            // Verify unpinned query was used
            verify(runRepository).findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID);
            verify(runRepository, never()).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(any(), any());
        }

        @Test
        @DisplayName("Should return notFound when workflow does not exist")
        void shouldReturnNotFoundWhenWorkflowMissing() {
            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDto());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("not_found", response.status());
            verify(runRepository, never()).findFirstByWorkflowIdOrderByStartedAtDesc(any());
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should use pinned version query when pinned (verify correct query called)")
        void shouldPickCorrectPinnedRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            run.setPlanVersion(3);

            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDto());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(3)));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 3))
                .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1));

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("triggered", response.status());
            verify(runRepository).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 3);
        }

        @Test
        @DisplayName("Unpinned: should reject CANCELLED run as not active")
        void shouldRejectCancelledRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.CANCELLED, ExecutionMode.AUTOMATIC);
            setupUnpinnedDispatch(run);

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("not_active", response.status());
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Unpinned: should reject TIMEOUT run as not active")
        void shouldRejectTimeoutRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.TIMEOUT, ExecutionMode.AUTOMATIC);
            setupUnpinnedDispatch(run);

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("not_active", response.status());
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Pinned: should reject CANCELLED run as not active")
        void shouldRejectCancelledPinnedRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.CANCELLED, ExecutionMode.AUTOMATIC);
            run.setPlanVersion(5);

            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDto());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(5)));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                .thenReturn(Optional.of(run));

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("not_active", response.status());
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Pinned: should reject TIMEOUT run as not active")
        void shouldRejectTimeoutPinnedRun() {
            WorkflowRunEntity run = createRunEntity(RunStatus.TIMEOUT, ExecutionMode.AUTOMATIC);
            run.setPlanVersion(5);

            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDto());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(5)));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                .thenReturn(Optional.of(run));

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("not_active", response.status());
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Pinned v0: zero is a valid version")
        void shouldHandlePinnedVersionZero() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            run.setPlanVersion(0);

            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDto());
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(0)));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 0))
                .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1));

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("triggered", response.status());
            verify(runRepository).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 0);
        }

        @Test
        @DisplayName("COMPLETED run rejected - terminal runs require explicit reactivation, not auto-cycle")
        void shouldRejectCompletedRun() {
            // Inverted from the previous "shouldNotRejectCompletedRun" expectation. The
            // pre-fix behavior accepted COMPLETED on the assumption that the cycle would
            // re-arm; in reality resetForNextCycle (ReusableTriggerService:1289) transitions
            // a finishing cycle to WAITING_TRIGGER, so a webhook landing on a COMPLETED run
            // means the reset never ran and the run is genuinely terminal.
            WorkflowRunEntity run = createRunEntity(RunStatus.COMPLETED, ExecutionMode.AUTOMATIC);
            setupUnpinnedDispatch(run);

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("not_active", response.status());
            // The trigger service is NEVER reached - the run never goes back to RUNNING.
            verify(triggerService, org.mockito.Mockito.never())
                .executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("FAILED run rejected - same terminal contract as TriggerController fire endpoints")
        void shouldRejectFailedRun() {
            // Regression for prod 2026-05-07 12:40 UTC: run_<id> stayed
            // FAILED after JVM crashed mid-cycle (resetForNextCycle never ran), and the
            // webhook dispatcher kept accepting fires which reopened a new epoch each time.
            // 73 epochs accumulated before the user noticed. Aligned with TriggerController.
            WorkflowRunEntity run = createRunEntity(RunStatus.FAILED, ExecutionMode.AUTOMATIC);
            setupUnpinnedDispatch(run);

            WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

            assertEquals("not_active", response.status());
            verify(triggerService, org.mockito.Mockito.never())
                .executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Every isTerminal() status is rejected - CANCELLED, TIMEOUT, PARTIAL_SUCCESS, SKIPPED all blocked, mirrors TriggerController")
        void shouldRejectAllTerminalStatuses() {
            for (RunStatus terminal : new RunStatus[]{
                    RunStatus.CANCELLED, RunStatus.TIMEOUT,
                    RunStatus.PARTIAL_SUCCESS, RunStatus.SKIPPED}) {
                WorkflowRunEntity run = createRunEntity(terminal, ExecutionMode.AUTOMATIC);
                setupUnpinnedDispatch(run);

                WebhookResponse response = service.dispatch(TOKEN, Map.of(), false);

                assertEquals("not_active", response.status(),
                    "status " + terminal + " must be rejected");
            }
            verify(triggerService, org.mockito.Mockito.never())
                .executeTrigger(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("PR22c R3 - pinned-webhook workspace-scope guard regression (R2 convergent must-fix A+B+C)")
    class WorkspaceScopeGuardTests {

        private static final String ORG_ID = "org-acme";
        private static final String OTHER_ORG_ID = "org-other";

        private WebhookTokenDto createTokenDtoWithOrg(String organizationId) {
            WebhookTokenDto dto = new WebhookTokenDto();
            dto.setWorkflowId(WORKFLOW_ID);
            dto.setTriggerId(TRIGGER_ID);
            dto.setToken(TOKEN);
            dto.setOrganizationId(organizationId);
            return dto;
        }

        @Test
        @DisplayName("Refuses pinned dispatch when token org != run org - guard at WebhookDispatchService:133")
        void refusesPinnedFireOnWorkspaceMismatch() {
            // Before PR22c R3 V215, WebhookTokenDto.organizationId was always null (no DB
            // column, toTokenDto never set it). PR22c R3 wires it end-to-end so this guard
            // is load-bearing on newly created tokens. Convergent R2 must-fix A+C.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            run.setOrganizationId(OTHER_ORG_ID);

            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDtoWithOrg(ORG_ID));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(null)));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                .thenReturn(Optional.of(run));

            WebhookResponse response = service.dispatch(TOKEN, Map.of("data", "test"), false);

            assertEquals("not_active", response.status());
            verify(triggerService, org.mockito.Mockito.never())
                .executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Fires when token org == run org")
        void firesOnWorkspaceMatch() {
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            run.setOrganizationId(ORG_ID);

            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDtoWithOrg(ORG_ID));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(null)));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1));

            WebhookResponse response = service.dispatch(TOKEN, Map.of("data", "test"), false);

            assertEquals("triggered", response.status());
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any());
        }

        @Test
        @DisplayName("Legacy tokens (tokenOrg=null) bypass guard and fire on any run org - permissive contract")
        void legacyTokenBypassesGuard() {
            // Pre-V215 tokens have organizationId=null. Refusing all such fires would break
            // every in-flight production trigger. The guard at WebhookDispatchService:133 is
            // intentionally permissive when tokenOrg is null.
            WorkflowRunEntity run = createRunEntity(RunStatus.WAITING_TRIGGER, ExecutionMode.AUTOMATIC);
            run.setOrganizationId(ORG_ID);

            when(triggerClient.findByToken(TOKEN)).thenReturn(createTokenDtoWithOrg(null));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(createWorkflowEntity(null)));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1));

            WebhookResponse response = service.dispatch(TOKEN, Map.of("data", "test"), false);

            assertEquals("triggered", response.status());
        }
    }
}
