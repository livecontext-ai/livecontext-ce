package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentTaskClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to the {@code agent_task_claims} append-only log. See
 * {@link AgentTaskClaimEntity} for the architectural rationale.
 */
@Repository
public interface AgentTaskClaimRepository extends JpaRepository<AgentTaskClaimEntity, Long> {

    /**
     * "What task is currently held by this run?" - most recent {@code claimed} event whose
     * {@code execution_id} hasn't been countered by a subsequent {@code released} event. Used by
     * {@code AgentObservabilityService.doRecordFromRequest} to populate the denormalised
     * {@code agent_executions.task_id} at end-of-run when the workflow caller didn't already
     * supply it.
     */
    @Query("""
        SELECT c FROM AgentTaskClaimEntity c
        WHERE c.executionId = :executionId
          AND c.event = 'claimed'
          AND NOT EXISTS (
            SELECT 1 FROM AgentTaskClaimEntity r
            WHERE r.executionId = c.executionId
              AND r.taskId = c.taskId
              AND r.event = 'released'
              AND r.at > c.at)
        ORDER BY c.at DESC
        """)
    List<AgentTaskClaimEntity> findActiveClaimsForExecution(@Param("executionId") UUID executionId);

    default Optional<AgentTaskClaimEntity> findLatestActiveClaim(UUID executionId) {
        List<AgentTaskClaimEntity> active = findActiveClaimsForExecution(executionId);
        return active.isEmpty() ? Optional.empty() : Optional.of(active.get(0));
    }

    /** Org-scoped batch history for the Task detail page (sequence of every claim/release). */
    @Query("""
        SELECT c FROM AgentTaskClaimEntity c
        WHERE c.taskId = :taskId AND c.organizationId = :organizationId
        ORDER BY c.at DESC
        """)
    List<AgentTaskClaimEntity> findByTaskIdOrgScoped(@Param("taskId") UUID taskId,
                                                     @Param("organizationId") String organizationId);
}
