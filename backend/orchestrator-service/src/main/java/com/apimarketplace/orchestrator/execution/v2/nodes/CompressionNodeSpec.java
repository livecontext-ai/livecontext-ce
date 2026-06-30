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
public class CompressionNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("COMPRESSION")
            .label("Compression")
            .category("core")
            .variablePrefix("core")
            .description("Compresses or decompresses data")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("result")
                    .type("string")
                    .description("The compressed or decompressed result")
                    .build(),
                OutputFieldDef.builder()
                    .key("file")
                    .type("object")
                    .description("Canonical FileRef for compress operation. Reference via {{core:label.output.file}} to render in interfaces; marketplace + share previews only work through this shape.")
                    .children(FileRefSchema.children())
                    .build(),
                OutputFieldDef.builder()
                    .key("operation")
                    .type("string")
                    .description("The compression operation performed")
                    .build(),
                OutputFieldDef.builder()
                    .key("format")
                    .type("string")
                    .description("The compression format used")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the operation was successful")
                    .build()
            ))
            .keywords(List.of("compress", "decompress", "gzip", "zip"))
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
