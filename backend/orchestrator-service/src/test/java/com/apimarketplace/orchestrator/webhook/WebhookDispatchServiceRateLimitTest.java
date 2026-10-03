package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.WebhookTokenDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for LC-042: {@code POST /webhook/{token}} had no rate limit at all.
 *
 * <p>{@code WebhookResponse.rateLimited()} existed and the controller mapped it to 429, but the
 * factory had ZERO producers, so the only per-fire gate was a boolean credit check. An
 * unauthenticated caller who knows a token could fire a workflow as fast as the network allowed,
 * and {@code sync=true} additionally parks a {@code DeferredResult} for up to 60s per request.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookDispatchService - fire-path rate limiting (LC-042)")
class WebhookDispatchServiceRateLimitTest {

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private WebhookResponseRegistry webhookResponseRegistry;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TRIGGER_ID = "trigger:my_webhook";
    private static final String TOKEN = "wh_abc123def456";
    private static final String OWNER = "tenant-42";
    private static final String RUN_ID = "run-123";

    /** Advanced by the tests so the sliding window is exercised without sleeping. */
    private MovableClock clock;
    private WebhookDispatchService service;

    /** A clock the test moves by hand: a sliding window is a function of time, not of sleeping. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-13T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        service = new WebhookDispatchService(
                triggerClient, workflowRepository, runRepository, triggerService,
                productionRunResolver, webhookResponseRegistry,
                new WebhookDispatchService.WebhookRateLimiter(clock));
    }

    private WorkflowRunEntity waitingRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setExecutionMode(ExecutionMode.AUTOMATIC);
        return run;
    }

    /** Wires a token that resolves to a fireable run owned by {@link #OWNER}. */
    private void wireFireableToken() {
        WebhookTokenDto dto = new WebhookTokenDto();
        dto.setWorkflowId(WORKFLOW_ID);
        dto.setTriggerId(TRIGGER_ID);
        dto.setToken(TOKEN);

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(OWNER);

        WorkflowRunEntity run = waitingRun();

        lenient().when(triggerClient.findByToken(any())).thenReturn(dto);
        lenient().when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        lenient().when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                new ProductionRunResolver.Resolution(
                        Optional.of(run), ProductionRunResolver.Outcome.FOUND, "wf"));
        lenient().when(triggerService.executeTrigger(any(), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.success(
                        RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1));
    }

    @Test
    @DisplayName("the token window 429s once the per-token budget is spent, and the trigger stops firing")
    void refusesOnceTokenBudgetIsSpent() {
        wireFireableToken();
        int limit = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_FIRES_PER_TOKEN;

        for (int i = 0; i < limit; i++) {
            assertThat(service.dispatch(TOKEN, Map.of(), false).status())
                    .as("fire %s of %s must still be accepted", i + 1, limit)
                    .isEqualTo("triggered");
        }

        WebhookResponse refused = service.dispatch(TOKEN, Map.of(), false);

        assertThat(refused.status()).isEqualTo("rate_limited");
        // The point of gating BEFORE the lookup: a refused fire must not reach the run at all.
        verify(triggerService, times(limit))
                .executeTrigger(any(), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any());
    }

    @Test
    @DisplayName("the window SLIDES: budget comes back once the oldest fires age out")
    void budgetRecoversAfterTheWindow() {
        wireFireableToken();
        int limit = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_FIRES_PER_TOKEN;

        for (int i = 0; i < limit; i++) {
            service.dispatch(TOKEN, Map.of(), false);
        }
        assertThat(service.dispatch(TOKEN, Map.of(), false).status()).isEqualTo("rate_limited");

        clock.advance(WebhookDispatchService.WebhookRateLimiter.WINDOW.plusSeconds(1));

        assertThat(service.dispatch(TOKEN, Map.of(), false).status())
                .as("a rate limit that never recovers is an outage, not a limit")
                .isEqualTo("triggered");
    }

