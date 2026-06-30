package com.apimarketplace.orchestrator.services.events;

import java.time.Instant;

/**
 * Published when a {@code USER_APPROVAL} signal is registered for a workflow run.
 *
 * <p>Consumed by {@code NotificationEmitter.onApprovalPending} via
 * {@link org.springframework.transaction.event.TransactionalEventListener}
 * (AFTER_COMMIT) to write one {@code APPROVAL_PENDING} notification row.
 *
 * <p>Carries the public-form {@code runId} (matches {@code signal_wait.run_id};
 * the listener resolves the workflow run via
 * {@code WorkflowRunRepository.findByRunIdPublic} for filter parity with the
 * {@code RUN_FAILED} listener). {@code signalWaitId} is the BIGINT primary key
 * used as {@code source_id} so the {@code (tenant_id, category, source_id)}
 * unique index dedupes multi-replica races.
 *
 * <p>{@code createdAt} is the signal-registration time (used as
 * {@code occurred_at}); {@code expiresAt} is nullable.
 */
public record WorkflowApprovalPendingEvent(
        String runIdPublic,
        long signalWaitId,
        int epoch,
        Instant createdAt,
        Instant expiresAt
) { }
