package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SftpNodeSpec - 3-way alignment: output declarations + customTransform envelope-strip contract")
class SftpNodeSpecTest {

    private final SftpNodeSpec spec = new SftpNodeSpec();

    @Test
    @DisplayName("definition: nodeType / label / category / variablePrefix are wired correctly")
    void coreMetadata() {
        NodeDefinition def = spec.definition();
        assertThat(def.nodeType()).isEqualTo("SFTP");
        assertThat(def.label()).isEqualTo("SFTP");
        assertThat(def.category()).isEqualTo("core");
        assertThat(def.variablePrefix()).isEqualTo("core");
    }

    @Test
    @DisplayName("outputs: declares canonical `file` FileRef (PR2 2026-05-15 clean break, no legacy flats)")
    void declaresCanonicalFileShape() {
        Set<String> keys = outputKeys();
        assertThat(keys)
            .as("SFTP must expose the canonical FileRef under `file` so the showcase HMAC rewriter recognises it for anonymous marketplace/share visitors")
            .contains("file")
            .doesNotContain("file_url", "file_name", "file_size", "content_type");
    }

    @Test
    @DisplayName("outputs: legacy file_content (base64 fallback) is NOT in the declared output set so the LLM never sees it as a primary surface")
    void doesNotDeclareFileContent() {
        assertThat(outputKeys())
            .as("file_content is a runtime debug key stripped by customTransform; it must not appear in the declared schema")
            .doesNotContain("file_content");
    }

    @Test
    @DisplayName("outputs: declares every operation-shared field (success, operation, remote_path, files, file_count, file, new_path, uploaded_size, duration_ms) - node_type is engine-envelope and must NOT be declared")
    void declaresAllOperationFields() {
        assertThat(outputKeys()).containsExactlyInAnyOrder(
            "success", "operation", "remote_path",
            "files", "file_count",
            "file",
            "new_path", "uploaded_size", "duration_ms"
        );
        // node_type is stripped by customTransform (ENGINE_ENVELOPE_KEYS); declaring it
        // as an output would lie to the LLM about what it can reference at runtime.
        assertThat(outputKeys()).doesNotContain("node_type");
    }

