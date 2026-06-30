package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResponseNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("RESPONSE")
            .label("Response")
            .category("core")
            .variablePrefix("core")
            .description("Sends a response message to the user")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("message")
                    .type("string")
                    .description("The response message content")
                    .build(),
                OutputFieldDef.builder()
                    .key("sent_at")
                    .type("datetime")
                    .description("ISO timestamp when the response was sent")
                    .defaultValue("__NOW__")
                    .build(),
                OutputFieldDef.builder()
                    .key("message_id")
                    .type("string")
                    .description("Unique identifier for the response message")
                    .build()
            ))
            .keywords(List.of("response", "reply", "message"))
            .build();
    }
}
