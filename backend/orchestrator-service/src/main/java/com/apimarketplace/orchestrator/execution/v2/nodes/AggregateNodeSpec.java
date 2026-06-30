package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node specification for Aggregate node with custom transform.
 *
 * Stores aggregated List-typed values at top level so expressions like
 * {{core:aggregate.output.likes}} resolve directly via NamespaceResolver.
 * Dynamic field names vary per configuration so cannot be declared statically.
 */
@Component
public class AggregateNodeSpec implements NodeSpec {

    private static final Set<String> META_FIELDS = Set.of(
        "aggregated_count", "node_type", "item_index", "item_id", "status",
        "received", "expected", "split_aggregate", "split_id", "item_count",
        "items", "results", "aggregated_results",
        "itemIndex", "itemId", "iteration", "currentIteration", "http_status",
        "tenant_id", "tenantId", "trigger_id", "triggerId", "epoch", "spawn", "absoluteIndex"
    );

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("AGGREGATE")
            .label("Aggregate")
            .category("core")
            .variablePrefix("core")
            .description("Aggregates data from parallel split branches")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("aggregated_count")
                    .type("number")
                    .description("Number of items aggregated")
                    .aliases(List.of("count"))
                    .build()
                // Dynamic aggregated fields (e.g. likes, captions) are stored
                // at top level - names vary per configuration, not declared statically.
            ))
            .keywords(List.of("aggregate", "collect", "gather", "combine"))
            .build();
    }

    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        Map<String, Object> dbOutput = new HashMap<>();

        // aggregated_count
        Object count = backendOutput.get("aggregated_count");
        if (count == null) count = backendOutput.get("count");
        dbOutput.put("aggregated_count", count);

        // Aggregated fields stored at top level so expressions like
        // {{core:aggregate.output.likes}} resolve directly.
        // Only List-typed values from non-metadata keys.
        for (Map.Entry<String, Object> entry : backendOutput.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!META_FIELDS.contains(key) && value instanceof List) {
                dbOutput.put(key, value);
            }
        }

        return dbOutput;
    }
}
