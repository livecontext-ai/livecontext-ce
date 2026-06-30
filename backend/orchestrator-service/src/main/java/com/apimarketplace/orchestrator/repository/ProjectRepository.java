package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    List<ProjectEntity> findByOwnerIdAndIsArchivedFalseOrderByUpdatedAtDesc(String ownerId);

    List<ProjectEntity> findByOwnerIdOrderByUpdatedAtDesc(String ownerId);

    boolean existsByOwnerIdAndSlug(String ownerId, String slug);

    @Query("SELECT p FROM ProjectEntity p WHERE p.id IN :ids ORDER BY p.updatedAt DESC")
    List<ProjectEntity> findByIdIn(@Param("ids") List<UUID> ids);

    long countByOwnerId(String ownerId);

    /**
     * Find projects visible in an organization workspace. Post-V261 every project
     * row carries a non-null organization_id (personal workspaces resolve to the
     * user's default personal org), so the legacy {@code OR p.ownerId = :userId}
     * branch was dead code that also bled cross-workspace rows owned by the
     * caller across orgs they're in. Strict-org match closes that leak.
     */
    @Query("SELECT p FROM ProjectEntity p WHERE p.organizationId = :orgId AND p.isArchived = false ORDER BY p.updatedAt DESC")
    List<ProjectEntity> findByOrganizationOrOwner(@Param("orgId") String orgId, @Param("userId") String userId);
}
