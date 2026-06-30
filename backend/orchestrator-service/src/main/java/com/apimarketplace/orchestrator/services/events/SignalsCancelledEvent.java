package com.apimarketplace.orchestrator.services.events;

import java.util.List;

/**
 * Published when one or more {@code USER_APPROVAL} signals are bulk-cancelled
 * (run cancellation, epoch close, blocking cancel on trigger reset, zombie
 * guard). The {@link com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService}
 * cancel paths bypass {@code SignalResolvedEvent} (they skip claim+resolve and
 * bulk-UPDATE directly), so the bell-side cancel-on-resolve listener would
 * leak stale {@code APPROVAL_PENDING} rows without this event.
 *
 * <p>Consumed by {@code NotificationEmitter.onSignalsCancelled} via
 * {@link org.springframework.transaction.event.TransactionalEventListener}
 * (AFTER_COMMIT). The listener resolves tenant via
 * {@code WorkflowRunRepository.findByRunIdPublic} (mirroring the insert listener)
 * and bulk-DELETEs notifications by {@code source_id}.
 *
 * <p>{@code userApprovalSignalIds} is pre-filtered upstream - only USER_APPROVAL
 * signal IDs are passed. {@code List.copyOf} in the canonical constructor
 * makes the event immutable across the AFTER_COMMIT boundary.
 */
public record SignalsCancelledEvent(
        String runIdPublic,
        List<Long> userApprovalSignalIds
) {
    public SignalsCancelledEvent {
        userApprovalSignalIds = List.copyOf(userApprovalSignalIds);
    }
}
