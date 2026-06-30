package com.apimarketplace.orchestrator.domain.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FileRef")
class FileRefTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFields() {
            FileRef ref = new FileRef("file", "tenant/path/doc.pdf", "doc.pdf", "application/pdf", 1024, "row-uuid-1");

            assertEquals("file", ref.type());
            assertEquals("tenant/path/doc.pdf", ref.path());
            assertEquals("doc.pdf", ref.name());
            assertEquals("application/pdf", ref.mimeType());
            assertEquals(1024, ref.size());
            assertEquals("row-uuid-1", ref.id());
        }

        @Test
        @DisplayName("Should allow null fields except size")
        void shouldAllowNullFields() {
            FileRef ref = new FileRef(null, null, null, null, 0, null);

            assertNull(ref.type());
            assertNull(ref.path());
            assertNull(ref.name());
            assertNull(ref.mimeType());
            assertEquals(0, ref.size());
            assertNull(ref.id());
        }
    }

    @Nested
    @DisplayName("of() factory method")
    class OfTests {

        @Test
        @DisplayName("Should create FileRef with TYPE_FILE type")
        void shouldCreateWithFileType() {
            FileRef ref = FileRef.of("tenant/path/image.png", "image.png", "image/png", 2048);

            assertEquals(FileRef.TYPE_FILE, ref.type());
            assertEquals("file", ref.type());
            assertEquals("tenant/path/image.png", ref.path());
            assertEquals("image.png", ref.name());
            assertEquals("image/png", ref.mimeType());
            assertEquals(2048, ref.size());
        }

        @Test
        @DisplayName("Should create valid FileRef for zero-size file")
        void shouldCreateForZeroSize() {
            FileRef ref = FileRef.of("path/empty.txt", "empty.txt", "text/plain", 0);
            assertEquals(0, ref.size());
        }
    }

    @Nested
    @DisplayName("isFileRef()")
    class IsFileRefTests {

        @Test
        @DisplayName("Should return true when type is 'file'")
        void shouldReturnTrueForFileType() {
            FileRef ref = FileRef.of("path/doc.pdf", "doc.pdf", "application/pdf", 100);
            assertTrue(ref.isFileRef());
        }

        @Test
        @DisplayName("Should return true for manually created with correct type")
        void shouldReturnTrueForManualFileType() {
            FileRef ref = new FileRef("file", "path", "name", "mime", 100, null);
            assertTrue(ref.isFileRef());
        }

        @Test
        @DisplayName("Should return false when type is not 'file'")
        void shouldReturnFalseForNonFileType() {
            FileRef ref = new FileRef("image", "path", "name", "mime", 100, null);
            assertFalse(ref.isFileRef());
        }

        @Test
        @DisplayName("Should return false when type is null")
        void shouldReturnFalseForNullType() {
            FileRef ref = new FileRef(null, "path", "name", "mime", 100, null);
            assertFalse(ref.isFileRef());
        }
    }

    @Nested
    @DisplayName("TYPE_FILE constant")
    class TypeFileConstantTests {

        @Test
        @DisplayName("TYPE_FILE should be 'file'")
        void typeFileShouldBeFile() {
            assertEquals("file", FileRef.TYPE_FILE);
        }
    }

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("Equal FileRefs should be equal")
        void equalFileRefsShouldBeEqual() {
            FileRef ref1 = FileRef.of("path", "name", "mime", 100);
            FileRef ref2 = FileRef.of("path", "name", "mime", 100);
            assertEquals(ref1, ref2);
        }

        @Test
        @DisplayName("Different FileRefs should not be equal")
        void differentFileRefsShouldNotBeEqual() {
            FileRef ref1 = FileRef.of("path1", "name", "mime", 100);
            FileRef ref2 = FileRef.of("path2", "name", "mime", 100);
            assertNotEquals(ref1, ref2);
        }
    }

    /**
     * Pins the JSON-serialised shape of {@link FileRef}. The marketplace + share preview
     * showcase rewriter ({@code ShowcaseFileRefRewriter.isFileRef} in publication-service)
     * recognises FileRef objects by probing the SERIALISED Map for {@code _type == "file"}
     * and a non-empty {@code path} string. The frontend {@code isFileRef} (file.service.ts)
     * and {@code injectFileProxyToken} use the same key set.
     *
     * <p>A regression dropping the {@code @JsonProperty("_type")} annotation on the {@code type}
     * field would silently emit {@code "type"} instead of {@code "_type"}, breaking the
     * rewriter for every workflow that uses the canonical FileRef output - anonymous
     * marketplace visitors would see broken images. These tests fail loudly if any of the
     * 5 canonical keys are renamed.
     */
    @Nested
    @DisplayName("JSON serialization - shape contract with ShowcaseFileRefRewriter")
    class JsonSerializationTests {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("Serialises the type field as `_type` (discriminator the showcase rewriter probes)")
        void serializesTypeFieldAsUnderscoredKey() throws Exception {
            FileRef ref = FileRef.of("tenant/wf/run/step/img.png", "img.png", "image/png", 2048L);
            JsonNode node = mapper.valueToTree(ref);

            assertTrue(node.has("_type"),
                "discriminator must be serialised as `_type` (not `type`) - required by ShowcaseFileRefRewriter.isFileRef");
            assertEquals("file", node.get("_type").asText());
            assertFalse(node.has("type"),
                "the plain `type` key must NOT appear - would shadow the discriminator probe");
        }

        @Test
        @DisplayName("Serialises path / name / mimeType / size as canonical keys")
        void serializesAllCanonicalKeys() throws Exception {
            FileRef ref = FileRef.of("tenant/wf/run/step/doc.pdf", "doc.pdf", "application/pdf", 1024L);
            JsonNode node = mapper.valueToTree(ref);

            assertEquals(5, node.size(),
                "exactly 5 fields expected (_type, path, name, mimeType, size) - adding fields silently breaks downstream contracts");
            assertEquals("tenant/wf/run/step/doc.pdf", node.get("path").asText());
            assertEquals("doc.pdf", node.get("name").asText());
            assertEquals("application/pdf", node.get("mimeType").asText());
            assertEquals(1024L, node.get("size").asLong());
        }

        @Test
        @DisplayName("Round-trips through ObjectMapper without losing `_type` (Map<String,Object> consumer parity)")
        void roundTripsThroughObjectMapper() throws Exception {
            FileRef ref = FileRef.of("p/q/r.bin", "r.bin", "application/octet-stream", 99L);
            String json = mapper.writeValueAsString(ref);

            // Re-read as a generic Map - this is exactly the shape ShowcaseFileRefRewriter,
            // FormDispatchService and the frontend isFileRef all probe (they NEVER use
            // instanceof FileRef; they all match by Map key set).
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> asMap = mapper.readValue(json, java.util.Map.class);
            assertEquals("file", asMap.get("_type"));
            assertEquals("p/q/r.bin", asMap.get("path"));
            assertEquals("r.bin", asMap.get("name"));
            assertEquals("application/octet-stream", asMap.get("mimeType"));
            assertEquals(99, ((Number) asMap.get("size")).longValue());
        }
    }
}
