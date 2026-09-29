package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.MarketingConsentResponse;
import com.apimarketplace.auth.dto.ProfileContextRequest;
import com.apimarketplace.auth.repository.UserAcquisitionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Writes the lifecycle facts of a user (locale, time zone, signup country / IP, first-touch
 * acquisition, marketing consent, activation) and tells {@link LifecycleEmailService} when
 * the Resend contact or an event must follow.
 *
 * <p>Every write is one conditional UPDATE that reports whether it changed anything, never a
 * whole-row save: that is what makes the write-once columns really write-once under racing
 * requests, and what keeps these writes from reverting a concurrent login bookkeeping write
 * on the same row.
 *
 * <p>Works the same in both editions; only the Resend side is inert in CE.
 */
@Service
public class UserLifecycleContextService {

    private static final Logger log = LoggerFactory.getLogger(UserLifecycleContextService.class);

    /** How long after account creation the Cloudflare country / IP still count as the signup's. */
    static final long SIGNUP_WINDOW_DAYS = 7L;

    private final UserRepository userRepository;
    private final UserAcquisitionRepository acquisitionRepository;
    private final LifecycleEmailService lifecycleEmails;
    private final Clock clock;
    /** REQUIRES_NEW: the signup claim never joins, nor marks rollback-only, anyone else's transaction. */
    private final TransactionOperations claimTx;

