package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the Merge node.
 *
 * Output schema:
 * - merged_branches: array of merged branch labels
 * - sources: object with each source node's output (keyed by nodeId)
 * - merged_items: array of all items collected from successful branches
 * - source_count / success_count: merge statistics
 */
@Component
public class MergeNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("MERGE")
            .label("Merge")
            .category("core")
            .variablePrefix("core")
            .description("Waits for all incoming branches and merges their results")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("merged_branches")
                    .type("array")
                    .description("List of branch labels that were merged")
                    .defaultValue(List.of())
                    .aliases(List.of("received_branches", "merged_data"))
                    .build(),
                OutputFieldDef.builder()
                    .key("sources")
                    .type("object")
                    .description("Each source branch output keyed by node ID")
                    .build(),
                OutputFieldDef.builder()
                    .key("merged_items")
                    .type("array")
                    .description("All items collected from successful branches")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("source_count")
                    .type("number")
                    .description("Total number of source branches")
                    .build(),
                OutputFieldDef.builder()
                    .key("success_count")
                    .type("number")
                    .description("Number of branches that completed successfully")
                    .build(),
                OutputFieldDef.builder()
                    .key("strategy")
                    .type("string")
                    .description("Merge strategy used")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_count")
                    .type("number")
                    .description("Total number of merged items")
                    .build()
            ))
            .keywords(List.of("merge", "join", "combine", "wait"))
            .build();
    }
}
