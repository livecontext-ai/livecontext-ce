package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SwitchNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SWITCH")
            .label("Switch")
            .category("core")
            .variablePrefix("core")
            .description("Evaluates a value against multiple cases")
            .branching(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("selected_branches")
                    .type("array")
                    .description("Case labels that matched")
                    .defaultValue(List.of())
                    .aliases(List.of("selected_case_label", "selected_case"))
                    .build(),
                OutputFieldDef.builder()
                    .key("selected_case_index")
                    .type("number")
                    .description("Index of the selected case for state reconstruction")
                    .build(),
                OutputFieldDef.builder()
                    .key("evaluations")
                    .type("array")
                    .description("Evaluation details for each case")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("skipped_branches")
                    .type("array")
                    .description("Cases that were not executed")
                    .defaultValue(List.of())
                    .aliases(List.of("skipped_case_labels", "skipped_cases"))
                    .build(),
                // split_item_count must be preserved: ReadyNodeCalculator uses it to detect
                // switch-in-split context and traverse ALL case targets (not just the last
                // item's selected case). Mirrors SplitAwareNodeExecutor injection.
                OutputFieldDef.builder()
                    .key("split_item_count")
                    .type("number")
                    .description("Total number of items when executed inside a split context (used for branch routing)")
                    .build()
            ))
            .keywords(List.of("switch", "case", "match"))
            .build();
    }
}
