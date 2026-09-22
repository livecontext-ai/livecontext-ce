package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
import com.apimarketplace.auth.metrics.AuthMetrics;
import org.springframework.context.annotation.Lazy;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for user resolution from the gateway.
 * Provides user information needed for authentication.
 *
 * Intentionally NOT @Transactional at class level.
 * Each database operation runs in its own transaction, which allows
 * proper handling of concurrent user creation (race condition on first login).
 *
 * Race condition scenario: on first login, the frontend sends ~10 parallel requests.
 * All go through the gateway, all call resolveUser(), all find the user missing,
 * all try to INSERT. Only one INSERT succeeds; the others fail with a unique constraint
 * violation. By keeping resolveUser() non-transactional, each DB call has its own
 * transaction. When save() fails, only that micro-transaction rolls back.
 * We catch DataIntegrityViolationException and retry the lookup - the user now exists.
 */
@Service
public class UserResolutionService {

    private static final Logger log = LoggerFactory.getLogger(UserResolutionService.class);

    private final UserRepository userRepository;
    private final CreditService creditService;
    private final UsernameValidator usernameValidator;
    private final AgeValidator ageValidator;
    private final OnboardingService onboardingService;
    private final OrganizationService organizationService;
    private final SubscriptionRepository subscriptionRepository;
    private final BillingCustomerRepository billingCustomerRepository;
    private final PlanRepository planRepository;
    private final CreditAttributionService creditAttributionService;
    private final PlanStorageQuotaSyncer quotaSyncer;
    /** Owns the transactional, per-user-locked creation of the bootstrap FREE subscription. */
    private final FreeSubscriptionProvisioner freeSubscriptionProvisioner;
    // PR6 dual-write: pre-compute both billing-plan and active-org-tier
    // so the gateway / frontend can consume either field. PR7 cutover
    // flips which one is canonical for X-User-Plan / capabilities gating.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlanResolutionService planResolutionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrganizationSamlLoginService samlLoginService;

    /**
     * Optional micrometer metric - increments when billingPlan != activeOrgPlan
     * on a resolve. PR6.5 dashboard {@code lc_plan_resolution_divergence_total}
     * reads this. Wired only when a MeterRegistry bean is present (no-op in
     * unit tests).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /**
     * How stale last_login_at ("last seen") has to be before we rewrite it.
     *
     * <p>Purely a write-throttle: resolveUser runs on every gateway request carrying a
     * JWT, and rewriting a row on each of them would be one UPDATE per request for no
     * added information.
     *
     * <p>It is NOT the login rule and must never become it again. Reading "the throttle
     * let a write through" as "this person just signed in" is what published one login
     * per active principal per 10 minutes, for as long as anything kept making requests:
     * an open tab, a scheduled workflow, an API key. Prod, 2026-09-17: 96 login.success
     * in 12h for 3 accounts, smallest gap exactly 10.0 minutes, against 16 real LOGIN
     * events in Keycloak over 24h. {@code auth_time} decides logins now.
     *
     * <p>Note that resolveUser does NOT run on every gateway request: the gateway caches a
     * resolution per provider id for 5 minutes behind a per-id lock, so auth-service sees
     * at most one resolve per user per 5 minutes per gateway replica. The consequence for
     * the login rule is that a fresh sign-in is observed up to 5 minutes late, and not at
     * all until that user's next request. Delayed, never double counted.
     */
    private static final long LOGIN_DEDUP_MINUTES = 10L;

    /**
     * OIDC claim naming the instant the end user authenticated. Constant across every
     * refresh of one session, newer on a new one. That is the whole login rule.
     */
    private static final String AUTH_TIME_CLAIM = "auth_time";

    /** How far ahead of us a token's {@code auth_time} may be before we refuse to store it. */
    private static final java.time.Duration MAX_AUTH_TIME_SKEW = java.time.Duration.ofHours(1);

    /**
     * FREE subscription ids whose initial credit grant is known to exist. The grant is
     * idempotent (ledger source_id unique), but {@link #attributeCreditsIfEligible} runs on
     * EVERY gateway request of a FREE user, so without this memo each request re-read the
     * subscription and the ledger and logged "Attributing credits" for nothing (75 lines
     * for one user in two short sessions, 2026-09-15). Keyed by subscription id, not user
     * id: a new FREE row after a downgrade has a new id and gets its own grant. Per-JVM
     * and never invalidated on purpose: a memoized id only ever means "granted", which
     * cannot become false. Bounded so a long-lived pod cannot grow it without limit.
     */
    private final Set<Long> freeInitGrantDone = ConcurrentHashMap.newKeySet();
    static final int FREE_INIT_GRANT_MEMO_MAX = 50_000;

    /**
     * Provider-and-reason pairs already warned about. Bounded by the provider enum times the
     * two reasons, so it needs no eviction.
     */
    private final Set<String> missingAuthTimeWarned = ConcurrentHashMap.newKeySet();

    @Autowired(required = false)
    private AuthEventRecorder authEventRecorder;

    /**
     * The issuer {@code JwtTokenProvider} stamps on tokens we mint ourselves. Bound from the
     * same property, so the two cannot drift apart into a guard that recognises nothing.
     */
    @org.springframework.beans.factory.annotation.Value("${auth.jwt.issuer:livecontext}")
    private String embeddedJwtIssuer = "livecontext";