    @Test
    @DisplayName("a refused fire never reaches the token lookup - the gate is before any I/O")
    void refusedFireDoesNotHitTheTokenLookup() {
        wireFireableToken();
        int limit = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_FIRES_PER_TOKEN;
        for (int i = 0; i < limit; i++) {
            service.dispatch(TOKEN, Map.of(), false);
        }

        service.dispatch(TOKEN, Map.of(), false);

        verify(triggerClient, atMost(limit)).findByToken(any());
    }

    @Test
    @DisplayName("a DIFFERENT token has its own budget - one noisy caller must not gate everyone")
    void perTokenBudgetsAreIndependent() {
        wireFireableToken();
        int limit = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_FIRES_PER_TOKEN;
        for (int i = 0; i < limit; i++) {
            service.dispatch(TOKEN, Map.of(), false);
        }
        assertThat(service.dispatch(TOKEN, Map.of(), false).status()).isEqualTo("rate_limited");

        assertThat(service.dispatch("wh_a_totally_different_token", Map.of(), false).status())
                .isEqualTo("triggered");
    }

    @Test
    @DisplayName("the OWNER cap 429s across tokens - fifty tokens must not buy fifty budgets")
    void ownerCapAppliesAcrossTokens() {
        wireFireableToken();
        int ownerLimit = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_FIRES_PER_OWNER;
        int tokenLimit = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_FIRES_PER_TOKEN;

        // Spread the fires over enough distinct tokens that no per-token window trips first.
        int fired = 0;
        for (int t = 0; fired < ownerLimit; t++) {
            for (int i = 0; i < tokenLimit && fired < ownerLimit; i++, fired++) {
                assertThat(service.dispatch("token-" + t, Map.of(), false).status())
                        .as("owner fire %s of %s", fired + 1, ownerLimit)
                        .isEqualTo("triggered");
            }
        }

        WebhookResponse refused = service.dispatch("token-fresh", Map.of(), false);

        assertThat(refused.status()).isEqualTo("rate_limited");
    }

    @Test
    @DisplayName("the sync-run ceiling refuses an owner's next sync fire once their slots are held")
    void concurrentSyncCeilingIsEnforced() {
        wireFireableToken();
        int ceiling = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER;

        // Every fire here happens at the same instant, so no slot is old enough for the settle
        // grace to consider releasing it: what is counted is the requests occupied right now.
        WorkflowRunEntity run = waitingRun();
        for (int i = 0; i < ceiling; i++) {
            run.setRunIdPublic("run-" + i);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                    new ProductionRunResolver.Resolution(
                            Optional.of(run), ProductionRunResolver.Outcome.FOUND, "wf"));
            assertThat(service.dispatch("token-" + i, Map.of(), true).status()).isEqualTo("triggered");
        }

        run.setRunIdPublic("run-over-ceiling");
        WebhookResponse refused = service.dispatch("token-over", Map.of(), true);

