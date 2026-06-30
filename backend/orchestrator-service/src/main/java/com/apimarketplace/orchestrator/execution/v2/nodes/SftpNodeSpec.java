package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node specification for the SFTP node.
 *
 * <p>Identity-with-envelope-strip; the canonical {@code file} FileRef is emitted by
 * {@code SftpNode.buildDownloadResult()} and {@code customTransform} just strips
 * engine-envelope keys plus SFTP-specific runtime debug keys ({@code host},
 * {@code error}, {@code file_content}) that must not leak into persisted JSONB.
 */
@Component
public class SftpNodeSpec implements NodeSpec {

    /** SFTP-specific runtime debug keys that must never leak into persisted JSONB. */
    private static final Set<String> SFTP_RUNTIME_KEYS = Set.of("host", "error", "file_content");

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SFTP")
            .label("SFTP")
            .category("core")
            .variablePrefix("core")
            .description("File operations on remote servers via SFTP (upload, download, list, delete, rename, mkdir)")
            .terminal(false)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("True if the SFTP operation succeeded")
                    .build(),
                OutputFieldDef.builder()
                    .key("operation")
                    .type("string")
                    .description("The SFTP operation performed (upload, download, list, delete, rename, mkdir)")
                    .build(),
                OutputFieldDef.builder()
                    .key("remote_path")
                    .type("string")
                    .description("The remote file/directory path")
                    .build(),
                OutputFieldDef.builder()
                    .key("files")
                    .type("array")
                    .description("Array of file entries (for list operation): {name, size, is_dir, modified}")
                    .build(),
                OutputFieldDef.builder()
                    .key("file_count")
                    .type("number")
                    .description("Number of file entries returned (for list operation)")
                    .build(),
                OutputFieldDef.builder()
                    .key("file")
                    .type("object")
                    .description("Canonical FileRef for the download operation. Reference via {{core:label.output.file}} to render in interfaces; marketplace + share previews only work through this shape.")
                    .children(FileRefSchema.children())
                    .build(),
                OutputFieldDef.builder()
                    .key("new_path")
                    .type("string")
                    .description("New file path after rename")
                    .build(),
                OutputFieldDef.builder()
                    .key("uploaded_size")
                    .type("number")
                    .description("Number of bytes written to the remote server (upload operation only)")
                    .build(),
                OutputFieldDef.builder()
                    .key("duration_ms")
                    .type("number")
                    .description("Total operation time in milliseconds")
                    .build()
            ))
            .keywords(List.of("sftp", "file", "transfer", "upload", "download", "remote", "server"))
            .build();
    }

    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        if (backendOutput == null) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>(backendOutput);
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(result::remove);
        SFTP_RUNTIME_KEYS.forEach(result::remove);
        return result;
    }
}
