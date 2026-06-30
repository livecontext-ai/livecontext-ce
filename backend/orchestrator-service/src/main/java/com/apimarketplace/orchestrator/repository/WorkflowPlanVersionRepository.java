package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowPlanVersionRepository extends JpaRepository<WorkflowPlanVersionEntity, UUID> {

    /**
     * Get the latest version number for a workflow.
     */
    @Query("SELECT MAX(v.version) FROM WorkflowPlanVersionEntity v WHERE v.workflowId = :workflowId")
    Optional<Integer> getMaxVersion(@Param("workflowId") UUID workflowId);

    /**
     * List all versions for a workflow (without plan data for performance), ordered by version DESC.
     */
    List<WorkflowPlanVersionEntity> findByWorkflowIdOrderByVersionDesc(UUID workflowId);

    /**
     * Get a specific version.
     */
    Optional<WorkflowPlanVersionEntity> findByWorkflowIdAndVersion(UUID workflowId, Integer version);

    /**
     * Count versions for a workflow.
     */
    long countByWorkflowId(UUID workflowId);

    /**
     * Delete oldest versions beyond the retention limit.
     * Keeps the N most recent versions.
     */
    @Modifying
    @Query(value = """
        DELETE FROM orchestrator.workflow_plan_versions
        WHERE workflow_id = :workflowId
        AND version NOT IN (
            SELECT version FROM orchestrator.workflow_plan_versions
            WHERE workflow_id = :workflowId
            ORDER BY version DESC
            LIMIT :keepCount
        )
        """, nativeQuery = true)
    int purgeOldVersions(@Param("workflowId") UUID workflowId, @Param("keepCount") int keepCount);

    /**
     * Delete oldest versions beyond the retention limit, but protect a specific version
     * from deletion (e.g., the pinned production version).
     */
    @Modifying
    @Query(value = """
        DELETE FROM orchestrator.workflow_plan_versions
        WHERE workflow_id = :workflowId
        AND version != :protectedVersion
        AND version NOT IN (
            SELECT version FROM orchestrator.workflow_plan_versions
            WHERE workflow_id = :workflowId
            ORDER BY version DESC
            LIMIT :keepCount
        )
        """, nativeQuery = true)
    int purgeOldVersionsExcluding(@Param("workflowId") UUID workflowId,
                                   @Param("keepCount") int keepCount,
                                   @Param("protectedVersion") int protectedVersion);
}
