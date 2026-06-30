package com.apimarketplace.orchestrator.trigger.strategy;

import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.springframework.stereotype.Component;

/**
 * Round-7 PR4 strategy for CHAT triggers.
 *
 * <p>Uses {@link ProductionRunResolver.RunSelectionPolicy#BY_PRODUCTION_RUN_ID} for
 * the standard new-session path. Per-session STICKY/MIGRATE policy (§3.6) is enforced
 * by {@code ChatDispatchService} on top of this strategy's verdict - when STICKY and
 * the session was created against a previous pin, the dispatcher overrides the
 * resolver's BY_PRODUCTION_RUN_ID lookup with the session's snapshot run id.
 */
@Component
public class ChatExecutionStrategy implements TriggerExecutionStrategy {

    @Override
    public TriggerType triggerType() {
        return TriggerType.CHAT;
    }

    @Override
    public ProductionRunResolver.RunSelectionPolicy runSelectionPolicy() {
        return ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID;
    }
}
