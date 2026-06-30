package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SubWorkflowNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SUB_WORKFLOW")
            .label("Sub-Workflow")
            .category("core")
            .variablePrefix("core")
            .description("Executes another workflow as a sub-workflow")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("result")
                    .type("object")
                    .description("Output from the sub-workflow execution")
                    .build(),
                OutputFieldDef.builder()
                    .key("subWorkflowId")
                    .type("string")
                    .description("ID of the executed sub-workflow")
                    .build(),
                OutputFieldDef.builder()
                    .key("subRunId")
                    .type("string")
                    .description("Run ID of the sub-workflow execution")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the sub-workflow completed successfully")
                    .defaultValue(false)
                    .build()
            ))
            .keywords(List.of("sub-workflow", "child", "nested"))
            .build();
    }
}
