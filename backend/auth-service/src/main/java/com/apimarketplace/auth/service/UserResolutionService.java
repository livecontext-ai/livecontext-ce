package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
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

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

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
     * Threshold below which two consecutive resolveUser() calls are considered the
     * same active session (and we do NOT count it as a new login). Above this gap
     * we increment auth_login_total and emit an audit LOGIN_SUCCESS.
     *
     * The dedup is enforced via an atomic SQL UPDATE (UserRepository
     * .updateLastLoginIfStale) that returns rowsUpdated as the canonical
     * "is this a real login" flag - safe under the ~10 parallel resolveUser()
     * calls the frontend issues per page load.
     */
    private static final long LOGIN_DEDUP_MINUTES = 10L;

    @Autowired(required = false)
    private AuthEventRecorder authEventRecorder;

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
                                  PlanStorageQuotaSyncer quotaSyncer) {
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
            attributeCreditsIfEligible(user);
            ensureSamlMembershipForBrokeredLogin(user, keycloakJwt);

            // 5. Atomic conditional last-login update - see updateLastLoginAtomic.
            // Goes through self-injected proxy so @Transactional applies.
            boolean realLogin = isNewUser || self.updateLastLoginAtomic(user);

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
    @Transactional
    private void ensureFreeSubscription(User user) {
        try {
            Optional<Subscription> existingSub = subscriptionRepository.findActiveByUserId(user.getId());
            if (existingSub.isPresent()) {
                return;
            }

            // Create BillingCustomer if needed
            BillingCustomer billingCustomer = billingCustomerRepository.findByUserId(user.getId())
                    .orElseGet(() -> {
                        BillingCustomer bc = new BillingCustomer(user, "internal");
                        return billingCustomerRepository.save(bc);
                    });

            // Get FREE plan
            Optional<Plan> freePlanOpt = planRepository.findByCode("FREE");
            if (freePlanOpt.isEmpty()) {
                log.error("FREE plan not found in database");
                return;
            }
            Plan freePlan = freePlanOpt.get();

            // Create subscription (credits attributed later when email is verified)
            Subscription sub = new Subscription();
            sub.setBillingCustomer(billingCustomer);
            sub.setPlan(freePlan);
            sub.setCadence("monthly");
            sub.setStatus("active");
            sub.setProvider("internal");
            sub.setCurrentPeriodStart(LocalDateTime.now());
            sub.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
            sub.setCancelAtPeriodEnd(false);
            Subscription saved = subscriptionRepository.save(sub);

            log.info("FREE subscription created for userId={} (subId={}). Credits pending email verification.", user.getId(), saved.getId());

            // Email-verified signup path - sync storage quota to FREE allowance.
            quotaSyncer.syncAfterCommit(user.getId(), freePlan);
        } catch (Exception e) {
            log.warn("Could not ensure free subscription for userId={}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Attributes FREE plan credits if the user's email is verified and credits haven't been granted yet.
     * Called on every resolveUser() - idempotent via CreditAttributionService sourceId checks.
     * Also called immediately after email verification for instant credit grant.
     *
     * IMPORTANT: Only applies to FREE plan (provider="internal") subscriptions.
     * Paid plans (Stripe) receive their credits via webhook handlers
     * (customer.subscription.created → CreditAttributionService.attributeOnSubscription).
     * Applying this to paid plans would cause double credit grants because the sourceIds differ
     * ("free_provision_{subId}" vs "plan_{stripeEventId}").
     */
    @Transactional
    public void attributeCreditsIfEligible(User user) {
        if (!user.isEmailVerified()) {
            return;
        }

        try {
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

            creditAttributionService.attributeOnSubscription(user.getId(), subscription, 0);
            log.debug("Credits attribution check done for userId={} (idempotent)", user.getId());
        } catch (Exception e) {
            log.warn("Could not attribute credits for userId={}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Atomic conditional update of {@code lastLoginAt}.
     *
     * Returns {@code true} iff this call actually moved the timestamp - meaning
     * either the user had never logged in OR their previous login was more than
     * {@link #LOGIN_DEDUP_MINUTES} ago. The boolean is the canonical "real new
     * login" flag and is safe under concurrent resolveUser() calls (the frontend
     * fires ~10 parallel requests per page load - only one will see {@code true}).
     *
     * Race-free because the WHERE clause and UPDATE happen in a single SQL
     * statement; without this, two concurrent reads would both see the old
     * timestamp and both fire a login event.
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
