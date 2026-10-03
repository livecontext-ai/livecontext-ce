package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerOffer;
import com.apimarketplace.auth.domain.PartnerOfferDelivery;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.repository.PartnerOfferDeliveryRepository;
import com.apimarketplace.auth.repository.PartnerOfferRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.stripe.StripeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Partner offer apps: delivered to whoever pays through the offer, after the payment")
class PartnerOfferDeliveryServiceTest {

    private static final long PARTNER = 42L;
    private static final long CLIENT = 7L;
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final UUID APP_A = UUID.fromString("6f1c0d2e-0000-4000-8000-00000000000a");
    private static final UUID APP_B = UUID.fromString("6f1c0d2e-0000-4000-8000-00000000000b");

    private PartnerOfferRepository offers;
    private PartnerOfferDeliveryRepository deliveries;
    private RewardRedemptionRepository redemptions;
    private UserRepository users;
    private UserService userService;
    private SubscriptionRepository subscriptions;
    private StripeClient stripe;
    private PartnerOfferAppsClient apps;
    /** The worker's queue: the sweep and the reconciliation. */
    private final List<Runnable> scheduled = new ArrayList<>();
    /** The install right after a payment. */
    private final List<Runnable> immediate = new ArrayList<>();
    /** True while a transaction runs, so a lazy association read outside one fails as it would on the worker. */
    private boolean inTransaction;
    private PartnerOfferDeliveryService service;
    private PartnerOffer offer;

