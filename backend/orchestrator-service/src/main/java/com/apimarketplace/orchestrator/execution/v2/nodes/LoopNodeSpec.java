package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LoopNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("LOOP")
            .label("Loop")
            .category("core")
            .variablePrefix("core")
            .description("Repeats execution until condition is met or max iterations reached")
            .branching(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("iteration")
                    .type("number")
                    .description("Current iteration number (0 on first entry, increments each iteration)")
                    .build(),
                OutputFieldDef.builder()
                    .key("maxIterations")
                    .type("number")
                    .description("Configured maximum allowed iterations")
                    .build(),
                OutputFieldDef.builder()
                    .key("terminated")
                    .type("boolean")
                    .description("Whether the loop has terminated (condition false or max iterations reached)")
                    .build(),
                OutputFieldDef.builder()
                    .key("enter_body")
                    .type("boolean")
                    .description("Whether the loop body should be entered this evaluation")
                    .build(),
                OutputFieldDef.builder()
                    .key("selected_path")
                    .type("string")
                    .description("The selected loop path: body or exit")
                    .build(),
                OutputFieldDef.builder()
                    .key("reason")
                    .type("string")
                    .description("Exit reason, present only once terminated: condition_false or max_iterations_reached")
                    .build()
            ))
            .keywords(List.of("loop", "repeat", "while", "iterate"))
            .build();
    }
}
