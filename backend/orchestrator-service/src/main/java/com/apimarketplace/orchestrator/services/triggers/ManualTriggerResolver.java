package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves manual triggers.
 * Manual triggers are fired by user action (clicking the lightning button or the Run API).
 * Output: { triggered_at: ISO timestamp, triggered_by: user display name, ...customFields }
 *
 * <p>Keys are snake_case to match the frontend schema, the node_type_documentation
 * seed (V11 {@code seed_node_type_documentation.sql}), and the
 * {@link com.apimarketplace.orchestrator.execution.v2.nodes.ManualTriggerNodeSpec}
 * declaration. {@code triggered_by} is the user's display name (Keycloak profile),
 * looked up via {@link AuthClient#getDisplayName(String)} with in-memory caching -
 * never the raw tenantId.
 */
@Slf4j
@Component
public class ManualTriggerResolver implements TriggerTypeHandler {

    @Autowired
    private TriggerUserResolver triggerUserResolver;

    @Override
    public boolean canHandle(String triggerType) {
        return "manual".equalsIgnoreCase(triggerType);
    }

    @Override
    public Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.info("Resolving manual trigger: {} for tenant: {}", trigger.id(), tenantId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("triggerId", trigger.id());
        payload.put("type", "manual");
        payload.put("status", "success");
        payload.put("source", "manual");

        // The main output: timestamp when the trigger was fired (snake_case).
        String triggeredAt = java.time.Instant.now().toString();
        payload.put("triggered_at", triggeredAt);

        // triggered_by = user display name via shared TriggerUserResolver.
        String triggeredBy = triggerUserResolver.resolveDisplayName(tenantId);
        payload.put("triggered_by", triggeredBy);

        // Include custom fields from trigger.params
        Map<String, Object> dataItem = new HashMap<>();
        dataItem.put("triggered_at", triggeredAt);
        dataItem.put("triggered_by", triggeredBy);

        if (trigger.params() != null && !trigger.params().isEmpty()) {
            payload.putAll(trigger.params());
            dataItem.putAll(trigger.params());
            log.info("Manual trigger {} includes custom fields: {}", trigger.id(), trigger.params().keySet());
        }

        // Also include in data array for compatibility
        payload.put("data", List.of(dataItem));
        payload.put("count", 1);

        log.info("Manual trigger {} resolved with triggered_at={}, triggered_by='{}'",
                trigger.id(), triggeredAt, triggeredBy);
        return payload;
    }
}
