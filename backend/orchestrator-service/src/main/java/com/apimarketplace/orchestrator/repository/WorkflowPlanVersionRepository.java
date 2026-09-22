package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
     * List all versions for a workflow, ordered by version DESC.
     *
     * <p>Returns FULL entities, plan bodies included: {@code plan} is an eager
     * {@code @JdbcTypeCode(SqlTypes.JSON)} basic attribute, so there is no lazy
     * loading to opt into. (An earlier version of this javadoc claimed the plans were
     * excluded "for performance" - they never were.) Callers pay one JSONB
     * deserialize per row, bounded by {@code workflow.versioning.max-versions};
     * {@code WorkflowPlanVersionService.findVersionWithMatchingContent} relies on the
     * bodies being present, so keep it that way or give it its own query.
     */
    List<WorkflowPlanVersionEntity> findByWorkflowIdOrderByVersionDesc(UUID workflowId);

    /**
     * The authorship of every stored version, newest first, WITHOUT the plan bodies.
     *
     * <p>Exists because {@link #findByWorkflowIdOrderByVersionDesc} deliberately carries
     * the plans (see its javadoc) and the caller here only ever reads three scalar columns.
     * Asking the full finder would deserialize up to {@code workflow.versioning.max-versions}
     * JSONB documents to answer "who edited this, and when" - a cost paid on a popover the
     * user opens casually, on a row already known to be bounded by retention.
     */
    @Query("""
        SELECT v.createdAt AS editedAt, v.createdBy AS userId
        FROM WorkflowPlanVersionEntity v
        WHERE v.workflowId = :workflowId
        ORDER BY v.version DESC
        """)
    List<VersionAuthorProjection> findVersionAuthors(@Param("workflowId") UUID workflowId);

    /**
     * Scalar view of one stored version's authorship - no plan body, and no version NUMBER:
     * nothing renders it, and selecting a column for a getter no caller reads is how a
     * projection grows a field that later reads as load-bearing. The ordering still uses
     * {@code version} - it just does not need to come back.
     */
    interface VersionAuthorProjection {
        Instant getEditedAt();
        /** The editing user's id ({@code created_by}); null on versions written before it was stamped. */
        String getUserId();
    }

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
