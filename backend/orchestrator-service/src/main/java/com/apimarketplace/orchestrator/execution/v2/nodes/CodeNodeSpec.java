package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodeNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("CODE")
            .label("Code")
            .category("core")
            .variablePrefix("core")
            .description("Executes code in a sandboxed environment")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("result")
                    .type("object")
                    .description("The code execution result/return value")
                    .build(),
                OutputFieldDef.builder()
                    .key("stdout")
                    .type("string")
                    .description("Standard output from execution")
                    .defaultValue("")
                    .build(),
                OutputFieldDef.builder()
                    .key("stderr")
                    .type("string")
                    .description("Standard error from execution")
                    .defaultValue("")
                    .build(),
                OutputFieldDef.builder()
                    .key("exitCode")
                    .type("number")
                    .description("Process exit code")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("language")
                    .type("string")
                    .description("Programming language used")
                    .defaultValue("javascript")
                    .build(),
                OutputFieldDef.builder()
                    .key("executionTime")
                    .type("number")
                    .description("Execution time in milliseconds")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether code executed successfully")
                    .defaultValue(false)
                    .build()
            ))
            .keywords(List.of("code", "execute", "script", "javascript", "python"))
            .build();
    }
}
