package com.apimarketplace.agent.service.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Safety-net startup sweep for leaked cascade reservations (§4.6 AGENT_BUDGET_HIERARCHY.md).
 *
 * <p>Sub-agent execution is synchronous: the outer {@code try/catch} in
 * {@code SubAgentExecutionHandler} guarantees that every spawn either (a) transfers settle
 * ownership to {@code AgentObservabilityService.recordFromRequest} BEFORE returning, or
 * (b) refunds the reservation in the catch on any throw. The only way {@code credits_reserved}
 * can outlive a JVM is if the process crashes between {@code tryReserveChain} and either
 * exit path - an OOM, {@code kill -9}, or container restart.
 *
 * <p>Because agent-service is stateless per process (no in-flight execution survives a
 * restart), every non-zero reservation at boot is definitionally leaked. This listener
 * runs once on {@link ApplicationReadyEvent} and clears them all in a single UPDATE.
 *
 * <p>Ordering: runs at {@code ApplicationReadyEvent}, AFTER Flyway and AFTER the event loop
 * is ready to accept requests - we do NOT want to block startup on a long UPDATE, but we
 * DO want to clear reservations before any new sub-agent spawn computes
 * {@code getMinFreeAcrossChain} (which would otherwise see stale reserved values and
 * under-size fresh reservations). In practice the UPDATE is bounded by the count of
 * non-zero rows (filtered by {@code WHERE credits_reserved > 0}) and completes in tens of
 * milliseconds.
 */
@Component
public class ReservationStartupCleanup {

    private static final Logger log = LoggerFactory.getLogger(ReservationStartupCleanup.class);

    private final BudgetReservationService budgetReservationService;

    public ReservationStartupCleanup(BudgetReservationService budgetReservationService) {
        this.budgetReservationService = budgetReservationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void clearLeakedReservationsOnStartup() {
        try {
            int cleared = budgetReservationService.clearAllReservations();
            if (cleared > 0) {
                log.warn("[BUDGET-CASCADE] Startup cleanup cleared {} leaked reservation(s). " +
                    "These are leftover from a prior crash - not a runtime leak.", cleared);
            } else {
                log.info("[BUDGET-CASCADE] Startup cleanup: no leaked reservations.");
            }
        } catch (Exception e) {
            // Never fail application startup over a reservation cleanup. Log loudly so the
            // operator notices and can run the UPDATE by hand if needed.
            log.error("[BUDGET-CASCADE] Failed to clear leaked reservations on startup: {}",
                e.getMessage(), e);
        }
    }
}
