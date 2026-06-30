package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node specification for Limit node.
 *
 * execute() already emits the final persisted shape (items, count, original_count, config),
 * so customTransform is an identity copy.
 */
@Component
public class LimitNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("LIMIT")
            .label("Limit")
            .category("core")
            .variablePrefix("core")
            .description("Limits the number of items in a collection")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("The limited subset of items")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Number of items in the limited result")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("original_count")
                    .type("number")
                    .description("Number of items before limiting")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("config")
                    .type("object")
                    .description("Limit configuration parameters")
                    .build()
            ))
            .keywords(List.of("limit", "take", "top", "head"))
            .build();
    }

    /**
     * Strips engine-envelope keys from the persisted output.
     * execute() already emits the final persisted shape (items, count, original_count, config);
     * no further reshaping is needed.
     */
    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        if (backendOutput == null) return new HashMap<>();
        Map<String, Object> result = new HashMap<>(backendOutput);
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(result::remove);
        return result;
    }
}
