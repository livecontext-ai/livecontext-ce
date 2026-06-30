package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;

import java.util.Map;

/**
 * Interface for trigger type handlers.
 * Each implementation handles a specific trigger type (datasource, manual, chat, webhook, workflow).
 */
public interface TriggerTypeHandler {

    /**
     * Check if this handler can process the given trigger type.
     *
     * @param triggerType the trigger type to check
     * @return true if this handler can process the trigger type
     */
    boolean canHandle(String triggerType);

    /**
     * Resolve the trigger and return its payload.
     *
     * @param trigger the trigger to resolve
     * @param tenantId the tenant ID
     * @param resolvedInputs resolved inputs from the workflow
     * @return the resolved trigger payload
     */
    Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs);
}
