package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GetRowsNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("GET_ROWS")
            .label("Get Rows")
            .category("table")
            .variablePrefix("table")
            .description("Retrieves rows from a datasource table")
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
                    .key("rows")
                    .type("array")
                    .description("Retrieved rows")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("row_count")
                    .type("number")
                    .description("Number of rows returned")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("rowCount")
                    .type("number")
                    .description("CamelCase alias for row_count")
                    .aliases(List.of("row_count"))
                    .build(),
                OutputFieldDef.builder()
                    .key("has_more")
                    .type("boolean")
                    .description("Whether more rows are available")
                    .build(),
                OutputFieldDef.builder()
                    .key("offset")
                    .type("number")
                    .description("Pagination offset used")
                    .build()
            ))
            .keywords(List.of("get", "rows", "read", "select", "table"))
            .build();
    }
}
