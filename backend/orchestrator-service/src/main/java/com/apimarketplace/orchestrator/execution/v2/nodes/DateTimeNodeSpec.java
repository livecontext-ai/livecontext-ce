package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DateTimeNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("DATE_TIME")
            .label("Date/Time")
            .category("core")
            .variablePrefix("core")
            .description("Performs date/time operations")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("result")
                    .type("string")
                    .description("The date/time operation result")
                    .build(),
                OutputFieldDef.builder()
                    .key("operation")
                    .type("string")
                    .description("The date/time operation performed")
                    .defaultValue("format")
                    .build()
            ))
            .keywords(List.of("date", "time", "datetime", "format", "parse"))
            .build();
    }
}
