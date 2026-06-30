package com.apimarketplace.interfaces.repository;

import com.apimarketplace.interfaces.domain.InterfaceRunSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterfaceRunSnapshotRepository extends JpaRepository<InterfaceRunSnapshotEntity, UUID> {

    Optional<InterfaceRunSnapshotEntity> findByInterfaceIdAndWorkflowRunId(UUID interfaceId, UUID workflowRunId);

    List<InterfaceRunSnapshotEntity> findByWorkflowRunId(UUID workflowRunId);

    List<InterfaceRunSnapshotEntity> findByInterfaceIdOrderByCreatedAtDesc(UUID interfaceId);

    boolean existsByInterfaceIdAndWorkflowRunId(UUID interfaceId, UUID workflowRunId);

    List<InterfaceRunSnapshotEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    void deleteByWorkflowRunId(UUID workflowRunId);

    // ===== Org-strict variants (post-V263) =====
    // The snapshot table has no organization_id column; isolation is enforced via the parent
    // {@link com.apimarketplace.interfaces.domain.InterfaceEntity#organizationId}, which is
    // NOT NULL since V263. The JOIN adds a single-row PK lookup on the parent interface
    // (indexed by PK), cheap relative to a snapshot scan.

    @Query("SELECT s FROM InterfaceRunSnapshotEntity s "
            + "WHERE s.interfaceId = :interfaceId AND s.workflowRunId = :workflowRunId "
            + "AND EXISTS (SELECT 1 FROM InterfaceEntity i "
            + "            WHERE i.id = s.interfaceId AND i.organizationId = :organizationId)")
    Optional<InterfaceRunSnapshotEntity> findByInterfaceIdAndWorkflowRunIdInOrgScope(
            @Param("interfaceId") UUID interfaceId,
            @Param("workflowRunId") UUID workflowRunId,
            @Param("organizationId") String organizationId);

    @Query("SELECT s FROM InterfaceRunSnapshotEntity s "
            + "WHERE s.workflowRunId = :workflowRunId "
            + "AND EXISTS (SELECT 1 FROM InterfaceEntity i "
            + "            WHERE i.id = s.interfaceId AND i.organizationId = :organizationId)")
    List<InterfaceRunSnapshotEntity> findByWorkflowRunIdInOrgScope(
            @Param("workflowRunId") UUID workflowRunId,
            @Param("organizationId") String organizationId);

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END "
            + "FROM InterfaceRunSnapshotEntity s "
            + "WHERE s.interfaceId = :interfaceId AND s.workflowRunId = :workflowRunId "
            + "AND EXISTS (SELECT 1 FROM InterfaceEntity i "
            + "            WHERE i.id = s.interfaceId AND i.organizationId = :organizationId)")
    boolean existsByInterfaceIdAndWorkflowRunIdInOrgScope(
            @Param("interfaceId") UUID interfaceId,
            @Param("workflowRunId") UUID workflowRunId,
            @Param("organizationId") String organizationId);
}
