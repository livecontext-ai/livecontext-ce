package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.WorkflowRunPricingPin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRunPricingPinRepository extends JpaRepository<WorkflowRunPricingPin, Long> {

    Optional<WorkflowRunPricingPin> findByRunIdAndPlatformCredentialId(
            String runId, Long platformCredentialId);

    /**
     * V148+ scope-aware lookup. Replaces {@link #findByRunIdAndPlatformCredentialId}
     * for new code; the legacy method is kept until post-soak migration drops
     * {@code run_id} from the table. Filters {@code cancelled=false} so a re-pin
     * after cancellation does not return the stale ghost.
     */
    Optional<WorkflowRunPricingPin> findByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
            String scopeKind, String scopeId, Long platformCredentialId);

    /**
     * Existence check for the delinquent in-flight bypass branch in
     * {@code CreditService.tryReserveMarkup}. Lookup-only - never creates a pin.
     */
    boolean existsByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
            String scopeKind, String scopeId, Long platformCredentialId);

    List<WorkflowRunPricingPin> findByScopeKindAndScopeIdAndCancelledFalse(
            String scopeKind, String scopeId);

    List<WorkflowRunPricingPin> findByRunId(String runId);

    /**
     * Live (non-cancelled) pins for a user. Used by
     * {@code cancel-active-pins} admin endpoint to stop markup billing mid-run.
     */
    List<WorkflowRunPricingPin> findByUserIdAndCancelledFalse(Long userId);

    /**
     * Batch-cancel every pin for a given run.
     * {@code @Modifying(clearAutomatically = true)} so subsequent reads see cancelled=true.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WorkflowRunPricingPin p SET p.cancelled = true, p.cancelledAt = :cancelledAt " +
           "WHERE p.runId = :runId AND p.cancelled = false")
    int cancelByRunId(@Param("runId") String runId, @Param("cancelledAt") Instant cancelledAt);

    /**
     * V148+ scope-aware cancel. Used by {@code cancelPinsForScope} on workflow
     * terminal (scope=RUN) and on stream end-of-conversation (scope=STREAM).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WorkflowRunPricingPin p SET p.cancelled = true, p.cancelledAt = :cancelledAt " +
           "WHERE p.scopeKind = :scopeKind AND p.scopeId = :scopeId AND p.cancelled = false")
    int cancelByScope(@Param("scopeKind") String scopeKind,
                       @Param("scopeId") String scopeId,
                       @Param("cancelledAt") Instant cancelledAt);

    /**
     * STREAM-pin TTL sweeper input. Returns idle (last_used_at &lt; cutoff) STREAM
     * pins. Skips RUN pins (they are cancelled at run-terminal regardless of TTL)
     * and pins still referenced by an active reservation row in the credit ledger.
     *
     * <p>The {@code NOT EXISTS} subquery is the safety net for chat sessions that
     * paused right at the 30-day boundary: even if {@code last_used_at} qualifies,
     * we don't sweep a pin that has an unexpired RESERVE pointing at it.
     */
    @Query(value = "SELECT p.* FROM auth.workflow_run_pricing_pin p " +
                   "WHERE p.scope_kind = 'STREAM' " +
                   "  AND p.last_used_at < :cutoff " +
                   "  AND NOT EXISTS (" +
                   "    SELECT 1 FROM auth.credit_ledger cl " +
                   "    WHERE cl.pin_id = p.id " +
                   "      AND cl.source_type = 'PLATFORM_MARKUP_RESERVE' " +
                   "      AND cl.created_at > :recentCutoff" +
                   "  ) " +
                   "ORDER BY p.last_used_at ASC " +
                   "LIMIT :limit",
           nativeQuery = true)
    List<WorkflowRunPricingPin> findIdleStreamPins(@Param("cutoff") Instant cutoff,
                                                     @Param("recentCutoff") Instant recentCutoff,
                                                     @Param("limit") int limit);

    /**
     * Sweeper input: every pin (cancelled or not) older than the cutoff.
     * Paired with a bulk delete; see {@code MarkupCleanupService}.
     */
    @Query("SELECT p FROM WorkflowRunPricingPin p WHERE p.createdAt < :cutoff")
    List<WorkflowRunPricingPin> findSweepable(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("DELETE FROM WorkflowRunPricingPin p WHERE p.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
