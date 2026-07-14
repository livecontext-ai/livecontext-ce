package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for TriggerController.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerController")
class TriggerControllerTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private ReusableTriggerService triggerService;

    @Mock
    private WorkflowResumeService resumeService;

    @Mock
    private com.apimarketplace.common.credit.CreditConsumptionClient creditClient;

    private TriggerController controller;

    /** Caller identity used by the trigger fires below - matches each run's stubbed tenant so the
     * TriggerController scope guard (isRunInScope) passes. */
    private static final String CALLER = "tenant-1";

    @BeforeEach
    void setUp() {
        lenient().when(creditClient.checkCredits(any())).thenReturn(true);
        controller = new TriggerController(runRepository, triggerService, resumeService, creditClient);
    }

    @Nested
    @DisplayName("TriggerResponse")
    class TriggerResponseTests {

        @Test
        @DisplayName("success should create triggered response")
        void successShouldCreateTriggered() {
            TriggerController.TriggerResponse response = TriggerController.TriggerResponse.success(
                "run-1", "trigger:webhook", TriggerType.WEBHOOK, 2, "OK", Set.of("mcp:step1"));

            assertThat(response.runId()).isEqualTo("run-1");
            assertThat(response.triggerId()).isEqualTo("trigger:webhook");
            assertThat(response.triggerType()).isEqualTo("webhook");
            assertThat(response.status()).isEqualTo("triggered");
            assertThat(response.message()).isEqualTo("OK");
            assertThat(response.epoch()).isEqualTo(2);
            assertThat(response.readySteps()).contains("mcp:step1");
        }

        @Test
        @DisplayName("success should handle null readySteps")
        void successShouldHandleNullReadySteps() {
            TriggerController.TriggerResponse response = TriggerController.TriggerResponse.success(
                "run-1", "trigger:manual", TriggerType.MANUAL, 1, "OK", null);

            assertThat(response.readySteps()).isEmpty();
        }

        @Test
        @DisplayName("error should create error response")
        void errorShouldCreateErrorResponse() {
            TriggerController.TriggerResponse response = TriggerController.TriggerResponse.error(
                "run-1", "Not found");

            assertThat(response.runId()).isEqualTo("run-1");
            assertThat(response.triggerId()).isNull();
            assertThat(response.triggerType()).isNull();
            assertThat(response.status()).isEqualTo("error");
            assertThat(response.message()).isEqualTo("Not found");
            assertThat(response.epoch()).isZero();
        }
    }

    @Nested
    @DisplayName("updateUserPlanMetadata - per-fire hot-row write skip")
    class UserPlanMetadataSkipTests {

        private void invoke(WorkflowRunEntity run, String userPlan) throws Exception {
            var m = TriggerController.class.getDeclaredMethod(
                    "updateUserPlanMetadata", WorkflowRunEntity.class, String.class);
            m.setAccessible(true);
            m.invoke(controller, run, userPlan);
        }

        private WorkflowRunEntity runWithPlan(String stampedPlan) {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic("run-1");
            if (stampedPlan != null) {
                run.setMetadata(new java.util.HashMap<>(Map.of("userPlan", stampedPlan)));
            }
            return run;
        }

        @Test
        @DisplayName("skips the upsert when metadata.userPlan already matches (reusable-trigger common case)")
        void skipsWhenUnchanged() throws Exception {
            invoke(runWithPlan("PRO"), "PRO");
            verify(runRepository, never()).upsertUserPlanMetadata(anyString(), anyString());
        }

        @Test
        @DisplayName("writes when the plan changed")
        void writesWhenChanged() throws Exception {
            when(runRepository.upsertUserPlanMetadata("run-1", "PRO")).thenReturn(1);
            invoke(runWithPlan("FREE"), "PRO");
            verify(runRepository).upsertUserPlanMetadata("run-1", "PRO");
        }

        @Test
        @DisplayName("writes on the first stamp (no metadata yet)")
        void writesWhenNoMetadata() throws Exception {
            when(runRepository.upsertUserPlanMetadata("run-1", "PRO")).thenReturn(1);
            invoke(runWithPlan(null), "PRO");
            verify(runRepository).upsertUserPlanMetadata("run-1", "PRO");
        }

        @Test
        @DisplayName("no-op when userPlan is blank")
        void noOpWhenBlank() throws Exception {
            invoke(runWithPlan("PRO"), "  ");
            verify(runRepository, never()).upsertUserPlanMetadata(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("triggerManual")
    class TriggerManualTests {

        @Test
        @DisplayName("Should return 404 when run not found")
        void shouldReturn404WhenRunNotFound() {
            when(runRepository.findByRunIdPublic("nonexistent")).thenReturn(Optional.empty());

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual("nonexistent", null, null, CALLER, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return 409 when run is not WAITING_TRIGGER")
        void shouldReturn409WhenNotWaiting() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(CALLER);
            when(run.getStatus()).thenReturn(RunStatus.CANCELLED);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual("run-1", null, null, CALLER, null);

            assertThat(response.getStatusCode().value()).isEqualTo(409);
        }

        @Test
        @DisplayName("Returns 409 on FAILED - terminal runs require explicit reactivation, not auto-cycle")
        void shouldReturn409WhenFailed() {
            // Regression for prod 2026-05-07 12:40 UTC: run_<id> stayed
            // FAILED after the JVM crashed mid-cycle (resetForNextCycle never ran), yet the
            // controller kept accepting trigger fires which opened a new epoch each time -
            // 73 epochs accumulated before the user noticed. A genuinely terminal run must
            // require an explicit reactivation.
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(CALLER);
            when(run.getStatus()).thenReturn(RunStatus.FAILED);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual("run-1", null, null, CALLER, null);

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            // The run is never handed to the trigger service when terminal - no cycling.
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Returns 409 on COMPLETED - same terminal contract as FAILED")
        void shouldReturn409WhenCompleted() {
            // The pre-fix code accepted COMPLETED as fireable on the assumption that the
            // cycle would re-arm. In normal flow resetForNextCycle transitions a finishing
            // cycle to WAITING_TRIGGER (line ~1289 of ReusableTriggerService), so the only
            // way this controller sees status=COMPLETED is when the reset never ran. Treat
            // it as terminal too.
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(CALLER);
            when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual("run-1", null, null, CALLER, null);

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Returns 409 on TIMEOUT and PARTIAL_SUCCESS too - every isTerminal() status is rejected")
        void shouldReturn409WhenTimeoutOrPartialSuccess() {
            for (RunStatus terminal : new RunStatus[]{
                    RunStatus.TIMEOUT, RunStatus.PARTIAL_SUCCESS, RunStatus.SKIPPED}) {
                WorkflowRunEntity run = mock(WorkflowRunEntity.class);
                lenient().when(run.getTenantId()).thenReturn(CALLER);
                when(run.getStatus()).thenReturn(terminal);
                when(runRepository.findByRunIdPublic("run-" + terminal)).thenReturn(Optional.of(run));

                ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerManual("run-" + terminal, null, null, CALLER, null);

                assertThat(response.getStatusCode().value())
                    .as("status %s must be rejected", terminal)
                    .isEqualTo(409);
            }
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Returns 409 on PENDING - only WAITING_TRIGGER/RUNNING/PAUSED are fireable, no others")
        void shouldReturn409WhenPending() {
            // Pin the contract: PENDING is a transient pre-WAITING_TRIGGER state. The fire
            // endpoints reject it, and getAvailableTriggers (which uses isTerminal()) does
            // NOT reject it - that asymmetry is intentional. PENDING runs surface their
            // triggers in the listing UI but the fire returns 409 until the run reaches
            // WAITING_TRIGGER. Document the contract here so the asymmetry doesn't drift.
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(CALLER);
            when(run.getStatus()).thenReturn(RunStatus.PENDING);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual("run-1", null, null, CALLER, null);

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("Happy-path WAITING_TRIGGER passes the canTrigger gate - only the canTrigger gate is asserted")
        void shouldNotBeRejectedAt409OnWaitingTrigger() {
            // Without this test, a regression that tightens canTrigger to e.g. only RUNNING
            // would silently break every webhook/manual fire and only the negative tests
            // would still pass. We don't mock the full plan/trigger pipeline - just assert
            // the controller doesn't return 409 (the canTrigger rejection code).
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(CALLER);
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual("run-1", null, null, CALLER, null);

            assertThat(response.getStatusCode().value())
                .as("WAITING_TRIGGER must pass canTrigger (not return 409)")
                .isNotEqualTo(409);
        }

        @Test
        @DisplayName("RUNNING and PAUSED also pass the canTrigger gate - parallel epochs and step-by-step keep working")
        void runningAndPausedPassCanTrigger() {
            for (RunStatus fireable : new RunStatus[]{RunStatus.RUNNING, RunStatus.PAUSED}) {
                WorkflowRunEntity run = mock(WorkflowRunEntity.class);
                lenient().when(run.getTenantId()).thenReturn(CALLER);
                when(run.getStatus()).thenReturn(fireable);
                when(run.getTenantId()).thenReturn("tenant-1");
                when(runRepository.findByRunIdPublic("run-" + fireable)).thenReturn(Optional.of(run));

                ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerManual("run-" + fireable, null, null, CALLER, null);

                assertThat(response.getStatusCode().value())
                    .as("status %s must pass canTrigger (not return 409)", fireable)
                    .isNotEqualTo(409);
            }
        }
    }

    @Nested
    @DisplayName("triggerChat")
    class TriggerChatTests {

        @Test
        @DisplayName("Should return 400 when payload missing message")
        void shouldReturn400WhenNoMessage() {
            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerChat("run-1", Map.of("other", "data"), null, CALLER, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("message");
        }

        @Test
        @DisplayName("Should return 400 when payload is null")
        void shouldReturn400WhenPayloadNull() {
            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerChat("run-1", null, null, CALLER, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("getAvailableTriggers")
    class GetAvailableTriggersTests {

        @Test
        @DisplayName("Should return 404 when run not found")
        void shouldReturn404WhenRunNotFound() {
            when(runRepository.findByRunIdPublic("nonexistent")).thenReturn(Optional.empty());

            var response = controller.getAvailableTriggers("nonexistent", CALLER, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return empty list when plan is null")
        void shouldReturnEmptyWhenPlanNull() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(CALLER);
            when(run.getPlan()).thenReturn(null);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            var response = controller.getAvailableTriggers("run-1", CALLER, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty list on terminal run - UI should not show fireable triggers when fire endpoint will reject them")
        void shouldReturnEmptyOnTerminalRun() {
            // Pin the contract that aligns the listing endpoint with the fire endpoints.
            // Without this, the UI of run_<id> (FAILED, 73 epochs) showed
            // the trigger as fireable; clicking would have hit a 409 from the fire endpoint.
            // The trigger plan is never even parsed for terminal runs (cheap short-circuit).
            for (RunStatus terminal : new RunStatus[]{
                    RunStatus.FAILED, RunStatus.COMPLETED, RunStatus.CANCELLED,
                    RunStatus.TIMEOUT, RunStatus.PARTIAL_SUCCESS, RunStatus.SKIPPED}) {
                WorkflowRunEntity run = mock(WorkflowRunEntity.class);
                lenient().when(run.getTenantId()).thenReturn(CALLER);
                when(run.getStatus()).thenReturn(terminal);
                when(runRepository.findByRunIdPublic("run-" + terminal)).thenReturn(Optional.of(run));

                var response = controller.getAvailableTriggers("run-" + terminal, CALLER, null);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody())
                    .as("status %s must surface no triggers", terminal)
                    .isEmpty();
                // Plan is not even read - the short-circuit fires before parsing.
                verify(run, org.mockito.Mockito.never()).getPlan();
            }
        }
    }

    @Nested
    @DisplayName("AUTOMATIC vs STEP_BY_STEP dispatch")
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    class DispatchModeTests {
        // Pin the contract introduced when the HTTP trigger endpoints were
        // switched from synchronous-wait to fire-and-forget for AUTOMATIC runs:
        //   AUTOMATIC → executeTriggerAsync (frees the Tomcat thread; SSE feeds the UI)
        //   STEP_BY_STEP → executeTrigger (engine pauses immediately, readySteps
        //                  seeds the SBS panel - keep sync)
        // A regression that swaps the branch (e.g. running SBS through async)
        // would silently break the SBS UI; sending AUTOMATIC through sync
        // brings back the 11s p99 we just fixed.

        private WorkflowRunEntity automaticRun(String runId) {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(CALLER);
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(run.getRunIdPublic()).thenReturn(runId);
            when(run.getExecutionMode()).thenReturn(
                com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.AUTOMATIC);
            when(run.getPlan()).thenReturn(Map.of(
                "triggers", java.util.List.of(Map.of(
                    "id", "manual-1",
                    "label", "manual_trigger",
                    "type", "manual",
                    "strategy", "single",
                    "params", Map.of()
                ))
            ));
            return run;
        }

        private WorkflowRunEntity sbsRun(String runId) {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getTenantId()).thenReturn(CALLER);
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(run.getTenantId()).thenReturn("tenant-1");
            when(run.getRunIdPublic()).thenReturn(runId);
            when(run.getExecutionMode()).thenReturn(
                com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.STEP_BY_STEP);
            when(run.getPlan()).thenReturn(Map.of(
                "triggers", java.util.List.of(Map.of(
                    "id", "manual-1",
                    "label", "manual_trigger",
                    "type", "manual",
                    "strategy", "single",
                    "params", Map.of()
                ))
            ));
            return run;
        }

        @Test
        @DisplayName("triggerManual on AUTOMATIC run dispatches via executeTriggerAsync (no sync wait)")
        void automaticRoutesToAsync() {
            WorkflowRunEntity run = automaticRun("run-auto");
            when(runRepository.findByRunIdPublic("run-auto")).thenReturn(Optional.of(run));
            when(triggerService.executeTriggerAsync(eq(run), any(), eq(TriggerType.MANUAL), any()))
                .thenReturn(TriggerExecutionResult.accepted("run-auto", "trigger:manual_trigger", TriggerType.MANUAL));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual("run-auto", null, null, CALLER, null);

            assertThat(response.getStatusCode().value()).isEqualTo(202);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo("triggered");
            assertThat(response.getBody().epoch())
                .as("AUTOMATIC must report epoch=-1 (placeholder until SSE)")
                .isEqualTo(-1);
            verify(triggerService).executeTriggerAsync(eq(run), any(), eq(TriggerType.MANUAL), any());
            verify(triggerService, never())
                .executeTrigger(eq(run), any(), eq(TriggerType.MANUAL), any());
        }

        @Test
        @DisplayName("triggerManual on STEP_BY_STEP run keeps the sync path (executeTrigger, not async)")
        void stepByStepRoutesToSync() {
            WorkflowRunEntity run = sbsRun("run-sbs");
            when(runRepository.findByRunIdPublic("run-sbs")).thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), any(), eq(TriggerType.MANUAL), any()))
                .thenReturn(TriggerExecutionResult.success(
                    "run-sbs", "trigger:manual_trigger", TriggerType.MANUAL,
                    Set.of("mcp:step1"), 0));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual("run-sbs", null, null, CALLER, null);

            assertThat(response.getStatusCode().value()).isEqualTo(202);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().epoch())
                .as("SBS path returns the real epoch from the sync result")
                .isEqualTo(0);
            assertThat(response.getBody().readySteps())
                .as("SBS path must seed readySteps for the panel - async would not deliver this")
                .containsExactly("mcp:step1");
            verify(triggerService).executeTrigger(eq(run), any(), eq(TriggerType.MANUAL), any());
            verify(triggerService, never())
                .executeTriggerAsync(eq(run), any(), eq(TriggerType.MANUAL), any());
        }

        @Test
        @DisplayName("triggerSpecific on AUTOMATIC run dispatches via executeTriggerAsync")
        void specificAutomaticRoutesToAsync() {
            WorkflowRunEntity run = automaticRun("run-auto-spec");
            when(runRepository.findByRunIdPublic("run-auto-spec")).thenReturn(Optional.of(run));
            when(triggerService.executeTriggerAsync(eq(run), eq("trigger:manual_trigger"),
                                                   eq(TriggerType.MANUAL), any()))
                .thenReturn(TriggerExecutionResult.accepted(
                    "run-auto-spec", "trigger:manual_trigger", TriggerType.MANUAL));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerSpecific("run-auto-spec", "manual", "trigger:manual_trigger", null, null, CALLER, null);

            assertThat(response.getStatusCode().value()).isEqualTo(202);
            verify(triggerService).executeTriggerAsync(eq(run), eq("trigger:manual_trigger"),
                                                      eq(TriggerType.MANUAL), any());
            verify(triggerService, never()).executeTrigger(eq(run), eq("trigger:manual_trigger"),
                                                           eq(TriggerType.MANUAL), any());
        }

        @Test
        @DisplayName("triggerSpecific on STEP_BY_STEP run keeps the sync path")
        void specificStepByStepRoutesToSync() {
            WorkflowRunEntity run = sbsRun("run-sbs-spec");
            when(runRepository.findByRunIdPublic("run-sbs-spec")).thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq("trigger:manual_trigger"),
                                              eq(TriggerType.MANUAL), any()))
                .thenReturn(TriggerExecutionResult.success(
                    "run-sbs-spec", "trigger:manual_trigger", TriggerType.MANUAL,
                    Set.of("mcp:next"), 0));

            ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerSpecific("run-sbs-spec", "manual", "trigger:manual_trigger", null, null, CALLER, null);

            assertThat(response.getStatusCode().value()).isEqualTo(202);
            assertThat(response.getBody().readySteps()).containsExactly("mcp:next");
            verify(triggerService).executeTrigger(eq(run), eq("trigger:manual_trigger"),
                                                  eq(TriggerType.MANUAL), any());
            verify(triggerService, never())
                .executeTriggerAsync(eq(run), eq("trigger:manual_trigger"),
                                    eq(TriggerType.MANUAL), any());
        }
    }

    @Nested
    @DisplayName("scope guard - cross-tenant trigger-fire IDOR")
    class ScopeGuardTests {

        @Test
        @DisplayName("a caller in a DIFFERENT tenant is blocked (404) and the trigger never fires - the IDOR fix")
        void crossTenantTriggerFireIsBlocked() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getTenantId()).thenReturn("owner-tenant");
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // triggerManual and triggerSpecific must BOTH reject a foreign caller before firing.
            ResponseEntity<TriggerController.TriggerResponse> manual =
                    controller.triggerManual("run-1", null, null, "attacker-tenant", null);
            ResponseEntity<TriggerController.TriggerResponse> specific =
                    controller.triggerSpecific("run-1", "manual", "trigger:x", null, null, "attacker-tenant", null);

            assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(specific.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("the run's owner (matching tenant) is NOT blocked by the scope guard")
        void sameTenantOwnerPassesScopeGuard() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getTenantId()).thenReturn("owner-tenant");
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<TriggerController.TriggerResponse> response =
                    controller.triggerManual("run-1", null, null, "owner-tenant", null);

            // Passes the scope guard (not 404); downstream may reject for other reasons, never the guard.
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("getAvailableTriggers 404s for a cross-tenant caller (metadata IDOR closed)")
        void getAvailableTriggersCrossTenantIsBlocked() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getTenantId()).thenReturn("owner-tenant");
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<java.util.List<TriggerInfo>> response =
                    controller.getAvailableTriggers("run-1", "attacker-tenant", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("an APPLICATION share visitor bound to the run's publication is ALLOWED to fire (share-context binding passes) "
                + "- proves the interactive trigger allow-list is not inert")
        void shareContextFireAllowedForMatchingPublication() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getTenantId()).thenReturn("owner-tenant");
            when(run.getPublicationId()).thenReturn("pub-1");
            when(run.getStatus()).thenReturn(RunStatus.WAITING_TRIGGER);
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            // Simulate the gateway/monolith share-context headers: owner-impersonation for APPLICATION
            // pub-1, which is exactly the run's publicationId.
            org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
            req.addHeader("X-Share-Context", "true");
            req.addHeader("X-Share-Resource-Type", "APPLICATION");
            req.addHeader("X-Share-Resource-Token", "pub-1");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(req));
            try {
                ResponseEntity<TriggerController.TriggerResponse> response =
                        controller.triggerManual("run-1", null, null, "owner-tenant", null);
                // The share binding passes -> NOT 404 (downstream may 400/409, never the guard's 404).
                assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        @DisplayName("an APPLICATION share visitor whose token does NOT match the run's publication is blocked (404)")
        void shareContextFireBlockedForForeignPublication() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getTenantId()).thenReturn("owner-tenant");
            when(run.getPublicationId()).thenReturn("pub-1");
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
            req.addHeader("X-Share-Context", "true");
            req.addHeader("X-Share-Resource-Type", "APPLICATION");
            req.addHeader("X-Share-Resource-Token", "pub-OTHER");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(req));
            try {
                ResponseEntity<TriggerController.TriggerResponse> response =
                        controller.triggerManual("run-1", null, null, "owner-tenant", null);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }
    }
}
