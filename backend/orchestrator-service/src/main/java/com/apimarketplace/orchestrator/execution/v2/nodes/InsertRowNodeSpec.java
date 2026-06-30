package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class InsertRowNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("INSERT_ROW")
            .label("Insert Row")
            .category("table")
            .variablePrefix("table")
            .description("Inserts a new row into a datasource table")
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
                    .key("row_id")
                    .type("string")
                    .description("ID of the inserted row")
                    .build(),
                OutputFieldDef.builder()
                    .key("created_at")
                    .type("datetime")
                    .description("ISO timestamp when the row was created")
                    .defaultValue("__NOW__")
                    .build(),
                OutputFieldDef.builder()
                    .key("inserted_count")
                    .type("number")
                    .description("Number of rows inserted")
                    .build(),
                OutputFieldDef.builder()
                    .key("inserted_values")
                    .type("object")
                    .description("Values that were inserted")
                    .defaultValue(Map.of())
                    .build()
            ))
            .keywords(List.of("insert", "create", "add", "row", "table"))
            .build();
    }
}
