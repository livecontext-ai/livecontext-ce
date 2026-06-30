package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ForkNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("FORK")
            .label("Fork")
            .category("core")
            .variablePrefix("core")
            .description("Executes all branches in parallel")
            .branching(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("branch_count")
                    .type("number")
                    .description("Number of parallel branches created")
                    .build(),
                OutputFieldDef.builder()
                    .key("branches")
                    .type("array")
                    .description("Array of branch objects, each with index, id, label, and target_count")
                    .defaultValue(List.of())
                    .aliases(List.of("forked_branches"))
                    .build()
            ))
            .keywords(List.of("fork", "parallel", "branch"))
            .build();
    }
}
