package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves form triggers when {@link com.apimarketplace.orchestrator.services.TriggerResolverService}
 * is asked to load trigger items, i.e. by
 * {@link com.apimarketplace.orchestrator.execution.v2.services.V2TriggerLoadingService#loadTriggerItemsIfNeeded}
 * on a step-by-step run once the form trigger has been received.
 *
 * <p><b>Why this handler exists.</b> Same defect as the one {@link ScheduleTriggerResolver} was
 * added for: a form trigger is FIRED by its submission endpoint through
 * {@code ReusableTriggerService}, which stamps the submitted values itself and never calls
 * {@code TriggerResolverService.resolveTrigger}. The step-by-step loader, however, called
 * {@code resolveTrigger} for every trigger type, and with no handler registered for
 * {@code form} it threw {@code "Unsupported trigger type: form"}, logged at ERROR with a stack
 * trace, and cached an empty item list.
 *
 * <p>The payload keeps that outcome (zero items) and drops the error: {@code data: []} plus
 * the explicit {@code count: 0} marker that
 * {@code V2TriggerLoadingService.extractTriggerItems} honours as "do not wrap the whole payload
 * as one phantom item". A form trigger has no items to iterate: its submitted values
 * ({@code form_data} and the per-field outputs documented for the node) belong to the fire,
 * not to this loader, so no output field is invented here. {@code trigger.params()} is NOT
 * merged in, unlike the schedule resolver: a form's params are field DEFINITIONS (labels,
 * types, options), and copying them next to submitted values would read as if they were data.
 */
@Slf4j
@Component
public class FormTriggerResolver implements TriggerTypeHandler {

    static final String TYPE = "form";

    @Override
    public boolean canHandle(String triggerType) {
        return TYPE.equalsIgnoreCase(triggerType);
    }

    @Override
    public Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.debug("Resolving form trigger: {} for tenant: {}", trigger.id(), tenantId);
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
