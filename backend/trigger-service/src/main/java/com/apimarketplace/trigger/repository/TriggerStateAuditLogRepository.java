package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.TriggerStateAuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for the v3.5 trigger lifecycle audit log
 * ({@code trigger.trigger_state_audit_log}). Append-only by contract; no
 * update/delete methods exposed here on purpose. Retention is handled by a
 * separate purge job (TODO when retention policy is finalised).
 *
 * <p>The two query shapes match the indexes shipped by V169:
 * <ul>
 *   <li>{@link #findByTriggerIdAndTriggerTypeOrderBySeqDesc} - "show me the
 *       history of trigger X" (covered by
 *       {@code idx_trigger_audit_log_trigger}).</li>
 *   <li>{@link #findByCreatedAtAfterOrderByCreatedAtDesc} - "what
 *       transitioned in the last N hours" (covered by
 *       {@code idx_trigger_audit_log_created_at}).</li>
 * </ul>
 */
@Repository
public interface TriggerStateAuditLogRepository
        extends JpaRepository<TriggerStateAuditLogEntity, Long> {

    /**
     * Lifecycle history of a single trigger, most recent first. Driven by
     * the partial index on {@code (trigger_id, trigger_type, seq DESC)} -
     * stable ordering even when {@code created_at} ties under bursty
     * activity.
     */
    Page<TriggerStateAuditLogEntity> findByTriggerIdAndTriggerTypeOrderBySeqDesc(
            String triggerId, String triggerType, Pageable pageable);

    /**
     * Cross-trigger transitions in a recent window - used by ops dashboards
     * and the suspension-reason recompute logic on reactivate (design v3.5
     * cascading-reverse rule).
     */
    List<TriggerStateAuditLogEntity> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since);

    /**
     * Bulk-delete rows older than the cutoff. Used by the daily retention
     * purge job ({@code TriggerStateAuditLogPurgeService}) to keep the audit
     * table bounded. Returns the number of rows actually deleted.
     *
     * <p>Native bulk DELETE rather than load-then-delete: at retention scale
     * (potentially thousands of rows per day) we don't want JPA to materialise
     * each entity into the persistence context first. The append-only nature
     * of the table means there's no orphan-association concern.
     */
    @Modifying
    @Query("DELETE FROM TriggerStateAuditLogEntity a WHERE a.createdAt < :cutoff")
    int deleteAllByCreatedAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * Batched delete for retention purge. Caps each pass at {@code batch} rows.
     * Caller loops until return &lt; batch. Avoids long lock-table on first run
     * after a retention bump (e.g. 30d → 365d migration creates a 12x backlog).
     *
     * <p>Native query: PG-specific {@code DELETE … WHERE id IN (SELECT … LIMIT)}
     * pattern, since plain JPQL has no DELETE … LIMIT.
     */
    @Modifying
    @Query(value = """
        DELETE FROM trigger.trigger_state_audit_log
         WHERE id IN (
            SELECT id FROM trigger.trigger_state_audit_log
             WHERE created_at < :cutoff
             ORDER BY id
             LIMIT :batch
         )
        """, nativeQuery = true)
    int deleteOldestBatch(@Param("cutoff") Instant cutoff, @Param("batch") int batch);
}
