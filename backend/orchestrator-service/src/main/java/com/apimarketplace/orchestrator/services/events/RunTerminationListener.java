package com.apimarketplace.orchestrator.services.events;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Round-7 redesign (PR3): observes {@link WorkflowRunTerminatedEvent} and re-arms the
 * workflow's {@code production_run_id} when the terminated run was the production
 * run-of-record.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} ensures the listener fires only
 * after the run-status write commits - so {@code WorkflowPinService.rearm} never
 * observes a not-yet-persisted state.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Terminated run is NOT the workflow's {@code production_run_id} → no-op.</li>
 *   <li>Terminated run IS the production run, status is COMPLETED → no rearm needed
 *       (the next pin event or manual run will re-point production_run_id).</li>
 *   <li>Terminated run IS the production run, status is CANCELLED/TIMEOUT/FAILED/
 *       PARTIAL_SUCCESS → invoke {@code rearm}, which picks the most recent TRUSTED
 *       run at the pinned version (or clears the column if none survives).</li>
 * </ul>
 *
 * <p>Failure isolation: any exception is logged + swallowed so a faulty rearm cannot
 * break the run-completion write that already committed.
 */
@Component
public class RunTerminationListener {

    private static final Logger logger = LoggerFactory.getLogger(RunTerminationListener.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowPinService pinService;

    public RunTerminationListener(WorkflowRepository workflowRepository,
                                  WorkflowPinService pinService) {
        this.workflowRepository = workflowRepository;
        this.pinService = pinService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunTerminated(WorkflowRunTerminatedEvent event) {
        try {
            handle(event);
        } catch (Exception e) {
            // Never throw - the originating transaction has already committed and any
            // exception here would surface as an unhandled error in the publisher
            // (Spring logs but doesn't propagate AFTER_COMMIT failures, but we log
            // explicitly with the run context for triage).
            logger.warn("[RunTerminationListener] failed for run {} (workflow {}): {}",
                    event.runId(), event.workflowId(), e.getMessage(), e);
        }
    }

    private void handle(WorkflowRunTerminatedEvent event) {
        UUID workflowId = event.workflowId();
        UUID runId = event.runId();
        RunStatus status = event.status();

        if (workflowId == null || runId == null || status == null) {
            return;
        }

        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            return;
        }
        WorkflowEntity workflow = workflowOpt.get();

        // Only act when the terminated run was the production run-of-record.
        UUID currentProductionRunId = workflow.getProductionRunId();
        if (currentProductionRunId == null || !currentProductionRunId.equals(runId)) {
            return;
        }

        // COMPLETED runs don't need rearm - see the project docs
        // "COMPLETED semantics": accumulating workflows in steady state cycle
        // WAITING_TRIGGER → RUNNING → WAITING_TRIGGER, so COMPLETED on the production
        // run means a deliberate stop. The dispatcher returns REFUSE_RUN_TERMINAL on
        // the next fire; admin must explicitly /rearm to start a new accumulation.
        if (status == RunStatus.COMPLETED) {
            logger.info("[RunTerminationListener] workflow {} production run {} COMPLETED - " +
                    "no auto-rearm (deliberate stop semantics)", workflowId, runId);
            return;
        }

        // Terminal non-COMPLETED → rearm. PinService picks the next TRUSTED run at the
        // pinned version (or sets production_run_id=NULL if none survives).
        boolean rearmed = pinService.rearm(workflowId);
        logger.info("[RunTerminationListener] workflow {} production run {} terminated " +
                "({}) → rearm result: production_run_id {}",
                workflowId, runId, status, rearmed ? "updated" : "cleared (no trusted run)");
    }
}
