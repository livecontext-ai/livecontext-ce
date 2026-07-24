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
 * Node specification for Public Link.
 *
 * <p>Turns a FileRef into a public, time-limited, HMAC-signed download URL on the
 * platform's own storage - for APIs that pull media from a URL instead of accepting an
 * upload (Instagram media containers, TikTok PULL_FROM_URL, link previews).</p>
 */
@Component
public class PublicLinkNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("PUBLIC_LINK")
            .label("Public Link")
            .category("core")
            .variablePrefix("core")
            .description("Mints a public, time-limited signed URL for a stored file")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("url")
                    .type("string")
                    .description("Absolute public URL for the file, fetchable by any external "
                        + "server with no credentials until it expires. Feed it to URL-pull "
                        + "APIs (Instagram video_url, TikTok PULL_FROM_URL, link params).")
                    .build(),
                OutputFieldDef.builder()
                    .key("expires_at")
                    .type("string")
                    .description("ISO timestamp when the link stops working (set via ttl_minutes, default 240)")
                    .build(),
                OutputFieldDef.builder()
                    .key("ttl_minutes")
                    .type("number")
                    .description("Effective link lifetime in minutes after clamping (5-10080)")
                    .build(),
                OutputFieldDef.builder()
                    .key("file")
                    .type("object")
                    .description("Echo of the input FileRef the link points at")
                    .children(FileRefSchema.children())
                    .build()
            ))
            .keywords(List.of("public", "link", "url", "share", "signed", "file", "expiring", "external"))
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
