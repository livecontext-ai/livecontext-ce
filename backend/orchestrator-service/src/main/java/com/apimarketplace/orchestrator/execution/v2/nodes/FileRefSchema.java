package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.OutputFieldDef;

import java.util.List;

/**
 * Canonical FileRef output sub-fields, exposed to the frontend Inspector via
 * {@link com.apimarketplace.orchestrator.controllers.NodeDefinitionController}.
 *
 * <p>Mirrors the FileRef record shape that runtime emits (see
 * {@code com.apimarketplace.orchestrator.execution.v2.context.FileRef}): {@code _type,
 * path, name, mimeType, size}. Shared across the 4 file-producer NodeSpecs
 * (DownloadFile, Sftp, ConvertToFile, Compression) so the Inspector can render
 * a consistent expandable tree under {@code output.file} and users can drag
 * sub-paths like {@code {{core:dl.output.file.path}}}.
 *
 * <p>3-Way Alignment: the children declared here match V219's
 * {@code node_type_documentation.outputs.file} description (LLM-facing doc) and
 * the runtime mapper's {@code result.put("file", fileRef)} (PR1).
 *
 * <p><b>{@code id} is intentionally asymmetric.</b> The Inspector tree (this schema) and the
 * frontend FileRef interface expose {@code id} because the frontend builds the opaque
 * {@code /api/proxy/files/by-id/{id}/raw} URL from it. The LLM-facing
 * {@code node_type_documentation.outputs.file} prose deliberately does NOT list {@code id}: per the
 * agent-POV rule the agent must use the FileRef OBJECT in {@code <img src>} (the frontend resolves
 * the URL) and must never drill {@code .id} (an internal storage handle, not an action input). So
 * "mapper emits id / Inspector shows id / LLM doc omits id" is a by-design split, not 3-way drift -
 * no Flyway doc migration is required for {@code id}.
 */
final class FileRefSchema {

    private FileRefSchema() {
        // utility class
    }

    /**
     * The 6 FileRef sub-fields (5 canonical + the opaque {@code id}). Order matches the FileRef
     * record declaration order (Jackson serialises {@code _type} first via
     * {@code @JsonProperty("_type")}).
     */
    static List<OutputFieldDef> children() {
        return List.of(
            OutputFieldDef.builder()
                .key("_type")
                .type("string")
                .description("Always 'file' - discriminator the frontend file-proxy injector and showcase HMAC rewriter probe.")
                .build(),
            OutputFieldDef.builder()
                .key("path")
                .type("string")
                .description("Object-storage key (S3 path) - what the proxy resolves to a signed URL.")
                .build(),
            OutputFieldDef.builder()
                .key("name")
                .type("string")
                .description("Original filename for download Content-Disposition.")
                .build(),
            OutputFieldDef.builder()
                .key("mimeType")
                .type("string")
                .description("MIME type (e.g. image/png, application/pdf) - drives interface <img>/<audio>/<video> selection.")
                .build(),
            OutputFieldDef.builder()
                .key("size")
                .type("number")
                .description("File size in bytes.")
                .build(),
            OutputFieldDef.builder()
                .key("id")
                .type("string")
                .description("storage.storage row UUID - opaque handle the frontend/agent use to build the "
                    + "/api/proxy/files/by-id/{id}/raw URL (no tenant id, no s3 key). Present on files produced "
                    + "after the opaque-URL cutover.")
                .build()
        );
    }
}
