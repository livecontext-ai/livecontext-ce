package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RespondToWebhookNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("RESPOND_TO_WEBHOOK")
            .label("Respond to Webhook")
            .category("core")
            .variablePrefix("core")
            .description("Sends a response back to the webhook caller")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("responded")
                    .type("boolean")
                    .description("Whether the response was sent successfully")
                    .defaultValue(false)
                    .build(),
                OutputFieldDef.builder()
                    .key("statusCode")
                    .type("number")
                    .description("HTTP status code sent in response")
                    .defaultValue(200)
                    .build(),
                OutputFieldDef.builder()
                    .key("contentType")
                    .type("string")
                    .description("Content-Type of the response")
                    .defaultValue("application/json")
                    .build()
            ))
            .keywords(List.of("respond", "webhook", "reply"))
            .build();
    }
}
