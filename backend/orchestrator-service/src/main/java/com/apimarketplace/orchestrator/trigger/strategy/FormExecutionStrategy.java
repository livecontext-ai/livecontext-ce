package com.apimarketplace.orchestrator.trigger.strategy;

import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.springframework.stereotype.Component;

/**
 * Round-7 PR4 strategy for FORM triggers.
 *
 * <p>Uses {@link ProductionRunResolver.RunSelectionPolicy#BY_PRODUCTION_RUN_ID} -
 * same fast-path as webhook.
 */
@Component
public class FormExecutionStrategy implements TriggerExecutionStrategy {

    @Override
    public TriggerType triggerType() {
        return TriggerType.FORM;
    }

    @Override
    public ProductionRunResolver.RunSelectionPolicy runSelectionPolicy() {
        return ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID;
    }
}
