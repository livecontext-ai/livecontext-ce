package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WebhookTriggerNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("WEBHOOK_TRIGGER")
            .label("Webhook Trigger")
            .category("trigger")
            .variablePrefix("trigger")
            .description("Triggered by an incoming webhook request")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("payload")
                    .type("object")
                    .description("Webhook request body")
                    .defaultValue(Map.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("headers")
                    .type("object")
                    .description("Webhook request headers")
                    .build(),
                OutputFieldDef.builder()
                    .key("query")
                    .type("object")
                    .description("Webhook query parameters")
                    .aliases(List.of("queryParams"))
                    .build(),
                OutputFieldDef.builder()
                    .key("method")
                    .type("string")
                    .description("HTTP method of the webhook request")
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_at")
                    .type("datetime")
                    .description("ISO timestamp when triggered")
                    .defaultValue("__NOW__")
                    .aliases(List.of("triggeredAt"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_by")
                    .type("string")
                    .description("Display name of the user owning the workflow. Empty when the webhook is unauthenticated.")
                    .defaultValue("")
                    .aliases(List.of("triggeredBy"))
                    .build()
            ))
            .keywords(List.of("webhook", "http", "trigger"))
            .build();
    }
}