    @BeforeEach
    void setUp() {
        offers = mock(PartnerOfferRepository.class);
        deliveries = mock(PartnerOfferDeliveryRepository.class);
        redemptions = mock(RewardRedemptionRepository.class);
        users = mock(UserRepository.class);
        userService = mock(UserService.class);
        subscriptions = mock(SubscriptionRepository.class);
        stripe = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        apps = mock(PartnerOfferAppsClient.class);
        scheduled.clear();
        immediate.clear();
        inTransaction = false;
        service = service(immediate::add, scheduled::add, false);

        offer = new PartnerOffer();
        offer.setId(1L);
        offer.setToken("Abc23XyZ9k");
        offer.setPartnerUserId(PARTNER);
        offer.setAppPublicationIds(List.of(APP_A.toString(), APP_B.toString()));
        when(offers.findByToken("Abc23XyZ9k")).thenReturn(Optional.of(offer));
        when(offers.findById(1L)).thenReturn(Optional.of(offer));
        when(redemptions.findByRedeemerUserIdAndProgram(anyLong(), eq(RewardProgram.PARTNER))).thenReturn(Optional.empty());
        // The client's paid plan is on (a test about it says otherwise).
        Subscription paid = mock(Subscription.class);
        Plan paidPlan = mock(Plan.class);
        when(paidPlan.getCode()).thenReturn("PRO");
        when(paid.getPlan()).thenReturn(paidPlan);
        when(paid.getProviderSubscriptionId()).thenReturn("sub_1");
        when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(paid));
    }

    private PartnerOfferDeliveryService service(Executor now, Executor batch, boolean unlimited) {
        return service(() -> stripe, now, batch, unlimited);
    }

    private PartnerOfferDeliveryService service(java.util.function.Supplier<StripeClient> stripeClient, Executor now,
                                                Executor batch, boolean unlimited) {
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class)) {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                inTransaction = true;
                try {
                    return super.execute(action);
                } finally {
                    inTransaction = false;
                }
            }
        };
        return new PartnerOfferDeliveryService(offers, deliveries, redemptions, users, userService, subscriptions,
                stripeClient, apps, tx, now, batch, unlimited, () -> NOW);
    }

    private void runScheduled() {
        List<Runnable> now = new ArrayList<>(scheduled);
        scheduled.clear();
        now.forEach(Runnable::run);
    }

    private static PartnerOfferDelivery delivery(long id, UUID app, PartnerOfferDelivery.Status status, int attempts) {
        PartnerOfferDelivery d = new PartnerOfferDelivery();
        d.setId(id);
        d.setOfferId(1L);
        d.setClientUserId(CLIENT);
        d.setPublicationId(app);
        d.setStatus(status);
        d.setAttempts(attempts);
        d.setNextAttemptAt(NOW);
        return d;
    }

    @Nested
    @DisplayName("when the checkout opens")
    class Expect {

        @Test
        @DisplayName("each app the offer gives waits for the payment, on the application's clock")
        void recordsWaitingApps() {
            service.expect("Abc23XyZ9k", CLIENT);

            verify(deliveries).expect(1L, CLIENT, APP_A, NOW);
            verify(deliveries).expect(1L, CLIENT, APP_B, NOW);
            assertThat(scheduled).isEmpty();
        }

        @Test
        @DisplayName("never blocks the checkout: an unknown offer records nothing, a failing store is only logged")
        void neverThrows() {
            service.expect("Unknown0001", CLIENT);
            service.expect(" ", CLIENT);
            service.expect("Abc23XyZ9k", null);
            verify(deliveries, never()).expect(anyLong(), anyLong(), any(), any());

            when(deliveries.expect(anyLong(), anyLong(), any(), any())).thenThrow(new IllegalStateException("db down"));
            service.expect("Abc23XyZ9k", CLIENT);
        }
    }

    @Nested
    @DisplayName("after the payment")
    class Enqueue {

        @Test
        @DisplayName("regression: each app moves on to install, then a try starts at once for that client, never queued behind a sweep or a reconciliation")
        void movesOnAndTries() {
            when(deliveries.paid(1L, CLIENT, APP_A, "in_1", NOW)).thenReturn(1);
            when(deliveries.paid(1L, CLIENT, APP_B, "in_1", NOW)).thenReturn(1);
            when(deliveries.findDueIdsForClient(CLIENT, NOW)).thenReturn(List.of());

            assertThat(service.enqueue("Abc23XyZ9k", CLIENT, "in_1")).isEqualTo(2);
            assertThat(immediate).hasSize(1);
            assertThat(scheduled).isEmpty();

            immediate.forEach(Runnable::run);
            verify(deliveries).findDueIdsForClient(CLIENT, NOW);
        }

        @Test
        @DisplayName("a replayed invoice moves nothing and starts nothing")
        void replayIsInert() {
            when(deliveries.paid(anyLong(), anyLong(), any(), anyString(), any())).thenReturn(0);

            assertThat(service.enqueue("Abc23XyZ9k", CLIENT, "in_1")).isZero();
            assertThat(immediate).isEmpty();
        }

        @Test
        @DisplayName("a deactivated offer still delivers: the client paid for it")
        void deactivatedOfferStillDelivers() {
            offer.setActive(false);
            when(deliveries.paid(anyLong(), anyLong(), any(), anyString(), any())).thenReturn(1);

            assertThat(service.enqueue("Abc23XyZ9k", CLIENT, "in_1")).isEqualTo(2);
        }

        @Test
        @DisplayName("an unknown or purged offer, a blank token, no client, or a malformed stored id deliver nothing")
        void nothingToDeliver() {
            assertThat(service.enqueue("Unknown0001", CLIENT, "in_1")).isZero();
            assertThat(service.enqueue(" ", CLIENT, "in_1")).isZero();
            assertThat(service.enqueue("Abc23XyZ9k", null, "in_1")).isZero();

            offer.setAppPublicationIds(List.of("not-an-id"));
            assertThat(service.enqueue("Abc23XyZ9k", CLIENT, "in_1")).isZero();
            verify(deliveries, never()).paid(anyLong(), anyLong(), any(), anyString(), any());
        }

        @Test
        @DisplayName("an executor that refuses (shutting down) loses nothing: the deliveries are recorded for the sweep")
        void executorRefusal() {
            Executor refusing = task -> { throw new RejectedExecutionException("shutting down"); };
            service = service(refusing, refusing, false);
            when(deliveries.paid(anyLong(), anyLong(), any(), anyString(), any())).thenReturn(1);

            assertThat(service.enqueue("Abc23XyZ9k", CLIENT, "in_1")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("tries")
    class Attempt {

        @Test
        @DisplayName("a delivery someone else holds (another pod, the sweep) is not installed twice")
        void heldElsewhere() {
            when(deliveries.claim(eq(5L), any(), any())).thenReturn(0);

            service.attempt(5L);

            verifyNoInteractions(apps);
        }

        @Test
        @DisplayName("regression: the lease is recognised as ours at the database's precision, with a clock finer than a millisecond")
        void leaseAtDatabasePrecision() {
            Instant fine = Instant.parse("2026-10-02T10:00:00.123456789Z");
            PartnerOfferDeliveryService precise = new PartnerOfferDeliveryService(offers, deliveries, redemptions, users, userService,
                    subscriptions, () -> stripe, apps, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                    immediate::add, scheduled::add, false, () -> fine);
            PartnerOfferDelivery d = delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, 1);
            when(deliveries.claim(eq(5L), any(), any())).thenAnswer(inv -> {
                // Postgres keeps microseconds.
                d.setNextAttemptAt(inv.<Instant>getArgument(2).truncatedTo(ChronoUnit.MICROS));
                return 1;
            });
            when(deliveries.findById(5L)).thenReturn(Optional.of(d));
            when(apps.install(APP_A, CLIENT)).thenReturn(new PartnerOfferAppsClient.Install(PartnerOfferAppsClient.Outcome.INSTALLED, null));

            precise.attempt(5L);

            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.INSTALLED);
            verify(deliveries).save(d);
        }

        @Test
        @DisplayName("regression: a delivery claimed past every recorded outcome is given up instead of being installed again every lease, for ever")
        void claimsAreBounded() {
            PartnerOfferDelivery d = delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, PartnerOfferDeliveryService.MAX_CLAIMS + 1);
            when(deliveries.claim(eq(5L), any(), any())).thenReturn(1);
            when(deliveries.findById(5L)).thenReturn(Optional.of(d));

            service.attempt(5L);

            verifyNoInteractions(apps);
            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.FAILED);
            assertThat(d.getLastError()).contains("without a recorded outcome");
            assertThat(PartnerOfferDeliveryService.MAX_CLAIMS).isGreaterThan(PartnerOfferDeliveryService.MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("a delivery claimed exactly as often as allowed is still installed: only past the bound is it given up")
        void lastAllowedClaimStillInstalls() {
            PartnerOfferDelivery d = delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, PartnerOfferDeliveryService.MAX_CLAIMS);
            Instant lease = NOW.plus(PartnerOfferDeliveryService.LEASE);
            when(deliveries.claim(5L, NOW, lease)).thenAnswer(inv -> {
                d.setNextAttemptAt(lease);
                return 1;
            });
            when(deliveries.findById(5L)).thenReturn(Optional.of(d));
            when(apps.install(APP_A, CLIENT)).thenReturn(new PartnerOfferAppsClient.Install(PartnerOfferAppsClient.Outcome.INSTALLED, null));

            service.attempt(5L);

            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.INSTALLED);
        }

        @Test
        @DisplayName("a worker that refuses (shutting down) does not keep the next sweep from starting")
        void refusedWorkerFreesTheSweep() {
            List<Runnable> accepted = new ArrayList<>();
            boolean[] refuse = {true};
            PartnerOfferDeliveryService svc = service(immediate::add, task -> {
                if (refuse[0]) throw new RejectedExecutionException("shutting down");
                accepted.add(task);
            }, false);

            svc.sweep();
            refuse[0] = false;
            svc.sweep();

            assertThat(accepted).hasSize(1);
        }

        @Test
        @DisplayName("regression: nothing is installed before the paid plan is on (the free plan's quota would refuse the app, or clone it without interfaces): the try waits, then installs")
        void noInstallBeforeThePaidPlan() {
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.empty());
            PartnerOfferDelivery d = delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, 1);
            when(deliveries.claim(eq(5L), any(), any())).thenAnswer(inv -> {
                d.setNextAttemptAt(inv.getArgument(2));
                return 1;
            });
            when(deliveries.findById(5L)).thenReturn(Optional.of(d));

            service.attempt(5L);

            verifyNoInteractions(apps);
            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.PENDING);
            assertThat(d.getLastError()).isEqualTo(PartnerOfferDeliveryService.PAID_PLAN_NOT_ON);
            assertThat(d.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));

            // The plan switches on: the next try installs.
            Subscription paid = mock(Subscription.class);
            Plan plan = mock(Plan.class);
            when(plan.getCode()).thenReturn("STARTER");
            when(paid.getPlan()).thenReturn(plan);
            when(paid.getProviderSubscriptionId()).thenReturn("sub_1");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(paid));
            when(apps.install(APP_A, CLIENT)).thenReturn(new PartnerOfferAppsClient.Install(PartnerOfferAppsClient.Outcome.INSTALLED, null));

            service.attempt(5L);

            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.INSTALLED);
        }

        @Test
        @DisplayName("taken with a lease past the install timeout, installed, recorded INSTALLED")
        void installs() {
            PartnerOfferDelivery d = delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, 1);
            Instant lease = NOW.plus(PartnerOfferDeliveryService.LEASE);
            when(deliveries.claim(5L, NOW, lease)).thenAnswer(inv -> {
                d.setNextAttemptAt(lease);
                return 1;
            });
            when(deliveries.findById(5L)).thenReturn(Optional.of(d));
            when(apps.install(APP_A, CLIENT)).thenReturn(new PartnerOfferAppsClient.Install(PartnerOfferAppsClient.Outcome.INSTALLED, null));

            service.attempt(5L);

            assertThat(PartnerOfferDeliveryService.LEASE).isGreaterThan(Duration.ofSeconds(65));
            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.INSTALLED);
            verify(deliveries).save(d);
        }

        @Test
        @DisplayName("regression: an answer that comes back after its lease ended is left to the try that holds the delivery now")
        void lateAnswerIsNotRecorded() {
            PartnerOfferDelivery d = delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, 1);
            when(deliveries.claim(eq(5L), any(), any())).thenAnswer(inv -> {
                // Another pod took it again after this lease ended: its own lease is in the row.
                d.setNextAttemptAt(NOW.plus(Duration.ofMinutes(9)));
                return 1;
            });
            when(deliveries.findById(5L)).thenReturn(Optional.of(d));
            when(apps.install(APP_A, CLIENT)).thenReturn(new PartnerOfferAppsClient.Install(PartnerOfferAppsClient.Outcome.REFUSED, "late"));

            service.attempt(5L);

            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.PENDING);
            verify(deliveries, never()).save(any());
        }

        @Test
        @DisplayName("regression: the sweep's installs run off the scheduler's thread, one sweep at a time per pod")
        void sweepRunsOnTheWorker() {
            when(deliveries.findDueIds(NOW, PartnerOfferDeliveryService.SWEEP_BATCH)).thenReturn(List.of());

            service.sweep();
            service.sweep(); // the first one has not run yet: not stacked

            verify(deliveries, never()).findDueIds(any(), anyInt());
            assertThat(scheduled).hasSize(1);
            runScheduled();
            verify(deliveries).findDueIds(NOW, PartnerOfferDeliveryService.SWEEP_BATCH);

            service.sweep(); // done: the next one may start
            assertThat(scheduled).hasSize(1);
        }

        @Test
        @DisplayName("the sweep tries every due delivery, and a failing one does not stop the others")
        void sweepCarriesOn() {
            when(deliveries.findDueIds(NOW, PartnerOfferDeliveryService.SWEEP_BATCH)).thenReturn(List.of(5L, 6L));
            when(deliveries.claim(eq(5L), any(), any())).thenThrow(new IllegalStateException("db hiccup"));
            PartnerOfferDelivery d6 = delivery(6L, APP_B, PartnerOfferDelivery.Status.PENDING, 1);
            when(deliveries.claim(eq(6L), any(), any())).thenAnswer(inv -> {
                d6.setNextAttemptAt(inv.getArgument(2));
                return 1;
            });
            when(deliveries.findById(6L)).thenReturn(Optional.of(d6));
            when(apps.install(APP_B, CLIENT)).thenReturn(new PartnerOfferAppsClient.Install(PartnerOfferAppsClient.Outcome.INSTALLED, null));

            service.sweep();
            runScheduled();

            assertThat(d6.getStatus()).isEqualTo(PartnerOfferDelivery.Status.INSTALLED);
        }

        @Test
        @DisplayName("self-hosted (credits unlimited): the sweep and the reconciliation read nothing")
        void offInCe() {
            PartnerOfferDeliveryService ce = service(immediate::add, scheduled::add, true);
            ce.sweep();
            ce.reconcile();

            assertThat(scheduled).isEmpty();
            assertThat(immediate).isEmpty();
            verifyNoInteractions(deliveries, apps, subscriptions);
        }
    }

    @Nested
    @DisplayName("recording a try")
    class Record {

        private PartnerOfferDelivery recorded(int attempts, PartnerOfferAppsClient.Outcome outcome, String reason) {
            PartnerOfferDelivery d = delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, attempts);
            service.record(d, new PartnerOfferAppsClient.Install(outcome, reason), NOW);
            return d;
        }

        @Test
        @DisplayName("refused for good: FAILED with the reason, never tried again")
        void refused() {
            PartnerOfferDelivery d = recorded(1, PartnerOfferAppsClient.Outcome.REFUSED, "refused: Publication is not active");

            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.FAILED);
            assertThat(d.getLastError()).isEqualTo("refused: Publication is not active");
        }

        @Test
        @DisplayName("worth another try: stays PENDING, after a backoff that grows from 30 s to 12 h")
        void retryBacksOff() {
            assertThat(recorded(0, PartnerOfferAppsClient.Outcome.RETRY, "throttled").getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(recorded(1, PartnerOfferAppsClient.Outcome.RETRY, "throttled").getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(recorded(5, PartnerOfferAppsClient.Outcome.RETRY, "unreachable").getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
            PartnerOfferDelivery last = recorded(PartnerOfferDeliveryService.MAX_ATTEMPTS - 1, PartnerOfferAppsClient.Outcome.RETRY, "unreachable");
            assertThat(last.getStatus()).isEqualTo(PartnerOfferDelivery.Status.PENDING);
            assertThat(last.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
            assertThat(last.getLastError()).isEqualTo("unreachable");
        }

        @Test
        @DisplayName("regression: after an install that got no answer the next try waits at least a lease, so an install still running on the other side is not cloned twice")
        void noAnswerWaitsALease() {
            assertThat(recorded(1, PartnerOfferAppsClient.Outcome.RETRY, "unreachable: ResourceAccessException").getNextAttemptAt())
                    .isEqualTo(NOW.plus(PartnerOfferDeliveryService.LEASE));
            assertThat(recorded(3, PartnerOfferAppsClient.Outcome.RETRY, "unreachable: ResourceAccessException").getNextAttemptAt())
                    .isEqualTo(NOW.plus(PartnerOfferDeliveryService.LEASE));
            // Past the lease, the usual backoff.
            assertThat(recorded(5, PartnerOfferAppsClient.Outcome.RETRY, "unreachable: ResourceAccessException").getNextAttemptAt())
                    .isEqualTo(NOW.plus(Duration.ofMinutes(10)));
            // An answer that says "not now" (throttled) carries no such risk.
            assertThat(recorded(1, PartnerOfferAppsClient.Outcome.RETRY, "throttled").getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        }

        @Test
        @DisplayName("the last try that still cannot install gives up: FAILED, saying why")
        void givesUp() {
            PartnerOfferDelivery d = recorded(PartnerOfferDeliveryService.MAX_ATTEMPTS, PartnerOfferAppsClient.Outcome.RETRY, "unreachable");

            assertThat(d.getStatus()).isEqualTo(PartnerOfferDelivery.Status.FAILED);
            assertThat(d.getLastError()).startsWith("gave up after " + PartnerOfferDeliveryService.MAX_ATTEMPTS + " tries");
        }

        @Test
        @DisplayName("regression: a plan refusal once the paid plan is on is given up within the hour")
        void planRefusalsAreBounded() {
            paidPlanOn();
            for (String reason : List.of(PartnerOfferAppsClient.PLAN_LIMIT, PartnerOfferAppsClient.PLAN_UPGRADE_REQUIRED)) {
                assertThat(recorded(1, PartnerOfferAppsClient.Outcome.RETRY, reason).getStatus()).as(reason).isEqualTo(PartnerOfferDelivery.Status.PENDING);
                // Every try before the last one still waits: the boundary is the last, not earlier.
                assertThat(recorded(PartnerOfferDeliveryService.PLAN_RETRY_ATTEMPTS - 1, PartnerOfferAppsClient.Outcome.RETRY, reason).getStatus())
                        .as(reason).isEqualTo(PartnerOfferDelivery.Status.PENDING);
                PartnerOfferDelivery last = recorded(PartnerOfferDeliveryService.PLAN_RETRY_ATTEMPTS, PartnerOfferAppsClient.Outcome.RETRY, reason);
                assertThat(last.getStatus()).as(reason).isEqualTo(PartnerOfferDelivery.Status.FAILED);
                assertThat(last.getLastError()).contains(reason);
            }
            Duration window = Duration.ZERO;
            for (int i = 0; i < PartnerOfferDeliveryService.PLAN_RETRY_ATTEMPTS - 1; i++) window = window.plus(PartnerOfferDeliveryService.BACKOFF[i]);
            assertThat(window).isBetween(Duration.ofMinutes(10), Duration.ofHours(1));
            // Any other passing failure keeps its full run.
            assertThat(recorded(PartnerOfferDeliveryService.PLAN_RETRY_ATTEMPTS, PartnerOfferAppsClient.Outcome.RETRY, "unreachable").getStatus())
                    .isEqualTo(PartnerOfferDelivery.Status.PENDING);
        }

        @Test
        @DisplayName("regression: while the paid plan is not on yet (its webhook late or retried hours later), a plan refusal keeps the full run instead of losing the apps")
        void planRefusalWaitsForThePaidPlan() {
            // No subscription yet, then still free: the plan has not switched on.
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.empty());
            for (int round = 0; round < 2; round++) {
                if (round == 1) {
                    Subscription free = mock(Subscription.class);
                    Plan plan = mock(Plan.class);
                    when(plan.getCode()).thenReturn("FREE");
                    when(free.getPlan()).thenReturn(plan);
                    when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(free));
                }
                PartnerOfferDelivery waiting = recorded(PartnerOfferDeliveryService.PLAN_RETRY_ATTEMPTS, PartnerOfferAppsClient.Outcome.RETRY,
                        PartnerOfferAppsClient.PLAN_LIMIT);
                assertThat(waiting.getStatus()).as("round " + round).isEqualTo(PartnerOfferDelivery.Status.PENDING);
                assertThat(recorded(PartnerOfferDeliveryService.MAX_ATTEMPTS, PartnerOfferAppsClient.Outcome.RETRY,
                        PartnerOfferAppsClient.PLAN_LIMIT).getStatus()).as("round " + round).isEqualTo(PartnerOfferDelivery.Status.FAILED);
            }
        }

        private void paidPlanOn() {
            Subscription paid = mock(Subscription.class);
            Plan plan = mock(Plan.class);
            when(plan.getCode()).thenReturn("PRO");
            when(paid.getPlan()).thenReturn(plan);
            when(paid.getProviderSubscriptionId()).thenReturn("sub_1");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(paid));
        }

        @Test
        @DisplayName("a long reason is clipped to the column")
        void clipsReason() {
            assertThat(recorded(1, PartnerOfferAppsClient.Outcome.REFUSED, "x".repeat(500)).getLastError()).hasSize(300);
        }
    }

    @Nested
    @DisplayName("finding a payment whose webhook could not be handled")
    class Reconcile {

        /** A subscription whose plan, like the entity's lazy association, cannot be read outside a transaction. */
        private Subscription subscription(String planCode, String providerId) {
            Subscription sub = mock(Subscription.class);
            Plan plan = mock(Plan.class);
            when(plan.getCode()).thenReturn(planCode);
            when(sub.getPlan()).thenAnswer(inv -> {
                if (!inTransaction) throw new org.hibernate.LazyInitializationException("could not initialize proxy - no Session");
                return plan;
            });
            when(sub.getProviderSubscriptionId()).thenReturn(providerId);
            return sub;
        }

        @BeforeEach
        void waiting() {
            when(deliveries.findAwaiting(NOW.minus(PartnerOfferDeliveryService.ABANDONED), NOW.minus(PartnerOfferDeliveryService.SETTLE),
                    PartnerOfferDeliveryService.RECONCILE_BATCH)).thenReturn(List.<Object[]>of(new Object[]{1L, CLIENT}));
        }

        @Test
        @DisplayName("regression: the reconciliation runs off the scheduler's thread, one at a time per pod")
        void runsOnTheWorker() {
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.empty());

            service.reconcile();
            service.reconcile();

            verifyNoInteractions(subscriptions);
            assertThat(scheduled).hasSize(1);
            runScheduled();
            verify(subscriptions).findActiveByUserId(CLIENT);
        }

        @Test
        @DisplayName("regression: the client's active subscription carries this offer: its apps install as if the webhook had come")
        void paidThroughTheOffer() throws Exception {
            Subscription sub = subscription("PRO", "sub_1");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(sub));
            when(stripe.subscriptions().retrieve("sub_1").getMetadata())
                    .thenReturn(Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k"));
            when(deliveries.promote(1L, CLIENT, NOW)).thenReturn(2);

            service.reconcileNow();

            verify(deliveries).forgetAbandoned(NOW.minus(PartnerOfferDeliveryService.ABANDONED));
            verify(deliveries).promote(1L, CLIENT, NOW);
            // Installed at once, not after the rest of this batch.
            assertThat(immediate).hasSize(1);
        }

        @Test
        @DisplayName("regression: the subscription's plan (a lazy association) is read inside a transaction, so a paid checkout is found and not marked unchecked forever")
        void readsThePlanInsideATransaction() throws Exception {
            Subscription sub = subscription("PRO", "sub_1");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(sub));
            when(stripe.subscriptions().retrieve("sub_1").getMetadata())
                    .thenReturn(Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k"));

            assertThat(service.payment(1L, CLIENT)).isEqualTo(PartnerOfferDeliveryService.Payment.THROUGH_OFFER);
            verify(sub, atLeastOnce()).getPlan();
        }

        @Test
        @DisplayName("a failing mark does not stop the batch: the next waiting checkout is still looked at")
        void failingMarkDoesNotStopTheBatch() {
            when(deliveries.findAwaiting(NOW.minus(PartnerOfferDeliveryService.ABANDONED), NOW.minus(PartnerOfferDeliveryService.SETTLE),
                    PartnerOfferDeliveryService.RECONCILE_BATCH)).thenReturn(List.<Object[]>of(new Object[]{1L, CLIENT}, new Object[]{1L, 8L}));
            when(subscriptions.findActiveByUserId(CLIENT)).thenThrow(new IllegalStateException("db hiccup"));
            doThrow(new IllegalStateException("still down")).when(deliveries).markChecked(1L, CLIENT, NOW);
            when(subscriptions.findActiveByUserId(8L)).thenReturn(Optional.empty());

            service.reconcileNow();

            verify(deliveries).markChecked(1L, 8L, NOW);
        }

        @Test
        @DisplayName("a cleanup of abandoned checkouts that fails does not keep the run from finding payments")
        void failingCleanupDoesNotSkipTheRun() throws Exception {
            when(deliveries.forgetAbandoned(any())).thenThrow(new IllegalStateException("db hiccup"));
            Subscription sub = subscription("PRO", "sub_1");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(sub));
            when(stripe.subscriptions().retrieve("sub_1").getMetadata())
                    .thenReturn(Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Abc23XyZ9k"));
            when(deliveries.promote(1L, CLIENT, NOW)).thenReturn(2);

            service.reconcileNow();

            verify(deliveries).promote(1L, CLIENT, NOW);
        }

        @Test
        @DisplayName("not paid yet (no subscription, still free) or Stripe unreadable: nothing moves, the checkout goes to the back of the queue")
        void notPaidYet() throws Exception {
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.empty());
            service.reconcileNow();

            Subscription free = subscription("FREE", "sub_free");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(free));
            service.reconcileNow();

            Subscription paid = subscription("PRO", "sub_2");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(paid));
            when(stripe.subscriptions().retrieve("sub_2")).thenThrow(new com.stripe.exception.ApiConnectionException("down"));
            service.reconcileNow();

            verify(deliveries, times(3)).markChecked(1L, CLIENT, NOW);
            verify(deliveries, never()).promote(anyLong(), anyLong(), any());
            verify(deliveries, never()).forgetPair(anyLong(), anyLong());
            assertThat(immediate).isEmpty();
        }

        @Test
        @DisplayName("paid without this offer (another checkout): its waiting apps are forgotten, never checked again")
        void paidElsewhere() throws Exception {
            Subscription other = subscription("PRO", "sub_2");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(other));
            when(stripe.subscriptions().retrieve("sub_2").getMetadata())
                    .thenReturn(Map.of(StripeBillingService.PARTNER_OFFER_METADATA, "Other00000"));

            service.reconcileNow();

            verify(deliveries).forgetPair(1L, CLIENT);
            verify(deliveries, never()).promote(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("without Stripe (not configured) no payment can be confirmed, and nothing moves")
        void noStripe() {
            service = service(() -> null, immediate::add, scheduled::add, false);
            Subscription sub = subscription("PRO", "sub_1");
            when(subscriptions.findActiveByUserId(CLIENT)).thenReturn(Optional.of(sub));

            service.reconcileNow();

            verify(deliveries, never()).promote(anyLong(), anyLong(), any());
            verify(deliveries).markChecked(1L, CLIENT, NOW);
        }
    }

    @Nested
    @DisplayName("the client's welcome")
    class Welcome {

        private final Map<String, Object> cardA = Map.of("id", APP_A.toString(), "title", "Invoice chaser");
        private final Map<String, Object> cardB = Map.of("id", APP_B.toString(), "title", "Lead finder");

        @BeforeEach
        void partner() {
            User partnerUser = new User();
            when(users.findById(PARTNER)).thenReturn(Optional.of(partnerUser));
            when(userService.getPublicProfile(partnerUser)).thenReturn(Optional.of(new PublicProfileDto(PARTNER,
                    "Northwind Studio", "northwind", "/a.png", null, LocalDateTime.now(), false, false, true, "gold")));
        }

        @Test
        @DisplayName("each app with where its delivery stands, in the offer's order; WAITING until the payment is confirmed")
        void statuses() {
            when(apps.offerable(PARTNER, offer.getAppPublicationIds())).thenReturn(List.of(cardA, cardB));
            when(deliveries.findByOfferIdAndClientUserId(1L, CLIENT)).thenReturn(List.of(
                    delivery(5L, APP_A, PartnerOfferDelivery.Status.INSTALLED, 1),
                    delivery(6L, APP_B, PartnerOfferDelivery.Status.AWAITING_PAYMENT, 0)));

            var welcome = service.welcome(CLIENT, "Abc23XyZ9k").orElseThrow();

            assertThat(welcome.apps()).extracting(a -> a.get("id"), a -> a.get("status"))
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(APP_A.toString(), "INSTALLED"),
                            org.assertj.core.groups.Tuple.tuple(APP_B.toString(), "WAITING"));
            assertThat(welcome.apps().get(0)).containsEntry("title", "Invoice chaser");
            assertThat(welcome.partner().displayName()).isEqualTo("Northwind Studio");
        }

        @Test
        @DisplayName("regression: an app no longer offered is not shown failing (before or after the payment); one already installed stays listed, by id")
        void withdrawnApps() {
            when(apps.offerable(PARTNER, offer.getAppPublicationIds())).thenReturn(List.of());
            when(deliveries.findByOfferIdAndClientUserId(1L, CLIENT)).thenReturn(List.of(
                    delivery(5L, APP_A, PartnerOfferDelivery.Status.AWAITING_PAYMENT, 0),
                    delivery(6L, APP_B, PartnerOfferDelivery.Status.FAILED, 1)));

            assertThat(service.welcome(CLIENT, "Abc23XyZ9k").orElseThrow().apps()).isEmpty();

            // Withdrawn after the payment: its install can only be refused, so it is not shown on
            // its way either; once in the workspace, it is the client's and stays.
            when(deliveries.findByOfferIdAndClientUserId(1L, CLIENT)).thenReturn(List.of(
                    delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, 1),
                    delivery(6L, APP_B, PartnerOfferDelivery.Status.INSTALLED, 1)));

            assertThat(service.welcome(CLIENT, "Abc23XyZ9k").orElseThrow().apps())
                    .containsExactly(Map.of("id", APP_B.toString(), "status", "INSTALLED"));
        }

        @Test
        @DisplayName("cards unreadable: the apps on their way still show, by id, failed ones included (nothing says they were withdrawn)")
        void cardsDown() {
            when(apps.offerable(anyLong(), any())).thenThrow(new org.springframework.web.client.ResourceAccessException("down"));
            when(deliveries.findByOfferIdAndClientUserId(1L, CLIENT)).thenReturn(List.of(
                    delivery(5L, APP_A, PartnerOfferDelivery.Status.PENDING, 1),
                    delivery(6L, APP_B, PartnerOfferDelivery.Status.FAILED, 12)));

            assertThat(service.welcome(CLIENT, "Abc23XyZ9k").orElseThrow().apps())
                    .containsExactly(Map.of("id", APP_A.toString(), "status", "PENDING"), Map.of("id", APP_B.toString(), "status", "FAILED"));
        }

        @Test
        @DisplayName("the partner's id (to write to them) goes to their client only: one who opened the offer's checkout, or one attributed to them")
        void partnerIdForTheirClientsOnly() {
            when(apps.offerable(anyLong(), any())).thenReturn(List.of(cardA, cardB));
            when(deliveries.findByOfferIdAndClientUserId(eq(1L), anyLong())).thenReturn(List.of());

            assertThat(service.welcome(CLIENT, "Abc23XyZ9k").orElseThrow().partnerUserId()).isNull();

            RewardRedemption toOther = new RewardRedemption();
            toOther.setOwnerUserId(99L);
            when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.of(toOther));
            assertThat(service.welcome(CLIENT, "Abc23XyZ9k").orElseThrow().partnerUserId()).isNull();

            RewardRedemption toPartner = new RewardRedemption();
            toPartner.setOwnerUserId(PARTNER);
            when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.of(toPartner));
            assertThat(service.welcome(CLIENT, "Abc23XyZ9k").orElseThrow().partnerUserId()).isEqualTo(PARTNER);

            when(redemptions.findByRedeemerUserIdAndProgram(CLIENT, RewardProgram.PARTNER)).thenReturn(Optional.empty());
            when(deliveries.findByOfferIdAndClientUserId(1L, CLIENT)).thenReturn(List.of(
                    delivery(5L, APP_A, PartnerOfferDelivery.Status.AWAITING_PAYMENT, 0)));
            assertThat(service.welcome(CLIENT, "Abc23XyZ9k").orElseThrow().partnerUserId()).isEqualTo(PARTNER);
        }

        @Test
        @DisplayName("a partner paying through their own link is not offered to write to themselves")
        void partnerIsNotTheirOwnContact() {
            when(apps.offerable(anyLong(), any())).thenReturn(List.of(cardA, cardB));
            when(deliveries.findByOfferIdAndClientUserId(1L, PARTNER)).thenReturn(List.of(
                    delivery(5L, APP_A, PartnerOfferDelivery.Status.INSTALLED, 1)));

            assertThat(service.welcome(PARTNER, "Abc23XyZ9k").orElseThrow().partnerUserId()).isNull();
        }

        @Test
        @DisplayName("nothing for an unknown offer, a blank or oversized token, or no caller")
        void nothing() {
            assertThat(service.welcome(CLIENT, "Unknown0001")).isEmpty();
            assertThat(service.welcome(CLIENT, " ")).isEmpty();
            assertThat(service.welcome(CLIENT, "X".repeat(17))).isEmpty();
            assertThat(service.welcome(null, "Abc23XyZ9k")).isEmpty();
        }
    }
}
