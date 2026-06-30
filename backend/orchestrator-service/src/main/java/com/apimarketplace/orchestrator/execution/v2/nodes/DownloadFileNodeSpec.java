package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node specification for Download File.
 *
 * <p>Identity-with-envelope-strip; the canonical {@code file} FileRef is emitted by
 * {@link DownloadFileNode#execute(com.apimarketplace.orchestrator.execution.v2.context.ExecutionContext)}
 * and {@link #customTransform(java.util.Map)} just strips engine-envelope keys.
 */
@Component
public class DownloadFileNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("DOWNLOAD_FILE")
            .label("Download File")
            .category("core")
            .variablePrefix("core")
            .description("Downloads a file from a URL and stores it")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("file")
                    .type("object")
                    .description("Canonical FileRef. Reference via {{core:label.output.file}} to render images in interfaces; marketplace + share previews work for anonymous visitors (showcase HMAC rewriter recognises this shape).")
                    .children(FileRefSchema.children())
                    .build(),
                OutputFieldDef.builder()
                    .key("source_url")
                    .type("string")
                    .description("Original URL that was downloaded from")
                    .build()
            ))
            .keywords(List.of("download", "file", "fetch", "save"))
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
