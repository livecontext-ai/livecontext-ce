package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the RemoveDuplicates node.
 *
 * Output schema:
 * - items: array (defaults to [])
 * - original_count: number (defaults to 0, coerced to int)
 * - deduplicated_count: number (defaults to 0, coerced to int)
 * - removed_count: number (defaults to 0, coerced to int)
 */
@Component
public class RemoveDuplicatesNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("REMOVE_DUPLICATES")
            .label("Remove Duplicates")
            .category("core")
            .variablePrefix("core")
            .description("Removes duplicate items from a list based on specified fields")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("Deduplicated items")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("original_count")
                    .type("number")
                    .description("Count before deduplication")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("deduplicated_count")
                    .type("number")
                    .description("Count after deduplication")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("removed_count")
                    .type("number")
                    .description("How many duplicates were removed")
                    .defaultValue(0)
                    .build()
            ))
            .keywords(List.of("deduplicate", "unique", "distinct", "remove duplicates"))
            .build();
    }
}
