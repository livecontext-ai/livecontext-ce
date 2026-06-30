package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DecisionNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("DECISION")
            .label("Decision")
            .category("core")
            .variablePrefix("core")
            .description("Evaluates conditions and routes to one branch")
            .branching(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("selected_branch")
                    .type("string")
                    .description("The branch that was selected (if/else/elseif_N)")
                    .build(),
                // selected_branch_index must be preserved: DecisionNode.getNextNodes() and
                // getSkippedChildNodes() read it from the persisted output to determine
                // which branch was selected. Dropping it breaks resume/restart/run-clone
                // and step-by-step paths (ALL branches treated as skipped).
                OutputFieldDef.builder()
                    .key("selected_branch_index")
                    .type("number")
                    .description("Zero-based index of the selected branch (used for routing on resume/restart)")
                    .build(),
                OutputFieldDef.builder()
                    .key("skipped_branches")
                    .type("array")
                    .description("Branches that were not executed")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("evaluations")
                    .type("array")
                    .description("Evaluation details for each condition")
                    .defaultValue(List.of())
                    .build(),
                // split_item_count must be preserved: ReadyNodeCalculator uses it to detect
                // decision-in-split context and traverse ALL branch targets (not just the
                // last item's selected branch). Mirrors SplitAwareNodeExecutor injection.
                OutputFieldDef.builder()
                    .key("split_item_count")
                    .type("number")
                    .description("Total number of items when executed inside a split context (used for branch routing)")
                    .build()
            ))
            .keywords(List.of("decision", "if", "else", "condition", "branch"))
            .build();
    }
}
