package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the Sort node.
 *
 * Output schema:
 * - sorted_items: array (defaults to [])
 * - count: number (defaults to 0)
 */
@Component
public class SortNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SORT")
            .label("Sort")
            .category("core")
            .variablePrefix("core")
            .description("Sorts a list of items by specified fields and directions")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("sorted_items")
                    .type("array")
                    .description("The reordered items array")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Total item count after sorting")
                    .defaultValue(0)
                    .build()
            ))
            .keywords(List.of("sort", "order", "arrange", "rank"))
            .build();
    }
}