    @Test
    @DisplayName("outputs: `file` child fields mirror the FileRef record shape (_type, path, name, mimeType, size) for Inspector tree expansion")
    void fileChildrenMirrorFileRefShape() {
        OutputFieldDef fileField = spec.definition().outputs().stream()
            .filter(f -> "file".equals(f.key()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("`file` output not declared on SftpNodeSpec"));
        Set<String> childKeys = fileField.children().stream()
            .map(OutputFieldDef::key)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(childKeys)
            .as("the Inspector tree must show the same sub-fields the runtime FileRef record exposes - agents drill `output.file.path` / `.name` / `.mimeType` / `.size`; `id` is the opaque handle the frontend builds the by-id URL from")
            .containsExactlyInAnyOrder("_type", "path", "name", "mimeType", "size", "id");
    }

    @Nested
    @DisplayName("customTransform - envelope-strip: removes engine-envelope + SFTP runtime-debug keys")
    class CustomTransform {

        @Test
        @DisplayName("strips engine-envelope keys (node_type, item_index, itemIndex, item_id, resolved_params) from persisted output")
        void stripsEngineEnvelopeKeys() {
            // FileRef stays under `file`. The spec receives the buildDownloadResult() map
            // and must strip engine-envelope keys so they do not leak into persisted JSONB.
            Map<String, Object> backend = new java.util.LinkedHashMap<>();
            backend.put("node_type", "SFTP");
            backend.put("item_index", 0);
            backend.put("itemIndex", 0);
            backend.put("item_id", "item-1");
            backend.put("resolved_params", Map.of("host", "sftp.example.com"));
            backend.put("success", true);
            backend.put("operation", "download");
            backend.put("remote_path", "/remote/report.csv");
            backend.put("duration_ms", 200L);
            backend.put("file", Map.of("_type", "file", "path", "tenant/report.csv", "name", "report.csv", "mimeType", "text/csv", "size", 4096L));

            Map<String, Object> result = spec.customTransform(backend);

            assertThat(result).doesNotContainKey("node_type");
            assertThat(result).doesNotContainKey("item_index");
            assertThat(result).doesNotContainKey("itemIndex");
            assertThat(result).doesNotContainKey("item_id");
            assertThat(result).doesNotContainKey("resolved_params");
            assertThat(result).containsKey("file");
            assertThat(result).isNotSameAs(backend); // defensive copy
        }

        @Test
        @DisplayName("strips SFTP runtime-debug keys (host, error, file_content) from persisted output")
        void stripsSftpRuntimeDebugKeys() {
            // buildErrorResult() adds host; base64 fallback adds file_content.
            // Both must be stripped so they do not appear in persisted JSONB.
            Map<String, Object> backend = new java.util.LinkedHashMap<>();
            backend.put("node_type", "SFTP");
            backend.put("success", false);
            backend.put("operation", "download");
            backend.put("remote_path", "/missing.txt");
            backend.put("duration_ms", 5L);
            backend.put("host", "sftp.example.com");
            backend.put("error", "Connection refused");
            backend.put("file_content", "aGVsbG8=");

            Map<String, Object> result = spec.customTransform(backend);

            assertThat(result).doesNotContainKey("host");
            assertThat(result).doesNotContainKey("error");
            assertThat(result).doesNotContainKey("file_content");
            assertThat(result.get("success")).isEqualTo(false);
        }

        @Test
        @DisplayName("List operation: passes through files[] + file_count, no `file` FileRef synthesised")
        void listOperationPassesThroughEntries() {
            Map<String, Object> backend = Map.of(
                "success", true,
                "operation", "list",
                "remote_path", "/remote",
                "duration_ms", 30L,
                "files", List.of(
                    Map.of("name", "a.txt", "size", 1L, "is_dir", false, "modified", "2026-04-28T00:00:00Z"),
                    Map.of("name", "b.txt", "size", 2L, "is_dir", false, "modified", "2026-04-28T00:00:01Z")
                ),
                "file_count", 2
            );

            Map<String, Object> result = spec.customTransform(backend);

            assertThat(result.get("operation")).isEqualTo("list");
            assertThat(result.get("file_count")).isEqualTo(2);
            assertThat(result.get("files")).asList().hasSize(2);
            assertThat(result).doesNotContainKey("file");
        }

        @Test
        @DisplayName("Rename operation: surfaces new_path, no `file` FileRef synthesised")
        void renameOperationPropagatesNewPath() {
            Map<String, Object> backend = Map.of(
                "success", true,
                "operation", "rename",
                "remote_path", "/old.txt",
                "duration_ms", 20L,
                "new_path", "/new.txt"
            );

            Map<String, Object> result = spec.customTransform(backend);

            assertThat(result.get("new_path")).isEqualTo("/new.txt");
            assertThat(result).doesNotContainKey("file");
        }

        @Test
        @DisplayName("Upload operation: surfaces uploaded_size, no `file` FileRef synthesised")
        void uploadOperationKeepsUploadedSizeOnly() {
            Map<String, Object> backend = Map.of(
                "success", true,
                "operation", "upload",
                "remote_path", "/upload.txt",
                "duration_ms", 40L,
                "uploaded_size", 1234L
            );

            Map<String, Object> result = spec.customTransform(backend);

            assertThat(result.get("operation")).isEqualTo("upload");
            assertThat(result.get("uploaded_size")).isEqualTo(1234L);
            assertThat(result).doesNotContainKey("file");
        }

        @Test
        @DisplayName("null input returns empty map without NPE")
        void nullInputReturnsEmptyMap() {
            assertThat(spec.customTransform(null)).isEmpty();
        }
    }

    private Set<String> outputKeys() {
        List<OutputFieldDef> fields = spec.definition().outputs();
        return fields.stream().map(OutputFieldDef::key).collect(java.util.stream.Collectors.toSet());
    }
}
