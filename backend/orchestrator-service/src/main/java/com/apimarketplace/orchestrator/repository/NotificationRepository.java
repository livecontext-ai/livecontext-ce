package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /**
     * Chunked retention purge - caller invokes in a loop until {@code 0} returned.
     * Bounded {@code LIMIT} avoids long-held row locks on hot-tenant tables
     * (uses a CTE because Postgres requires the LIMIT to be inside the SELECT).
     */
    @Modifying
    @Query(value = "DELETE FROM orchestrator.notifications " +
            "WHERE id IN (" +
            "  SELECT id FROM orchestrator.notifications " +
            "  WHERE occurred_at < :cutoff " +
            "  ORDER BY occurred_at " +
            "  LIMIT :batchSize)",
            nativeQuery = true)
    int deleteOlderThanChunked(@Param("cutoff") Instant cutoff,
                               @Param("batchSize") int batchSize);

    /**
     * CASA LC-066: {@code [id, subject_id]} of AGENT_TASK notifications that still carry the
     * task's name ({@code payload.subjectName}), after {@code afterId}, by id (keyset).
     */
    @Query(value = "SELECT id, subject_id FROM orchestrator.notifications "
            + "WHERE subject_type = 'AGENT_TASK' AND payload ->> 'subjectName' IS NOT NULL AND id > :afterId "
            + "ORDER BY id LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findNamedAgentTaskNotifications(@Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * CASA LC-066: withholds the task's name on these tasks' AGENT_TASK notifications, the form
     * the emitter writes for a RESTRICTED task today ({@code restricted: true}, no
     * {@code subjectName}): the bell and the messages then show their generic task name.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE orchestrator.notifications "
            + "SET payload = (payload - 'subjectName') || jsonb_build_object('restricted', true) "
            + "WHERE subject_type = 'AGENT_TASK' AND subject_id IN (:taskIds) "
            + "AND payload ->> 'subjectName' IS NOT NULL",
            nativeQuery = true)
    int withholdAgentTaskNames(@Param("taskIds") Collection<UUID> taskIds);
}
