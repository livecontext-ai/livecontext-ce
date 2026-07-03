package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
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
public interface AgentTaskRecurrenceRepository extends JpaRepository<AgentTaskRecurrenceEntity, UUID> {

    // Batch A (2026-05-20): the 4 tenant-only finders (findByIdAndTenantId,
    // findByTenantIdAndCreatedByAgentIdOrderByCreatedAtDesc,
    // findByTenantIdAndTargetAgentIdOrderByCreatedAtDesc,
    // findByTenantIdOrderByCreatedAtDesc) were orphans - AgentTaskRecurrenceService
    // already uses org-strict finders exclusively post-Round-7/8 sweep. Deleted
    // to remove the cross-workspace bleed surface.

    /** PR27.2 - strict-org recurrence list. */
    @Query("SELECT r FROM AgentTaskRecurrenceEntity r WHERE r.organizationId = :orgId ORDER BY r.createdAt DESC")
    List<AgentTaskRecurrenceEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(@Param("orgId") String orgId);

    /** PR27.2 - strict-org single fetch. */
    @Query("SELECT r FROM AgentTaskRecurrenceEntity r WHERE r.id = :id AND r.organizationId = :orgId")
    Optional<AgentTaskRecurrenceEntity> findByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    /** Fetches all enabled recurrences whose next_fire_at is past due. */
    @Query("""
        SELECT r FROM AgentTaskRecurrenceEntity r
         WHERE r.enabled = true
           AND r.nextFireAt <= :now
         ORDER BY r.nextFireAt ASC
    """)
    List<AgentTaskRecurrenceEntity> findDue(@Param("now") Instant now);

    /**
     * CAS fire-slot claim: advances the fire tracker only if {@code next_fire_at}
     * is still the value the caller read from {@code findDue}. ShedLock alone is
     * not sufficient against double-fire: with {@code lockAtMostFor} expiry (a
     * tick that runs longer than 55s) or clock skew, a second instance can
     * re-fetch the same due row. Two concurrent claims race on this single
     * conditional UPDATE; exactly one matches, the loser gets 0 rows and must
     * not spawn a task.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE AgentTaskRecurrenceEntity r
           SET r.nextFireAt = :next,
               r.lastFiredAt = :firedAt,
               r.fireCount = r.fireCount + 1
         WHERE r.id = :id
           AND r.nextFireAt = :expected
    """)
    int claimFireSlot(@Param("id") UUID id,
                      @Param("expected") Instant expected,
                      @Param("next") Instant next,
                      @Param("firedAt") Instant firedAt);
}