    /**
     * Self-injection so updateLastLoginAtomic() goes through the Spring proxy
     * and its @Transactional annotation is honored. Direct self-call would
     * bypass the proxy and the @Modifying query would fail with
     * "Executing an update/delete query" - no active transaction.
     * @Lazy avoids the chicken-and-egg circular dependency at startup.
     */
    @Autowired
    @Lazy
    private UserResolutionService self;

    public UserResolutionService(UserRepository userRepository,
                                  CreditService creditService,
                                  UsernameValidator usernameValidator,
                                  AgeValidator ageValidator,
                                  OnboardingService onboardingService,
                                  OrganizationService organizationService,
                                  SubscriptionRepository subscriptionRepository,
                                  BillingCustomerRepository billingCustomerRepository,
                                  PlanRepository planRepository,
                                  CreditAttributionService creditAttributionService,
                                  PlanStorageQuotaSyncer quotaSyncer,
                                  FreeSubscriptionProvisioner freeSubscriptionProvisioner) {
        this.freeSubscriptionProvisioner = freeSubscriptionProvisioner;
        this.userRepository = userRepository;
        this.creditService = creditService;
        this.usernameValidator = usernameValidator;
        this.ageValidator = ageValidator;
        this.onboardingService = onboardingService;
        this.organizationService = organizationService;
        this.subscriptionRepository = subscriptionRepository;
        this.billingCustomerRepository = billingCustomerRepository;
        this.planRepository = planRepository;
        this.creditAttributionService = creditAttributionService;
        this.quotaSyncer = quotaSyncer;
    }

    /**
     * Resolves a user by their providerId (Keycloak sub).
     *
     * NOT @Transactional: each DB operation has its own transaction, allowing
     * catch-and-retry on concurrent user creation (DataIntegrityViolationException).
     *
     * @param providerId The provider identifier (Keycloak sub UUID)
     * @param keycloakJwt The Keycloak JWT to extract real user information
     * @return UserResolutionResponse with all user information, or null if not found
     */
    public UserResolutionResponse resolveUser(String providerId, String keycloakJwt) {
        log.debug("Resolving user for providerId: {}", providerId);

        try {
            // 1. Find user by providerId
            Optional<User> userOpt = userRepository.findByProviderId(providerId);
            boolean isNewUser = false;

            if (userOpt.isEmpty()) {
                userOpt = findLocalUserByLegacyNumericSubject(providerId, keycloakJwt);
            }

            // 2. If not found, try to create (with race condition handling)
            if (userOpt.isEmpty()) {
                if (keycloakJwt != null) {
                    Optional<User> created = findOrCreateUser(providerId, keycloakJwt);
                    // findOrCreateUser may return an existing-by-email user (Keycloak
                    // recreation) - in that case it's NOT a new user. Detect via
                    // lastLoginAt == null on the freshly created row.
                    isNewUser = created.isPresent() && created.get().getLastLoginAt() == null;
                    userOpt = created;
                } else {
                    log.warn("User not found for providerId: {} and no JWT provided", providerId);
                    recordFailure(providerTagFromJwt(keycloakJwt), "no_jwt");
                    return null;
                }
            }

            if (userOpt.isEmpty()) {
                log.warn("Could not find or create user for providerId: {}", providerId);
                recordFailure(providerTagFromJwt(keycloakJwt), "user_not_found");
                return null;
            }

            User user = userOpt.get();
            log.debug("User found: {} (ID: {})", user.getEmail(), user.getId());

            // 3. Ensure user has a username
            ensureUsername(user);

            // 4. Ensure user has a free subscription
            ensureFreeSubscription(user);

            // 4b. Attribute credits if email is verified (idempotent)
            attributeCreditsSafely(user);
            ensureSamlMembershipForBrokeredLogin(user, keycloakJwt);

            // 5. "Last seen" bookkeeping. Throttled, and deliberately not a login signal.
            // Goes through the self-injected proxy so @Transactional applies, and through a
            // catch on THIS side of it (see updateLastLoginSafely).
            updateLastLoginSafely(user);

            // 5a. Did this token carry a NEWER authentication than anything we have seen
            // for this account? That, and only that, is a login. A refreshed token repeats
            // its session's auth_time and matches nothing; a token with no auth_time at all
            // (an API key resolve, a self-hosted embedded token) is a non-interactive
            // principal and never claims to be a sign-in.
            LocalDateTime authenticatedAt = authenticationInstant(keycloakJwt, user);
            boolean newAuthentication = authenticatedAt != null
                    && recordAuthenticationSafely(user, authenticatedAt);
            boolean realLogin = isNewUser || newAuthentication;

            // 5b. Record metrics + audit only on real login transitions.
            if (realLogin && authEventRecorder != null) {
                String tag = authEventRecorder.providerTag(user.getAuthProvider());
                if (isNewUser) {
                    authEventRecorder.recordSignupAndLogin(user.getId(), tag, false);
                } else {
                    authEventRecorder.recordLoginSuccess(user.getId(), tag);
                }
            }

            // 6. Build and return response
            return buildResolutionResponse(user, canonicalProviderId(user, providerId));

        } catch (CrossProviderAccountConflictException e) {
            // A login matched an existing account ONLY by email but with a
            // different sign-in method - deny (fail closed), never merge.
            log.warn("Resolution denied for providerId {} - {}", providerId, e.getMessage());
            recordFailure(providerTagFromJwt(keycloakJwt), "cross_provider_conflict");
            return null;
        } catch (Exception e) {
            log.error("Error resolving user for providerId: {}", providerId, e);
            recordFailure(providerTagFromJwt(keycloakJwt), "internal_error");
            return null;
        }
    }

