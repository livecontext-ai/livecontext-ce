package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CreateColumnNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("CREATE_COLUMN")
            .label("Create Column")
            .category("table")
            .variablePrefix("table")
            .description("Creates new columns in a datasource table")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("operation")
                    .type("string")
                    .description("CRUD operation name")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the operation succeeded")
                    .build(),
                OutputFieldDef.builder()
                    .key("message")
                    .type("string")
                    .description("Human-readable result message")
                    .build(),
                OutputFieldDef.builder()
                    .key("createdColumns")
                    .type("array")
                    .description("List of columns that were created")
                    .defaultValue(List.of())
                    .aliases(List.of("created_columns"))
                    .build()
            ))
            .keywords(List.of("create", "column", "schema", "table"))
            .build();
    }
}
