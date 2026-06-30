package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HttpRequestNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("HTTP_REQUEST")
            .label("HTTP Request")
            .category("core")
            .variablePrefix("core")
            .description("Makes an HTTP request to an external URL")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the request was successful")
                    .defaultValue(true)
                    .build(),
                OutputFieldDef.builder()
                    .key("status")
                    .type("number")
                    .description("HTTP status code")
                    .aliases(List.of("statusCode"))
                    .build(),
                OutputFieldDef.builder()
                    .key("statusText")
                    .type("string")
                    .description("HTTP status text")
                    .aliases(List.of("status_text"))
                    .build(),
                OutputFieldDef.builder()
                    .key("data")
                    .type("object")
                    .description("Response body")
                    .aliases(List.of("body", "response"))
                    .build(),
                OutputFieldDef.builder()
                    .key("headers")
                    .type("object")
                    .description("Response headers")
                    .build(),
                OutputFieldDef.builder()
                    .key("error")
                    .type("string")
                    .description("Error message if request failed")
                    .aliases(List.of("errorMessage"))
                    .build()
            ))
            .keywords(List.of("http", "request", "api", "rest", "fetch"))
            .build();
    }
}
