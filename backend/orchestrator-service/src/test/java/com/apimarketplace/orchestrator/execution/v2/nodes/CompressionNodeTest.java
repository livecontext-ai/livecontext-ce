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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CompressionNode.
 * Tests compress/decompress operations with gzip and deflate formats.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CompressionNode")
class CompressionNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("data", "test-value");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Shape-parity regression tests
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Canonical-only regression pin (PR2 2026-05-15 clean break):
     * execute() emits ONLY the canonical FileRef under the "file" key. The legacy flat
     * fields file_url / file_name / file_size / content_type are NO LONGER emitted -
     * agents and SpEL templates must address sub-fields via {{core:compress.output.file.url}}
     * etc. customTransform() remains identity over the canonical map (modulo engine envelope
     * stripping).
     */
    @Nested
    @DisplayName("Shape parity: execute() output matches persisted shape (canonical-only)")
    class ShapeParityTests {

        @Mock
        private FileStorageService fileStorageService;

        @Test
        @DisplayName("executeOutputMatchesPersistedShape: execute() canonical keys equal customTransform() keys")
        void executeOutputMatchesPersistedShape() {
            FileRef stored = FileRef.of("tenant/wf/run/node/compressed.gz", "compressed.gz", "application/gzip", 42L);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(stored);

            Core.CompressionConfig config = new Core.CompressionConfig("compress", "gzip", "hello world", "compressed");
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress")
                .compressionConfig(config)
                .build();
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());

            Map<String, Object> executeOutput = result.output();

            // PR2 2026-05-15 clean break: emit ONLY the canonical FileRef under 'file'.
            // The legacy flat fields (file_url/file_name/file_size/content_type) are gone -
            // pin both the canonical presence/type AND the explicit absence of each legacy key.
            assertInstanceOf(FileRef.class,
                executeOutput.get("file"),
                "execute() `file` value must be a FileRef record");
            assertFalse(executeOutput.containsKey("file_url"),     "PR2 clean break - must NOT emit legacy file_url");
            assertFalse(executeOutput.containsKey("file_name"),    "PR2 clean break - must NOT emit legacy file_name");
            assertFalse(executeOutput.containsKey("file_size"),    "PR2 clean break - must NOT emit legacy file_size");
            assertFalse(executeOutput.containsKey("content_type"), "PR2 clean break - must NOT emit legacy content_type");

            // customTransform must strip engine-envelope keys and preserve canonical FileRef only.
            CompressionNodeSpec spec = new CompressionNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);
            assertInstanceOf(FileRef.class,
                persistedOutput.get("file"),
                "persisted `file` value must be a FileRef record");
            assertFalse(persistedOutput.containsKey("file_url"),     "PR2 clean break - must NOT emit legacy file_url");
            assertFalse(persistedOutput.containsKey("file_name"),    "PR2 clean break - must NOT emit legacy file_name");
            assertFalse(persistedOutput.containsKey("file_size"),    "PR2 clean break - must NOT emit legacy file_size");
            assertFalse(persistedOutput.containsKey("content_type"), "PR2 clean break - must NOT emit legacy content_type");
            assertFalse(persistedOutput.containsKey("node_type"),    "persisted output must NOT contain engine key node_type");
            assertFalse(persistedOutput.containsKey("item_index"),   "persisted output must NOT contain engine key item_index");
            assertFalse(persistedOutput.containsKey("itemIndex"),    "persisted output must NOT contain engine key itemIndex");
            assertFalse(persistedOutput.containsKey("item_id"),      "persisted output must NOT contain engine key item_id");
            assertFalse(persistedOutput.containsKey("resolved_params"), "persisted output must NOT contain engine key resolved_params");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Gzip roundtrip tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Gzip Compress/Decompress Roundtrip")
    class GzipRoundtripTests {

        @Test
        @DisplayName("Should compress and decompress roundtrip with gzip")
        void shouldCompressAndDecompressRoundtripGzip() {
            String original = "Hello, World! This is a test string for compression.";

            // Compress
            Core.CompressionConfig compressConfig = new Core.CompressionConfig("compress", "gzip", original, null);
            CompressionNode compressNode = CompressionNode.builder()
                .nodeId("core:compress_gzip")
                .compressionConfig(compressConfig)
                .build();

            NodeExecutionResult compressResult = compressNode.execute(context);
            assertTrue(compressResult.isSuccess());
            String compressed = (String) compressResult.output().get("result");
            assertNotNull(compressed);
            assertNotEquals(original, compressed);
            assertEquals("compress", compressResult.output().get("operation"));
            assertEquals("gzip", compressResult.output().get("format"));
            assertTrue((Boolean) compressResult.output().get("success"));

            // Decompress
            Core.CompressionConfig decompressConfig = new Core.CompressionConfig("decompress", "gzip", compressed, null);
            CompressionNode decompressNode = CompressionNode.builder()
                .nodeId("core:decompress_gzip")
                .compressionConfig(decompressConfig)
                .build();

            NodeExecutionResult decompressResult = decompressNode.execute(context);
            assertTrue(decompressResult.isSuccess());
            String decompressed = (String) decompressResult.output().get("result");
            assertEquals(original, decompressed);
            assertEquals("decompress", decompressResult.output().get("operation"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Deflate roundtrip tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Deflate Compress/Decompress Roundtrip")
    class DeflateRoundtripTests {

        @Test
        @DisplayName("Should compress and decompress roundtrip with deflate")
        void shouldCompressAndDecompressRoundtripDeflate() {
            String original = "Hello, World! This is a test string for deflate compression.";

            // Compress
            Core.CompressionConfig compressConfig = new Core.CompressionConfig("compress", "deflate", original, null);
            CompressionNode compressNode = CompressionNode.builder()
                .nodeId("core:compress_deflate")
                .compressionConfig(compressConfig)
                .build();

            NodeExecutionResult compressResult = compressNode.execute(context);
            assertTrue(compressResult.isSuccess());
            String compressed = (String) compressResult.output().get("result");
            assertNotNull(compressed);
            assertNotEquals(original, compressed);

            // Decompress
            Core.CompressionConfig decompressConfig = new Core.CompressionConfig("decompress", "deflate", compressed, null);
            CompressionNode decompressNode = CompressionNode.builder()
                .nodeId("core:decompress_deflate")
                .compressionConfig(decompressConfig)
                .build();

            NodeExecutionResult decompressResult = decompressNode.execute(context);
            assertTrue(decompressResult.isSuccess());
            String decompressed = (String) decompressResult.output().get("result");
            assertEquals(original, decompressed);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Zip roundtrip tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Zip Compress/Decompress Roundtrip")
    class ZipRoundtripTests {

        @Test
        @DisplayName("Should compress and decompress roundtrip with zip")
        void shouldCompressAndDecompressRoundtripZip() {
            String original = "Hello, World! This is a test string for zip compression.";

            // Compress
            Core.CompressionConfig compressConfig = new Core.CompressionConfig("compress", "zip", original, "test.txt");
            CompressionNode compressNode = CompressionNode.builder()
                .nodeId("core:compress_zip")
                .compressionConfig(compressConfig)
                .build();

            NodeExecutionResult compressResult = compressNode.execute(context);
            assertTrue(compressResult.isSuccess());
            String compressed = (String) compressResult.output().get("result");
            assertNotNull(compressed);

            // Decompress
            Core.CompressionConfig decompressConfig = new Core.CompressionConfig("decompress", "zip", compressed, null);
            CompressionNode decompressNode = CompressionNode.builder()
                .nodeId("core:decompress_zip")
                .compressionConfig(decompressConfig)
                .build();

            NodeExecutionResult decompressResult = decompressNode.execute(context);
            assertTrue(decompressResult.isSuccess());
            assertEquals(original, decompressResult.output().get("result"));
        }

        @Test
        @DisplayName("Should use default filename when not specified")
        void shouldUseDefaultFilename() {
            Core.CompressionConfig config = new Core.CompressionConfig("compress", "zip", "data", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_zip_default")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("result"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Base64 roundtrip tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Base64 Encode/Decode Roundtrip")
    class Base64RoundtripTests {

        @Test
        @DisplayName("Should encode and decode roundtrip with base64")
        void shouldEncodeAndDecodeRoundtripBase64() {
            String original = "Hello, World! Base64 encoding test.";

            // Encode
            Core.CompressionConfig encodeConfig = new Core.CompressionConfig("compress", "base64", original, null);
            CompressionNode encodeNode = CompressionNode.builder()
                .nodeId("core:encode_base64")
                .compressionConfig(encodeConfig)
                .build();

            NodeExecutionResult encodeResult = encodeNode.execute(context);
            assertTrue(encodeResult.isSuccess());
            String encoded = (String) encodeResult.output().get("result");
            assertNotNull(encoded);
            assertNotEquals(original, encoded);

            // Decode
            Core.CompressionConfig decodeConfig = new Core.CompressionConfig("decompress", "base64", encoded, null);
            CompressionNode decodeNode = CompressionNode.builder()
                .nodeId("core:decode_base64")
                .compressionConfig(decodeConfig)
                .build();

            NodeExecutionResult decodeResult = decodeNode.execute(context);
            assertTrue(decodeResult.isSuccess());
            assertEquals(original, decodeResult.output().get("result"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Null/empty input handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null/Empty Input Handling")
    class NullEmptyInputTests {

        @Test
        @DisplayName("Should handle null value gracefully")
        void shouldHandleNullValue() {
            Core.CompressionConfig config = new Core.CompressionConfig("compress", "gzip", null, null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_null")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            assertEquals("", result.output().get("result"));
        }

        @Test
        @DisplayName("Should handle empty string value")
        void shouldHandleEmptyStringValue() {
            Core.CompressionConfig config = new Core.CompressionConfig("compress", "gzip", "", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_empty")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            assertEquals("", result.output().get("result"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Invalid compressed data
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Invalid Compressed Data")
    class InvalidDataTests {

        @Test
        @DisplayName("Should fail on invalid base64 for decompress")
        void shouldFailOnInvalidBase64ForDecompress() {
            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "gzip", "not-valid-base64!!!", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:decompress_invalid")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail on valid base64 but not gzip data")
        void shouldFailOnValidBase64ButNotGzipData() {
            // Valid base64 but not valid gzip content
            String invalidGzip = java.util.Base64.getEncoder().encodeToString("not-gzip-data".getBytes());
            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "gzip", invalidGzip, null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:decompress_bad_gzip")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Zip bomb protection tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Zip Bomb Protection")
    class ZipBombProtectionTests {

        @Test
        @DisplayName("Should reject decompressed gzip data exceeding 50MB limit")
        void shouldRejectOversizedGzipDecompression() throws Exception {
            // Create a gzip-compressed payload that expands beyond the limit.
            // We compress a stream of zeros (compresses extremely well) so the
            // compressed payload is tiny but decompresses to > MAX_DECOMPRESSED_SIZE.
            long targetSize = CompressionNode.MAX_DECOMPRESSED_SIZE + 1024;
            byte[] hugePayload = new byte[(int) Math.min(targetSize, Integer.MAX_VALUE)];
            java.util.Arrays.fill(hugePayload, (byte) 'A');

            java.io.ByteArrayOutputStream compressedOut = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(compressedOut)) {
                gzipOut.write(hugePayload);
            }
            String base64Compressed = java.util.Base64.getEncoder().encodeToString(compressedOut.toByteArray());

            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "gzip", base64Compressed, null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:decompress_bomb")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent() && result.errorMessage().get().contains("maximum allowed size"));
        }

        @Test
        @DisplayName("Should reject decompressed zip data exceeding 50MB limit")
        void shouldRejectOversizedZipDecompression() throws Exception {
            long targetSize = CompressionNode.MAX_DECOMPRESSED_SIZE + 1024;
            byte[] hugePayload = new byte[(int) Math.min(targetSize, Integer.MAX_VALUE)];
            java.util.Arrays.fill(hugePayload, (byte) 'B');

            java.io.ByteArrayOutputStream compressedOut = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zipOut = new java.util.zip.ZipOutputStream(compressedOut)) {
                zipOut.putNextEntry(new java.util.zip.ZipEntry("bomb.txt"));
                zipOut.write(hugePayload);
                zipOut.closeEntry();
            }
            String base64Compressed = java.util.Base64.getEncoder().encodeToString(compressedOut.toByteArray());

            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "zip", base64Compressed, null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:decompress_zip_bomb")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent() && result.errorMessage().get().contains("maximum allowed size"));
        }

        @Test
        @DisplayName("Should reject decompressed deflate data exceeding 50MB limit")
        void shouldRejectOversizedDeflateDecompression() throws Exception {
            long targetSize = CompressionNode.MAX_DECOMPRESSED_SIZE + 1024;
            byte[] hugePayload = new byte[(int) Math.min(targetSize, Integer.MAX_VALUE)];
            java.util.Arrays.fill(hugePayload, (byte) 'C');

            java.io.ByteArrayOutputStream compressedOut = new java.io.ByteArrayOutputStream();
            try (java.util.zip.DeflaterOutputStream deflater = new java.util.zip.DeflaterOutputStream(compressedOut)) {
                deflater.write(hugePayload);
            }
            String base64Compressed = java.util.Base64.getEncoder().encodeToString(compressedOut.toByteArray());

            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "deflate", base64Compressed, null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:decompress_deflate_bomb")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent() && result.errorMessage().get().contains("maximum allowed size"));
        }

        @Test
        @DisplayName("Should allow decompression within 50MB limit")
        void shouldAllowDecompressionWithinLimit() {
            // Normal-sized data should work fine
            String original = "This is a normal-sized string for decompression testing.";
            Core.CompressionConfig compressConfig = new Core.CompressionConfig("compress", "gzip", original, null);
            CompressionNode compressNode = CompressionNode.builder()
                .nodeId("core:compress_normal")
                .compressionConfig(compressConfig)
                .build();

            NodeExecutionResult compressResult = compressNode.execute(context);
            assertTrue(compressResult.isSuccess());
            String compressed = (String) compressResult.output().get("result");

            Core.CompressionConfig decompressConfig = new Core.CompressionConfig("decompress", "gzip", compressed, null);
            CompressionNode decompressNode = CompressionNode.builder()
                .nodeId("core:decompress_normal")
                .compressionConfig(decompressConfig)
                .build();

            NodeExecutionResult decompressResult = decompressNode.execute(context);
            assertTrue(decompressResult.isSuccess());
            assertEquals(original, decompressResult.output().get("result"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Unknown format tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Unknown Format Handling")
    class UnknownFormatTests {

        @Test
        @DisplayName("Should fail on unknown compression format")
        void shouldFailOnUnknownCompressionFormat() {
            Core.CompressionConfig config = new Core.CompressionConfig("compress", "lz4", "test data", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_unknown")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent() && result.errorMessage().get().contains("Unsupported compression format"));
            assertTrue(result.errorMessage().isPresent() && result.errorMessage().get().contains("lz4"));
        }

        @Test
        @DisplayName("Should fail on unknown decompression format")
        void shouldFailOnUnknownDecompressionFormat() {
            // base64 of "test" so the base64 decode succeeds before format check
            String base64Input = java.util.Base64.getEncoder().encodeToString("test".getBytes());
            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "brotli", base64Input, null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:decompress_unknown")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().isPresent() && result.errorMessage().get().contains("Unsupported decompression format"));
            assertTrue(result.errorMessage().isPresent() && result.errorMessage().get().contains("brotli"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Default operation/format
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Default Operation and Format")
    class DefaultsTests {

        @Test
        @DisplayName("Should default to compress operation when null")
        void shouldDefaultToCompressOperationWhenNull() {
            Core.CompressionConfig config = new Core.CompressionConfig(null, "gzip", "test", null);
            assertEquals("compress", config.operation());
        }

        @Test
        @DisplayName("Should default to gzip format when null")
        void shouldDefaultToGzipFormatWhenNull() {
            Core.CompressionConfig config = new Core.CompressionConfig("compress", null, "test", null);
            assertEquals("gzip", config.format());
        }

        @Test
        @DisplayName("Should default both operation and format when null")
        void shouldDefaultBothWhenNull() {
            Core.CompressionConfig config = new Core.CompressionConfig(null, null, "test", null);
            assertEquals("compress", config.operation());
            assertEquals("gzip", config.format());
        }

        @Test
        @DisplayName("Should compress with defaults and produce valid output")
        void shouldCompressWithDefaultsAndProduceValidOutput() {
            Core.CompressionConfig config = new Core.CompressionConfig(null, null, "default test", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_defaults")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());
            assertEquals("compress", result.output().get("operation"));
            assertEquals("gzip", result.output().get("format"));
            assertNotNull(result.output().get("result"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Node metadata tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Node Metadata")
    class MetadataTests {

        @Test
        @DisplayName("Should have correct node type")
        void shouldHaveCorrectNodeType() {
            Core.CompressionConfig config = new Core.CompressionConfig("compress", "gzip", "test", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress")
                .compressionConfig(config)
                .build();

            assertEquals(NodeType.COMPRESSION, node.getType());
            assertEquals("core:compress", node.getNodeId());
        }

        @Test
        @DisplayName("Should return config via getter")
        void shouldReturnConfigViaGetter() {
            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "deflate", "data", "file.txt");
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress")
                .compressionConfig(config)
                .build();

            assertEquals(config, node.getConfig());
            assertEquals("decompress", node.getConfig().operation());
            assertEquals("deflate", node.getConfig().format());
            assertEquals("data", node.getConfig().value());
            assertEquals("file.txt", node.getConfig().filename());
        }

        @Test
        @DisplayName("Should build with null config and use defaults")
        void shouldBuildWithNullConfigAndUseDefaults() {
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress")
                .build();

            assertNotNull(node.getConfig());
            assertEquals("compress", node.getConfig().operation());
            assertEquals("gzip", node.getConfig().format());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // S3 Upload tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("S3 Upload")
    class S3UploadTests {

        @Mock
        private FileStorageService fileStorageService;

        @Test
        @DisplayName("compress emits only canonical FileRef under `file` - no legacy flats (PR2 2026-05-15)")
        void shouldEmitOnlyCanonicalFileRefOnCompress() {
            FileRef expectedRef = FileRef.of("path/compressed.gz", "compressed.gz", "application/gzip", 50);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), eq("compressed.gz"), eq("application/gzip"), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(expectedRef);

            Core.CompressionConfig config = new Core.CompressionConfig("compress", "gzip", "test data to compress", "compressed");
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_canonical_only")
                .compressionConfig(config)
                .build();
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertInstanceOf(FileRef.class, result.output().get("file"),
                "execute() must emit canonical FileRef under `file`");
            assertFalse(result.output().containsKey("file_url"),     "PR2 clean break - must NOT emit legacy file_url");
            assertFalse(result.output().containsKey("file_name"),    "PR2 clean break - must NOT emit legacy file_name");
            assertFalse(result.output().containsKey("file_size"),    "PR2 clean break - must NOT emit legacy file_size");
            assertFalse(result.output().containsKey("content_type"), "PR2 clean break - must NOT emit legacy content_type");
        }

        @Test
        @DisplayName("Should upload gzip compressed data to S3")
        void shouldUploadGzipToS3() {
            FileRef expectedRef = FileRef.of("path/compressed.gz", "compressed.gz", "application/gzip", 50);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), eq("compressed.gz"), eq("application/gzip"), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(expectedRef);

            Core.CompressionConfig config = new Core.CompressionConfig("compress", "gzip", "test data to compress", "compressed");
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_gzip")
                .compressionConfig(config)
                .build();
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("compressed.gz", file.name());
            assertEquals("application/gzip", file.mimeType());
        }

        @Test
        @DisplayName("Should upload zip compressed data to S3")
        void shouldUploadZipToS3() {
            FileRef expectedRef = FileRef.of("path/archive.zip", "archive.zip", "application/zip", 60);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), eq("archive.zip"), eq("application/zip"), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(expectedRef);

            Core.CompressionConfig config = new Core.CompressionConfig("compress", "zip", "test data", "archive");
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_zip")
                .compressionConfig(config)
                .build();
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("application/zip", file.mimeType());
        }

        @Test
        @DisplayName("Should not upload on decompress")
        void shouldNotUploadOnDecompress() {
            // First compress to get valid base64
            String original = "test data for decompress";
            Core.CompressionConfig compressConfig = new Core.CompressionConfig("compress", "gzip", original, null);
            CompressionNode compressNode = CompressionNode.builder()
                .nodeId("core:compress")
                .compressionConfig(compressConfig)
                .build();
            NodeExecutionResult compressResult = compressNode.execute(context);
            String compressed = (String) compressResult.output().get("result");

            // Now decompress with fileStorageService set
            Core.CompressionConfig decompressConfig = new Core.CompressionConfig("decompress", "gzip", compressed, null);
            CompressionNode decompressNode = CompressionNode.builder()
                .nodeId("core:decompress")
                .compressionConfig(decompressConfig)
                .build();
            decompressNode.setFileStorageService(fileStorageService);

            NodeExecutionResult result = decompressNode.execute(context);

            assertTrue(result.isSuccess());
            assertTrue(result.output().containsKey("file"),
                "PR2 shape-stability: `file` key must be present so `{{...output.file}}` resolves consistently");
            assertNull(result.output().get("file"),
                "decompress must emit `file:null` (no FileRef produced)");
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("Should succeed when FileStorageService is null")
        void shouldSucceedWhenFileStorageServiceIsNull() {
            Core.CompressionConfig config = new Core.CompressionConfig("compress", "gzip", "test data", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress")
                .compressionConfig(config)
                .build();
            // No fileStorageService set

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertTrue(result.output().containsKey("file"),
                "PR2 shape-stability: `file` key must be present even when FileStorageService is null");
            assertNull(result.output().get("file"),
                "PR2 shape-stability: `file` must be null when no FileRef was produced");
            assertNotNull(result.output().get("result"));
        }

        @Test
        @DisplayName("failureOutputDoesNotContainErrorKey: persisted output must not contain undeclared 'error' key on failure")
        void failureOutputDoesNotContainErrorKey() {
            // Trigger failure by supplying invalid base64 for decompress
            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "gzip", "not-valid-base64!!!", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_fail")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure(), "Expected failure on invalid input");
            Map<String, Object> executeOutput = result.output();
            assertFalse(executeOutput.containsKey("error"),
                "execute() output must NOT contain undeclared 'error' key - use NodeExecutionResult error channel");

            // Also verify customTransform does not surface an 'error' key
            CompressionNodeSpec spec = new CompressionNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);
            assertFalse(persistedOutput.containsKey("error"),
                "persisted output must NOT contain undeclared 'error' key");
        }

        @Test
        @DisplayName("PR2 regression: failure output emits `file:null` and NO legacy flat keys (file_url/file_name/file_size/content_type)")
        void failureOutputEmitsCanonicalShapeOnly() {
            // Decompress on invalid base64 → fail path. Same scenario as
            // failureOutputDoesNotContainErrorKey but asserts the PR2 contract.
            Core.CompressionConfig config = new Core.CompressionConfig("decompress", "gzip", "not-valid-base64!!!", null);
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress_fail")
                .compressionConfig(config)
                .build();

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure(), "Expected failure on invalid input");
            Map<String, Object> executeOutput = result.output();
            assertTrue(executeOutput.containsKey("file"),
                "PR2 shape-stability: `file` key must be present on failure for downstream `{{...output.file}}` to resolve");
            assertNull(executeOutput.get("file"), "PR2: `file` must be null on failure");
            assertFalse(executeOutput.containsKey("file_url"), "PR2 clean break: legacy `file_url` must not appear on failure");
            assertFalse(executeOutput.containsKey("file_name"), "PR2 clean break: legacy `file_name` must not appear on failure");
            assertFalse(executeOutput.containsKey("file_size"), "PR2 clean break: legacy `file_size` must not appear on failure");
            assertFalse(executeOutput.containsKey("content_type"), "PR2 clean break: legacy `content_type` must not appear on failure");

            CompressionNodeSpec spec = new CompressionNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);
            assertTrue(persistedOutput.containsKey("file"), "PR2: `file:null` must survive customTransform on failure");
            assertNull(persistedOutput.get("file"));
            assertFalse(persistedOutput.containsKey("file_url"));
            assertFalse(persistedOutput.containsKey("file_name"));
            assertFalse(persistedOutput.containsKey("file_size"));
            assertFalse(persistedOutput.containsKey("content_type"));
        }

        @Test
        @DisplayName("Should succeed when S3 upload fails")
        void shouldSucceedWhenS3UploadFails() {
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenThrow(new RuntimeException("S3 unavailable"));

            Core.CompressionConfig config = new Core.CompressionConfig("compress", "gzip", "test data", "compressed");
            CompressionNode node = CompressionNode.builder()
                .nodeId("core:compress")
                .compressionConfig(config)
                .build();
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertTrue(result.output().containsKey("file"),
                "PR2 shape-stability: `file` key must be present even when S3 upload throws");
            assertNull(result.output().get("file"),
                "PR2 shape-stability: `file` must be null when S3 upload throws");
            assertNotNull(result.output().get("result"));
        }
    }
}
