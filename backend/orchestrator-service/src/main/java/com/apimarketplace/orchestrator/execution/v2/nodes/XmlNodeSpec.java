package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class XmlNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("XML")
            .label("XML")
            .category("core")
            .variablePrefix("core")
            .description("Converts between XML and JSON formats")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("result")
                    .type("object")
                    .description("The conversion result")
                    .build(),
                OutputFieldDef.builder()
                    .key("operation")
                    .type("string")
                    .description("The operation performed")
                    .defaultValue("xmlToJson")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the operation was successful")
                    .defaultValue(false)
                    .build()
            ))
            .keywords(List.of("xml", "json", "convert", "parse"))
            .build();
    }
}
