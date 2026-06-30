package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GuardrailNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("GUARDRAIL")
            .label("Guardrail")
            .category("agent")
            .variablePrefix("agent")
            .description("Validates input/output against safety rules")
            .outputs(List.of(
                // node_type must be preserved: ReadyNodeCalculator uses ExecutionMetadataKeys.getNodeType()
                // on the stored output for routing checks.
                OutputFieldDef.builder()
                    .key("node_type")
                    .type("string")
                    .description("Internal node type identifier (always 'GUARDRAIL')")
                    .defaultValue("GUARDRAIL")
                    .build(),
                OutputFieldDef.builder()
                    .key("passed")
                    .type("boolean")
                    .description("Whether the content passed all guardrail checks")
                    .build(),
                OutputFieldDef.builder()
                    .key("violations")
                    .type("array")
                    .description("List of violations found")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("details")
                    .type("object")
                    .description("Detailed guardrail evaluation results")
                    .build(),
                OutputFieldDef.builder()
                    .key("sanitized")
                    .type("string")
                    .description("Sanitized version of the input if applicable")
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
                // split_item_count preserved for consistency with sync path (SplitAwareNodeExecutor
                // injects it on every node in split context).
                OutputFieldDef.builder()
                    .key("split_item_count")
                    .type("number")
                    .description("Total number of items when executed inside a split context")
                    .build()
            ))
            .keywords(List.of("guardrail", "safety", "validate", "check"))
            .build();
    }
}
