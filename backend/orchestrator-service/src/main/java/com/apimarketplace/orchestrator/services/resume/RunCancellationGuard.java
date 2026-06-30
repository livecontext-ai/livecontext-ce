package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Single source of truth for "should I drop this late piece of work because the run
 * is stopped/terminal?". Used by async-agent paths (delivery + recovery) that race
 * against {@code stopWorkflow()} and {@code cancelWorkflow()}.
 *
 * <p><b>Why this exists.</b> Both {@code stopWorkflow()} and
 * {@code resetForNextCycle()} (the normal between-fires transition for reusable
 * triggers) put the run in {@link RunStatus#WAITING_TRIGGER}. Status alone cannot
 * distinguish them, but only {@code stopWorkflow()} (and {@code cancelWorkflow()})
 * sets the agent cancel signal in Redis via
 * {@link WorkflowRedisPublisher#setAgentCancelSignal(String)}. We use that signal
 * as the discriminator so that late results from a previous epoch of a still-alive
 * reusable-trigger run can drive their successors instead of being silently
 * dropped.
 *
 * <p>This was responsible for the prod incident on Gmail Auto-Labeler
 * (run da7994c7) where 5 async classify items persisted but never fanned out to
 * their {@code mcp:apply_*} successors.
 */
@Component
public class RunCancellationGuard {

    private static final Logger logger = LoggerFactory.getLogger(RunCancellationGuard.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowRedisPublisher workflowRedisPublisher;

    public RunCancellationGuard(
            WorkflowRunRepository runRepository,
            @Autowired(required = false) WorkflowRedisPublisher workflowRedisPublisher) {
        this.runRepository = runRepository;
        this.workflowRedisPublisher = workflowRedisPublisher;
    }

    /**
     * Return {@code true} when the run should be considered stopped/terminal for the
     * purpose of dropping late async work.
     *
     * <p>Decision matrix:
     * <ul>
     *   <li>Run not found → {@code true} (treat as terminal, conservative).</li>
     *   <li>{@code status.isTerminal()} (CANCELLED / FAILED / COMPLETED / TIMEOUT / …)
     *       → {@code true}.</li>
     *   <li>{@code status == WAITING_TRIGGER}: check the agent cancel signal in Redis.
     *       Set ⇒ stopped via {@code stopWorkflow()} ⇒ {@code true}; not set ⇒ alive
     *       between fires ⇒ {@code false}.</li>
     *   <li>Anything else (RUNNING / PAUSED) → {@code false}.</li>
     * </ul>
     *
     * <p>Fail-open on exceptions: if we can't check, allow traversal. The async
     * delivery path is idempotent (claim-before-process at persistence layer), so
     * a stray traversal on a terminal run is safer than a dropped traversal on an
     * alive one.
     */
    public boolean isRunStoppedOrTerminal(String runId) {
        try {
            Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return true;
            }
            RunStatus status = runOpt.get().getStatus();
            if (status.isTerminal()) {
                return true;
            }
            // WAITING_TRIGGER is ambiguous: stopWorkflow() vs resetForNextCycle().
            // Disambiguate via the agent cancel signal - only stopWorkflow/cancelWorkflow
            // sets it. Fail-open if the publisher is unavailable (treat as alive).
            if (status == RunStatus.WAITING_TRIGGER) {
                return workflowRedisPublisher != null
                        && workflowRedisPublisher.isAgentCancelSignalSet(runId);
            }
            return false;
        } catch (Exception e) {
            logger.warn("[RunCancellationGuard] Status check failed for runId={}: {} - allowing traversal (fail-open)",
                    runId, e.getMessage());
            return false;
        }
    }
}
