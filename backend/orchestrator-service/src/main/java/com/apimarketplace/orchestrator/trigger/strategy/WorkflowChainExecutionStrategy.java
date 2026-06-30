package com.apimarketplace.orchestrator.trigger.strategy;

import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.springframework.stereotype.Component;

/**
 * Round-7 PR4 strategy for WORKFLOW chained triggers (one workflow's completion
 * fires another).
 *
 * <p>Uses {@link ProductionRunResolver.RunSelectionPolicy#BY_PRODUCTION_RUN_ID} for
 * the same O(1) FK fast-path as webhook/form/chat. Trust comes from the parent
 * workflow's lifecycle, not from a strict accumulation requirement.
 */
@Component
public class WorkflowChainExecutionStrategy implements TriggerExecutionStrategy {

    @Override
    public TriggerType triggerType() {
        return TriggerType.WORKFLOW;
    }

    @Override
    public ProductionRunResolver.RunSelectionPolicy runSelectionPolicy() {
        return ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID;
    }
}
