package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DeleteRowNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("DELETE_ROW")
            .label("Delete Row")
            .category("table")
            .variablePrefix("table")
            .description("Deletes rows from a datasource table")
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
                    .key("deleted_count")
                    .type("number")
                    .description("Number of rows deleted")
                    .build(),
                OutputFieldDef.builder()
                    .key("rows_affected")
                    .type("number")
                    .description("Number of rows affected")
                    .aliases(List.of("deleted_count"))
                    .build(),
                OutputFieldDef.builder()
                    .key("deleted_at")
                    .type("datetime")
                    .description("ISO timestamp when the deletion was performed")
                    .build()
            ))
            .keywords(List.of("delete", "remove", "row", "table"))
            .build();
    }
}
