package com.apimarketplace.orchestrator.trigger.strategy;

import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.TriggerType;

import java.util.UUID;

/**
 * Round-7 PR4: shared contract for the 5 production-trigger dispatchers
 * (schedule, webhook, form, chat, workflow-chain).
 *
 * <p>Each strategy decides:
 * <ol>
 *   <li>Which {@link ProductionRunResolver.RunSelectionPolicy} fits its semantics
 *       (schedule = LATEST_WAITING_TRIGGER strict accumulation; others = BY_PRODUCTION_RUN_ID
 *       O(1) lookup with LATEST_TRUSTED fallback).</li>
 *   <li>How to map a {@link ProductionRunResolver.Outcome} to a {@link DispatchVerdict}.</li>
 *   <li>How to translate a verdict into a per-trigger surface (HTTP code, cron skip, …)
 *       - that surface translation lives in the dispatcher controllers, not here.</li>
 * </ol>
 *
 * <p>This interface deliberately stays minimal: the existing dispatchers
 * ({@code ScheduleExecutorService}, {@code WebhookDispatchService}, …) keep their
 * orchestration logic intact in PR4 - they just adopt the policy + verdict contract
 * via this interface. A full collapse into a single coordinator is reserved for a
 * future PR if dispatcher duplication becomes painful.
 */
public interface TriggerExecutionStrategy {

    /**
     * Trigger type this strategy handles. Used by {@link TriggerDispatchCoordinator}
     * to route incoming dispatches to the right strategy.
     */
    TriggerType triggerType();

    /**
     * Run-selection policy this trigger type uses. Schedules use
     * {@link ProductionRunResolver.RunSelectionPolicy#LATEST_WAITING_TRIGGER} (strict
     * accumulation); webhook/form/chat/workflow-chain use
     * {@link ProductionRunResolver.RunSelectionPolicy#BY_PRODUCTION_RUN_ID} (PR3 FK
     * lookup with TRUSTED fallback).
     */
    ProductionRunResolver.RunSelectionPolicy runSelectionPolicy();

    /**
     * Resolve the verdict for this trigger fire. Default implementation: invoke the
     * resolver with this strategy's policy and translate the outcome via
     * {@link #defaultVerdictFor(ProductionRunResolver.Outcome)}.
     *
     * <p>Implementations may override to add per-trigger checks (credits, idempotency,
     * STICKY chat session, etc.) before calling super.
     */
    default DispatchVerdict resolveVerdict(UUID workflowId,
                                           ProductionRunResolver resolver) {
        ProductionRunResolver.Resolution resolution = resolver.resolve(workflowId, runSelectionPolicy());
        return defaultVerdictFor(resolution.outcome());
    }

    /**
     * Standard mapping from resolver outcome to dispatch verdict. Shared across all
     * strategies so the dispatcher → admin → observability chain stays consistent.
     */
    static DispatchVerdict defaultVerdictFor(ProductionRunResolver.Outcome outcome) {
        return switch (outcome) {
            case FOUND              -> DispatchVerdict.FIRE;
            case NOT_PINNED         -> DispatchVerdict.REFUSE_NOT_PINNED;
            case NO_PRODUCTION_RUN  -> DispatchVerdict.REFUSE_RUN_MISSING;
            case WORKFLOW_MISSING   -> DispatchVerdict.REFUSE_WORKFLOW_MISSING;
        };
    }
}
