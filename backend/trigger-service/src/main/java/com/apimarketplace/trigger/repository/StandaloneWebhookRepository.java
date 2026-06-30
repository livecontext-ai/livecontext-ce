package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for standalone webhooks.
 */
@Repository
public interface StandaloneWebhookRepository extends JpaRepository<StandaloneWebhookEntity, UUID> {

    Optional<StandaloneWebhookEntity> findByToken(String token);

    // ──────────────────────────────────────────────────────────────────────
    // Strict-isolation finders - post-V261 every row has a non-null
    // organization_id (gateway always injects X-Organization-ID, personal-
    // workspace users get their personal org UUID). The legacy
    // *OrganizationIdIsNull* personal-scope variants were removed in the
    // V261 sweep; callers route 100% through *OrganizationIdStrict*. The
    // anonymous fire path (findByToken) is intentionally NOT scope-routed -
    // the token IS the auth and the orchestrator stamps the returned
    // entity's organization_id onto the created workflow_run.
    // ──────────────────────────────────────────────────────────────────────

    @Query("SELECT w FROM StandaloneWebhookEntity w "
         + "WHERE w.id = :id AND w.organizationId = :orgId")
    Optional<StandaloneWebhookEntity> findByIdAndOrganizationIdStrict(
            @Param("id") UUID id, @Param("orgId") String orgId);

    @Query("SELECT w FROM StandaloneWebhookEntity w "
         + "WHERE w.organizationId = :orgId ORDER BY w.createdAt DESC")
    List<StandaloneWebhookEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(
            @Param("orgId") String orgId);

    @Query("SELECT COUNT(w) FROM StandaloneWebhookEntity w "
         + "WHERE w.organizationId = :orgId AND w.workflowId IS NOT NULL")
    long countByOrganizationIdStrictAndWorkflowIdIsNotNull(@Param("orgId") String orgId);

    List<StandaloneWebhookEntity> findByWorkflowId(UUID workflowId);

    /** Strict-org dedup. */
    @Query("SELECT w FROM StandaloneWebhookEntity w "
         + "WHERE w.organizationId = :orgId AND w.sourceNodeId = :sourceNodeId")
    Optional<StandaloneWebhookEntity> findByOrganizationIdStrictAndSourceNodeId(
            @Param("orgId") String orgId, @Param("sourceNodeId") String sourceNodeId);

    /**
     * Orphan reaper: rows never linked to a workflow and older than `cutoff`.
     */
    List<StandaloneWebhookEntity> findByWorkflowIdIsNullAndCreatedAtBefore(java.time.Instant cutoff);

    /**
     * Distinct non-null workflow IDs older than {@code ageCutoff} - fed to the stale-FK
     * reaper. See {@code ScheduledExecutionRepository.findDistinctNonNullWorkflowIds}
     * for the race-guard rationale.
     */
    @Query("SELECT DISTINCT w.workflowId FROM StandaloneWebhookEntity w " +
           "WHERE w.workflowId IS NOT NULL AND w.createdAt < :ageCutoff")
    Set<UUID> findDistinctNonNullWorkflowIds(@Param("ageCutoff") java.time.Instant ageCutoff);

    /**
     * Bulk delete by workflow IDs - invoked after orchestrator confirms the
     * referenced workflows no longer exist.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM StandaloneWebhookEntity w WHERE w.workflowId IN :workflowIds")
    int deleteByWorkflowIdIn(@Param("workflowIds") Collection<UUID> workflowIds);
}
