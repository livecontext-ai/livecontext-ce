package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.Organization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /**
     * Lock one organization row while performing admission checks that depend
     * on the current member count.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Organization o WHERE o.id = :id")
    Optional<Organization> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Find organization by unique slug.
     */
    Optional<Organization> findBySlug(String slug);

    /**
     * Find the personal organization of a user.
     */
    Optional<Organization> findByOwnerIdAndIsPersonalTrue(Long ownerId);

    /**
     * Find all organizations owned by a user.
     */
    List<Organization> findByOwnerId(Long ownerId);

    /**
     * Count the workspaces a user OWNS and that are not soft-deleted - drives the per-plan
     * {@code max_workspaces} cap at creation time. Soft-deleted (pending-purge) workspaces do
     * NOT count, so deleting one frees a slot.
     */
    long countByOwnerIdAndDeletedAtIsNull(Long ownerId);

    /**
     * Owned, non-soft-deleted workspaces ordered oldest-first - drives the
     * downgrade reconciliation, which keeps the personal workspace + the
     * (cap-1) OLDEST non-personal ones active and pauses the most-recent excess.
     */
    List<Organization> findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long ownerId);

    /**
     * Check if a slug is already taken.
     */
    boolean existsBySlug(String slug);

    /**
     * Check if a user already has a personal organization.
     */
    boolean existsByOwnerIdAndIsPersonalTrue(Long ownerId);

    /**
     * Workspaces eligible for hard-purge: soft-deleted before the grace cutoff, not yet
     * purged, and never personal (the base workspace is never purgeable). Drives the
     * {@code WorkspacePurgeScheduler}.
     */
    @Query("SELECT o FROM Organization o WHERE o.deletedAt IS NOT NULL " +
           "AND o.deletedAt < :cutoff AND o.purgedAt IS NULL AND o.isPersonal = false")
    List<Organization> findWorkspacesPastGracePeriod(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Distinct owner ids that own at least one ACTIVE (non-soft-deleted) NON-PERSONAL workspace -
     * the only owners whose workspace count can exceed the plan cap, so the only ones worth
     * reconciling. Drives the CE-only {@code WorkspacePauseReconcileScheduler}: CE has no
     * Stripe webhook or admin plan-change event to react to, so it periodically re-applies the
     * cloud-governing plan's {@code max_workspaces} cap. Owners with only their personal
     * workspace are skipped (nothing to pause).
     */
    @Query("SELECT DISTINCT o.owner.id FROM Organization o " +
           "WHERE o.isPersonal = false AND o.deletedAt IS NULL")
    List<Long> findDistinctOwnerIdsWithActiveNonPersonalWorkspaces();
}
