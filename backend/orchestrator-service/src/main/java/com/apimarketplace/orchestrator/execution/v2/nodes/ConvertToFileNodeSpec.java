package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConvertToFileNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("CONVERT_TO_FILE")
            .label("Convert to File")
            .category("core")
            .variablePrefix("core")
            .description("Converts data to a file format (CSV, Excel, JSON)")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("file")
                    .type("object")
                    .description("Canonical FileRef. Reference via {{core:label.output.file}} to render in interfaces; marketplace + share previews only work through this shape.")
                    .children(FileRefSchema.children())
                    .build(),
                OutputFieldDef.builder()
                    .key("result")
                    .type("string")
                    .description("Inline file contents (xlsx: base64; csv/json/txt: plain text)")
                    .build(),
                OutputFieldDef.builder()
                    .key("format")
                    .type("string")
                    .description("Output file format")
                    .defaultValue("csv")
                    .build(),
                OutputFieldDef.builder()
                    .key("row_count")
                    .type("number")
                    .description("Number of rows written")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether conversion was successful")
                    .defaultValue(false)
                    .build()
            ))
            .keywords(List.of("convert", "file", "export", "csv", "excel"))
            .build();
    }

    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        if (backendOutput == null) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>(backendOutput);
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(result::remove);
        return result;
    }
}
