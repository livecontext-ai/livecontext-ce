package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node specification for Manual Trigger with custom transform.
 *
 * Manual triggers can carry custom params (data_inputs from execute API).
 * These dynamic fields are flattened to root level.
 */
@Component
public class ManualTriggerNodeSpec implements NodeSpec {

    private static final Set<String> INTERNAL_FIELDS = Set.of(
        "triggerId", "trigger_id", "tenantId", "tenant_id", "type", "source",
        "status", "data", "count", "node_type", "item_index", "itemIndex",
        "item_id", "resolved_params", "epoch", "spawn"
    );

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("MANUAL_TRIGGER")
            .label("Manual Trigger")
            .category("trigger")
            .variablePrefix("trigger")
            .description("Manually triggered workflow execution")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("triggered_at")
                    .type("datetime")
                    .description("ISO timestamp when triggered")
                    .defaultValue("__NOW__")
                    .aliases(List.of("triggeredAt"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_by")
                    .type("string")
                    .description("Display name of the user who triggered the workflow (empty string when unknown). Never the raw tenantId.")
                    .defaultValue("")
                    .aliases(List.of("triggeredBy", "user"))
                    .build()
            ))
            .keywords(List.of("manual", "trigger", "start"))
            .build();
    }

    /**
     * Custom transform: passes through declared fields + any custom params from data_inputs.
     */
    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        Map<String, Object> dbOutput = new HashMap<>();

        Object triggeredAt = backendOutput.get("triggeredAt");
        if (triggeredAt == null) triggeredAt = backendOutput.get("triggered_at");
        dbOutput.put("triggered_at", triggeredAt != null ? triggeredAt : Instant.now().toString());

        Object triggeredBy = backendOutput.get("triggered_by");
        if (triggeredBy == null) triggeredBy = backendOutput.get("triggeredBy");
        if (triggeredBy == null) triggeredBy = backendOutput.get("user");
        dbOutput.put("triggered_by", triggeredBy != null ? triggeredBy : "");

        // Passthrough all custom params (data_inputs) - dynamic fields
        for (Map.Entry<String, Object> entry : backendOutput.entrySet()) {
            if (!INTERNAL_FIELDS.contains(entry.getKey()) && !dbOutput.containsKey(entry.getKey())) {
                dbOutput.put(entry.getKey(), entry.getValue());
            }
        }

        return dbOutput;
    }
}
