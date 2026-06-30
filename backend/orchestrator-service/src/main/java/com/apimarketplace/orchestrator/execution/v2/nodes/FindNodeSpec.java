package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FindNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("FIND")
            .label("Find")
            .category("table")
            .variablePrefix("table")
            .description("Finds rows matching criteria, optionally spawning parallel contexts")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("Found rows matching criteria")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_count")
                    .type("number")
                    .description("Number of items found")
                    .build(),
                OutputFieldDef.builder()
                    .key("total_before_limit")
                    .type("number")
                    .description("Total matching rows before limit applied")
                    .build(),
                OutputFieldDef.builder()
                    .key("max_items")
                    .type("number")
                    .description("Maximum items configured")
                    .build(),
                OutputFieldDef.builder()
                    .key("has_more")
                    .type("boolean")
                    .description("Whether more items exist beyond max_items")
                    .build(),
                OutputFieldDef.builder()
                    .key("find_id")
                    .type("string")
                    .description("Unique identifier for this find operation")
                    .build(),
                OutputFieldDef.builder()
                    .key("exit_reason")
                    .type("string")
                    .description("Reason for completion: items_found or empty_result")
                    .aliases(List.of("spawn_reason"))
                    .build(),
                OutputFieldDef.builder()
                    .key("terminated")
                    .type("boolean")
                    .description("Whether the find was terminated early")
                    .build(),
                OutputFieldDef.builder()
                    .key("split_strategy")
                    .type("string")
                    .description("Strategy used for splitting results")
                    .build(),
                OutputFieldDef.builder()
                    .key("split_id")
                    .type("string")
                    .description("Split identifier when used with parallel contexts")
                    .build()
            ))
            .keywords(List.of("find", "search", "query", "lookup"))
            .build();
    }
}
