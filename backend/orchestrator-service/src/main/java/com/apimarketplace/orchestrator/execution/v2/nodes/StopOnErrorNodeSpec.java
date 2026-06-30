package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the StopOnError node.
 *
 * Output schema:
 * - error_message: string (the resolved error message)
 * - error_code: string (optional error code)
 * - stopped_at: datetime (defaults to current time via __NOW__ sentinel)
 * - status: string (defaults to "failed")
 */
@Component
public class StopOnErrorNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("STOP_ON_ERROR")
            .label("Stop on Error")
            .category("core")
            .variablePrefix("core")
            .description("Immediately fails the workflow with an error message and optional error code")
            .terminal(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("error_message")
                    .type("string")
                    .description("The resolved error message that caused the failure")
                    .build(),
                OutputFieldDef.builder()
                    .key("error_code")
                    .type("string")
                    .description("Optional error code for programmatic handling")
                    .build(),
                OutputFieldDef.builder()
                    .key("stopped_at")
                    .type("datetime")
                    .description("ISO timestamp when the error stop was triggered")
                    .defaultValue("__NOW__")
                    .build(),
                OutputFieldDef.builder()
                    .key("status")
                    .type("string")
                    .description("Final status: always 'failed' for stop on error")
                    .defaultValue("failed")
                    .build()
            ))
            .keywords(List.of("stop", "error", "fail", "abort", "terminate", "halt"))
            .build();
    }
}
