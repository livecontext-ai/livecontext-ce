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

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByProviderId(String providerId);

    Optional<User> findByApiKeyHash(String apiKeyHash);

    /**
     * O(1) "does ANY account exist" probe (derived {@code LIMIT 1}, never a count
     * scan - the cloud shares this service and its users table is large). Feeds
     * the public CE first-run signal ({@code CeStatusView.hasUsers}) so a virgin
     * install routes {@code /login} to admin-account creation instead of the
     * "Welcome back" sign-in.
     */
    Optional<User> findFirstBy();

    /**
     * Atomic conditional update of last_login_at. Sets the timestamp to {@code now}
     * iff the current value is null OR strictly older than {@code threshold}.
     * Returns the number of rows updated (0 or 1) - used as the canonical "is this
     * a real new login" flag for metrics/audit so concurrent resolveUser() calls
     * during a single page load only fire ONE login event.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.lastLoginAt = :now " +
           "WHERE u.id = :userId AND (u.lastLoginAt IS NULL OR u.lastLoginAt < :threshold)")
    int updateLastLoginIfStale(@Param("userId") Long userId,
                               @Param("now") LocalDateTime now,
                               @Param("threshold") LocalDateTime threshold);

    @Query("SELECT u FROM User u WHERE u.enabled = false AND u.deactivatedAt IS NOT NULL AND u.deactivatedAt < :cutoff")
    List<User> findAccountsPastGracePeriod(@Param("cutoff") LocalDateTime cutoff);
}
