package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.SkillEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, UUID> {

    // Batch A (2026-05-20): findByTenantIdAndIsActiveTrueOrderByCreatedAtDesc,
    // findByTenantIdOrderByCreatedAtDesc, countByTenantId were orphans -
    // SkillService now goes through org-strict + findVisibleForTenant.

    /** PR27.2 - strict-org skill list. */
    @Query("SELECT s FROM SkillEntity s WHERE s.organizationId = :orgId ORDER BY s.createdAt DESC")
    List<SkillEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(@Param("orgId") String orgId);

    /** PR27.2 - strict-org count. */
    @Query("SELECT COUNT(s) FROM SkillEntity s WHERE s.organizationId = :orgId")
    long countByOrganizationIdStrict(@Param("orgId") String orgId);

    // ===== Recent-activity aggregator (V237 partial indexes back these) =====

    /**
     * Top-N skills in an org workspace ordered by last edit time. Backed by
     * the V237 partial index {@code idx_skills_org_updated_at}. Used by the
     * agents-recent-activity endpoint which returns the union of agents +
     * skills in a single response.
     */
    @Query("SELECT s FROM SkillEntity s WHERE s.organizationId = :orgId ORDER BY s.updatedAt DESC")
    List<SkillEntity> findRecentByOrganizationIdStrict(@Param("orgId") String orgId, Pageable pageable);

    // Batch A (2026-05-20): findByTenantIdAndFolderIdIsNullOrderByCreatedAtDesc,
    // findByTenantIdAndFolderIdOrderByCreatedAtDesc were orphans - SkillService
    // uses the *AndOrganizationId*Strict variants exclusively.

    /** Phase 6c - strict-org skills at the root of the folder tree. */
    @Query("SELECT s FROM SkillEntity s WHERE s.organizationId = :orgId AND s.folderId IS NULL ORDER BY s.createdAt DESC")
    List<SkillEntity> findByOrganizationIdAndFolderIdIsNullStrictOrderByCreatedAtDesc(@Param("orgId") String orgId);

    /** Phase 6c - strict-org skills inside a folder. */
    @Query("SELECT s FROM SkillEntity s WHERE s.organizationId = :orgId AND s.folderId = :folderId ORDER BY s.createdAt DESC")
    List<SkillEntity> findByOrganizationIdAndFolderIdStrictOrderByCreatedAtDesc(@Param("orgId") String orgId, @Param("folderId") UUID folderId);

    /** Phase 6c - strict-org single skill fetch. */
    @Query("SELECT s FROM SkillEntity s WHERE s.id = :id AND s.organizationId = :orgId")
    Optional<SkillEntity> findByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    List<SkillEntity> findByTenantIdAndDefaultKeyIsNotNull(String tenantId);

    /**
     * Seed-check projection (2026-06-11): counts the tenant's built-in rows
     * without hydrating SkillEntity (incl. the instructions LOB) - this runs
     * on EVERY skills list since the Phase 6c seed-regression fix, so it must
     * stay a cheap count.
     */
    long countByTenantIdAndDefaultKeyIsNotNull(String tenantId);

    Optional<SkillEntity> findByTenantIdAndDefaultKey(String tenantId, String defaultKey);

    /**
     * Return every skill owned by the given tenant plus every admin-managed global
     * skill. Globals owned by the tenant are deduplicated by id via DISTINCT.
     */
    @Query("SELECT DISTINCT s FROM SkillEntity s " +
           "WHERE s.tenantId = :tenantId OR s.isGlobal = true " +
           "ORDER BY s.createdAt DESC")
    List<SkillEntity> findVisibleForTenant(@Param("tenantId") String tenantId);

    /**
     * Phase 6c - strict-org visible skills (org-scope + globals). Globals are
     * admin-managed and visible everywhere; the org-scoped view also includes
     * them so org-teammates see the same baseline as personal scope.
     */
    @Query("SELECT DISTINCT s FROM SkillEntity s " +
           "WHERE s.organizationId = :orgId OR s.isGlobal = true " +
           "ORDER BY s.createdAt DESC")
    List<SkillEntity> findVisibleForOrganization(@Param("orgId") String orgId);

    /**
     * V275 (2026-05-21) - the default-active subset of {@link
     * #findVisibleForOrganization(String)}. Read on every new general-chat
     * conversation to seed the system-prompt skill list, so it must stay tight:
     * the partial index {@code idx_skills_is_default_active} (V275) backs the
     * is_default_active=true filter.
     */
    @Query("SELECT DISTINCT s FROM SkillEntity s " +
           "WHERE s.isDefaultActive = true " +
           "  AND (s.organizationId = :orgId OR s.isGlobal = true) " +
           "ORDER BY s.createdAt DESC")
    List<SkillEntity> findDefaultActiveVisibleForOrganization(@Param("orgId") String orgId);

    // ===== Skill bundle (cloud -> CE distribution), V374 =====

    /**
     * Cloud-side bundle snapshot source: every admin-managed global skill. Ordered
     * deterministically so the canonical payload is byte-stable across builds.
     */
    List<SkillEntity> findByIsGlobalTrueOrderByCreatedAtAsc();

    /**
     * CE-side idempotent upsert key: the single row applied from a given cloud skill.
     * {@code source_bundle_key} carries the cloud skill's UUID (V374 partial unique index).
     */
    Optional<SkillEntity> findBySourceBundleKey(String sourceBundleKey);

    /**
     * CE-side: every bundle-applied (cloud-owned) row. Used by the applier to soft-remove
     * the rows whose cloud skill disappeared from the latest bundle.
     */
    List<SkillEntity> findBySourceBundleKeyIsNotNull();
}
