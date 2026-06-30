package com.apimarketplace.orchestrator.services.transaction;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Phase A2 (archi-refoundation 2026-05-04) - extracts the duplicated
 * {@code if (isSynchronizationActive()) registerSynchronization(...)} pattern
 * found in {@code OrchestrationRecoveryService:331-343} and
 * {@code UnifiedSignalService:277-287}.
 *
 * <p>Both call-sites need to defer a side-effect (publish to Redis, broadcast
 * a workflow event) until AFTER the active database transaction commits, so
 * remote listeners do not observe pre-commit state. Both also need a fallback
 * to run the side-effect inline when the caller is OUTSIDE a transaction
 * (notably async paths post-{@code @TransactionalEventListener(AFTER_COMMIT)}
 * and unit tests that mock out the {@code @Transactional} boundary).
 *
 * <p>Without the {@code isSynchronizationActive()} guard,
 * {@code TransactionSynchronizationManager.registerSynchronization} throws
 * {@code IllegalStateException("Transaction synchronization is not active")},
 * which would crash {@code SnapshotService.markDirty} on every call from an
 * async path (audit B+C v6).
 */
public final class TransactionalHelper {

    private TransactionalHelper() {
        // utility
    }

    /**
     * Run the action AFTER the current transaction commits, OR immediately if
     * no transaction is active. Fail-safe: the action's exceptions during the
     * inline path propagate to the caller; during the deferred path they are
     * swallowed by Spring's afterCommit machinery (consistent with existing
     * patterns) - callers that need exception visibility should run inside
     * a transaction OR add their own logging.
     *
     * @param action side-effect to run; never {@code null}
     */
    public static void runAfterCommitOrNow(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // No active TX - caller is async-post-commit (e.g. SignalResumeService
            // @TransactionalEventListener AFTER_COMMIT or @Async path). The DB write
            // we'd be racing against has already committed upstream, so running
            // inline preserves the read-after-commit invariant.
            action.run();
        }
    }
}
