package com.apimarketplace.orchestrator.services.transaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase A2 (archi-refoundation 2026-05-04) - regression for the
 * extracted {@code runAfterCommitOrNow} pattern. Validates the two-mode
 * behavior that the duplicated impls in {@code OrchestrationRecoveryService}
 * and {@code UnifiedSignalService} relied on:
 * <ul>
 *   <li>In-tx: defer until afterCommit</li>
 *   <li>No-tx: run inline (no IllegalStateException)</li>
 * </ul>
 */
@DisplayName("TransactionalHelper")
class TransactionalHelperTest {

    @AfterEach
    void clearSyncManager() {
        // Defensive: tests that initSynchronization should clear afterwards. If
        // a test fails mid-flight we do it again here to avoid bleed.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Runs the action inline when no transaction is active (no IllegalStateException)")
    void runsInlineWhenNoTxActive() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        AtomicInteger counter = new AtomicInteger(0);
        TransactionalHelper.runAfterCommitOrNow(counter::incrementAndGet);

        assertEquals(1, counter.get(),
                "Action must run inline when no TX is active - without this guard, "
              + "TransactionSynchronizationManager.registerSynchronization throws "
              + "IllegalStateException, breaking SnapshotService.markDirty on every "
              + "@Async post-commit path (audit B+C v6).");
    }

    @Test
    @DisplayName("Defers the action until afterCommit when a transaction is active")
    void defersWhenTxActive() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger counter = new AtomicInteger(0);

        TransactionalHelper.runAfterCommitOrNow(counter::incrementAndGet);

        // Action should NOT have run yet - it's queued for afterCommit
        assertEquals(0, counter.get(), "Action must be deferred when TX is active");

        // Simulate commit by walking the registered synchronizations
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        assertEquals(1, counter.get(), "Action must run after afterCommit fires");
    }

    @Test
    @DisplayName("Null action is a no-op (defensive guard)")
    void nullActionIsNoOp() {
        // Should not throw
        TransactionalHelper.runAfterCommitOrNow(null);
    }
}
