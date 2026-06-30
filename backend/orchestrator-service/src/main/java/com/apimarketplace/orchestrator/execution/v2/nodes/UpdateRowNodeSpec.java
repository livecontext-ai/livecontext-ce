package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UpdateRowNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("UPDATE_ROW")
            .label("Update Row")
            .category("table")
            .variablePrefix("table")
            .description("Updates rows in a datasource table")
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
                    .key("updated_count")
                    .type("number")
                    .description("Number of rows updated")
                    .build(),
                OutputFieldDef.builder()
                    .key("rows_affected")
                    .type("number")
                    .description("Number of rows affected")
                    .aliases(List.of("updated_count"))
                    .build(),
                OutputFieldDef.builder()
                    .key("updated_at")
                    .type("datetime")
                    .description("ISO timestamp when the update was performed")
                    .build()
            ))
            .keywords(List.of("update", "modify", "edit", "row", "table"))
            .build();
    }
}
