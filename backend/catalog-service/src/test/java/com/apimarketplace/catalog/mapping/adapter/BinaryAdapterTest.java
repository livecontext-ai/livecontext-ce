package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BinaryAdapter class.
 *
 * BinaryAdapter handles binary format data for mapping operations.
 */
@DisplayName("BinaryAdapter")
class BinaryAdapterTest {

    private BinaryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BinaryAdapter();
    }

    // ========================================================================
    // isCollection tests
    // ========================================================================

    @Nested
    @DisplayName("isCollection()")
    class IsCollectionTests {

        @Test
        @DisplayName("should always return false for binary data")
        void shouldAlwaysReturnFalseForBinaryData() {
            byte[] input = new byte[]{0x00, 0x01, 0x02, 0x03};
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for empty input")
        void shouldReturnFalseForEmptyInput() {
            byte[] input = new byte[0];
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }
    }

    // ========================================================================
    // iterateItems tests
    // ========================================================================

    @Nested
    @DisplayName("iterateItems()")
    class IterateItemsTests {

        @Test
        @DisplayName("should return empty list for binary data")
        void shouldReturnEmptyListForBinaryData() {
            byte[] input = new byte[]{0x00, 0x01, 0x02, 0x03};
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            assertFalse(items.iterator().hasNext());
        }
    }

    // ========================================================================
    // evalScalar tests
    // ========================================================================

    @Nested
    @DisplayName("evalScalar()")
    class EvalScalarTests {

        @Test
        @DisplayName("should extract value from metadata map")
        void shouldExtractValueFromMetadataMap() {
            Map<String, Object> context = Map.of("size", 100, "mime_type", "application/pdf");

            Object result = adapter.evalScalar(context, "size");

            assertEquals(100, result);
        }

        @Test
        @DisplayName("should return null for missing key")
        void shouldReturnNullForMissingKey() {
            Map<String, Object> context = Map.of("size", 100);

            Object result = adapter.evalScalar(context, "missing");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for null context")
        void shouldReturnNullForNullContext() {
            Object result = adapter.evalScalar(null, "key");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for non-map context")
        void shouldReturnNullForNonMapContext() {
            Object result = adapter.evalScalar("not a map", "key");

            assertNull(result);
        }
    }

    // ========================================================================
    // evalNodes tests
    // ========================================================================

    @Nested
    @DisplayName("evalNodes()")
    class EvalNodesTests {

        @Test
        @DisplayName("should return empty list")
        void shouldReturnEmptyList() {
            Iterable<?> result = adapter.evalNodes(Map.of(), "path");

            assertFalse(result.iterator().hasNext());
        }
    }

    // ========================================================================
    // flatten tests
    // ========================================================================

    @Nested
    @DisplayName("flatten()")
    class FlattenTests {

        @Test
        @DisplayName("should extract size metadata")
        void shouldExtractSizeMetadata() {
            byte[] input = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04};

            Map<String, Object> result = adapter.flatten(input);

            assertEquals(5, result.get("size"));
        }

        @Test
        @DisplayName("should detect PDF MIME type")
        void shouldDetectPdfMimeType() {
            byte[] input = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("application/pdf", result.get("mime_type"));
            assertEquals("pdf", result.get("extension"));
        }

        @Test
        @DisplayName("should detect PNG MIME type")
        void shouldDetectPngMimeType() {
            byte[] input = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A};

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("image/png", result.get("mime_type"));
            assertEquals("png", result.get("extension"));
        }

        @Test
        @DisplayName("should detect JPEG MIME type")
        void shouldDetectJpegMimeType() {
            byte[] input = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0};

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("image/jpeg", result.get("mime_type"));
            assertEquals("jpg", result.get("extension"));
        }

        @Test
        @DisplayName("should detect ZIP MIME type")
        void shouldDetectZipMimeType() {
            byte[] input = new byte[]{0x50, 0x4B, 0x03, 0x04};

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("application/zip", result.get("mime_type"));
            assertEquals("zip", result.get("extension"));
        }

        @Test
        @DisplayName("should detect GIF MIME type")
        void shouldDetectGifMimeType() {
            byte[] input = new byte[]{0x47, 0x49, 0x46, 0x38, 0x39};

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("image/gif", result.get("mime_type"));
            assertEquals("gif", result.get("extension"));
        }

        @Test
        @DisplayName("should return octet-stream for unknown type")
        void shouldReturnOctetStreamForUnknownType() {
            byte[] input = new byte[]{0x00, 0x00, 0x00, 0x00};

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("application/octet-stream", result.get("mime_type"));
            assertEquals("bin", result.get("extension"));
        }

        @Test
        @DisplayName("should calculate hash")
        void shouldCalculateHash() {
            byte[] input = new byte[]{0x01, 0x02, 0x03, 0x04};

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("hash"));
            assertTrue(result.get("hash").toString().length() > 0);
        }

        @Test
        @DisplayName("should detect text data")
        void shouldDetectTextData() {
            byte[] input = "Hello, World!".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertTrue((Boolean) result.get("is_text"));
            assertEquals("Hello, World!", result.get("text_content"));
        }

        @Test
        @DisplayName("should include magic bytes")
        void shouldIncludeMagicBytes() {
            byte[] input = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("magic_bytes"));
            assertEquals("01020304", result.get("magic_bytes"));
        }
    }

    // ========================================================================
    // getRoot tests
    // ========================================================================

    @Nested
    @DisplayName("getRoot()")
    class GetRootTests {

        @Test
        @DisplayName("should return metadata map")
        void shouldReturnMetadataMap() {
            byte[] input = new byte[]{0x00, 0x01, 0x02, 0x03};

            Object result = adapter.getRoot(input);

            assertNotNull(result);
            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) result;
            assertEquals(4, metadata.get("size"));
        }

        @Test
        @DisplayName("should handle empty input")
        void shouldHandleEmptyInput() {
            byte[] input = new byte[0];

            Object result = adapter.getRoot(input);

            assertNotNull(result);
            assertTrue(result instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) result;
            assertEquals(0, metadata.get("size"));
        }
    }
}
