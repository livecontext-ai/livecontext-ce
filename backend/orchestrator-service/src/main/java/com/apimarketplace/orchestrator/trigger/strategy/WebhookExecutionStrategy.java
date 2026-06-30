package com.apimarketplace.orchestrator.trigger.strategy;

import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.springframework.stereotype.Component;

/**
 * Round-7 PR4 strategy for WEBHOOK triggers.
 *
 * <p>Uses {@link ProductionRunResolver.RunSelectionPolicy#BY_PRODUCTION_RUN_ID} for
 * an O(1) lookup via {@code workflows.production_run_id} (PR3 FK). Falls back to
 * LATEST_TRUSTED if the FK is NULL.
 */
@Component
public class WebhookExecutionStrategy implements TriggerExecutionStrategy {

    @Override
    public TriggerType triggerType() {
        return TriggerType.WEBHOOK;
    }

    @Override
    public ProductionRunResolver.RunSelectionPolicy runSelectionPolicy() {
        return ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID;
    }
}
