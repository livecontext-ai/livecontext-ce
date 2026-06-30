package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the Set node (Set / Edit Fields).
 */
@Component
public class SetNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SET")
            .label("Set")
            .category("core")
            .variablePrefix("core")
            .description("Assign or transform fields on the input data (Set / Edit Fields)")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("fields")
                    .type("object")
                    .description("Map of resolved field assignments (after type coercion)")
                    .build(),
                OutputFieldDef.builder()
                    .key("output")
                    .type("object")
                    .description("Final merged object - fields merged onto the upstream input data, or only the assigned fields when keepOnlySet=true")
                    .build(),
                OutputFieldDef.builder()
                    .key("keep_only_set")
                    .type("boolean")
                    .description("Whether only the assigned fields are returned (true) or merged with input (false)")
                    .defaultValue(false)
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Number of assigned fields")
                    .defaultValue(0)
                    .build()
            ))
            .keywords(List.of("set", "edit", "assign", "field", "transform"))
            .build();
    }
}
