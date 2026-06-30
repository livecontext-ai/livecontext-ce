package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SftpNode.
 * Tests focus on validation paths (null config, missing required fields)
 * since real SFTP connections cannot be tested in unit tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SftpNode")
class SftpNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-0",
            0,
            Map.of(),
            mockPlan
        );
    }

    @Nested
    @DisplayName("execute - validation")
    class ExecuteValidation {

        @Test
        @DisplayName("should return failure when config is null")
        void execute_withNullConfig_returnsFailure() {
            SftpNode node = new SftpNode("core:sftp", null);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("SFTP configuration is required"));
            assertEquals("SFTP", result.output().get("node_type"));
        }

        @Test
        @DisplayName("should return failure when host is missing")
        void execute_withMissingHost_returnsFailure() {
            Core.SftpConfig config = new Core.SftpConfig(
                null, null, "user", "password", "pass123", null,
                "list", "/remote/path", null, null, null, null
            );
            SftpNode node = new SftpNode("core:sftp", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("'host' is required"));
        }

        @Test
        @DisplayName("should return failure when remotePath is missing")
        void execute_withMissingRemotePath_returnsFailure() {
            Core.SftpConfig config = new Core.SftpConfig(
                "myhost.example.com", null, "user", "password", "pass123", null,
                "list", null, null, null, null, null
            );
            SftpNode node = new SftpNode("core:sftp", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("'remotePath' is required"));
        }

        @Test
        @DisplayName("should return failure when host is blank")
        void execute_withBlankHost_returnsFailure() {
            Core.SftpConfig config = new Core.SftpConfig(
                "   ", null, "user", "password", "pass123", null,
                "list", "/remote/path", null, null, null, null
            );
            SftpNode node = new SftpNode("core:sftp", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().get().contains("'host' is required"));
        }

        @Test
        @DisplayName("should return failure when remotePath is blank")
        void execute_withBlankRemotePath_returnsFailure() {
            Core.SftpConfig config = new Core.SftpConfig(
                "myhost.example.com", null, "user", "password", "pass123", null,
                "list", "   ", null, null, null, null
            );
            SftpNode node = new SftpNode("core:sftp", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().get().contains("'remotePath' is required"));
        }

        @Test
        @DisplayName("PR2 regression: download failure emits `file:null` and NO legacy flat keys (file_url/file_name/file_size/content_type)")
        void execute_downloadFailure_emitsCanonicalShapeOnly() {
            // host blank → buildErrorResult fires with operation='download'.
            Core.SftpConfig config = new Core.SftpConfig(
                "   ", null, "user", "password", "pass123", null,
                "download", "/remote/file.bin", null, null, null, null
            );
            SftpNode node = new SftpNode("core:sftp", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess(), "Expected failure on blank host");
            Map<String, Object> output = result.output();
            assertTrue(output.containsKey("file"),
                "PR2 shape-stability: download failure must emit `file:null` so `{{...output.file}}` resolves consistently");
            assertNull(output.get("file"), "PR2: `file` must be null on download failure");
            assertFalse(output.containsKey("file_url"), "PR2 clean break: legacy `file_url` must not appear on failure");
            assertFalse(output.containsKey("file_name"), "PR2 clean break: legacy `file_name` must not appear on failure");
            assertFalse(output.containsKey("file_size"), "PR2 clean break: legacy `file_size` must not appear on failure");
            assertFalse(output.containsKey("content_type"), "PR2 clean break: legacy `content_type` must not appear on failure");

            // Confirm SftpNodeSpec.customTransform keeps `file:null` after envelope-strip.
            SftpNodeSpec spec = new SftpNodeSpec();
            Map<String, Object> persisted = spec.customTransform(output);
            assertTrue(persisted.containsKey("file"), "PR2: `file:null` must survive customTransform");
            assertNull(persisted.get("file"));
            assertFalse(persisted.containsKey("file_url"));
            assertFalse(persisted.containsKey("file_name"));
            assertFalse(persisted.containsKey("file_size"));
            assertFalse(persisted.containsKey("content_type"));
        }

        @Test
        @DisplayName("PR2 regression: non-download failure (list/upload/etc.) does NOT emit `file` key - operation-scoped")
        void execute_nonDownloadFailure_doesNotEmitFileKey() {
            // host blank → buildErrorResult fires with operation='list'. `file` MUST be absent.
            Core.SftpConfig config = new Core.SftpConfig(
                "   ", null, "user", "password", "pass123", null,
                "list", "/remote/path", null, null, null, null
            );
            SftpNode node = new SftpNode("core:sftp", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            // For non-download operations, `file` is not a declared output; keeping the key
            // absent matches NodeSpec.outputs() description ("file: Canonical FileRef for the
            // download operation"). Consumers probing `{{core:list.output.file}}` get null
            // from the template engine either way, but the in-memory map stays operation-scoped.
            assertFalse(result.output().containsKey("file"),
                "PR2 operation-scoped: `file` key MUST be absent for non-download operations");
            assertFalse(result.output().containsKey("file_url"));
            assertFalse(result.output().containsKey("file_name"));
            assertFalse(result.output().containsKey("file_size"));
            assertFalse(result.output().containsKey("content_type"));
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build SftpNode with builder pattern")
        void builder_pattern_works() {
            Core.SftpConfig config = new Core.SftpConfig(
                "sftp.example.com", 2222, "admin", "password", "secret", null,
                "upload", "/upload/file.txt", "file content here", null, 60000, null
            );

            SftpNode node = SftpNode.builder()
                .nodeId("core:my_sftp")
                .sftpConfig(config)
                .build();

            assertEquals("core:my_sftp", node.getNodeId());
            assertEquals(NodeType.SFTP, node.getType());
            assertNotNull(node.getConfig());
            assertEquals("sftp.example.com", node.getConfig().host());
            assertEquals(2222, node.getConfig().port());
            assertEquals("upload", node.getConfig().operation());
            assertEquals("/upload/file.txt", node.getConfig().remotePath());
        }
    }

    /**
     * Regression pin (PR2 2026-05-15): buildDownloadResult() emits ONLY the canonical FileRef
     * under `file`. The legacy flat fields (file_url, file_name, file_size, content_type) were
     * removed - emitting them would mislead workflow authors into using a shape that the
     * showcase HMAC rewriter doesn't recognise.
     */
    @Nested
    @DisplayName("Shape parity: buildDownloadResult() emits canonical-only FileRef")
    class ShapeParityTests {

        @Mock FileStorageService fileStorageService;

        @Test
        @DisplayName("executeOutputMatchesPersistedShape: only `file` FileRef, no legacy flats; customTransform() is identity")
        void executeOutputMatchesPersistedShape() {
            when(mockPlan.getId()).thenReturn("wf-1");
            FileRef stored = FileRef.of("tenant/wf/run/sftp/file.bin", "file.bin", "application/octet-stream", 10L);
            when(fileStorageService.upload(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any())).thenReturn(stored);

            Core.SftpConfig config = new Core.SftpConfig(
                "host", 22, "u", "p", null, null,
                "download", "/remote/file.bin", null, null, 30000, null);
            SftpNode node = new SftpNode("core:sftp", config);
            node.setFileStorageService(fileStorageService);

            byte[] data = "0123456789".getBytes();
            Map<String, Object> executeOutput = node.buildDownloadResult(data, "/remote/file.bin", context);

            // Canonical FileRef ONLY (PR2 2026-05-15 clean break). The value MUST be a FileRef
            // record (catches a regression that puts a String or Map here, which would still
            // pass containsKey but break the showcase rewriter / frontend detection).
            assertInstanceOf(com.apimarketplace.orchestrator.domain.file.FileRef.class,
                executeOutput.get("file"),
                "buildDownloadResult() `file` value must be a FileRef record");
            assertFalse(executeOutput.containsKey("file_url"),     "buildDownloadResult() must NOT emit legacy file_url (PR2 clean break)");
            assertFalse(executeOutput.containsKey("file_name"),    "buildDownloadResult() must NOT emit legacy file_name (PR2 clean break)");
            assertFalse(executeOutput.containsKey("file_size"),    "buildDownloadResult() must NOT emit legacy file_size (PR2 clean break)");
            assertFalse(executeOutput.containsKey("content_type"), "buildDownloadResult() must NOT emit legacy content_type (PR2 clean break)");

            // customTransform must strip envelope/runtime keys but preserve `file`.
            SftpNodeSpec spec = new SftpNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);
            assertTrue(persistedOutput.containsKey("file"), "customTransform() must preserve the canonical `file` key");
        }
    }

    /**
     * Regression for the v3.0 SFTP migration: download used to return raw
     * inline base64 in {@code file_content}, which never made it to S3 and
     * never appeared in the user's Files panel. The contract now matches
     * DownloadFileNode / ConvertToFileNode - bytes go through
     * {@link FileStorageService}, the result carries canonical flat keys, and
     * the {@code storage.storage} index row is populated by the storage
     * adapter so the Files panel surfaces the download.
     */
    @Nested
    @DisplayName("download - S3 upload contract (v3.0)")
    class DownloadS3Contract {

        @Mock FileStorageService fileStorageService;

        @Test
        @DisplayName("uploads bytes via FileStorageService and returns FileRef under output.file (no inline base64)")
        void downloadProducesFileRef() {
            when(mockPlan.getId()).thenReturn("workflow-123");
            FileRef stored = FileRef.of(
                    "tenant-1/workflow-123/run-1/core:sftp/report.csv",
                    "report.csv", "text/csv", 6L);
            when(fileStorageService.upload(
                    eq("tenant-1"), eq("workflow-123"), eq("run-1"), eq("core:sftp"),
                    eq("report.csv"), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                    .thenReturn(stored);

            Core.SftpConfig config = new Core.SftpConfig(
                    "host", 22, "u", "p", null, null,
                    "download", "/remote/report.csv", null, null, 30000, null);
            SftpNode node = new SftpNode("core:sftp", config);
            node.setFileStorageService(fileStorageService);

            byte[] data = "hello\n".getBytes();
            Map<String, Object> result = node.buildDownloadResult(data, "/remote/report.csv", context);

            // PR2 2026-05-15: canonical FileRef under `file` ONLY (no legacy flats).
            assertThat(result.get("file"))
                .as("`file` value must be a FileRef record - pinning instance type catches regressions that put a String or Map here")
                .isInstanceOf(com.apimarketplace.orchestrator.domain.file.FileRef.class);
            assertThat(result).doesNotContainKey("file_url");
            assertThat(result).doesNotContainKey("file_name");
            assertThat(result).doesNotContainKey("file_size");
            assertThat(result).doesNotContainKey("content_type");
            assertThat(result)
                    .as("v3.0: bytes go through S3 - agent + downstream nodes get canonical `file`, NOT raw base64")
                    .doesNotContainKey("file_content");
        }

        @Test
        @DisplayName("falls back to inline base64 when FileStorageService is unwired (test profile / legacy)")
        void downloadFallsBackToBase64WhenStorageMissing() {
            Core.SftpConfig config = new Core.SftpConfig(
                    "host", 22, "u", "p", null, null,
                    "download", "/remote/file.bin", null, null, 30000, null);
            SftpNode node = new SftpNode("core:sftp", config);
            // No FileStorageService injected.

            byte[] data = "binary-payload".getBytes();
            Map<String, Object> result = node.buildDownloadResult(data, "/remote/file.bin", context);

            assertThat(result).doesNotContainKey("file");
            assertThat(result.get("file_content"))
                    .asString()
                    .as("fallback path keeps the bytes in base64 so downstream nodes still see the payload")
                    .isEqualTo(java.util.Base64.getEncoder().encodeToString(data));
        }

        @Test
        @DisplayName("upload failure → fall back to inline base64 (non-fatal: bytes still reach downstream)")
        void downloadFallsBackOnUploadFailure() {
            when(mockPlan.getId()).thenReturn("workflow-123");
            when(fileStorageService.upload(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                    .thenThrow(new RuntimeException("storage flake"));

            Core.SftpConfig config = new Core.SftpConfig(
                    "host", 22, "u", "p", null, null,
                    "download", "/remote/x.txt", null, null, 30000, null);
            SftpNode node = new SftpNode("core:sftp", config);
            node.setFileStorageService(fileStorageService);

            byte[] data = "x".getBytes();
            Map<String, Object> result = node.buildDownloadResult(data, "/remote/x.txt", context);

            assertThat(result).doesNotContainKey("file");
            assertThat(result.get("file_content"))
                    .asString()
                    .isEqualTo(java.util.Base64.getEncoder().encodeToString(data));
        }
    }
}
