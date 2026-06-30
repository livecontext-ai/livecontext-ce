package com.apimarketplace.orchestrator.services.events;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;

import java.util.UUID;

/**
 * Fired by {@code WorkflowRunPersistenceService.recordWorkflowCompletion} after a
 * workflow run row reaches a terminal status (COMPLETED, FAILED, CANCELLED, TIMEOUT,
 * PARTIAL_SUCCESS). Round-7 redesign (PR3): consumed by {@code RunTerminationListener}
 * to invoke {@code WorkflowPinService.rearm} when the terminated run was the workflow's
 * production_run_id.
 *
 * <p>Spring's {@code @TransactionalEventListener(phase = AFTER_COMMIT)} guarantees the
 * listener runs only when the run-status write commits - so we never rearm against a
 * not-yet-persisted state.
 */
public record WorkflowRunTerminatedEvent(
        UUID runId,
        UUID workflowId,
        RunStatus status,
        Integer planVersion
) {
}
