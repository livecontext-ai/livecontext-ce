package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node specification for Summarize node with custom transform.
 *
 * Copies flattened aggregation results (dynamic non-metadata keys) to root level
 * - cannot be expressed as static field definitions.
 */
@Component
public class SummarizeNodeSpec implements NodeSpec {

    private static final Set<String> METADATA_FIELDS = Set.of(
        "itemId",
        "iteration", "currentIteration", "status", "http_status",
        "tenant_id", "tenantId", "trigger_id", "triggerId",
        "epoch", "spawn", "absoluteIndex"
        // engine-envelope keys (node_type, item_index, itemIndex, item_id, resolved_params)
        // are stripped via GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS in customTransform
    );

    private static final Set<String> STATIC_FIELDS = Set.of(
        "groups", "total_groups", "total_items", "aggregation_count"
        // resolved_params is part of ENGINE_ENVELOPE_KEYS - handled via forEach below
    );

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SUMMARIZE")
            .label("Summarize")
            .category("core")
            .variablePrefix("core")
            .description("Groups and aggregates data with statistical functions")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("groups")
                    .type("array")
                    .description("List of group results with aggregated values")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("total_groups")
                    .type("number")
                    .description("Count of distinct groups")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("total_items")
                    .type("number")
                    .description("Total items processed")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("aggregation_count")
                    .type("number")
                    .description("Number of aggregation operations applied")
                    .defaultValue(0)
                    .build()
                // When no groupBy is configured, each aggregation alias is also stored
                // directly at the top level (e.g. {{core:summarize.output.total_salary}}).
                // Alias names vary per configuration and cannot be declared statically.
            ))
            .keywords(List.of("summarize", "group", "aggregate", "statistics"))
            .build();
    }

    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        Map<String, Object> dbOutput = new HashMap<>();

        dbOutput.put("groups", backendOutput.getOrDefault("groups", List.of()));
        dbOutput.put("total_groups", backendOutput.getOrDefault("total_groups", 0));
        dbOutput.put("total_items", backendOutput.getOrDefault("total_items", 0));
        dbOutput.put("aggregation_count", backendOutput.getOrDefault("aggregation_count", 0));

        // Copy flattened aggregation results (non-metadata, non-static, non-envelope)
        for (Map.Entry<String, Object> entry : backendOutput.entrySet()) {
            String key = entry.getKey();
            if (!METADATA_FIELDS.contains(key) && !STATIC_FIELDS.contains(key)
                    && !GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.contains(key)) {
                dbOutput.put(key, entry.getValue());
            }
        }
        // Remove engine-envelope keys shared across all node specs
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(dbOutput::remove);

        return dbOutput;
    }
}
