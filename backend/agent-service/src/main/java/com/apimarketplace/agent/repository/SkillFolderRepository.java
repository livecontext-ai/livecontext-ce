package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.SkillFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkillFolderRepository extends JpaRepository<SkillFolderEntity, UUID> {

    // Batch A (2026-05-20): findByTenantIdOrderByNameAsc,
    // findByTenantIdAndParentIdIsNullOrderByNameAsc,
    // findByTenantIdAndParentIdOrderByNameAsc were orphans - SkillFolderService
    // uses org-strict finders exclusively.

    /** PR27.2 - strict-org folder list. */
    @Query("SELECT f FROM SkillFolderEntity f WHERE f.organizationId = :orgId ORDER BY f.name ASC")
    List<SkillFolderEntity> findByOrganizationIdStrictOrderByNameAsc(@Param("orgId") String orgId);

    /** Phase 6c - strict-org single fetch (rename/move/delete/getFolderContents gate). */
    @Query("SELECT f FROM SkillFolderEntity f WHERE f.id = :id AND f.organizationId = :orgId")
    Optional<SkillFolderEntity> findByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    /** Phase 6c - strict-org root-level subfolders. */
    @Query("SELECT f FROM SkillFolderEntity f WHERE f.organizationId = :orgId AND f.parentId IS NULL ORDER BY f.name ASC")
    List<SkillFolderEntity> findByOrganizationIdAndParentIdIsNullStrictOrderByNameAsc(@Param("orgId") String orgId);

    /** Phase 6c - strict-org subfolders of a parent. */
    @Query("SELECT f FROM SkillFolderEntity f WHERE f.organizationId = :orgId AND f.parentId = :parentId ORDER BY f.name ASC")
    List<SkillFolderEntity> findByOrganizationIdAndParentIdStrictOrderByNameAsc(@Param("orgId") String orgId, @Param("parentId") UUID parentId);

    // ===== V275 (2026-05-21): folder is_global - admin-managed visibility =====
    //
    // Mirrors SkillRepository.findVisibleForOrganization: globals are visible
    // alongside the org's own folders. Globals carry their creator's
    // organizationId, so DISTINCT dedups when the caller's org is the creator.

    /** V275 - org-scope + global folders, sorted by name. */
    @Query("SELECT DISTINCT f FROM SkillFolderEntity f " +
           "WHERE f.organizationId = :orgId OR f.isGlobal = true " +
           "ORDER BY f.name ASC")
    List<SkillFolderEntity> findVisibleForOrganization(@Param("orgId") String orgId);

    /** V275 - org-scope + global root-level folders. */
    @Query("SELECT DISTINCT f FROM SkillFolderEntity f " +
           "WHERE (f.organizationId = :orgId OR f.isGlobal = true) " +
           "  AND f.parentId IS NULL " +
           "ORDER BY f.name ASC")
    List<SkillFolderEntity> findVisibleRootForOrganization(@Param("orgId") String orgId);

    /** V275 - org-scope + global children of a parent. */
    @Query("SELECT DISTINCT f FROM SkillFolderEntity f " +
           "WHERE (f.organizationId = :orgId OR f.isGlobal = true) " +
           "  AND f.parentId = :parentId " +
           "ORDER BY f.name ASC")
    List<SkillFolderEntity> findVisibleChildrenForOrganization(@Param("orgId") String orgId,
                                                                @Param("parentId") UUID parentId);

    /**
     * V275 - strict single-folder fetch including globals. Used as the gate
     * before exposing folder contents and before any folder-write op - admins
     * editing a global folder must reach it even if their active workspace
     * isn't the folder's creator org.
     */
    @Query("SELECT f FROM SkillFolderEntity f " +
           "WHERE f.id = :id AND (f.organizationId = :orgId OR f.isGlobal = true)")
    Optional<SkillFolderEntity> findVisibleByIdForOrganization(@Param("id") UUID id, @Param("orgId") String orgId);
}
