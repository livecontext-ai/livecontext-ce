package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.PendingSignalEntity;
import com.apimarketplace.orchestrator.domain.PendingSignalEntity.SignalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for pending and pre-approved signals in step-by-step mode.
 * Replaces Redis cache for persistent step-by-step state.
 */
@Repository
public interface PendingSignalRepository extends JpaRepository<PendingSignalEntity, Long> {

    // ========================================================================
    // FIND METHODS
    // ========================================================================

    /**
     * Find a specific signal by run, item, node, and type.
     */
    Optional<PendingSignalEntity> findByRunIdAndItemIdAndNodeIdAndSignalType(
            String runId, String itemId, String nodeId, SignalType signalType);

    /**
     * Find all signals for a run.
     */
    List<PendingSignalEntity> findByRunId(String runId);

    /**
     * Find all pending signals for a run.
     */
    List<PendingSignalEntity> findByRunIdAndSignalType(String runId, SignalType signalType);

    /**
     * Find all signals for a specific node in a run.
     */
    List<PendingSignalEntity> findByRunIdAndNodeId(String runId, String nodeId);

    /**
     * Find pending signal for a specific run, item, and node.
     */
    default Optional<PendingSignalEntity> findPending(String runId, String itemId, String nodeId) {
        return findByRunIdAndItemIdAndNodeIdAndSignalType(runId, itemId, nodeId, SignalType.PENDING);
    }

    /**
     * Find pre-approved signal for a specific run, item, and node.
     */
    default Optional<PendingSignalEntity> findPreApproved(String runId, String itemId, String nodeId) {
        return findByRunIdAndItemIdAndNodeIdAndSignalType(runId, itemId, nodeId, SignalType.PRE_APPROVED);
    }

    // ========================================================================
    // EXISTS METHODS
    // ========================================================================

    /**
     * Check if a signal exists.
     */
    boolean existsByRunIdAndItemIdAndNodeIdAndSignalType(
            String runId, String itemId, String nodeId, SignalType signalType);

    /**
     * Check if pending signal exists.
     */
    default boolean existsPending(String runId, String itemId, String nodeId) {
        return existsByRunIdAndItemIdAndNodeIdAndSignalType(runId, itemId, nodeId, SignalType.PENDING);
    }

    /**
     * Check if pre-approved signal exists.
     */
    default boolean existsPreApproved(String runId, String itemId, String nodeId) {
        return existsByRunIdAndItemIdAndNodeIdAndSignalType(runId, itemId, nodeId, SignalType.PRE_APPROVED);
    }

    // ========================================================================
    // AGGREGATE QUERIES
    // ========================================================================

    /**
     * Get all pending node IDs for a run.
     */
    @Query("SELECT DISTINCT p.nodeId FROM PendingSignalEntity p WHERE p.runId = :runId AND p.signalType = 'PENDING'")
    Set<String> findPendingNodeIds(@Param("runId") String runId);

    /**
     * Get all pre-approved node IDs for a run.
     */
    @Query("SELECT DISTINCT p.nodeId FROM PendingSignalEntity p WHERE p.runId = :runId AND p.signalType = 'PRE_APPROVED'")
    Set<String> findPreApprovedNodeIds(@Param("runId") String runId);

    /**
     * Count pending signals for a run.
     */
    long countByRunIdAndSignalType(String runId, SignalType signalType);

    /**
     * Find all pending item IDs for a specific node in a run.
     */
    @Query("SELECT p.itemId FROM PendingSignalEntity p WHERE p.runId = :runId AND p.nodeId = :nodeId AND p.signalType = 'PENDING'")
    Set<String> findPendingItemIdsForNode(@Param("runId") String runId, @Param("nodeId") String nodeId);

    // ========================================================================
    // DELETE METHODS
    // ========================================================================

    /**
     * Delete a specific signal.
     */
    @Modifying
    void deleteByRunIdAndItemIdAndNodeIdAndSignalType(
            String runId, String itemId, String nodeId, SignalType signalType);

    /**
     * Delete pending signal.
     */
    @Modifying
    default void deletePending(String runId, String itemId, String nodeId) {
        deleteByRunIdAndItemIdAndNodeIdAndSignalType(runId, itemId, nodeId, SignalType.PENDING);
    }

    /**
     * Delete pre-approved signal.
     */
    @Modifying
    default void deletePreApproved(String runId, String itemId, String nodeId) {
        deleteByRunIdAndItemIdAndNodeIdAndSignalType(runId, itemId, nodeId, SignalType.PRE_APPROVED);
    }

    /**
     * Delete all signals for a run (cleanup).
     */
    @Modifying
    void deleteByRunId(String runId);

    /**
     * Delete all signals for the given run IDs.
     * Used during workflow deletion to clean up orphaned signal data.
     */
    @Modifying
    @Query("DELETE FROM PendingSignalEntity p WHERE p.runId IN :runIds")
    int deleteByRunIdIn(@Param("runIds") List<String> runIds);

    /**
     * Delete all signals for a node in a run.
     */
    @Modifying
    void deleteByRunIdAndNodeId(String runId, String nodeId);

    /**
     * Delete expired pre-approved signals.
     */
    @Modifying
    @Query("DELETE FROM PendingSignalEntity p WHERE p.signalType = 'PRE_APPROVED' AND p.expiresAt IS NOT NULL AND p.expiresAt < :now")
    int deleteExpiredPreApprovals(@Param("now") Instant now);
}
