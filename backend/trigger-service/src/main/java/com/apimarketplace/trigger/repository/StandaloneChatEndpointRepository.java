package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
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

@Repository
public interface StandaloneChatEndpointRepository extends JpaRepository<StandaloneChatEndpointEntity, UUID> {

    Optional<StandaloneChatEndpointEntity> findByToken(String token);

    // Strict-isolation finders - see StandaloneWebhookRepository javadoc.
    // Post-V261 the *OrganizationIdIsNull* personal-scope variants were
    // removed because every row carries a non-null organization_id.

    @Query("SELECT c FROM StandaloneChatEndpointEntity c "
         + "WHERE c.id = :id AND c.organizationId = :orgId")
    Optional<StandaloneChatEndpointEntity> findByIdAndOrganizationIdStrict(
            @Param("id") UUID id, @Param("orgId") String orgId);

    @Query("SELECT c FROM StandaloneChatEndpointEntity c "
         + "WHERE c.organizationId = :orgId ORDER BY c.createdAt DESC")
    List<StandaloneChatEndpointEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(
            @Param("orgId") String orgId);

    @Query("SELECT COUNT(c) FROM StandaloneChatEndpointEntity c "
         + "WHERE c.organizationId = :orgId AND c.workflowId IS NOT NULL")
    long countByOrganizationIdStrictAndWorkflowIdIsNotNull(@Param("orgId") String orgId);

    List<StandaloneChatEndpointEntity> findByWorkflowId(UUID workflowId);

    /** Strict-org dedup. */
    @Query("SELECT c FROM StandaloneChatEndpointEntity c "
         + "WHERE c.organizationId = :orgId AND c.sourceNodeId = :sourceNodeId")
    Optional<StandaloneChatEndpointEntity> findByOrganizationIdStrictAndSourceNodeId(
            @Param("orgId") String orgId, @Param("sourceNodeId") String sourceNodeId);

    List<StandaloneChatEndpointEntity> findByWorkflowIdIsNullAndCreatedAtBefore(java.time.Instant cutoff);

    /**
     * Distinct non-null workflow IDs older than {@code ageCutoff} - fed to the stale-FK
     * reaper. See {@code ScheduledExecutionRepository.findDistinctNonNullWorkflowIds}
     * for the race-guard rationale.
     */
    @Query("SELECT DISTINCT c.workflowId FROM StandaloneChatEndpointEntity c " +
           "WHERE c.workflowId IS NOT NULL AND c.createdAt < :ageCutoff")
    Set<UUID> findDistinctNonNullWorkflowIds(@Param("ageCutoff") java.time.Instant ageCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM StandaloneChatEndpointEntity c WHERE c.workflowId IN :workflowIds")
    int deleteByWorkflowIdIn(@Param("workflowIds") Collection<UUID> workflowIds);
}