    /**
     * Best-effort provider extraction when we don't have a User yet (failure path).
     * Bounded output: never returns null or free-form strings.
     */
    private String providerTagFromJwt(String jwt) {
        if (jwt == null || jwt.isBlank()) return "keycloak";
        try {
            String idp = SignedJWT.parse(jwt).getJWTClaimsSet().getStringClaim("identity_provider");
            if ("google".equals(idp)) return "google";
            if ("github".equals(idp)) return "github";
        } catch (Exception ignored) {}
        return "keycloak";
    }

    private Optional<User> findLocalUserByLegacyNumericSubject(String providerId, String jwt) {
        if (!isLocalEmbeddedToken(jwt)) {
            return Optional.empty();
        }
        try {
            Long userId = Long.parseLong(providerId);
            return userRepository.findById(userId)
                    .filter(user -> user.getAuthProvider() == AuthProvider.LOCAL);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Is this a token {@code JwtTokenProvider} minted, rather than one an identity provider
     * issued?
     *
     * <p>Keyed on the {@code token_type} claim AND our own issuer, both of which
     * {@code JwtTokenProvider} stamps on every token it signs. Two conditions rather than
     * one because this is the only branch in the design with NO detector: a token
     * misclassified as ours short-circuits with no login, no counter and no log line, so a
     * false positive here is invisible by construction. {@code token_type} alone would
     * classify any future token that happens to carry that unnamespaced claim; pinning the
     * issuer as well means a token has to claim to be ours before we treat it as ours.
     *
     * <p>Deliberately NOT keyed on {@code provider == "local"}: that claim carries the
     * SIGN-IN METHOD, so a self-hosted person who used Google carries
     * {@code provider: "google"} on a token we minted ourselves. Testing for "local" would
     * let that token through as if an identity provider had sent it, and the install would
     * then be told that "keycloak" had stopped sending auth_time, on a deployment running no
     * Keycloak at all.
     */
    private boolean isOwnEmbeddedToken(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return false;
        }
        try {
            var claims = SignedJWT.parse(jwt).getJWTClaimsSet();
            return claims.getStringClaim("token_type") != null
                    && embeddedJwtIssuer.equals(claims.getIssuer());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLocalEmbeddedToken(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return false;
        }
        try {
            String provider = SignedJWT.parse(jwt).getJWTClaimsSet().getStringClaim("provider");
            return AuthProvider.LOCAL.getProvider().equals(provider);
        } catch (Exception e) {
            return false;
        }
    }

    private String canonicalProviderId(User user, String requestedProviderId) {
        String storedProviderId = user.getProviderId();
        if (storedProviderId != null && !storedProviderId.isBlank()) {
            return storedProviderId;
        }
        return requestedProviderId;
    }

    private void recordFailure(String providerTag, String reason) {
        if (authEventRecorder != null) {
            authEventRecorder.recordLoginFailure(providerTag, reason);
        }
    }

    private void ensureSamlMembershipForBrokeredLogin(User user, String jwt) {
        if (samlLoginService == null || jwt == null || jwt.isBlank()) {
            return;
        }
        String identityProvider = null;
        try {
            identityProvider = SignedJWT.parse(jwt).getJWTClaimsSet().getStringClaim("identity_provider");
            samlLoginService.ensureMembershipForIdentityProvider(user, identityProvider);
        } catch (SamlMembershipException e) {
            throw e;
        } catch (Exception e) {
            if (isOrganizationSamlAlias(identityProvider)) {
                throw new SamlMembershipException("Could not join SAML workspace", e);
            }
            log.warn("Could not ensure SAML organization membership for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private boolean isOrganizationSamlAlias(String alias) {
        return alias != null && alias.matches("^org-[0-9a-fA-F]{32}-saml$");
    }

    /**
     * Checks if a user can make a request (quotas and status).
     */
    public boolean canUserMakeRequest(String providerId) {
        UserResolutionResponse userInfo = resolveUser(providerId, null);
        return userInfo != null && userInfo.canMakeRequest();
    }

    /**
     * Updates the userVersion for cache coherence.
     * Transactional: read + increment + write must be atomic.
     */
    @Transactional
    public Long updateUserVersion(String providerId) {
        log.info("Updating userVersion for providerId: {}", providerId);

        try {
            Optional<User> userOpt = userRepository.findByProviderId(providerId);
            if (userOpt.isEmpty()) {
                log.warn("User not found for userVersion update: {}", providerId);
                return null;
            }

            User user = userOpt.get();
            Long newVersion = user.getUserVersion() + 1;
            user.setUserVersion(newVersion);
            userRepository.save(user);

            log.info("UserVersion updated: {} -> {}", newVersion - 1, newVersion);
            return newVersion;

        } catch (Exception e) {
            log.error("Error updating userVersion for providerId: {}", providerId, e);
            return null;
        }
    }

    // ========== Private methods ==========

    /**
     * Attempts to create a user, handling race conditions gracefully.
     * If creation fails due to a unique constraint violation (another concurrent
     * request already created the same user), catches the exception and retries
     * the lookup - the user now exists in the database.
     */
    private Optional<User> findOrCreateUser(String providerId, String keycloakJwt) {
        try {
            User newUser = createUserFromKeycloakJwt(providerId, keycloakJwt);
            return Optional.of(newUser);
        } catch (DataIntegrityViolationException e) {
            // Race condition: another concurrent request already created this user.
            // This is expected on first login when multiple requests arrive simultaneously.
            log.info("Concurrent user creation detected for providerId: {}, retrying lookup", providerId);

            // Retry by providerId (most common case: same providerId, duplicate INSERT)
            Optional<User> retryByProvider = userRepository.findByProviderId(providerId);
            if (retryByProvider.isPresent()) {
                return retryByProvider;
            }

            // Fallback: find by email (handles Keycloak user recreation with new providerId)
            return findByEmailFromJwt(keycloakJwt);
        }
    }

    /**
     * Extracts email from JWT and looks up user by email.
     * Used as fallback when providerId lookup fails after a race condition.
     *
     * Provider-aware: a race-retry must NOT return an account owned by a different
     * sign-in method just because the email matches - that would be a cross-provider
     * takeover through the concurrency path.
     */
    private Optional<User> findByEmailFromJwt(String keycloakJwt) {
        try {
            SignedJWT jwt = SignedJWT.parse(keycloakJwt);
            var claims = jwt.getJWTClaimsSet();
            String email = claims.getStringClaim("email");
            if (email != null && !email.isEmpty()) {
                AuthProvider incomingProvider = resolveAuthProviderFromIdentityProvider(
                        claims.getStringClaim("identity_provider"));
                Optional<User> byEmail = userRepository.findByEmail(email);
                if (byEmail.isPresent()) {
                    AuthProvider existingProvider = byEmail.get().getAuthProvider() != null
                            ? byEmail.get().getAuthProvider() : AuthProvider.KEYCLOAK;
                    if (existingProvider != incomingProvider) {
                        log.warn("SECURITY: race-retry email lookup for {} matched a {} account but the login is {} "
                                + "- not returning it.", email, existingProvider, incomingProvider);
                        return Optional.empty();
                    }
                }
                return byEmail;
            }
        } catch (Exception e) {
            log.warn("Failed to extract email from JWT for retry lookup", e);
        }
        return Optional.empty();
    }

    /**
     * Maps the Keycloak {@code identity_provider} claim to our AuthProvider.
     * Absent claim (direct Keycloak password login) → KEYCLOAK.
     */
    private AuthProvider resolveAuthProviderFromIdentityProvider(String identityProvider) {
        if ("google".equals(identityProvider)) {
            return AuthProvider.GOOGLE;
        }
        if ("github".equals(identityProvider)) {
            return AuthProvider.GITHUB;
        }
        return AuthProvider.KEYCLOAK;
    }

    /**
     * Ensures the user has a username. Generates one if missing
     * (e.g. users created via JWT JIT path without username).
     */
    private void ensureUsername(User user) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            try {
                String defaultUsername = usernameValidator.generateUniqueUsername(
                        usernameValidator.buildUsernameFromProviderId(user.getProviderId())
                );
                user.setUsername(defaultUsername);
                userRepository.save(user);
                log.info("Default username assigned: {}", defaultUsername);
            } catch (Exception e) {
                log.warn("Failed to assign default username for user {}: {}", user.getId(), e.getMessage());
            }
        }
    }

    /**
     * Ensures the user has a FREE subscription.
     * Creates BillingCustomer + Subscription if missing.
     * Credits are attributed separately in attributeCreditsIfEligible() (requires email verification).
     * Idempotent: skips if an active subscription already exists.
     */
    private void ensureFreeSubscription(User user) {
        // Delegated to a separate bean ON PURPOSE. The check-and-insert has to be one
        // transaction holding a per-user lock; inlined here it could never be, because
        // resolveUser calls this from inside the same bean (self-invocation bypasses the
        // proxy) and the method was private (which the proxy cannot advise at all). The two
        // halves therefore ran unsynchronised, and concurrent first-login requests each
        // created a subscription. See FreeSubscriptionProvisioner for the full story.
        //
        // The catch lives HERE, on this side of the provisioner's proxy, and that placement is
        // the whole point. Inside the transaction it would be useless: an
        // UnexpectedRollbackException raised at the commit is thrown by the proxy, after the
        // method body has returned, so no catch within the bean can see it. Out here it is
        // reachable, and the only sane answer to it is this one. A user who exists must resolve;
        // a missing FREE subscription is bookkeeping, it costs nobody their session and the next
        // request repairs it. Letting it bubble into resolveUser's generic handler is what turned
        // one duplicate-key into a login failure for roughly four of every ten new accounts.
        try {
            freeSubscriptionProvisioner.provisionIfMissing(user);
        } catch (Exception e) {
            log.warn("Could not ensure free subscription for userId={}, continuing: {}",
                    user.getId(), e.toString());
        }
    }

    /**
     * {@link #attributeCreditsIfEligible} with the failure handled OUTSIDE its transaction.
     *
     * <p>Same reasoning as {@link #ensureFreeSubscription}: credit attribution is idempotent
     * bookkeeping replayed on every resolve, so a bad run is repaired by the next request and
     * must never cost the caller their login.
     */
    private void attributeCreditsSafely(User user) {
        try {
            attributeCreditsIfEligible(user);
        } catch (Exception e) {
            log.warn("Could not attribute credits for userId={}, continuing: {}",
                    user.getId(), e.toString());
        }
    }

    /**
     * Attributes FREE plan credits if the user's email is verified and credits haven't been granted yet.
     * Called on every resolveUser() - idempotent via CreditAttributionService sourceId checks.
     * Also called immediately after email verification for instant credit grant.
     *
     * IMPORTANT: Only applies to the FREE plan. Two different subscription shapes must be
     * excluded, and for a while only the first one was:
     * <ul>
     *   <li>Paid (Stripe) subscriptions receive their credits via webhook handlers
     *       ({@code customer.subscription.created} -> {@code attributeOnSubscription}); granting
     *       here too would double-count because the sourceIds differ.</li>
     *   <li>Admin-granted comp tiers keep {@code provider='internal'}, so a provider-only guard
     *       let them through. The moment the plan flips FREE -> STARTER/PRO/TEAM,
     *       {@code grantsBasePack} turns true and the never-used {@code pack_sub_N_init} key
     *       fires, handing out the 5K base pack a SECOND time on top of the one
     *       {@code AdminPlanService.assignPlan} already granted. Production shows exactly that:
     *       {@code pack_sub_15_init} landed 5 seconds after the comp grant it duplicated, and
     *       the same happened on subs 14, 20 and 21 (20,000 credits over-granted in total).</li>
     * </ul>
     * A comp tier's credits belong to {@code assignPlan} and its monthly renewal, never to this
     * bootstrap path.
     */
    @Transactional
    public void attributeCreditsIfEligible(User user) {
        if (!user.isEmailVerified()) {
            return;
        }

        // No catch here, deliberately, though the two callers reach this method very differently
        // and only one of them made the old catch a lie. From EmailVerificationController the
        // call goes through the proxy, so @Transactional applies: a swallowed failure would not
        // stay swallowed there, the transaction is flagged rollback-only and the commit throws an
        // UnexpectedRollbackException from outside this body, where no catch of its own can
        // intercept it. From resolveUser it is a plain self-call, so no proxy and no transaction,
        // and moving the catch out was a no-op for behaviour. It is still the right place for it:
        // one handler, on the side of the proxy where a handler can work, for both callers.
        // attributeCreditsSafely holds it here, the controller holds its own there.
        Optional<Subscription> subOpt = subscriptionRepository.findActiveByUserId(user.getId());
        if (subOpt.isEmpty()) {
            return;
        }

        Subscription subscription = subOpt.get();
        Plan plan = subscription.getPlan();
        if (plan == null) {
            return;
        }

        // Only grant credits for FREE (internal) subscriptions.
        // Paid plans get credits via Stripe webhooks - granting here would double-count.
        if (!"internal".equalsIgnoreCase(subscription.getProvider())) {
            return;
        }
        // ... and only while the plan is still FREE. An admin comp tier is also
        // provider='internal', and its credits come from assignPlan; letting it reach
        // attributeOnSubscription releases the unused pack_sub_N_init key for a duplicate
        // 5K grant. See the javadoc for the four production accounts this hit.
        if (!"FREE".equalsIgnoreCase(plan.getCode())) {
            return;
        }

        // Already granted for this subscription (see freeInitGrantDone): nothing to read,
        // nothing to log. The memo is filled only after a successful attribution call, so
        // a failed grant (DB hiccup) is retried on the next request as before.
        if (freeInitGrantDone.contains(subscription.getId())) {
            return;
        }

        creditAttributionService.attributeOnSubscription(user.getId(), subscription, 0);
        rememberFreeInitGrant(subscription.getId());
        log.debug("Credits attribution check done for userId={} (idempotent)", user.getId());
    }

    private void rememberFreeInitGrant(Long subscriptionId) {
        if (subscriptionId == null) {
            return;
        }
        if (freeInitGrantDone.size() >= FREE_INIT_GRANT_MEMO_MAX) {
            // Crude bound, same idiom as NonceUtil's cache: a cleared memo only costs one
            // extra idempotent ledger check per subscription, never a wrong grant.
            freeInitGrantDone.clear();
        }
        freeInitGrantDone.add(subscriptionId);
    }

    /**
     * The instant this token says the person authenticated, or {@code null} when the
     * token does not say.
     *
     * <p>Reads the OIDC {@code auth_time} claim. Three outcomes, and the difference
     * between the last two is the point:
     * <ul>
     *   <li><b>Claim present</b> - returned. The caller compares it against what we
     *       already stored for this account.</li>
     *   <li><b>No token at all</b> - {@code null}, silently. This is the API-key resolve
     *       ({@code ApiKeyService} passes a null JWT) and the self-hosted embedded token,
     *       both non-interactive by construction. Nothing is wrong and nothing is
     *       reported; they are simply not sign-ins.</li>
     *   <li><b>Token present but carrying no {@code auth_time}</b> - {@code null}, and
     *       LOUD: a counter plus a one-shot warning. This is the failure mode that would
     *       otherwise sink the whole feature without a trace, because "we counted no
     *       logins" and "nobody logged in" look identical on a dashboard. If an identity
     *       provider ever stops sending the claim, the counter says so on the first
     *       request instead of the login graph quietly flatlining.</li>
     * </ul>
     */
    LocalDateTime authenticationInstant(String jwt, User user) {
        if (jwt == null || jwt.isBlank() || isOwnEmbeddedToken(jwt)) {
            // Expected shapes, never reported. A null token is the API-key resolve
            // (ApiKeyService passes one on purpose). An embedded token is minted by our own
            // JwtTokenProvider, which has no auth_time to give and no session to describe;
            // its sign-in is recorded where it happens, in PasswordAuthService and
            // OAuthUserProcessor. Without the second test they would be reported as a broken
            // identity provider, and mislabelled too: providerTagFromJwt can only ever answer
            // keycloak, google or github, so a self-hosted install would page about
            // "keycloak" it does not run.
            return null;
        }
        try {
            Date authTime = SignedJWT.parse(jwt).getJWTClaimsSet().getDateClaim(AUTH_TIME_CLAIM);
            if (authTime != null) {
                Instant authenticatedAt = Instant.ofEpochMilli(authTime.getTime());
                // A value from the future would be written once and then never beaten, so the
                // account's logins would go uncounted until the wall clock caught up, with no
                // way back short of editing the row. The very monotonicity that makes two
                // live sessions safe is what makes a skewed value unrecoverable, so it is
                // refused at the door rather than stored. An hour is far more than any real
                // clock drift and far less than the skew a misconfigured provider produces.
                if (authenticatedAt.isAfter(Instant.now().plus(MAX_AUTH_TIME_SKEW))) {
                    log.warn("Ignoring auth_time {} for user {}: more than {} in the future",
                            authenticatedAt, user != null ? user.getId() : null, MAX_AUTH_TIME_SKEW);
                    // Its own reason, because the page has to send an operator to the clock
                    // and not to the token configuration. Reporting a future-dated claim as
                    // "carries no auth_time" would be a correct alert with a wrong diagnosis.
                    reportUnusableAuthTime(jwt, AuthMetrics.AUTH_TIME_FUTURE);
                    return null;
                }
                // UTC, not the JVM zone. The column is TIMESTAMPTZ and every value in it is
                // compared only against other values from this same conversion, so the zone
                // has to be FIXED rather than merely consistent: with a local zone, the hour
                // that repeats at the end of DST maps two different instants to one
                // LocalDateTime, and a genuinely newer authentication inside it would not
                // compare greater and would be dropped in silence, once a year.
                return LocalDateTime.ofInstant(authenticatedAt, ZoneOffset.UTC);
            }
        } catch (Exception e) {
            // An unparseable token is not our problem here: the gateway already validated
            // the signature to get this far. Treat it like a token with no claim so the
            // gap is still visible rather than swallowed.
            log.debug("Could not read {} for user {}: {}", AUTH_TIME_CLAIM,
                    user != null ? user.getId() : null, e.getMessage());
        }
        reportUnusableAuthTime(jwt, AuthMetrics.AUTH_TIME_ABSENT);
        return null;
    }

    /**
     * Counts, and warns once per provider per JVM, that a real token arrived without
     * {@code auth_time}. Once per provider rather than once per request: the condition is
     * a property of the identity provider's configuration, so repeating it on every
     * request would bury it in its own volume. The counter carries the volume.
     */
    private void reportUnusableAuthTime(String jwt, String reason) {
        String providerTag = providerTagFromJwt(jwt);
        if (authEventRecorder != null) {
            authEventRecorder.recordAuthTimeClaimMissing(providerTag, reason);
        }
        if (missingAuthTimeWarned.add(providerTag + ":" + reason)) {
            log.warn("Tokens from provider '{}' cannot date their authentication ({}={}) - logins "
                            + "from this provider can no longer be counted or audited.",
                    providerTag, AUTH_TIME_CLAIM, reason);
        }
    }

    /**
     * Atomic conditional advance of {@code lastAuthenticatedAt}.
     *
     * <p>Returns {@code true} exactly once per authentication event, for the same reason
     * {@code updateLastLoginAtomic} is race-free: the comparison and the write are one SQL
     * statement, so when several resolves for the same user race (two gateway replicas with
     * cold caches, or a cache entry dropped mid-page-load) only one can observe the
     * transition.
     *
     * <p><b>Throws rather than swallowing, on purpose.</b> Catching here would not contain
     * the failure: a DataAccessException from the {@code @Modifying} query marks the
     * surrounding transaction rollback-only, and the PROXY then throws
     * UnexpectedRollbackException at commit, which is after this method has returned. The
     * only place that can absorb it is the call site, outside the boundary, which is where
     * {@link #resolveUser} catches it. Swallowing it here would look safe and leave the
     * caller taking the rollback exception anyway, which would fail the whole resolution
     * and sign the person out because a counter could not be written.
     */
    @Transactional
    public boolean recordAuthenticationAtomic(User user, LocalDateTime authenticatedAt) {
        int rows = userRepository.recordAuthenticationIfNewer(user.getId(), authenticatedAt);
        if (rows > 0) {
            user.setLastAuthenticatedAt(authenticatedAt); // keep in-memory entity consistent
            return true;
        }
        return false;
    }

    /**
     * {@link #recordAuthenticationAtomic} with the transaction boundary crossed first, so a
     * storage fault costs a login count and never a sign-in. Fails CLOSED: an account that
     * could not be written is reported as "not a new login", because inventing sign-ins on
     * the audit trail a security review reads is the worse of the two errors.
     */
    private boolean recordAuthenticationSafely(User user, LocalDateTime authenticatedAt) {
        try {
            return self.recordAuthenticationAtomic(user, authenticatedAt);
        } catch (Exception e) {
            log.warn("Failed to record authentication instant for user {}: {}",
                    user.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * {@link #updateLastLoginAtomic} with the same boundary crossed. "Last seen" is
     * bookkeeping: it must never be the reason a resolution fails.
     */
    private void updateLastLoginSafely(User user) {
        try {
            self.updateLastLoginAtomic(user);
        } catch (Exception e) {
            log.warn("Failed to update lastLoginAt for user {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Atomic conditional update of {@code lastLoginAt}, the "last seen" marker.
     *
     * <p>Moves the timestamp iff it is null or older than {@link #LOGIN_DEDUP_MINUTES},
     * which is a WRITE THROTTLE and nothing more. The returned boolean is deliberately
     * ignored by {@code resolveUser}: it used to be read as the canonical "real new login"
     * flag, and since the condition it answers is "has enough wall-clock passed", that
     * published one login per active principal per ten minutes for anything that kept
     * making requests. {@link #recordAuthenticationAtomic} is the login signal.
     *
     * <p>Single SQL statement, so concurrent resolves cannot both move it.
     */
    @Transactional
    public boolean updateLastLoginAtomic(User user) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime threshold = now.minusMinutes(LOGIN_DEDUP_MINUTES);
            int rows = userRepository.updateLastLoginIfStale(user.getId(), now, threshold);
            if (rows > 0) {
                user.setLastLoginAt(now); // keep in-memory entity consistent
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to update lastLoginAt for user {}: {}", user.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Builds the full UserResolutionResponse with plan, roles, onboarding status, and
     * the credit-wallet balance. The legacy cycle-counter quotas (tokens / requests /
     * storage) were retired - CreditService is the single source of truth for billing.
     */
    private UserResolutionResponse buildResolutionResponse(User user, String providerId) {
        String plan = subscriptionRepository.findActiveByUserId(user.getId())
                .map(sub -> sub.getPlan() != null ? sub.getPlan().getCode() : "FREE")
                .orElse("FREE");

        Set<String> roles = user.getRoles();

        boolean needsOnboarding = onboardingService.needsOnboarding(providerId);
        boolean profileIncomplete = needsOnboarding;
        boolean firstLogin = needsOnboarding;

        UserResolutionResponse response = new UserResolutionResponse(
                user.getId(),
                user.getProviderId(),
                user.getEmail(),
                plan,
                roles,
                user.getUserVersion(),
                user.isEnabled(),
                firstLogin,
                profileIncomplete,
                needsOnboarding
        );

        // Get remaining credits
        try {
            response.setRemainingCredits(creditService.getBalance(user.getId()));
        } catch (Exception e) {
            log.warn("Could not get credit balance for user {}: {}", user.getId(), e.getMessage());
        }

        // Get default organization ID and role
        try {
            var defaultMembership = organizationService.getDefaultMembership(user.getId());
            if (defaultMembership.isPresent()) {
                var membership = defaultMembership.get();
                response.setDefaultOrganizationId(membership.getOrganization().getId().toString());
                response.setDefaultOrganizationRole(membership.getRole().name());
            }
        } catch (Exception e) {
            log.warn("Could not get default organization for user {}: {}", user.getId(), e.getMessage());
        }

        // PR0.5: pack the full membership list so the gateway can validate
        // active-org claims (`X-Active-Organization-ID` header) sent by the
        // frontend without doing an extra HTTP round-trip. Fail-soft: empty
        // list means "no active-org switching available", which falls back
        // to the default org context in AuthenticationFilter.
        try {
            response.setMemberships(organizationService.listUserMembershipsDto(user.getId()));
        } catch (Exception e) {
            log.warn("Could not list memberships for user {}: {}", user.getId(), e.getMessage());
        }

        // PR6 dual-write: populate billingPlan + activeOrgPlan in parallel.
        // PR7 cutover (now permanent): override the legacy `plan` field with
        // the active-workspace tier so the gateway's X-User-Plan header
        // reflects workspace context (Q1=b).
        if (planResolutionService != null) {
            try {
                response.setBillingPlan(planResolutionService.resolveBillingPlan(user.getId()));
                response.setActiveOrgPlan(planResolutionService.resolveActiveOrgTier(user.getId()));

                // Observability metric - tracks how often the billing plan
                // differs from the active-workspace tier. Useful to watch
                // post-cutover for upgrade/downgrade patterns.
                if (meterRegistry != null
                        && response.getBillingPlan() != null
                        && response.getActiveOrgPlan() != null
                        && !response.getBillingPlan().equals(response.getActiveOrgPlan())) {
                    meterRegistry.counter("lc_plan_resolution_divergence_total",
                            "billing", response.getBillingPlan(),
                            "active", response.getActiveOrgPlan()).increment();
                }

                if (response.getActiveOrgPlan() != null) {
                    response.setPlan(response.getActiveOrgPlan());
                }
            } catch (Exception e) {
                log.warn("Could not resolve dual-write plan fields for user {}: {}", user.getId(), e.getMessage());
            }
        }

        return response;
    }

    /**
     * Creates a user with real values extracted from Keycloak JWT.
     *
     * IMPORTANT: DataIntegrityViolationException is NOT caught here - it propagates
     * to findOrCreateUser() which handles the race condition retry logic.
     *
     * @param providerId The provider identifier (Keycloak sub UUID)
     * @param keycloakJwt The Keycloak JWT to extract information
     * @return The created user
     * @throws DataIntegrityViolationException if a concurrent request already created the user
     */
    private User createUserFromKeycloakJwt(String providerId, String keycloakJwt) {
        log.info("Creating user from Keycloak JWT for providerId: {}", providerId);

        try {
            SignedJWT jwt = SignedJWT.parse(keycloakJwt);
            var claims = jwt.getJWTClaimsSet();

            String email = claims.getStringClaim("email");
            AuthProvider incomingProvider = resolveAuthProviderFromIdentityProvider(
                    claims.getStringClaim("identity_provider"));

            // Re-point an existing account onto a NEW Keycloak sub ONLY when the
            // SAME sign-in method is being recreated (legitimate Keycloak user
            // recreation: same email + same provider, fresh internal id). Matching
            // on email ALONE would let a different provider silently take over an
            // existing account - the reported cross-provider account merge. This is
            // the app-layer half of the defense; the Keycloak first-broker-login
            // block is the other half.
            if (email != null && !email.isEmpty()) {
                Optional<User> existingByEmail = userRepository.findByEmail(email);
                if (existingByEmail.isPresent()) {
                    User existing = existingByEmail.get();
                    // Assumes the stored authProvider reflects the brokering IdP
                    // (set from identity_provider at creation). A legacy row missing
                    // it is treated as KEYCLOAK (the original password default).
                    AuthProvider existingProvider = existing.getAuthProvider() != null
                            ? existing.getAuthProvider() : AuthProvider.KEYCLOAK;
                    if (existingProvider == incomingProvider) {
                        log.info("Keycloak user recreation for email {} (provider {}): re-pointing providerId {} -> {}",
                                email, incomingProvider, existing.getProviderId(), providerId);
                        existing.setProviderId(providerId);
                        return userRepository.save(existing);
                    }
                    log.warn("SECURITY: refusing cross-provider account link for email {} - existing account uses {}, "
                            + "incoming login is {}. Not merging.", email, existingProvider, incomingProvider);
                    throw new CrossProviderAccountConflictException(existingProvider, incomingProvider);
                }
            }

            User newUser = new User();
            newUser.setProviderId(providerId);

            if (email != null && !email.isEmpty()) {
                newUser.setEmail(email);
            }

            // Generate default username
            String defaultUsername = usernameValidator.generateUniqueUsername(
                    usernameValidator.buildUsernameFromProviderId(providerId)
            );
            newUser.setUsername(defaultUsername);

            // Extract name from JWT (OIDC standard claims)
            String givenName = claims.getStringClaim("given_name");
            String familyName = claims.getStringClaim("family_name");
            if (givenName != null && !givenName.isEmpty()) {
                newUser.setFirstName(givenName);
            }
            if (familyName != null && !familyName.isEmpty()) {
                newUser.setLastName(familyName);
            }
            // Fallback to name claim
            if (givenName == null && familyName == null) {
                String name = claims.getStringClaim("name");
                if (name != null && !name.isEmpty()) {
                    String[] nameParts = name.split(" ");
                    if (nameParts.length >= 2) {
                        newUser.setFirstName(nameParts[0]);
                        newUser.setLastName(nameParts[1]);
                    } else {
                        newUser.setFirstName(name);
                    }
                }
            }

            // Avatar URL
            String picture = claims.getStringClaim("picture");
            if (picture != null && !picture.isEmpty()) {
                newUser.setAvatarUrl(picture);
            }

            // Email verified
            Boolean emailVerified = claims.getBooleanClaim("email_verified");
            newUser.setEmailVerified(emailVerified != null ? emailVerified : false);

            // Auth provider - resolved once above from the identity_provider claim.
            newUser.setAuthProvider(incomingProvider);

            newUser.setEnabled(true);
            newUser.setRoles(new java.util.HashSet<>(Set.of("USER")));
            newUser.setUserVersion(1L);

            User savedUser = userRepository.save(newUser);
            log.info("User created: {} (ID: {}) - Email: {}",
                    savedUser.getUsername(), savedUser.getId(), savedUser.getEmail());

            return savedUser;

        } catch (DataIntegrityViolationException e) {
            // Let DataIntegrityViolationException propagate for race condition handling
            // in findOrCreateUser() - do NOT wrap it in RuntimeException
            throw e;
        } catch (CrossProviderAccountConflictException e) {
            // Security denial - must reach resolveUser untouched, NOT be wrapped
            // as a generic internal error.
            throw e;
        } catch (Exception e) {
            log.error("Error creating user for providerId: {}", providerId, e);
            throw new RuntimeException("Unable to create user with Keycloak data", e);
        }
    }

}
