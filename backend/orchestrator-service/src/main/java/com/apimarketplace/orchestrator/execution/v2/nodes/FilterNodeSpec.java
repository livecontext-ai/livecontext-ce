package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the Filter node.
 *
 * Output schema mirrors the Filter node:
 * - matched: boolean (defaults to false)
 * - items: array of matching items (defaults to [])
 * - rejected_items: array of non-matching items (defaults to [])
 * - count: number of matching items (defaults to 0)
 * - rejected_count: number of rejected items (defaults to 0)
 * - original_count: number of items in the input (defaults to 0)
 * - filter_mode: string (defaults to "and")
 * - conditions_evaluated: number (defaults to 0)
 * - data: object (conditional - only included when present in raw output)
 */
@Component
public class FilterNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("FILTER")
            .label("Filter")
            .category("core")
            .variablePrefix("core")
            .description("Filters items based on conditions")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("matched")
                    .type("boolean")
                    .description("Whether any item passed the filter conditions")
                    .defaultValue(false)
                    .build(),
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("Items that passed the filter conditions")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("rejected_items")
                    .type("array")
                    .description("Items that did not pass the filter conditions")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Number of items that passed the filter")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("rejected_count")
                    .type("number")
                    .description("Number of items that did not pass the filter")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("original_count")
                    .type("number")
                    .description("Number of items in the resolved input")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("filter_mode")
                    .type("string")
                    .description("Filter mode: 'and' or 'or'")
                    .defaultValue("and")
                    .build(),
                OutputFieldDef.builder()
                    .key("conditions_evaluated")
                    .type("number")
                    .description("Count of conditions evaluated")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("data")
                    .type("object")
                    .description("Original input data, present only if matched")
                    .build()
            ))
            .keywords(List.of("filter", "where", "condition", "match"))
            .build();
    }
}
