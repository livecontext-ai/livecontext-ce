package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves error triggers (fired when a watched workflow fails) when
 * {@link com.apimarketplace.orchestrator.services.TriggerResolverService} is asked to load
 * trigger items by
 * {@link com.apimarketplace.orchestrator.execution.v2.services.V2TriggerLoadingService#loadTriggerItemsIfNeeded}.
 *
 * <p>Same defect and same contract as {@link FormTriggerResolver} and
 * {@link ScheduleTriggerResolver}: the failure event is delivered by the fire path, which never
 * calls {@code resolveTrigger}; the loader used to throw {@code "Unsupported trigger type:
 * error"}, log it at ERROR and cache zero items. This handler keeps the zero items
 * ({@code data: []}, {@code count: 0}) and removes the error. No output field is added.
 */
@Slf4j
@Component
public class ErrorTriggerResolver implements TriggerTypeHandler {

    static final String TYPE = "error";

    @Override
    public boolean canHandle(String triggerType) {
        return TYPE.equalsIgnoreCase(triggerType);
    }

    @Override
    public Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.debug("Resolving error trigger: {} for tenant: {}", trigger.id(), tenantId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("triggerId", trigger.id());
        payload.put("type", TYPE);
        payload.put("status", "success");
        payload.put("source", TYPE);
        payload.put("data", List.of());
        payload.put("count", 0);
        return payload;
    }
}
