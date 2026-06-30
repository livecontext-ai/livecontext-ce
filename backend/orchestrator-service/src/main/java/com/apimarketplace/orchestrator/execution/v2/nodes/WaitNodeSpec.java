package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WaitNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("WAIT")
            .label("Wait")
            .category("core")
            .variablePrefix("core")
            .description("Pauses execution for a specified duration")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("waited_ms")
                    .type("number")
                    .description("Duration waited in milliseconds")
                    .aliases(List.of("waited", "duration_ms"))
                    .build(),
                OutputFieldDef.builder()
                    .key("status")
                    .type("string")
                    .description("Final wait status")
                    .build(),
                OutputFieldDef.builder()
                    .key("started_at")
                    .type("datetime")
                    .description("ISO timestamp when wait started")
                    .build(),
                OutputFieldDef.builder()
                    .key("completed_at")
                    .type("datetime")
                    .description("ISO timestamp when wait completed")
                    .aliases(List.of("resumed_at"))
                    .build(),
                OutputFieldDef.builder()
                    .key("duration_ms")
                    .type("number")
                    .description("Duration in milliseconds while a long wait is awaiting its timer signal")
                    .build(),
                OutputFieldDef.builder()
                    .key("expires_at")
                    .type("datetime")
                    .description("ISO timestamp when a long wait timer expires")
                    .build()
            ))
            .keywords(List.of("wait", "delay", "pause", "timer", "sleep"))
            .build();
    }
}
