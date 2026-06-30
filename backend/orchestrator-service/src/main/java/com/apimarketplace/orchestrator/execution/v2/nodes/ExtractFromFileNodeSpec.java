package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExtractFromFileNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("EXTRACT_FROM_FILE")
            .label("Extract from File")
            .category("core")
            .variablePrefix("core")
            .description("Extracts structured data from files (CSV, Excel, JSON)")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("Extracted rows/items")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("format")
                    .type("string")
                    .description("Detected file format")
                    .defaultValue("csv")
                    .build(),
                OutputFieldDef.builder()
                    .key("rowCount")
                    .type("number")
                    .description("Number of rows extracted")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("columnCount")
                    .type("number")
                    .description("Number of columns detected")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("columns")
                    .type("array")
                    .description("Column names/headers")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether extraction was successful")
                    .defaultValue(false)
                    .build()
            ))
            .keywords(List.of("extract", "file", "csv", "excel", "import"))
            .build();
    }
}
