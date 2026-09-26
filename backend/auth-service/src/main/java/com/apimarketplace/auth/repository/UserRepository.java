package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByProviderId(String providerId);

    /**
     * Batch lookup by Keycloak/local provider ids. Sibling to the inherited
     * {@code findAllById} (numeric ids). Used by
     * {@code OnboardingService.resolveUserSummaries} to derive a fallback
     * display name from the {@code users} row (full name / username / email)
     * for ids that have no {@code user_onboarding} display name - notably CE
     * embedded users, who register without ever creating a UserOnboarding row.
     */
    List<User> findByProviderIdIn(java.util.Collection<String> providerIds);


    Optional<User> findByEmailAndAuthProvider(String email, String authProvider);

    @Query("SELECT u FROM User u WHERE u.email = :email AND u.authProvider = :provider")
    Optional<User> findByEmailAndProvider(@Param("email") String email, @Param("provider") AuthProvider provider);

    /**
     * The user's id when the account exists, holding a {@code FOR KEY SHARE} lock on its row until
     * the caller's transaction ends; empty otherwise.
     *
     * <p>For a write that references {@code auth.users} by a header-supplied id: checking with
     * {@code existsById} and then inserting leaves a window where a concurrent account deletion
     * commits in between and the insert fails on the foreign key (a 500). Taken inside the same
     * transaction as the insert, this lock makes the deletion wait for the insert instead.
     */
    @Query(value = "SELECT u.id FROM auth.users u WHERE u.id = :id FOR KEY SHARE", nativeQuery = true)
    Optional<Long> lockExistingForKeyShare(@Param("id") Long id);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByProviderId(String providerId);

    Optional<User> findByApiKeyHash(String apiKeyHash);

    /**
     * The subset of {@code userIds} that hold the platform ADMIN role and are still
     * enabled. Feeds {@code VerifiedAccountService}: an admin is verified by virtue of
     * the role, with nothing stored on their profile, so today's admins and tomorrow's
     * are covered without a backfill.
     *
     * <p>A join over the EAGER {@code user_roles} element collection on purpose: loading
     * the users themselves would fire one extra select per row for that collection, which
     * on a marketplace page is one query per card.
     */
    @Query("""
            SELECT u.id FROM User u JOIN u.roles r
             WHERE r = 'ADMIN'
               AND u.enabled = true
               AND u.id IN :userIds
            """)
    List<Long> findAdminIdsIn(@Param("userIds") java.util.Collection<Long> userIds);

    /**
     * Every enabled platform admin that Keycloak knows (a provider id), for the admin
     * two-factor enforcement sweep. The admin list is a handful of rows.
     */
    @Query("""
            SELECT DISTINCT u FROM User u JOIN u.roles r
             WHERE r = 'ADMIN'
               AND u.enabled = true
               AND u.providerId IS NOT NULL
            """)
    List<User> findEnabledAdminsWithProviderId();

    /**
     * O(1) "does ANY account exist" probe (derived {@code LIMIT 1}, never a count
     * scan - the cloud shares this service and its users table is large). Feeds
     * the public CE first-run signal ({@code CeStatusView.hasUsers}) so a virgin
     * install routes {@code /login} to admin-account creation instead of the
     * "Welcome back" sign-in.
     */
    Optional<User> findFirstBy();

    /**
     * Atomic conditional update of last_login_at ("last seen"). Sets the timestamp to
     * {@code now} iff the current value is null OR strictly older than {@code threshold}.
     *
     * <p>The rowcount is a write-throttle, NOT a login signal. It used to be read as the
     * canonical "is this a real new login" flag, which is what made an open browser tab
     * or a scheduled workflow publish one login every {@code LOGIN_DEDUP_MINUTES}: the
     * condition it answers is "has enough wall-clock passed", and wall-clock passing is
     * not an authentication. {@link #recordAuthenticationIfNewer} is the login signal.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.lastLoginAt = :now " +
           "WHERE u.id = :userId AND (u.lastLoginAt IS NULL OR u.lastLoginAt < :threshold)")
    int updateLastLoginIfStale(@Param("userId") Long userId,
                               @Param("now") LocalDateTime now,
                               @Param("threshold") LocalDateTime threshold);

    /**
     * Atomic conditional advance of last_authenticated_at, and THE canonical
     * "did this person just authenticate" flag.
     *
     * <p>Returns 1 exactly once per authentication event. {@code authenticatedAt} comes
     * from the token's OIDC {@code auth_time} claim, which is constant for every refresh
     * of one session and newer on a new one, so a token that merely got refreshed matches
     * nothing and counts nothing.
     *
     * <p>Strictly {@code <}, never {@code <=}, so re-presenting the same token is a no-op.
     * The comparison being a single SQL statement is what makes it safe under the ~10
     * parallel resolves a page load fires and across auth replicas: only one of them can
     * observe the transition, so only one login event is emitted.
     *
     * <p>Deliberately does not touch last_login_at. One column per concern: this one
     * answers "when did they last sign in", the other "when were they last seen".
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.lastAuthenticatedAt = :authenticatedAt " +
           "WHERE u.id = :userId AND (u.lastAuthenticatedAt IS NULL OR u.lastAuthenticatedAt < :authenticatedAt)")
    int recordAuthenticationIfNewer(@Param("userId") Long userId,
                                    @Param("authenticatedAt") LocalDateTime authenticatedAt);

    /**
     * Accounts whose grace period has expired and which are therefore due for hard-deletion.
     *
     * <p>Selection is the deactivation date and nothing else, deliberately. An earlier version also
     * excluded anyone whose {@code lastLoginAt} was newer than their {@code deactivatedAt}, reading
     * that as "they came back, do not delete them". That signal does not mean what it looks like:
     * {@code lastLoginAt} is written by {@code UserResolutionService} on gateway user-resolution
     * whenever it is more than {@code LOGIN_DEDUP_MINUTES} old, BEFORE the gateway checks
     * {@code canMakeRequest()}, and a blocked account is re-resolved on EVERY request because
     * {@code AuthenticationFilter} drops its cache entry for anyone who cannot make requests. The
     * restore interstitial itself issues one of those requests. So merely following the e-mail's
     * instruction to sign in, looking at the screen and signing out again moved the deletion, while
     * {@code getDeletionStatus} kept reporting {@code deactivatedAt + grace} to that same person.
     * Showing a date we do not honour is worse than the risk the clause was guarding against.
     *
     * <p>What protects a returning person now is the thing built for it: they are told the date on
     * every blocked request and can cancel in one click, which clears {@code deactivatedAt} and
     * removes the row from this query. Someone who sees that screen and does not act is having
     * their original request honoured, on the day they were given.
     */
    @Query("SELECT u FROM User u WHERE u.enabled = false AND u.deactivatedAt IS NOT NULL " +
           "AND u.deactivatedAt < :cutoff")
    List<User> findAccountsPastGracePeriod(@Param("cutoff") LocalDateTime cutoff);

    // ---- Lifecycle email context (V527). Each one is a single conditional UPDATE that
    // returns 1 only when it CHANGED something, so the caller knows whether the Resend
    // contact needs a sync, and no whole-row save can revert a concurrent login write. ----

    /** An explicit pick in the UI always wins and pins the locale. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.locale = :locale, u.localeExplicit = true " +
           "WHERE u.id = :userId AND (u.locale IS NULL OR u.locale <> :locale OR u.localeExplicit = false)")
    int updateLocaleExplicit(@Param("userId") Long userId, @Param("locale") String locale);

    /** The locale the app merely displayed: never overwrites an explicit pick. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.locale = :locale " +
           "WHERE u.id = :userId AND u.localeExplicit = false AND (u.locale IS NULL OR u.locale <> :locale)")
    int updateLocaleImplicit(@Param("userId") Long userId, @Param("locale") String locale);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.timeZone = :timeZone " +
           "WHERE u.id = :userId AND (u.timeZone IS NULL OR u.timeZone <> :timeZone)")
    int updateTimeZone(@Param("userId") Long userId, @Param("timeZone") String timeZone);

    /** Write-once. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.signupCountry = :country WHERE u.id = :userId AND u.signupCountry IS NULL")
    int captureSignupCountry(@Param("userId") Long userId, @Param("country") String country);

    /**
     * Write-once, for good: the capture instant survives the 12-month purge of the address,
     * so an account whose IP was purged is never re-captured.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.signupIp = :ip, u.signupIpCapturedAt = :now " +
           "WHERE u.id = :userId AND u.signupIp IS NULL AND u.signupIpCapturedAt IS NULL")
    int captureSignupIp(@Param("userId") Long userId, @Param("ip") String ip, @Param("now") java.time.Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.marketingConsent = :consent, u.marketingConsentAt = :now WHERE u.id = :userId")
    int updateMarketingConsent(@Param("userId") Long userId, @Param("consent") boolean consent,
                               @Param("now") java.time.Instant now);

    /** Returns 1 exactly once per account: the first workflow ever created. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.activatedAt = :now WHERE u.id = :userId AND u.activatedAt IS NULL")
    int markActivatedIfFirst(@Param("userId") Long userId, @Param("now") java.time.Instant now);

    /** Write-once: 1 for the ONE caller that may send user.signed_up, 0 for every later or racing one. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.lifecycleSignupEmittedAt = :now WHERE u.id = :userId AND u.lifecycleSignupEmittedAt IS NULL")
    int markSignupEmittedIfFirst(@Param("userId") Long userId, @Param("now") java.time.Instant now);

    /**
     * Gives back a signup stamp whose welcome never left Resend: clears it only while it still
     * holds {@code stampedAt}, the value the releasing attempt wrote, so it never undoes another claim.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.lifecycleSignupEmittedAt = NULL WHERE u.id = :userId AND u.lifecycleSignupEmittedAt = :stampedAt")
    int releaseSignupEmitted(@Param("userId") Long userId, @Param("stampedAt") java.time.Instant stampedAt);

    /** Nulls every signup IP captured before the cutoff (the 12-month retention). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.signupIp = NULL " +
           "WHERE u.signupIp IS NOT NULL AND u.signupIpCapturedAt < :cutoff")
    int purgeSignupIpsCapturedBefore(@Param("cutoff") java.time.Instant cutoff);
}
