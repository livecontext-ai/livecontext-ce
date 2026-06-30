package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.UserOnboarding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for UserOnboarding entity.
 */
@Repository
public interface UserOnboardingRepository extends JpaRepository<UserOnboarding, Long> {

    /**
     * Find onboarding by user ID.
     */
    Optional<UserOnboarding> findByUserId(Long userId);

    /**
     * Find onboarding by user's provider ID (Keycloak sub).
     */
    @Query("SELECT uo FROM UserOnboarding uo JOIN uo.user u WHERE u.providerId = :providerId")
    Optional<UserOnboarding> findByUserProviderId(@Param("providerId") String providerId);

    /**
     * Check if onboarding exists for a user.
     */
    boolean existsByUserId(Long userId);

    /**
     * Batch lookup by numeric user ids. Used by the recent-activity aggregator
     * (orchestrator-service) to resolve createdBy → displayName for up to 50
     * rows in a single round-trip, avoiding the N+1 that a per-row
     * {@link #findByUserProviderId} call would produce.
     *
     * <p>{@code JOIN FETCH} initialises the {@code user} association eagerly so
     * the controller can read {@code uo.getUser().getId()} / {@code .getProviderId()}
     * without triggering a per-row lazy load (the User is needed to key the
     * response map back to the input id).
     */
    @Query("SELECT uo FROM UserOnboarding uo JOIN FETCH uo.user u WHERE u.id IN :userIds")
    List<UserOnboarding> findAllByUserIdIn(@Param("userIds") Collection<Long> userIds);

    /**
     * Batch lookup by Keycloak provider ids (UUID sub strings). Sibling to
     * {@link #findAllByUserIdIn} - the batch resolve endpoint partitions input
     * by numeric vs UUID form (same dispatch as
     * {@code InternalAuthController#getDisplayName}) and calls both, then
     * merges the results. {@code JOIN FETCH} for the same reason.
     */
    @Query("SELECT uo FROM UserOnboarding uo JOIN FETCH uo.user u WHERE u.providerId IN :providerIds")
    List<UserOnboarding> findAllByUserProviderIdIn(@Param("providerIds") Collection<String> providerIds);

    /**
     * Find onboarding by user id with the {@code user} association eagerly
     * fetched. Used by the {@code /users/{id}/publisher-profile} endpoint,
     * which traverses {@code uo.user.email} and {@code uo.user.avatarUrl}
     * to build the publication snapshot - without {@code JOIN FETCH} the
     * controller would depend on Spring's open-in-view to lazy-load, which
     * silently breaks the day someone disables OSIV.
     */
    @Query("SELECT uo FROM UserOnboarding uo JOIN FETCH uo.user u WHERE u.id = :userId")
    Optional<UserOnboarding> findByUserIdFetchUser(@Param("userId") Long userId);

    /**
     * Sibling of {@link #findByUserIdFetchUser} keyed on Keycloak {@code sub}.
     */
    @Query("SELECT uo FROM UserOnboarding uo JOIN FETCH uo.user u WHERE u.providerId = :providerId")
    Optional<UserOnboarding> findByUserProviderIdFetchUser(@Param("providerId") String providerId);

    /**
     * Check if display name is already taken by another user.
     */
    @Query("SELECT CASE WHEN COUNT(uo) > 0 THEN true ELSE false END FROM UserOnboarding uo WHERE LOWER(uo.displayName) = LOWER(:displayName) AND uo.user.id <> :excludeUserId")
    boolean existsByDisplayNameIgnoreCaseAndUserIdNot(@Param("displayName") String displayName, @Param("excludeUserId") Long excludeUserId);

    /**
     * Check if display name is already taken (for new users).
     */
    @Query("SELECT CASE WHEN COUNT(uo) > 0 THEN true ELSE false END FROM UserOnboarding uo WHERE LOWER(uo.displayName) = LOWER(:displayName)")
    boolean existsByDisplayNameIgnoreCase(@Param("displayName") String displayName);
}
