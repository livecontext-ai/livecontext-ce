package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
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
public interface StandaloneFormEndpointRepository extends JpaRepository<StandaloneFormEndpointEntity, UUID> {

    Optional<StandaloneFormEndpointEntity> findByToken(String token);

    // Strict-isolation finders - see StandaloneWebhookRepository javadoc.
    // Post-V261 the *OrganizationIdIsNull* personal-scope variants were
    // removed because every row carries a non-null organization_id.

    @Query("SELECT f FROM StandaloneFormEndpointEntity f "
         + "WHERE f.id = :id AND f.organizationId = :orgId")
    Optional<StandaloneFormEndpointEntity> findByIdAndOrganizationIdStrict(
            @Param("id") UUID id, @Param("orgId") String orgId);

    @Query("SELECT f FROM StandaloneFormEndpointEntity f "
         + "WHERE f.organizationId = :orgId ORDER BY f.createdAt DESC")
    List<StandaloneFormEndpointEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(
            @Param("orgId") String orgId);

    @Query("SELECT COUNT(f) FROM StandaloneFormEndpointEntity f "
         + "WHERE f.organizationId = :orgId AND f.workflowId IS NOT NULL")
    long countByOrganizationIdStrictAndWorkflowIdIsNotNull(@Param("orgId") String orgId);

    List<StandaloneFormEndpointEntity> findByWorkflowId(UUID workflowId);

    /** Strict-org dedup. */
    @Query("SELECT f FROM StandaloneFormEndpointEntity f "
         + "WHERE f.organizationId = :orgId AND f.sourceNodeId = :sourceNodeId")
    Optional<StandaloneFormEndpointEntity> findByOrganizationIdStrictAndSourceNodeId(
            @Param("orgId") String orgId, @Param("sourceNodeId") String sourceNodeId);

    List<StandaloneFormEndpointEntity> findByWorkflowIdIsNullAndCreatedAtBefore(java.time.Instant cutoff);

    /**
     * Distinct non-null workflow IDs older than {@code ageCutoff} - fed to the stale-FK
     * reaper. See {@code ScheduledExecutionRepository.findDistinctNonNullWorkflowIds}
     * for the race-guard rationale.
     */
    @Query("SELECT DISTINCT f.workflowId FROM StandaloneFormEndpointEntity f " +
           "WHERE f.workflowId IS NOT NULL AND f.createdAt < :ageCutoff")
    Set<UUID> findDistinctNonNullWorkflowIds(@Param("ageCutoff") java.time.Instant ageCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM StandaloneFormEndpointEntity f WHERE f.workflowId IN :workflowIds")
    int deleteByWorkflowIdIn(@Param("workflowIds") Collection<UUID> workflowIds);
}
