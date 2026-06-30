package com.apimarketplace.orchestrator.services.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code ReusableTriggerService.resetForNextCycle} when an epoch
 * of an accumulating reusable-trigger run (schedule / webhook / chat / form)
 * ends with {@code hasFailures=true}.
 *
 * <p>This is the per-epoch counterpart of {@link WorkflowRunTerminatedEvent}.
 * The latter only fires when the WHOLE run reaches a terminal status
 * (FAILED/CANCELLED/TIMEOUT/PARTIAL_SUCCESS); reusable-trigger runs cycle
 * {@code RUNNING → WAITING_TRIGGER → RUNNING} indefinitely and never reach
 * those terminal states. Without a per-epoch event, the bell is silent for
 * the dominant production workflow shape - confirmed gap from V172 audits.
 *
 * <p>Carries the same fields as {@code WorkflowRunTerminatedEvent} plus the
 * epoch number, used by {@code NotificationEmitter} to form a per-epoch
 * idempotency key {@code source_id = runId + ":" + epoch}.
 *
 * <p>Spring's {@code @TransactionalEventListener(AFTER_COMMIT)} guarantees
 * the listener runs only after the {@code resetForNextCycle} write commits,
 * so a notification cannot be emitted for an epoch that was rolled back.
 */
public record WorkflowEpochFailedEvent(
        UUID runId,
        UUID workflowId,
        int epoch,
        Integer planVersion,
        String tenantId,
        String runIdPublic,
        Instant endedAt
) {
}
