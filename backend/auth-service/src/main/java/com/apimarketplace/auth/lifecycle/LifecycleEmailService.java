package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.analytics.PersonaBuckets;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PlanResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Feeds the Resend lifecycle automations: keeps one Resend CONTACT per user in sync and
 * sends named EVENTS (see {@link LifecycleEvents}).
 *
 * <p>Every public method is fire-and-forget and never throws. When called inside a
 * transaction the work is deferred to AFTER COMMIT, so a rolled-back checkout or debit
 * never sends an email trigger; outside one it is submitted at once. The user row (and the
 * plan / persona) is read on the Resend worker thread in a read-only transaction, never on
 * the caller's thread. Contact first, event second, on the same single thread: an automation
 * started by the event always sees the contact's current properties.
 *
 * <p>A no-op when the Resend client is inactive (CE, or cloud without a key). Deactivated,
 * disabled and email-unverified accounts receive nothing. The signup IP is never read here, so it can never
 * reach Resend.
 */
@Service
public class LifecycleEmailService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleEmailService.class);

    static final String FALLBACK_LOCALE = "en";
    static final String FALLBACK_PLAN = "free";
    static final String FALLBACK_PERSONA = "other";
    static final String FALLBACK_TIME_ZONE = "UTC";
    static final String FALLBACK_COUNTRY = "XX";

    /** What {@link #submitLocalized} did with an event. */
    public enum Dispatch {
        /** Queued for the Resend worker. */
        QUEUED,
        /** Nothing to do: the Resend client is inactive (CE, or cloud without a key). */
        INACTIVE,
        /** Refused: the worker queue has no room for it right now; the caller may retry later. */
        BUSY
    }

    /**
     * A write-once claim gating one event ({@code checkout.started} window, {@code user.signed_up}
     * stamp). {@link #getAsBoolean} tries to take it; {@link #release} gives back a GRANTED claim
     * whose event never left Resend (timeout, 5xx), so a later attempt can still send it. A
     * release must only undo what its own grant wrote, never a racing emitter's claim, and must
     * never throw.
     */
    @FunctionalInterface
    public interface Claim extends BooleanSupplier {
        /** Called at most once, only after a granted claim whose send failed. Default: nothing to undo. */
        default void release() {
        }
    }

    private final ResendClient resend;
    private final UserRepository userRepository;
    private final UserOnboardingRepository onboardingRepository;
    private final PlanResolutionService planResolutionService;
    private final TransactionOperations readTx;

    /** Product analytics (PostHog). Optional: a null field emits nothing. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics;

    @org.springframework.beans.factory.annotation.Autowired
    public LifecycleEmailService(ResendClient resend,
                                 UserRepository userRepository,
                                 UserOnboardingRepository onboardingRepository,
                                 PlanResolutionService planResolutionService,
                                 PlatformTransactionManager transactionManager) {
        this(resend, userRepository, onboardingRepository, planResolutionService, readOnly(transactionManager));
    }

    LifecycleEmailService(ResendClient resend,
                          UserRepository userRepository,
                          UserOnboardingRepository onboardingRepository,
                          PlanResolutionService planResolutionService,
                          TransactionOperations readTx) {
        this.resend = resend;
        this.userRepository = userRepository;
        this.onboardingRepository = onboardingRepository;
        this.planResolutionService = planResolutionService;
        this.readTx = readTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager tm) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setReadOnly(true);
        return t;
    }

    public boolean isActive() {
        return resend != null && resend.isActive();
    }

    /** Re-sends the contact (first name + properties) of this user. */
    public void syncContact(Long userId) {
        dispatch(userId, null, null);
    }

    /** Sends one event for this user, contact untouched. */
    public void emit(Long userId, String event, Map<String, Object> payload) {
        if (event == null) return;
        dispatch(userId, event, payload, false);
    }

    /**
     * Sends one event for this user, contact untouched, but only if {@code claim} answers true.
     * The claim runs on the Resend worker thread, so it is only ever attempted once the caller's
     * transaction has COMMITTED and the queue has ACCEPTED the task, and only for a user who would
     * actually receive the event: a rolled-back caller, a full queue or an ineligible account never
     * consumes it, and the claim's own database work can never touch the caller's transaction.
     * Used for {@code checkout.started}, whose claim is a durable once-per-window throttle.
     */
    public void emitIfClaimed(Long userId, String event, Map<String, Object> payload, Claim claim) {
        dispatchIfClaimed(userId, event, payload, false, claim);
    }

    /**
     * {@link #syncContactAndEmit} gated by a write-once {@code claim}, with the same guarantees as
     * {@link #emitIfClaimed}: the claim runs on the worker, after commit, only once the queue
     * accepted the task and only for an account that would receive the event, so a rolled-back
     * caller, a refused enqueue or an ineligible account leaves it free for a later attempt. Used for
     * {@code user.signed_up}, whose claim is the {@code lifecycle_signup_emitted_at} stamp.
     */
    public void syncContactAndEmitIfClaimed(Long userId, String event, Map<String, Object> payload,
                                            Claim claim) {
        dispatchIfClaimed(userId, event, payload, true, claim);
    }

    private void dispatchIfClaimed(Long userId, String event, Map<String, Object> payload, boolean syncContact,
                                   Claim claim) {
        if (event == null || claim == null || !isActive() || userId == null) return;
        try {
            final Map<String, Object> safePayload = payload != null ? new LinkedHashMap<>(payload) : Map.of();
            afterCommit(() -> {
                // A refused enqueue never claimed anything, so a later path can still send the
                // event; but it is gone for THIS attempt, which must be visible.
                if (!resend.submit(() -> run(userId, event, locale -> safePayload, syncContact, claim))) {
                    log.warn("[lifecycle] event {} for user {} not queued (worker queue full), dropped unclaimed",
                            event, userId);
                }
            });
        } catch (Exception e) {
            log.debug("[lifecycle] dispatch failed (dropped): {}", e.toString());
        }
    }

    /** Syncs the contact, THEN sends the event, in that order. */
    public void syncContactAndEmit(Long userId, String event, Map<String, Object> payload) {
        dispatch(userId, event, payload);
    }

    /**
     * Syncs the contact, then sends the event whose payload is built for the contact's LOCALE
     * ({@code en} when unknown) on the worker thread, where the user row is read anyway. Used by
     * the events other services send (trophies, monthly recap), whose payload carries labels the
     * email shows as-is.
     *
     * <p>Unlike the other methods it is NOT deferred to after commit (its callers are not
     * transactional) and it REPORTS what happened, so a bulk sender can slow down instead of
     * losing events: {@code bulk} work is refused while the worker queue is half full (see
     * {@link ResendClient#submitBulk}). Never throws.
     */
    public Dispatch submitLocalized(Long userId, String event, Function<String, Map<String, Object>> payloadForLocale,
                                    boolean bulk) {
        if (!isActive()) return Dispatch.INACTIVE;
        if (userId == null || event == null || payloadForLocale == null) return Dispatch.INACTIVE;
        try {
            Runnable task = () -> run(userId, event, payloadForLocale, true);
            boolean queued = bulk ? resend.submitBulk(task) : resend.submit(task);
            return queued ? Dispatch.QUEUED : Dispatch.BUSY;
        } catch (Exception e) {
            log.debug("[lifecycle] localized dispatch failed: {}", e.toString());
            return Dispatch.BUSY;
        }
    }

    /** Deletes the Resend contact of an account that no longer exists. */
    public void deleteContact(String email) {
        if (!isActive() || email == null || email.isBlank()) return;
        afterCommit(() -> resend.submit(() -> resend.deleteContact(email)));
    }

    private void dispatch(Long userId, String event, Map<String, Object> payload) {
        dispatch(userId, event, payload, true);
    }

    private void dispatch(Long userId, String event, Map<String, Object> payload, boolean syncContact) {
        if (!isActive() || userId == null) return;
        try {
            final Map<String, Object> safePayload = payload != null ? new LinkedHashMap<>(payload) : Map.of();
            afterCommit(() -> resend.submit(() -> run(userId, event, safePayload, syncContact)));
        } catch (Exception e) {
            log.debug("[lifecycle] dispatch failed (dropped): {}", e.toString());
        }
    }

    /** Runs on the Resend worker thread. */
    void run(Long userId, String event, Map<String, Object> payload, boolean syncContact) {
        run(userId, event, locale -> payload, syncContact);
    }

    private void run(Long userId, String event, Function<String, Map<String, Object>> payloadForLocale,
                     boolean syncContact) {
        run(userId, event, payloadForLocale, syncContact, null);
    }

    private void run(Long userId, String event, Function<String, Map<String, Object>> payloadForLocale,
                     boolean syncContact, Claim claim) {
        Optional<ContactSnapshot> snapshot = readTx.execute(status -> snapshot(userId));
        if (snapshot == null || snapshot.isEmpty()) return;
        // Claimed outside the read transaction above, right before the send: nothing else can
        // stop the event once the claim is granted.
        if (claim != null && !claim.getAsBoolean()) return;
        ContactSnapshot c = snapshot.get();
        boolean sent = false;
        boolean attempted = false;
        try {
            if (syncContact) {
                resend.upsertContact(c.email(), c.firstName(), c.properties());
            }
            if (event != null) {
                attempted = true;
                sent = resend.sendEvent(c.email(), event, payloadForLocale.apply(c.properties().get("locale")));
            }
        } finally {
            // A granted claim is only spent by an event that actually left: give it back
            // otherwise, or a Resend timeout would cost this user the email for good.
            if (claim != null && !sent) releaseClaim(userId, event, claim);
            if (attempted) recordSent(userId, event, sent);
        }
    }

    /** Best-effort analytics for an event send that was attempted; never throws. */
    private void recordSent(Long userId, String event, boolean delivered) {
        if (analytics == null) return;
        try {
            analytics.lifecycleEventSent(userId, event, delivered);
        } catch (Exception e) {
            log.debug("[lifecycle] analytics for {} dropped: {}", event, e.toString());
        }
    }

    private static void releaseClaim(Long userId, String event, Claim claim) {
        try {
            claim.release();
            log.warn("[lifecycle] event {} not delivered for user {}, claim released for a later attempt",
                    event, userId);
        } catch (Exception e) {
            log.warn("[lifecycle] event {} not delivered for user {}, and its claim could not be released: {}",
                    event, userId, e.toString());
        }
    }

    Optional<ContactSnapshot> snapshot(Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) return Optional.empty();
        User user = userOpt.get();
        if (user.getEmail() == null || user.getEmail().isBlank()) return Optional.empty();
        if (!user.isEnabled() || user.getDeactivatedAt() != null) return Optional.empty();
        // An unverified address may not belong to whoever typed it: no contact, no event.
        // The signup event is sent once the address is verified (EmailVerificationService).
        if (!user.isEmailVerified()) return Optional.empty();
        return Optional.of(new ContactSnapshot(user.getEmail(), user.getFirstName(), properties(user)));
    }

    /**
     * The contact properties, every one a string with a documented fallback. The signup IP
     * is deliberately absent: it is abuse-prevention data and never leaves auth-service.
     *
     * <p>{@code activated} is what the welcome automation re-checks right before each
     * follow-up. A Resend {@code wait_for_event} only catches events that arrive WHILE it
     * waits, so an activation during the welcome delay would otherwise not stop the sequence.
     * It is read here, at send time, so every sync (and the one that precedes each event)
     * carries the current state.
     */
    Map<String, String> properties(User user) {
        Map<String, String> props = new LinkedHashMap<>();
        String locale = LifecycleInputs.locale(user.getLocale());
        props.put("locale", locale != null ? locale : FALLBACK_LOCALE);
        props.put("plan", plan(user.getId()));
        props.put("persona", persona(user.getId()));
        props.put("timezone", user.getTimeZone() != null ? user.getTimeZone() : FALLBACK_TIME_ZONE);
        props.put("country", user.getSignupCountry() != null ? user.getSignupCountry() : FALLBACK_COUNTRY);
        props.put("marketing_consent", user.isMarketingConsent() ? "yes" : "no");
        props.put("activated", user.getActivatedAt() != null ? "yes" : "no");
        return props;
    }

    private String plan(Long userId) {
        try {
            String code = planResolutionService != null ? planResolutionService.resolveBillingPlan(userId) : null;
            return code == null || code.isBlank() ? FALLBACK_PLAN : code.trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return FALLBACK_PLAN;
        }
    }

    private String persona(Long userId) {
        try {
            String bucket = onboardingRepository.findByUserId(userId)
                    .map(o -> PersonaBuckets.profession(o.getProfession()))
                    .orElse(null);
            return bucket != null ? bucket : FALLBACK_PERSONA;
        } catch (Exception e) {
            return FALLBACK_PERSONA;
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    record ContactSnapshot(String email, String firstName, Map<String, String> properties) {
    }
}
