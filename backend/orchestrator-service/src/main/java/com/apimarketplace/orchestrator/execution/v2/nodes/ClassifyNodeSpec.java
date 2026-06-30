package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ClassifyNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("CLASSIFY")
            .label("Classify")
            .category("agent")
            .variablePrefix("agent")
            .description("Classifies input into predefined categories using AI")
            .outputs(List.of(
                // node_type must be preserved: ReadyNodeCalculator uses ExecutionMetadataKeys.getNodeType()
                // on the stored output for split-context routing.
                OutputFieldDef.builder()
                    .key("node_type")
                    .type("string")
                    .description("Internal node type identifier (always 'CLASSIFY')")
                    .defaultValue("CLASSIFY")
                    .build(),
                OutputFieldDef.builder()
                    .key("selected_category")
                    .type("string")
                    .description("The category selected by the classifier")
                    .aliases(List.of("category"))
                    .build(),
                // selected_category_index is read by getSelectedPort() for port-qualified edge emission
                OutputFieldDef.builder()
                    .key("selected_category_index")
                    .type("number")
                    .description("Zero-based index of the selected category (used for branch routing)")
                    .build(),
                OutputFieldDef.builder()
                    .key("confidence")
                    .type("number")
                    .description("Confidence score of the classification")
                    .build(),
                OutputFieldDef.builder()
                    .key("reasoning")
                    .type("string")
                    .description("Explanation for the classification decision")
                    .build(),
                OutputFieldDef.builder()
                    .key("tokens_used")
                    .type("number")
                    .description("Total tokens consumed")
                    .build(),
                OutputFieldDef.builder()
                    .key("duration_ms")
                    .type("number")
                    .description("Execution duration in milliseconds")
                    .aliases(List.of("durationMs"))
                    .build(),
                OutputFieldDef.builder()
                    .key("model")
                    .type("string")
                    .description("LLM model used")
                    .build(),
                OutputFieldDef.builder()
                    .key("provider")
                    .type("string")
                    .description("LLM provider used")
                    .build(),
                // split_item_count must be preserved: ReadyNodeCalculator uses it to detect
                // classify-in-split context and traverse ALL category targets (not just the
                // last item's selected branch). Dropping it causes parallel branches to not
                // execute when classify runs inside a Split. Mirrors SplitAwareNodeExecutor.
                OutputFieldDef.builder()
                    .key("split_item_count")
                    .type("number")
                    .description("Total number of items when executed inside a split context (used for branch routing)")
                    .build()
            ))
            .keywords(List.of("classify", "categorize", "label"))
            .build();
    }
}
