package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the Exit node.
 *
 * Output schema:
 * - exited_at: datetime (defaults to current time via __NOW__ sentinel)
 * - reason: string (defaults to "branch_exited")
 * - status: string (defaults to "exited")
 */
@Component
public class ExitNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("EXIT")
            .label("Exit")
            .category("core")
            .variablePrefix("core")
            .description("Ends execution along this branch. Other parallel branches continue normally.")
            .terminal(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("exited_at")
                    .type("datetime")
                    .description("ISO timestamp when exit was triggered")
                    .defaultValue("__NOW__")
                    .build(),
                OutputFieldDef.builder()
                    .key("reason")
                    .type("string")
                    .description("Configured or default reason for exiting")
                    .defaultValue("branch_exited")
                    .build(),
                OutputFieldDef.builder()
                    .key("status")
                    .type("string")
                    .description("Final status: exited, completed")
                    .defaultValue("exited")
                    .build()
            ))
            .keywords(List.of("exit", "end", "terminate", "halt"))
            .build();
    }
}
