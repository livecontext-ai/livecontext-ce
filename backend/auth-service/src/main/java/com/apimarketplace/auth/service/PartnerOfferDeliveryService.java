package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerOffer;
import com.apimarketplace.auth.domain.PartnerOfferDelivery;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.repository.PartnerOfferDeliveryRepository;
import com.apimarketplace.auth.repository.PartnerOfferRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.stripe.StripeClient;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Delivers the applications of a partner's offer to the clients who pay through it (V560).
 *
 * <p>When the checkout opens from an offer, {@link #expect} writes one AWAITING_PAYMENT row per
 * application. The checkout carries the offer's token in the Stripe subscription's metadata
 * ({@link StripeBillingService#PARTNER_OFFER_METADATA}); when that subscription's first invoice is
 * paid, {@link #enqueue} moves the rows to PENDING (writing them if they were never written), and
 * each app is installed in the client's workspace: tried at once, then by the sweep with a growing
 * backoff, until it is installed or refused for good. A payment whose webhook could not be handled
 * is found again by {@link #reconcile}, which reads the subscription's own metadata. Nothing is
 * installed before the payment: a client who cancels or walks away receives nothing, and the
 * waiting rows are forgotten after a few days. One who pays receives the apps even with the tab
 * closed, since nothing here waits on the browser.
 *
 * <p>Each try first takes its delivery ({@link PartnerOfferDeliveryRepository#CLAIM_SQL}), so two
 * pods, or the sweep and the try after payment, never install the same app twice.
 */
@Service
public class PartnerOfferDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(PartnerOfferDeliveryService.class);

    /** Wait after each failed try, then a last try: a little over a day from the first to the last. */
    static final Duration[] BACKOFF = {
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2),
            Duration.ofHours(4), Duration.ofHours(8), Duration.ofHours(12)};
    static final int MAX_ATTEMPTS = BACKOFF.length + 1;
    /**
     * A refusal from the client's plan (its apps quota, or a feature it lacks) once the paid plan
     * is on: given up after this many tries (about 20 minutes). While the paid plan is not on yet
     * (its webhook late, or retried by Stripe hours later), the refusal keeps the full run.
     */
    static final int PLAN_RETRY_ATTEMPTS = 6;
    /**
     * Claims past every recorded outcome (each try records one, the last gives up): a delivery
     * claimed this often lost its results, and is given up instead of being installed for ever.
     */
    static final int MAX_CLAIMS = MAX_ATTEMPTS + 2;
    /** The reason a try recorded while the client's paid plan was not on yet. */
    static final String PAID_PLAN_NOT_ON = "paid_plan_not_on";
    static final Set<String> PLAN_REASONS = Set.of(PartnerOfferAppsClient.PLAN_LIMIT, PartnerOfferAppsClient.PLAN_UPGRADE_REQUIRED);
    /** How long a try holds its delivery: past the install timeout, so a crashed try frees it. */
    static final Duration LEASE = Duration.ofMinutes(3);
    /** A checkout's payment is looked for after this long (its webhook normally came long before). */
    static final Duration SETTLE = Duration.ofMinutes(10);
    /** A checkout still unpaid after this long was abandoned: its waiting rows go. */
    static final Duration ABANDONED = Duration.ofDays(3);
    static final int SWEEP_BATCH = 20;
    static final int RECONCILE_BATCH = 50;
    private static final int MAX_ERROR = 300;

    /** What the client's welcome screen shows; {@code partnerUserId} only when they may write to the partner. */
    public record Welcome(PublicProfileDto partner, Long partnerUserId, List<Map<String, Object>> apps) {}

    private final PartnerOfferRepository offers;
    private final PartnerOfferDeliveryRepository deliveries;
    private final RewardRedemptionRepository redemptions;
    private final UserRepository users;
    private final UserService userService;
    private final SubscriptionRepository subscriptions;
    private final Supplier<StripeClient> stripe;
    private final PartnerOfferAppsClient apps;
    private final TransactionTemplate tx;
    /** The install right after a payment: never queued behind a batch. */
    private final Executor immediate;
    /** The sweep and the reconciliation, one batch after the other. */
    private final Executor worker;
    private final List<ExecutorService> owned = new ArrayList<>();
    private final boolean unlimited;
    private final Supplier<Instant> clock;
    /** One sweep, and one reconciliation, at a time on this pod: a slow one is not stacked under the next. */
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private final AtomicBoolean reconciling = new AtomicBoolean();

    /** What a reconciliation learns about one waiting checkout. */
    enum Payment { THROUGH_OFFER, ELSEWHERE, NONE }

    @Autowired
    public PartnerOfferDeliveryService(PartnerOfferRepository offers, PartnerOfferDeliveryRepository deliveries,
                                       RewardRedemptionRepository redemptions, UserRepository users,
                                       UserService userService, SubscriptionRepository subscriptions,
                                       ObjectProvider<StripeClient> stripe, PartnerOfferAppsClient apps,
                                       PlatformTransactionManager transactionManager,
                                       @Value("${credit.unlimited:false}") boolean unlimited) {
        // Off the scheduler's single thread: a slow publication-service or Stripe must not hold
        // back the other scheduled jobs of this service.
        this(offers, deliveries, redemptions, users, userService, subscriptions, stripe::getIfAvailable, apps,
                new TransactionTemplate(transactionManager), daemon("partner-offer-install"), daemon("partner-offer-batch"),
                unlimited, Instant::now);
    }

    private static ExecutorService daemon(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    PartnerOfferDeliveryService(PartnerOfferRepository offers, PartnerOfferDeliveryRepository deliveries,
                                RewardRedemptionRepository redemptions, UserRepository users,
                                UserService userService, SubscriptionRepository subscriptions,
                                Supplier<StripeClient> stripe, PartnerOfferAppsClient apps, TransactionTemplate tx,
                                Executor immediate, Executor worker, boolean unlimited, Supplier<Instant> clock) {
        this.offers = offers;
        this.deliveries = deliveries;
        this.redemptions = redemptions;
        this.users = users;
        this.userService = userService;
        this.subscriptions = subscriptions;
        this.stripe = stripe;
        this.apps = apps;
        this.tx = tx;
        this.immediate = immediate;
        this.worker = worker;
        if (immediate instanceof ExecutorService es) owned.add(es);
        if (worker instanceof ExecutorService es && worker != immediate) owned.add(es);
        this.unlimited = unlimited;
        this.clock = clock;
    }

    @PreDestroy
    void stop() {
        owned.forEach(ExecutorService::shutdownNow);
    }

    /**
     * A checkout just opened from the offer {@code token}: each app it gives waits for the payment.
     * Never throws: the payment itself (and its webhook) does not depend on this record, which is
     * only there to find a payment whose webhook could not be handled.
     */
    public void expect(String token, Long clientUserId) {
        if (token == null || token.isBlank() || clientUserId == null) return;
        try {
            tx.executeWithoutResult(status -> offers.findByToken(token.trim()).ifPresent(offer -> {
                Instant now = clock.get();
                for (UUID publicationId : publicationIds(offer)) {
                    deliveries.expect(offer.getId(), clientUserId, publicationId, now);
                }
            }));
        } catch (RuntimeException e) {
            log.warn("Partner offer apps not recorded at checkout for user {}: {}", clientUserId, e.getMessage());
        }
    }

    /**
     * The client paid the first invoice of a subscription opened from the offer {@code token}: each
     * application the offer gives is to install (whatever the offer's state now: the client paid
     * for it), and they are tried straight away. Returns how many deliveries moved on.
     */
    public int enqueue(String token, Long clientUserId, String invoiceId) {
        if (token == null || token.isBlank() || clientUserId == null) return 0;
        Integer moved = tx.execute(status -> {
            PartnerOffer offer = offers.findByToken(token.trim()).orElse(null);
            if (offer == null) {
                // Purged between the checkout and the payment: nothing is left to give.
                log.warn("Invoice {} paid by user {} names a partner offer that no longer exists: no app to deliver", invoiceId, clientUserId);
                return 0;
            }
            Instant now = clock.get();
            int n = 0;
            for (UUID publicationId : publicationIds(offer)) {
                n += deliveries.paid(offer.getId(), clientUserId, publicationId, invoiceId, now);
            }
            if (n > 0) log.info("Partner offer #{}: {} application(s) to install for user {} (invoice {})", offer.getId(), n, clientUserId, invoiceId);
            return n;
        });
        int count = moved == null ? 0 : moved;
        if (count > 0) startNow(clientUserId);
        return count;
    }

    /** The backstop: every delivery whose next try is due, a batch at a time, off the scheduler's thread. */
    @Scheduled(fixedDelayString = "${partner-offer.delivery-sweep-ms:30000}", initialDelay = 45_000)
    // Held at least 20 s: the work runs on the worker, so without a floor the lock would be
    // released at once and every pod would sweep the same rows (the claim keeps that harmless,
    // but it is wasted work).
    @SchedulerLock(name = "partner_offer_delivery", lockAtMostFor = "PT2M", lockAtLeastFor = "PT20S")
    public void sweep() {
        if (unlimited) return;
        onWorker(sweeping, () -> attemptAll(deliveries.findDueIds(clock.get(), SWEEP_BATCH)));
    }

    /** Runs {@code work} on the worker unless the previous one (guarded by {@code flag}) is still going. */
    private void onWorker(AtomicBoolean flag, Runnable work) {
        if (!flag.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    work.run();
                } finally {
                    flag.set(false);
                }
            });
        } catch (RuntimeException shuttingDown) {
            flag.set(false);
        }
    }

    /**
     * Finds the payments whose webhook could not be handled: a checkout opened from an offer, still
     * waiting after its webhook should have come, whose client now has an active subscription that
     * carries this offer's token. Those apps are installed as if the webhook had come. Checkouts
     * waiting for days were abandoned, and are forgotten.
     */
    @Scheduled(fixedDelayString = "${partner-offer.reconcile-ms:600000}", initialDelay = 120_000)
    @SchedulerLock(name = "partner_offer_reconcile", lockAtMostFor = "PT9M", lockAtLeastFor = "PT5M")
    public void reconcile() {
        if (unlimited) return;
        // On the worker: the Stripe reads must not hold the scheduler's single thread.
        onWorker(reconciling, this::reconcileNow);
    }

    void reconcileNow() {
        Instant now = clock.get();
        try {
            // A cleanup that fails is retried next run; it must not keep this run from finding payments.
            try {
                Integer forgotten = tx.execute(status -> deliveries.forgetAbandoned(now.minus(ABANDONED)));
                if (forgotten != null && forgotten > 0) log.info("Forgot {} partner offer app(s) of checkouts never paid", forgotten);
            } catch (RuntimeException e) {
                log.warn("Partner offer reconciliation: abandoned checkouts not cleaned up: {}", e.getMessage());
            }
            for (Object[] pair : deliveries.findAwaiting(now.minus(ABANDONED), now.minus(SETTLE), RECONCILE_BATCH)) {
                Long offerId = ((Number) pair[0]).longValue();
                Long clientUserId = ((Number) pair[1]).longValue();
                try {
                    switch (payment(offerId, clientUserId)) {
                        case THROUGH_OFFER -> {
                            Integer promoted = tx.execute(status -> deliveries.promote(offerId, clientUserId, clock.get()));
                            if (promoted != null && promoted > 0) {
                                log.warn("Partner offer #{}: payment of user {} found without its webhook, {} app(s) to install", offerId, clientUserId, promoted);
                                startNow(clientUserId);
                            }
                        }
                        // Subscribed without this offer: its apps will never be theirs, and the
                        // checkout is not looked at again.
                        case ELSEWHERE -> tx.execute(status -> deliveries.forgetPair(offerId, clientUserId));
                        case NONE -> tx.execute(status -> deliveries.markChecked(offerId, clientUserId, clock.get()));
                    }
                } catch (RuntimeException e) {
                    log.warn("Partner offer #{}: payment of user {} not checked: {}", offerId, clientUserId, e.getMessage());
                    try {
                        tx.execute(status -> deliveries.markChecked(offerId, clientUserId, clock.get()));
                    } catch (RuntimeException alsoFailed) {
                        // It stays first in the queue: the next run looks at it again.
                        log.warn("Partner offer #{}: user {} not marked checked: {}", offerId, clientUserId, alsoFailed.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("Partner offer reconciliation failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Whether the client paid through this offer: their active paid subscription carries its token
     * (THROUGH_OFFER), or another one (ELSEWHERE); NONE while they have no paid subscription (or
     * Stripe is not configured here, so nothing can be confirmed). Throws when Stripe cannot answer.
     */
    Payment payment(Long offerId, Long clientUserId) {
        // Read inside a transaction: the subscription's plan is a lazy association, and this runs
        // on the worker, where nothing else holds a session open.
        record Paid(String providerSubscriptionId, String planCode) {}
        Paid sub = tx.execute(status -> subscriptions.findActiveByUserId(clientUserId)
                .map(s -> new Paid(s.getProviderSubscriptionId(), s.getPlan() == null ? null : s.getPlan().getCode()))
                .orElse(null));
        if (sub == null || sub.providerSubscriptionId() == null || "FREE".equalsIgnoreCase(sub.planCode())) return Payment.NONE;
        StripeClient client = stripe.get();
        PartnerOffer offer = offers.findById(offerId).orElse(null);
        if (client == null || offer == null) return Payment.NONE;
        try {
            Map<String, String> metadata = client.subscriptions().retrieve(sub.providerSubscriptionId()).getMetadata();
            boolean ours = metadata != null && offer.getToken().equals(metadata.get(StripeBillingService.PARTNER_OFFER_METADATA));
            return ours ? Payment.THROUGH_OFFER : Payment.ELSEWHERE;
        } catch (com.stripe.exception.StripeException e) {
            throw new IllegalStateException("subscription unreadable: " + e.getMessage(), e);
        }
    }

    private void startNow(Long clientUserId) {
        try {
            immediate.execute(() -> attemptAll(deliveries.findDueIdsForClient(clientUserId, clock.get())));
        } catch (RuntimeException shuttingDown) {
            // The deliveries are recorded: the sweep tries them.
            log.warn("Partner offer apps of user {}: immediate install not started ({}); the sweep will", clientUserId, shuttingDown.toString());
        }
    }

    private void attemptAll(List<Long> ids) {
        for (Long id : ids) {
            try {
                attempt(id);
            } catch (Exception e) {
                // The lease frees it for the next sweep.
                log.error("Partner offer delivery {} try failed: {}", id, e.getMessage(), e);
            }
        }
    }

    /** One try at one delivery, when no one else holds it. */
    void attempt(Long id) {
        Instant now = clock.get();
        // Millisecond precision: the lease is read back from the database to check it is still ours.
        Instant lease = now.plus(LEASE).truncatedTo(ChronoUnit.MILLIS);
        Integer taken = tx.execute(status -> deliveries.claim(id, now, lease));
        if (taken == null || taken != 1) return;
        PartnerOfferDelivery delivery = deliveries.findById(id).orElse(null);
        if (delivery == null) return;
        if (delivery.getAttempts() > MAX_CLAIMS) {
            tx.executeWithoutResult(status -> deliveries.findById(id).ifPresent(d -> {
                d.setStatus(PartnerOfferDelivery.Status.FAILED);
                d.setLastError(clip("gave up after " + d.getAttempts() + " claims without a recorded outcome"));
                deliveries.save(d);
            }));
            log.error("Partner offer delivery {}: claimed {} times without a recorded outcome, given up", id, delivery.getAttempts());
            return;
        }
        // Not before the paid plan is on: on the free plan the install would be refused, or would
        // clone the app without the interfaces past the free quota and still answer "installed".
        // A wait like any other, within the full run.
        Boolean planOn = tx.execute(status -> paidPlanIsOn(delivery.getClientUserId()));
        PartnerOfferAppsClient.Install result = Boolean.TRUE.equals(planOn)
                // Outside any transaction: the install is a remote call that can take a while.
                ? apps.install(delivery.getPublicationId(), delivery.getClientUserId())
                : new PartnerOfferAppsClient.Install(PartnerOfferAppsClient.Outcome.RETRY, PAID_PLAN_NOT_ON);
        tx.executeWithoutResult(status -> deliveries.findById(id).ifPresent(d -> {
            // Past its lease (a very slow answer), another try may hold it now: that one records.
            // Compared to the millisecond: the database keeps microseconds, the clock may have nanos.
            if (d.getNextAttemptAt() == null || lease.toEpochMilli() != d.getNextAttemptAt().toEpochMilli()) {
                log.warn("Partner offer delivery {}: a late answer ({}) is left to the try that holds it now", id, result.outcome());
                return;
            }
            record(d, result, clock.get());
        }));
    }

    void record(PartnerOfferDelivery d, PartnerOfferAppsClient.Install result, Instant now) {
        switch (result.outcome()) {
            case INSTALLED -> {
                d.setStatus(PartnerOfferDelivery.Status.INSTALLED);
                d.setLastError(null);
                log.info("Partner offer delivery {}: application {} installed for user {}", d.getId(), d.getPublicationId(), d.getClientUserId());
            }
            case REFUSED -> {
                d.setStatus(PartnerOfferDelivery.Status.FAILED);
                d.setLastError(clip(result.reason()));
                log.warn("Partner offer delivery {}: application {} refused for user {}: {}", d.getId(), d.getPublicationId(), d.getClientUserId(), result.reason());
            }
            case RETRY -> {
                boolean planRefusal = PLAN_REASONS.contains(result.reason());
                if (d.getAttempts() >= MAX_ATTEMPTS
                        || (planRefusal && d.getAttempts() >= PLAN_RETRY_ATTEMPTS && paidPlanIsOn(d.getClientUserId()))) {
                    d.setStatus(PartnerOfferDelivery.Status.FAILED);
                    d.setLastError(clip("gave up after " + d.getAttempts() + " tries: " + result.reason()));
                    log.error("Partner offer delivery {}: gave up on application {} for user {}: {}", d.getId(), d.getPublicationId(), d.getClientUserId(), result.reason());
                } else {
                    Duration wait = BACKOFF[Math.min(Math.max(d.getAttempts(), 1), BACKOFF.length) - 1];
                    // No answer (a timeout): the install may still be running on the other side.
                    // The next try waits at least a lease, so it does not clone the app a second time.
                    if (result.reason() != null && result.reason().startsWith(PartnerOfferAppsClient.UNREACHABLE) && wait.compareTo(LEASE) < 0) wait = LEASE;
                    d.setNextAttemptAt(now.plus(wait));
                    d.setLastError(clip(result.reason()));
                }
            }
        }
        deliveries.save(d);
    }

    /**
     * What the client sees right after paying through {@code token}: each application the offer
     * gives with where its delivery stands ({@code WAITING} until the payment is confirmed, then
     * {@code PENDING}, {@code INSTALLED} or {@code FAILED}), and the partner. Empty for an unknown
     * offer. The partner's account id (to write to them) is given only to a client of theirs: one
     * attributed to them, or one their offer delivers to.
     */
    public Optional<Welcome> welcome(Long clientUserId, String token) {
        if (clientUserId == null || token == null || token.isBlank() || token.length() > 16) return Optional.empty();
        // Writable: the partner's public profile generates its @handle on first read.
        record Read(PartnerOffer offer, List<PartnerOfferDelivery> rows, PublicProfileDto partner, boolean theirClient) {}
        Read read = tx.execute(status -> {
            PartnerOffer offer = offers.findByToken(token.trim()).orElse(null);
            if (offer == null) return null;
            List<PartnerOfferDelivery> rows = deliveries.findByOfferIdAndClientUserId(offer.getId(), clientUserId);
            PublicProfileDto partner = users.findById(offer.getPartnerUserId()).flatMap(userService::getPublicProfile).orElse(null);
            boolean attributed = redemptions.findByRedeemerUserIdAndProgram(clientUserId, RewardProgram.PARTNER)
                    .map(r -> Objects.equals(r.getOwnerUserId(), offer.getPartnerUserId()))
                    .orElse(false);
            return new Read(offer, rows, partner, attributed || !rows.isEmpty());
        });
        if (read == null) return Optional.empty();

        Map<String, Map<String, Object>> cards = new HashMap<>();
        boolean cardsRead = false;
        try {
            for (Map<String, Object> card : apps.offerable(read.offer().getPartnerUserId(), read.offer().getAppPublicationIds())) {
                cards.put(String.valueOf(card.get("id")), card);
            }
            cardsRead = true;
        } catch (Exception e) {
            log.warn("Partner offer #{}: application cards unavailable for the welcome screen: {}", read.offer().getId(), e.getMessage());
        }
        Map<UUID, PartnerOfferDelivery> byApp = new HashMap<>();
        for (PartnerOfferDelivery d : read.rows()) byApp.put(d.getPublicationId(), d);

        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID publicationId : publicationIds(read.offer())) {
            PartnerOfferDelivery delivery = byApp.get(publicationId);
            Map<String, Object> card = cards.get(publicationId.toString());
            // An app no longer offered (withdrawn, made private) is never shown unless it is
            // already in the client's workspace: its install can only be refused, and the client
            // must not watch an app they were never shown fail. When the cards cannot be read,
            // the apps on their way still show, by id.
            boolean onItsWay = delivery != null && delivery.getStatus() != PartnerOfferDelivery.Status.AWAITING_PAYMENT;
            boolean withdrawn = card == null && cardsRead;
            if (withdrawn && (delivery == null || delivery.getStatus() != PartnerOfferDelivery.Status.INSTALLED)) continue;
            if (card == null && !onItsWay) continue;
            Map<String, Object> app = new LinkedHashMap<>(card != null ? card : Map.of("id", publicationId.toString()));
            app.put("status", onItsWay ? delivery.getStatus().name() : "WAITING");
            out.add(app);
        }
        // Never the caller themselves: a partner paying through their own link has no one to write to.
        boolean writable = read.theirClient() && !Objects.equals(clientUserId, read.offer().getPartnerUserId());
        return Optional.of(new Welcome(read.partner(), writable ? read.offer().getPartnerUserId() : null, out));
    }

    /** Whether the client's paid plan is on: a Stripe subscription on a plan that is not FREE. */
    private boolean paidPlanIsOn(Long clientUserId) {
        return subscriptions.findActiveByUserId(clientUserId)
                .map(s -> s.getProviderSubscriptionId() != null && s.getPlan() != null
                        && !"FREE".equalsIgnoreCase(s.getPlan().getCode()))
                .orElse(false);
    }

    private static List<UUID> publicationIds(PartnerOffer offer) {
        List<UUID> ids = new ArrayList<>();
        for (String id : offer.getAppPublicationIds()) {
            try {
                if (id != null) ids.add(UUID.fromString(id.trim()));
            } catch (IllegalArgumentException ignored) {
                // Not an id: nothing to deliver for it.
            }
        }
        return ids;
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR);
    }
}
