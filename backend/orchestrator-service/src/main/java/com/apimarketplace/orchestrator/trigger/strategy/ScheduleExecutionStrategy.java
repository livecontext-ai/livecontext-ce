package com.apimarketplace.orchestrator.trigger.strategy;

import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.springframework.stereotype.Component;

/**
 * Round-7 PR4 strategy for SCHEDULE triggers.
 *
 * <p>Uses {@link ProductionRunResolver.RunSelectionPolicy#LATEST_WAITING_TRIGGER}
 * (strict accumulation pattern) - schedules require a WAITING_TRIGGER run to fire on,
 * COMPLETED runs are deliberate stops per §3.4 COMPLETED semantics.
 */
@Component
public class ScheduleExecutionStrategy implements TriggerExecutionStrategy {

    @Override
    public TriggerType triggerType() {
        return TriggerType.SCHEDULE;
    }

    @Override
    public ProductionRunResolver.RunSelectionPolicy runSelectionPolicy() {
        return ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER;
    }
}
