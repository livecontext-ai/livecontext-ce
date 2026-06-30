package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.OrganizationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, Long> {

    /**
     * Find all memberships for a user.
     */
    List<OrganizationMember> findByUser_Id(Long userId);

    /**
     * Find a specific membership.
     */
    Optional<OrganizationMember> findByOrganization_IdAndUser_Id(UUID organizationId, Long userId);

    /**
     * Find a membership where the underlying organization is NOT soft-deleted.
     * Used by `OrganizationController` to validate `X-Organization-ID` headers:
     * a soft-deleted org's membership row still exists but must not satisfy
     * "current org" queries. Equivalent to {@link #findByOrganization_IdAndUser_Id}
     * + a `deletedAt IS NULL` filter.
     */
    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.organization o LEFT JOIN FETCH o.owner "
         + "WHERE o.id = :organizationId AND m.user.id = :userId AND o.deletedAt IS NULL")
    Optional<OrganizationMember> findActiveByOrganizationIdAndUserId(
            @Param("organizationId") UUID organizationId, @Param("userId") Long userId);

    /**
     * Find the user's default organization membership.
     */
    Optional<OrganizationMember> findByUser_IdAndIsDefaultTrue(Long userId);

    /**
     * Like {@link #findByUser_IdAndIsDefaultTrue} but filters out soft-deleted
     * organizations. Used by `OrganizationController.getCurrent` so the user
     * never lands on a tomb-stoned default org after a cascade-delete.
     */
    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.organization o LEFT JOIN FETCH o.owner "
         + "WHERE m.user.id = :userId AND m.isDefault = TRUE AND o.deletedAt IS NULL")
    Optional<OrganizationMember> findActiveDefaultByUserId(@Param("userId") Long userId);

    /**
     * The user's OWN personal-org membership (they are its owner; is_personal=true,
     * 1:1 by construction). Used as the safe fallback when their default workspace
     * is a "dormant" team org they can no longer enter - so plan/context resolution
     * lands on the personal workspace instead of stranding on the dormant org.
     */
    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.organization o LEFT JOIN FETCH o.owner "
         + "WHERE m.user.id = :userId AND o.isPersonal = TRUE AND o.owner.id = :userId AND o.deletedAt IS NULL")
    Optional<OrganizationMember> findPersonalByUserId(@Param("userId") Long userId);

    /**
     * Find the user's default organization membership ONLY when the default
     * organization is personal (1:1 with the user, never shared).
     *
     * <p>V226 audit fix: Subscription create sites must NOT stamp
     * organization_id with a TEAM org id, because the V225 partial unique
     * index `idx_subscription_active_per_org WHERE status='active' AND
     * organization_id IS NOT NULL` would COLLIDE when two TEAM-default
     * members each get a FREE sub stamped with the same TEAM org id. The
     * silent catch around `ensureFreeSubscription` would swallow the
     * unique-violation and leave the second user with no subscription at all.
     *
     * <p>Personal orgs are 1:1 with the user (UNIQUE owner_id + is_personal=true),
     * so stamping only on personal targets is collision-free by construction.
     * Pair with V226 backfill which uses the same filter for the same reason.
     */
    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.organization o "
         + "WHERE m.user.id = :userId AND m.isDefault = TRUE "
         + "AND o.isPersonal = TRUE AND o.deletedAt IS NULL")
    Optional<OrganizationMember> findDefaultPersonalByUserId(@Param("userId") Long userId);

    /**
     * Find all members of an organization.
     */
    List<OrganizationMember> findByOrganization_Id(UUID organizationId);

    /**
     * Project just the member user ids for an organization. Avoids a lazy-load
     * on the {@code user} association (no JOIN FETCH needed) - used by the
     * internal {@code /organizations/{orgId}/member-ids} endpoint that
     * agent-service calls to validate a human task assignee / reviewer.
     */
    @Query("SELECT m.user.id FROM OrganizationMember m WHERE m.organization.id = :organizationId")
    List<Long> findMemberUserIdsByOrganizationId(@Param("organizationId") UUID organizationId);

    /**
     * Check if a user is a member of an organization.
     */
    boolean existsByOrganization_IdAndUser_Id(UUID organizationId, Long userId);

    /**
     * Count members in an organization.
     */
    long countByOrganization_Id(UUID organizationId);

    /**
     * Find memberships with organization eagerly loaded (for listing user's orgs).
     *
     * <p>Filters out soft-deleted organizations (deletedAt IS NOT NULL) - the
     * cascade-delete flow (PR-cascade) sets deletedAt on the org row, and
     * without this filter the ex-owner kept seeing the org in /me, breaking
     * the "delete" UX. Caught by E2E `scripts/test-org-e2e.sh` FINDING block.
     */
    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.organization o LEFT JOIN FETCH o.owner WHERE m.user.id = :userId AND o.deletedAt IS NULL ORDER BY m.isDefault DESC, m.joinedAt ASC")
    List<OrganizationMember> findByUserIdWithOrganization(@Param("userId") Long userId);

    /**
     * Workspaces the user OWNS that are soft-deleted but not yet hard-purged (in the grace
     * window). Surfaced ONLY to the owner in /me so they can restore - the gateway membership
     * path ({@link #findByUserIdWithOrganization}) keeps excluding deleted orgs, so a soft-
     * deleted workspace stays non-enterable.
     */
    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.organization o LEFT JOIN FETCH o.owner " +
           "WHERE m.user.id = :userId AND o.deletedAt IS NOT NULL AND o.purgedAt IS NULL " +
           "AND m.role = com.apimarketplace.auth.domain.OrganizationRole.OWNER ORDER BY o.deletedAt DESC")
    List<OrganizationMember> findPendingDeletionOwnedByUser(@Param("userId") Long userId);
}
