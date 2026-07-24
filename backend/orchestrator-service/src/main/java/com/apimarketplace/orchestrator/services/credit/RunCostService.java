package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Accumulates the cost of a workflow run from its agent executions.
 *
 * <p>Agent executions are the ONLY thing that consumes credits inside a run.
 * When agent-service settles an execution's credits it notifies the orchestrator
 * (see {@code InternalRunCostController}); this service records the delta on the
 * run and broadcasts the fresh figures to the run-mode UI over the WS event bus.
 *
 * <p>Cost is stored in credits (1 credit = $0.001). The total is kept across ALL
 * epochs of the run, with a per-epoch breakdown so the RunInfo panel can show
 * both "cost of this run" and per-epoch cost.
 */
@Service
public class RunCostService {

    private static final Logger log = LoggerFactory.getLogger(RunCostService.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowEventPublisher eventPublisher;

    public RunCostService(WorkflowRunRepository runRepository,
                          WorkflowEventPublisher eventPublisher) {
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Record a settled agent cost on a run and emit the fresh totals.
     *
     * <p>Best-effort and idempotent-safe: the increment is a pure monotonic add,
     * so a retried notification only over-counts if the caller sends the same
     * delta twice - callers send exactly once per settled execution. A zero (or
     * negative) delta is a no-op. Never throws to the caller: a cost-tracking
     * failure must not break the notification endpoint.
     *
     * @param runIdPublic public run id (also the WS channel key)
     * @param orgId       run's organization id, or null for personal scope
     * @param epoch       epoch the agent executed in
     * @param credits     credits consumed by this agent execution
     */
    @Transactional
    public void recordAgentCost(String runIdPublic, String orgId, int epoch, BigDecimal credits) {
        if (runIdPublic == null || runIdPublic.isBlank()) {
            return;
        }
        if (credits == null || credits.signum() <= 0) {
            // Nothing consumed (0-token/cached-only or a rejected consumption):
            // no total change, nothing to broadcast.
            return;
        }

        String epochKey = Integer.toString(Math.max(epoch, 0));
        int rows;
        try {
            rows = runRepository.incrementRunCost(runIdPublic, orgId, epochKey, credits);
        } catch (Exception e) {
            log.warn("[RunCost] increment failed for runId={} epoch={} credits={}: {}",
                    runIdPublic, epoch, credits, e.getMessage());
            return;
        }
        if (rows == 0) {
            // Run deleted between execution and settle, or a cross-scope
            // notification (orgId mismatch). Either way there is nothing to
            // update - do not emit a stale event.
            log.debug("[RunCost] no run matched runId={} orgId={} (deleted or scope mismatch)", runIdPublic, orgId);
            return;
        }

        // Read back the fresh figures within the same tx so the event reflects
        // this increment (and any concurrent one that committed meanwhile).
        BigDecimal total = runRepository.findCostCreditsByRunIdPublic(runIdPublic).orElse(BigDecimal.ZERO);
        BigDecimal epochCost = runRepository.findEpochCostByRunIdPublic(runIdPublic, epochKey).orElse(BigDecimal.ZERO);
        BigDecimal budget = runRepository.findWorkflowBudgetByRunIdPublic(runIdPublic).orElse(null);

        try {
            eventPublisher.emitRunCost(runIdPublic, epoch, epochCost, total, budget);
        } catch (Exception e) {
            log.warn("[RunCost] emitRunCost failed for runId={}: {}", runIdPublic, e.getMessage());
        }
        log.debug("[RunCost] runId={} epoch={} +{} -> total={} (budget={})",
                runIdPublic, epoch, credits, total, budget);
    }
}