        assertThat(refused.status()).isEqualTo("rate_limited");
        // Nothing was registered for the refused run: a refused sync fire must not leave an
        // expectation behind that a later respond node would try to resolve.
        verify(webhookResponseRegistry, never()).expect("run-over-ceiling");
    }

    @Test
    @DisplayName("an ASYNC fire is unaffected by the sync ceiling - it parks nothing")
    void asyncFireIgnoresSyncCeiling() {
        wireFireableToken();
        int ceiling = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER;

        WorkflowRunEntity run = waitingRun();
        for (int i = 0; i < ceiling; i++) {
            run.setRunIdPublic("run-" + i);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                    new ProductionRunResolver.Resolution(
                            Optional.of(run), ProductionRunResolver.Outcome.FOUND, "wf"));
            service.dispatch("token-" + i, Map.of(), true);
        }

        run.setRunIdPublic("run-async");
        assertThat(service.dispatch("token-async", Map.of(), false).status()).isEqualTo("triggered");
    }

    @Test
    @DisplayName("a sync slot is released when the trigger fails, so a failing workflow cannot exhaust it")
    void syncSlotIsReleasedOnTriggerFailure() {
        wireFireableToken();
        when(triggerService.executeTrigger(any(), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenReturn(TriggerExecutionResult.failure(RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "boom"));

        int ceiling = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER;
        WorkflowRunEntity run = waitingRun();
        for (int i = 0; i < ceiling + 5; i++) {
            run.setRunIdPublic("run-" + i);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                    new ProductionRunResolver.Resolution(
                            Optional.of(run), ProductionRunResolver.Outcome.FOUND, "wf"));
            WebhookResponse response = service.dispatch("token-" + i, Map.of(), true);
            assertThat(response.status())
                    .as("a failed trigger releases its slot, so the ceiling is never reached")
                    .isEqualTo("error");
        }
    }

    /**
     * The property that makes the ceiling a CONCURRENCY ceiling. A sync fire that has been
     * answered occupies nothing, and the slot must come back without waiting out the TTL:
     * otherwise the real control is "N distinct sync runs per TTL" (roughly 0.2 fires/second per
     * owner at the shipped numbers) and a tenant whose sync webhooks all answer in 200ms still
     * 429s at N.
     */
    @Test
    @DisplayName("a sync fire that has been ANSWERED releases its slot, long before the TTL")
    void answeredSyncFireReleasesItsSlot() {
        wireFireableToken();
        int ceiling = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER;
        WorkflowRunEntity run = waitingRun();
        when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                new ProductionRunResolver.Resolution(
                        Optional.of(run), ProductionRunResolver.Outcome.FOUND, "wf"));

        for (int i = 0; i < ceiling; i++) {
            run.setRunIdPublic("run-" + i);
            assertThat(service.dispatch("token-" + i, Map.of(), true).status()).isEqualTo("triggered");
        }

        // Inside the settle grace the slots are still held: an observation this fresh cannot tell
        // an answered request from the handoff between the dispatch thread and the controller.
        clock.advance(Duration.ofSeconds(1));
        run.setRunIdPublic("run-blocked");
        assertThat(service.dispatch("token-blocked", Map.of(), true).status())
                .as("the grace must protect a request that is mid-handoff")
                .isEqualTo("rate_limited");

        // Past the grace the registry reports nothing pending for any of them, so they are free.
        // This is 3 seconds in, against a 90-second TTL.
        clock.advance(Duration.ofSeconds(2));
        run.setRunIdPublic("run-after-settle");
        assertThat(service.dispatch("token-after-settle", Map.of(), true).status())
                .as("an answered sync fire must not cost its owner a slot for the whole TTL")
                .isEqualTo("triggered");
    }

    /**
     * The other half of the same contract: a request that IS still occupied keeps its slot, so
     * releasing early cannot be mistaken for "the ceiling stopped counting". The TTL stays the
     * backstop for an occupancy that never ends.
     */
    @Test
    @DisplayName("a sync fire still PARKED keeps its slot past the grace; only the TTL reclaims it")
    void stillParkedSyncFireKeepsItsSlotUntilTheTtl() {
        wireFireableToken();
        int ceiling = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER;
        WorkflowRunEntity run = waitingRun();
        when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                new ProductionRunResolver.Resolution(
                        Optional.of(run), ProductionRunResolver.Outcome.FOUND, "wf"));
        // Every one of these callers is still waiting on its DeferredResult.
        lenient().when(webhookResponseRegistry.hasPending(any())).thenReturn(true);

        for (int i = 0; i < ceiling; i++) {
            run.setRunIdPublic("run-" + i);
            assertThat(service.dispatch("token-" + i, Map.of(), true).status()).isEqualTo("triggered");
        }

        clock.advance(WebhookDispatchService.WebhookRateLimiter.SETTLE_GRACE.plusSeconds(10));
        run.setRunIdPublic("run-blocked");
        assertThat(service.dispatch("token-blocked", Map.of(), true).status())
                .as("slots for requests that are genuinely parked must not be handed out")
                .isEqualTo("rate_limited");

        // Past the TTL they are reclaimed even though the probe still claims they are parked:
        // an occupancy that never ends is a leak, not a request.
        clock.advance(WebhookDispatchService.WebhookRateLimiter.DEFAULT_SYNC_SLOT_TTL.plusSeconds(1));
        run.setRunIdPublic("run-after-ttl");
        assertThat(service.dispatch("token-after-ttl", Map.of(), true).status()).isEqualTo("triggered");
    }

    /**
     * The probe must cover the phase BEFORE any DeferredResult exists. Dispatch blocks the
     * calling thread inside executeTrigger, and a workflow that runs for longer than the settle
     * grace would otherwise have its slot handed to someone else while its caller is still
     * waiting - over-admitting exactly when the process is busiest.
     */
    @Test
    @DisplayName("a sync fire still inside executeTrigger holds its slot even past the grace")
    void slotIsHeldWhileTheDispatchThreadIsStillBlocked() {
        wireFireableToken();
        WorkflowRunEntity run = waitingRun();
        when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                new ProductionRunResolver.Resolution(
                        Optional.of(run), ProductionRunResolver.Outcome.FOUND, "wf"));

        // A limiter of one slot, so the second fire can only succeed by stealing the first.
        WebhookDispatchService oneSlot = new WebhookDispatchService(
                triggerClient, workflowRepository, runRepository, triggerService,
                productionRunResolver, webhookResponseRegistry,
                new WebhookDispatchService.WebhookRateLimiter(clock, 120, 600, 1, 1, Duration.ofSeconds(90)));

        // The first fire is still inside executeTrigger when the second arrives: the stub advances
        // the clock past the settle grace and re-enters dispatch from within the trigger call.
        WebhookResponse[] nested = new WebhookResponse[1];
        when(triggerService.executeTrigger(any(), eq(TRIGGER_ID), eq(TriggerType.WEBHOOK), any()))
                .thenAnswer(invocation -> {
                    if (nested[0] == null) {
                        clock.advance(WebhookDispatchService.WebhookRateLimiter.SETTLE_GRACE.plusSeconds(5));
                        run.setRunIdPublic("run-second");
                        nested[0] = oneSlot.dispatch("token-second", Map.of(), true);
                        run.setRunIdPublic("run-first");
                    }
                    return TriggerExecutionResult.success(
                            RUN_ID, TRIGGER_ID, TriggerType.WEBHOOK, "ok", Set.of(), 1);
                });

        run.setRunIdPublic("run-first");
        assertThat(oneSlot.dispatch("token-first", Map.of(), true).status()).isEqualTo("triggered");

        assertThat(nested[0].status())
                .as("the first caller was still blocked in executeTrigger: its slot is not free")
                .isEqualTo("rate_limited");
    }

    /**
     * The reason the ceiling is scoped per owner. A platform-wide-only ceiling means one tenant
     * whose sync webhooks never reach a respond node 429s every OTHER tenant's sync webhooks,
     * which is a worse outage than the abuse it prevents.
     */
    @Test
    @DisplayName("one owner at their sync ceiling does not refuse a DIFFERENT owner's sync fire")
    void syncCeilingIsScopedPerOwner() {
        wireFireableToken();
        int ceiling = WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER;
        WorkflowRunEntity run = waitingRun();
        when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                new ProductionRunResolver.Resolution(
                        Optional.of(run), ProductionRunResolver.Outcome.FOUND, "wf"));

        for (int i = 0; i < ceiling; i++) {
            run.setRunIdPublic("run-" + i);
            assertThat(service.dispatch("token-" + i, Map.of(), true).status()).isEqualTo("triggered");
        }
        run.setRunIdPublic("run-blocked");
        assertThat(service.dispatch("token-blocked", Map.of(), true).status()).isEqualTo("rate_limited");

        // Same platform, different tenant: their first sync fire must still go through.
        WorkflowEntity otherWorkflow = new WorkflowEntity();
        UUID otherWorkflowId = UUID.randomUUID();
        otherWorkflow.setId(otherWorkflowId);
        otherWorkflow.setTenantId("tenant-99");
        WebhookTokenDto otherToken = new WebhookTokenDto();
        otherToken.setWorkflowId(otherWorkflowId);
        otherToken.setTriggerId(TRIGGER_ID);
        otherToken.setToken("wh_other_tenant");
        WorkflowRunEntity otherRun = waitingRun();
        otherRun.setRunIdPublic("run-other-tenant");
        when(triggerClient.findByToken("wh_other_tenant")).thenReturn(otherToken);
        when(workflowRepository.findById(otherWorkflowId)).thenReturn(Optional.of(otherWorkflow));
        when(productionRunResolver.resolve(eq(otherWorkflowId), any())).thenReturn(
                new ProductionRunResolver.Resolution(
                        Optional.of(otherRun), ProductionRunResolver.Outcome.FOUND, "wf2"));

        assertThat(service.dispatch("wh_other_tenant", Map.of(), true).status()).isEqualTo("triggered");
    }

    /**
     * The ceilings are properties so an operator whose legitimate traffic hits one can raise it
     * without a code change. A non-positive override must NOT silently disable the guard.
     */
    @Test
    @DisplayName("configured ceilings are honoured, and a non-positive value falls back to the default")
    void configuredCeilingsAreHonoured() {
        WebhookDispatchService.WebhookRateLimiter configured =
                new WebhookDispatchService.WebhookRateLimiter(clock, 5, 7, 2, 3, Duration.ofSeconds(30));

        assertThat(configured.maxFiresPerToken()).isEqualTo(5);
        assertThat(configured.maxFiresPerOwner()).isEqualTo(7);
        assertThat(configured.maxConcurrentSyncPerOwner()).isEqualTo(2);
        assertThat(configured.maxConcurrentSync()).isEqualTo(3);
        assertThat(configured.syncSlotTtl()).isEqualTo(Duration.ofSeconds(30));

        WebhookDispatchService.WebhookRateLimiter zeroed =
                new WebhookDispatchService.WebhookRateLimiter(clock, 0, -1, 0, 0, Duration.ZERO);

        assertThat(zeroed.maxFiresPerToken())
                .isEqualTo(WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_FIRES_PER_TOKEN);
        assertThat(zeroed.maxFiresPerOwner())
                .isEqualTo(WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_FIRES_PER_OWNER);
        assertThat(zeroed.maxConcurrentSyncPerOwner())
                .isEqualTo(WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER);
        assertThat(zeroed.maxConcurrentSync())
                .isEqualTo(WebhookDispatchService.WebhookRateLimiter.DEFAULT_MAX_CONCURRENT_SYNC);
        assertThat(zeroed.syncSlotTtl())
                .isEqualTo(WebhookDispatchService.WebhookRateLimiter.DEFAULT_SYNC_SLOT_TTL);
    }

    /**
     * The platform-wide backstop still exists above the per-owner ceiling: many owners each
     * under their own ceiling must not be able to saturate the process without limit.
     */
    @Test
    @DisplayName("the platform-wide backstop refuses once total held slots reach it, across owners")
    void platformWideBackstopStillApplies() {
        WebhookDispatchService.WebhookRateLimiter limiter =
                new WebhookDispatchService.WebhookRateLimiter(clock, 120, 600, 2, 3, Duration.ofSeconds(90));

        assertThat(limiter.acquireSyncSlot("run-a1", "owner-a")).isTrue();
        assertThat(limiter.acquireSyncSlot("run-a2", "owner-a")).isTrue();
        // owner-a is at its per-owner ceiling of 2.
        assertThat(limiter.acquireSyncSlot("run-a3", "owner-a")).isFalse();
        assertThat(limiter.acquireSyncSlot("run-b1", "owner-b")).isTrue();
        // 3 held platform-wide: the backstop refuses even a brand-new owner.
        assertThat(limiter.acquireSyncSlot("run-c1", "owner-c")).isFalse();
    }
}