    /** Product analytics (PostHog). Optional: a null field emits nothing. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics;

    /**
     * Carries an explicit language pick to the person's Keycloak user, so the login and
     * password-reset pages and the mails Keycloak sends are in it too. Absent in CE
     * ({@code auth.mode=embedded}), where there is no Keycloak: a null field syncs nothing, which
     * is correct rather than degraded.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.service.KeycloakAdminEmailVerifier keycloakAdmin;

    /**
     * Where the Keycloak write actually runs. One thread and a small queue: the work is rare (an
     * explicit language pick), each unit is a few HTTP calls, and nothing waits on the result.
     * A full queue drops the sync rather than blocking a request - the language then lags until
     * the next pick, which is the same outcome as any other failure of a best-effort write.
     *
     * <p>Overridable in a test so the assertion does not have to chase another thread.
     */
    private java.util.concurrent.Executor keycloakSyncExecutor = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable, "kc-locale-sync");
                thread.setDaemon(true);
                return thread;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    @org.springframework.beans.factory.annotation.Autowired
    public UserLifecycleContextService(UserRepository userRepository,
                                       UserAcquisitionRepository acquisitionRepository,
                                       LifecycleEmailService lifecycleEmails,
                                       PlatformTransactionManager transactionManager) {
        this(userRepository, acquisitionRepository, lifecycleEmails, Clock.systemUTC(),
                CheckoutStartedThrottle.requiresNew(transactionManager));
    }

    UserLifecycleContextService(UserRepository userRepository,
                                UserAcquisitionRepository acquisitionRepository,
                                LifecycleEmailService lifecycleEmails,
                                Clock clock) {
        this(userRepository, acquisitionRepository, lifecycleEmails, clock, TransactionOperations.withoutTransaction());
    }

    UserLifecycleContextService(UserRepository userRepository,
                                UserAcquisitionRepository acquisitionRepository,
                                LifecycleEmailService lifecycleEmails,
                                Clock clock,
                                TransactionOperations claimTx) {
        this.userRepository = userRepository;
        this.acquisitionRepository = acquisitionRepository;
        this.lifecycleEmails = lifecycleEmails;
        this.clock = clock;
        this.claimTx = claimTx;
    }

    /**
     * Applies what the app reported ({@code PUT /api/users/profile/context}).
     *
     * @param cfCountry value of the {@code CF-IPCountry} header, may be null
     * @param cfIp      value of the {@code CF-Connecting-IP} header, may be null; stored for
     *                  abuse prevention only and never logged above DEBUG nor sent anywhere
     * @return true when a contact property changed (a Resend sync was scheduled)
     */
    @Transactional
    public boolean updateContext(Long userId, ProfileContextRequest request, String cfCountry, String cfIp) {
        if (userId == null) return false;
        boolean contactChanged = false;
        Instant now = clock.instant();

        if (request != null) {
            String locale = LifecycleInputs.locale(request.locale());
            if (locale != null) {
                int rows = Boolean.TRUE.equals(request.localeExplicit())
                        ? userRepository.updateLocaleExplicit(userId, locale)
                        : userRepository.updateLocaleImplicit(userId, locale);
                contactChanged |= rows > 0;
                // On every deliberate pick, NOT only when the database row changed.
                //
                // `updateLocaleExplicit` answers 0 when the locale is already that value with the
                // flag already set - which is true for everyone who picked a language before this
                // shipped, since `locale_explicit` has existed since V527. Gating on `rows > 0`
                // therefore excluded exactly the population this feature is for: their Keycloak
                // attribute would never be written, and no backfill exists.
                //
                // What dropping the gate costs, stated honestly rather than as "one GET": this
                // runs once per tab session (the frontend reports context that often), and each
                // run is a findById here plus, in `setUserLocale`, an admin token and a GET of
                // the representation. It stops there when the attribute already holds this value,
                // which is the usual case, so the PUT is rare. All of it is on a bounded
                // background executor after commit, and a saturated pool is logged and dropped:
                // a missed sync costs a Keycloak page in the wrong language, never a failed
                // request.
                if (Boolean.TRUE.equals(request.localeExplicit())) {
                    syncKeycloakLocale(userId, locale);
                }
            } else if (request.locale() != null) {
                log.debug("[lifecycle] ignoring unsupported locale for user {}", userId);
            }

            String timeZone = LifecycleInputs.timeZone(request.timeZone());
            if (timeZone != null) {
                int rows;
                if (Boolean.TRUE.equals(request.timeZoneFollowsDevice())) {
                    rows = userRepository.releaseTimeZone(userId, timeZone);
                } else if (Boolean.TRUE.equals(request.timeZoneExplicit())) {
                    rows = userRepository.updateTimeZoneExplicit(userId, timeZone);
                } else {
                    rows = userRepository.updateTimeZoneImplicit(userId, timeZone);
                }
                contactChanged |= rows > 0;
            } else if (request.timeZone() != null) {
                log.debug("[lifecycle] ignoring invalid time zone for user {}", userId);
            }

            // First touch, like the signup country / IP below: only an account still at its
            // signup, or every pre-existing account would get today's page as its first touch.
            if (request.acquisition() != null && createdRecently(userId, now)) {
                captureAcquisition(userId, request.acquisition(), now);
            }
        }

        String country = LifecycleInputs.country(cfCountry);
        String ip = LifecycleInputs.ip(cfIp);
        // "Signup" country / IP: only an account created in the last SIGNUP_WINDOW_DAYS is
        // still at its signup, so a pre-existing account is never labelled with the country
        // or address it happens to browse from today.
        if ((country != null || ip != null) && createdRecently(userId, now)) {
            if (country != null) {
                contactChanged |= userRepository.captureSignupCountry(userId, country) > 0;
            }
            if (ip != null) {
                // Not a contact property: never triggers a sync, never leaves this service.
                userRepository.captureSignupIp(userId, ip, now);
            }
        }

        if (contactChanged) {
            lifecycleEmails.syncContact(userId);
        }
        return contactChanged;
    }

    /**
     * True when the account was created within {@link #SIGNUP_WINDOW_DAYS} of {@code now}.
     * {@code created_at} is written as a JVM-local {@code LocalDateTime}, so the bound is
     * computed in the same zone.
     */
    private boolean createdRecently(Long userId, Instant now) {
        return createdRecently(userRepository.findById(userId).map(User::getCreatedAt).orElse(null), now);
    }

    private static boolean createdRecently(LocalDateTime createdAt, Instant now) {
        if (createdAt == null) return false;
        LocalDateTime bound = LocalDateTime.ofInstant(now, ZoneId.systemDefault()).minusDays(SIGNUP_WINDOW_DAYS);
        return !createdAt.isBefore(bound);
    }

    @Transactional(readOnly = true)
    public Optional<MarketingConsentResponse> getMarketingConsent(Long userId) {
        if (userId == null) return Optional.empty();
        return userRepository.findById(userId)
                .map(u -> new MarketingConsentResponse(u.isMarketingConsent(), u.getMarketingConsentAt()));
    }

    /** @return false when the user does not exist */
    @Transactional
    public boolean setMarketingConsent(Long userId, boolean consent) {
        if (userId == null) return false;
        // Read BEFORE the write (the UPDATE is unconditional and clears the persistence context):
        // only a real change is a consent event. Unknown previous value = no event, never a guess.
        // The read exists only for analytics, so it is skipped when nothing would be captured.
        Boolean previous = analytics != null && analytics.isActive()
                ? userRepository.findById(userId).map(User::isMarketingConsent).orElse(null)
                : null;
        int rows = userRepository.updateMarketingConsent(userId, consent, clock.instant());
        if (rows == 0) return false;
        lifecycleEmails.syncContact(userId);
        if (previous != null && previous != consent && analytics != null) {
            try {
                analytics.marketingConsentChanged(userId, consent);
            } catch (Exception e) {
                log.debug("[lifecycle] marketing consent analytics dropped: {}", e.toString());
            }
        }
        return true;
    }

    /**
     * Idempotent: stamps {@code activated_at} the first time and emits {@code user.activated}
     * only then. Every later call is a no-op. The contact is synced first so its
     * {@code activated} property is {@code yes} before the event: the welcome sequence
     * re-checks that property before each follow-up.
     *
     * @return true when this call was the activation
     */
    @Transactional
    public boolean recordActivation(Long userId) {
        if (userId == null) return false;
        int rows = userRepository.markActivatedIfFirst(userId, clock.instant());
        if (rows == 0) return false;
        lifecycleEmails.syncContactAndEmit(userId, LifecycleEvents.USER_ACTIVATED, Map.of());
        return true;
    }

    /**
     * Write-once: syncs the contact then emits {@code user.signed_up}, for the FIRST claim of
     * {@code lifecycle_signup_emitted_at} only. The account-creating login and the verification
     * of an address unverified at creation both land here, and two first logins can race on one
     * account: the conditional UPDATE lets exactly one of them send.
     *
     * <p>Nothing is written on the caller's thread. The stamp is claimed on the lifecycle worker,
     * after the caller COMMITTED and the queue ACCEPTED the task, right before the send (see
     * {@link LifecycleEmailService#syncContactAndEmitIfClaimed}). So a failing claim can never
     * roll back the caller (an email verification), and a refused enqueue, a rolled-back caller
     * or a still-unverified account leaves the stamp free for a later path to send the welcome.
     */
    public void recordSignup(Long userId) {
        if (userId == null) return;
        lifecycleEmails.syncContactAndEmitIfClaimed(userId, LifecycleEvents.USER_SIGNED_UP, Map.of(),
                new SignupClaim(userId));
    }

    /**
     * Re-attempts {@code user.signed_up} on a login of an account that should have had it and has
     * not: verified, no stamp, created within {@link #SIGNUP_WINDOW_DAYS}. That is the state a
     * welcome whose send failed leaves behind (its claim is given back), and nothing else retries
     * it: the account-creating login happens once. The write-once stamp still decides who sends.
     */
    public void retrySignupIfUnsent(User user) {
        if (user == null || user.getId() == null || !user.isEmailVerified()) return;
        if (user.getLifecycleSignupEmittedAt() != null) return;
        if (!createdRecently(user.getCreatedAt(), clock.instant())) return;
        recordSignup(user.getId());
    }

    /**
     * The {@code lifecycle_signup_emitted_at} stamp, claimed and (when the welcome never left)
     * released on the lifecycle worker, each in its OWN transaction ({@code REQUIRES_NEW}).
     */
    private final class SignupClaim implements LifecycleEmailService.Claim {
        private final Long userId;
        private Instant stampedAt;

        SignupClaim(Long userId) {
            this.userId = userId;
        }

        @Override
        public boolean getAsBoolean() {
            // Microseconds: what a TIMESTAMPTZ stores, so the release can match the stamp exactly.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (!claimSignup(userId, now)) return false;
            stampedAt = now;
            return true;
        }

        @Override
        public void release() {
            if (stampedAt == null) return;
            try {
                claimTx.execute(status -> userRepository.releaseSignupEmitted(userId, stampedAt));
            } catch (Exception e) {
                log.warn("[lifecycle] user.signed_up stamp of user {} not released: {}", userId, e.toString());
            }
        }
    }

    /**
     * A database error answers "not claimed" (no welcome this time, the stamp stays free).
     */
    private boolean claimSignup(Long userId, Instant now) {
        try {
            Integer rows = claimTx.execute(status -> userRepository.markSignupEmittedIfFirst(userId, now));
            return rows != null && rows > 0;
        } catch (Exception e) {
            log.warn("[lifecycle] user.signed_up claim failed for user {}: {}", userId, e.toString());
            return false;
        }
    }

    /** Resolves the {@code X-User-ID} of an internal caller: numeric id first, provider id second. */
    @Transactional(readOnly = true)
    public Optional<Long> resolveUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) return Optional.empty();
        String v = userIdHeader.trim();
        try {
            long id = Long.parseLong(v);
            return userRepository.existsById(id) ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException e) {
            return userRepository.findByProviderId(v).map(User::getId);
        }
    }

    /**
     * Tells Keycloak the language this person picked, addressed by the provider id it knows them
     * by. Silent when there is no Keycloak (CE), no user row, or no provider id: none of those is
     * an error, and a language that lags must never fail the request that changed it.
     *
     * <p>The HTTP runs after commit, on a background thread, whenever a transaction is active.
     * The identity lookup that resolves the Keycloak id stays INLINE, inside the transaction: it
     * is one indexed read on a row this request already touched, and deferring it would mean
     * holding a detached id past the commit that may have rolled back.
     *
     * <p>Stated that way rather than as an unconditional rule, because the first branch below is
     * the exception: with no synchronization active (a direct call, a test) there is no commit to
     * wait for, so it goes inline. Production reaches this only through a {@code @Transactional}
     * {@code updateContext}, so the inline path is unreachable there, but a comment that promises
     * "never inside the transaction" is a promise the code does not keep.
     *
     * <p>Three blocking HTTP calls (an admin token, the user GET, the user PUT) against timeouts
     * of 5 s + 15 s would otherwise hold a database connection - and, on the transaction
     * synchronization alone, the request thread - for up to a minute, on an endpoint any signed-in
     * person can drive in a loop by alternating their language. After commit is also the only
     * truthful moment: a rollback further down this method would otherwise leave Keycloak holding
     * a language the database never stored.
     *
     * <p>The language may lag by the length of one HTTP call, which is the right trade: nothing
     * the person is about to do depends on it, and the pages it affects are ones they reach later
     * and from elsewhere.
     */
    private void syncKeycloakLocale(Long userId, String locale) {
        if (keycloakAdmin == null) return;
        String providerId;
        try {
            providerId = userRepository.findById(userId)
                    .map(User::getProviderId)
                    .filter(id -> !id.isBlank())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("[lifecycle] Keycloak locale sync skipped for user {}: {}", userId, e.getMessage());
            return;
        }
        if (providerId == null) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to wait for (a direct call, a test): do it now.
            keycloakAdmin.setUserLocale(providerId, locale);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // afterCommit still runs on the CALLER's thread, inside processCommit, so doing
                // the three HTTP calls here would free the database connection and keep holding
                // the request. Handed to the executor instead; a failure is already swallowed by
                // setUserLocale, and this catch covers a saturated pool.
                try {
                    keycloakSyncExecutor.execute(() -> keycloakAdmin.setUserLocale(providerId, locale));
                } catch (RuntimeException rejected) {
                    log.debug("[lifecycle] Keycloak locale sync not scheduled for user {}: {}",
                            userId, rejected.getMessage());
                }
            }
        });
    }

    private void captureAcquisition(Long userId, ProfileContextRequest.Acquisition a, Instant now) {
        if (a == null) return;
        String utmSource = LifecycleInputs.text(a.utmSource(), LifecycleInputs.MAX_UTM_LENGTH);
        String utmMedium = LifecycleInputs.text(a.utmMedium(), LifecycleInputs.MAX_UTM_LENGTH);
        String utmCampaign = LifecycleInputs.text(a.utmCampaign(), LifecycleInputs.MAX_UTM_LENGTH);
        String utmContent = LifecycleInputs.text(a.utmContent(), LifecycleInputs.MAX_UTM_LENGTH);
        String utmTerm = LifecycleInputs.text(a.utmTerm(), LifecycleInputs.MAX_UTM_LENGTH);
        String referrer = LifecycleInputs.text(a.referrer(), LifecycleInputs.MAX_URL_LENGTH);
        String landingPath = LifecycleInputs.text(a.landingPath(), LifecycleInputs.MAX_URL_LENGTH);
        Instant firstSeenAt = parseInstant(a.firstSeenAt(), now);
        if (utmSource == null && utmMedium == null && utmCampaign == null && utmContent == null
                && utmTerm == null && referrer == null && landingPath == null && firstSeenAt == null) {
            return;
        }
        acquisitionRepository.insertIfAbsent(userId, utmSource, utmMedium, utmCampaign, utmContent, utmTerm,
                referrer, landingPath, firstSeenAt, now);
    }

    /** ISO-8601 instant or offset date-time; null when unparseable, clamped to now when in the future. */
    static Instant parseInstant(String raw, Instant now) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        Instant parsed;
        try {
            parsed = Instant.parse(v);
        } catch (Exception e) {
            try {
                parsed = OffsetDateTime.parse(v).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
        return parsed.isAfter(now) ? now : parsed;
    }
}
