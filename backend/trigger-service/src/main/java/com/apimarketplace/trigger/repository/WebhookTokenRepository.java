package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.WebhookTokenEntity;
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
 * Repository for webhook tokens.
 * Supports multi-DAG webhooks where each trigger has its own token.
 */
@Repository
public interface WebhookTokenRepository extends JpaRepository<WebhookTokenEntity, Long> {

    /**
     * Find a webhook token entity by its token value.
     * Used during webhook dispatch to route to the correct workflow and trigger.
     */
    Optional<WebhookTokenEntity> findByToken(String token);

    /**
     * Find token for a specific workflow and trigger combination.
     */
    Optional<WebhookTokenEntity> findByWorkflowIdAndTriggerId(UUID workflowId, String triggerId);

    /**
     * List-based lookup - used by ensureTokenForTrigger to gracefully handle pre-migration duplicates.
     */
    List<WebhookTokenEntity> findAllByWorkflowIdAndTriggerId(UUID workflowId, String triggerId);

    /**
     * Find all tokens for a workflow.
     * Used to return all webhook URLs for a workflow.
     */
    List<WebhookTokenEntity> findByWorkflowId(UUID workflowId);

    /**
     * Delete all tokens for a workflow.
     * Used when workflow is deleted.
     */
    @Modifying
    void deleteByWorkflowId(UUID workflowId);

    /**
     * Delete tokens for triggers that are no longer in the workflow plan.
     * Used during sync to clean up orphan tokens.
     */
    @Modifying
    @Query("DELETE FROM WebhookTokenEntity w WHERE w.workflowId = :workflowId AND w.triggerId NOT IN :triggerIds")
    void deleteByWorkflowIdAndTriggerIdNotIn(@Param("workflowId") UUID workflowId, @Param("triggerIds") List<String> triggerIds);

    /**
     * Check if a token exists for a workflow and trigger.
     */
    boolean existsByWorkflowIdAndTriggerId(UUID workflowId, String triggerId);

    /**
     * Find which workflow IDs (from the given set) have at least one webhook token.
     * Used by orchestrator to identify active webhook workflows without cross-service SQL.
     */
    @Query("SELECT DISTINCT w.workflowId FROM WebhookTokenEntity w WHERE w.workflowId IN :ids")
    Set<UUID> findWorkflowIdsWithTokens(@Param("ids") Collection<UUID> ids);

}
