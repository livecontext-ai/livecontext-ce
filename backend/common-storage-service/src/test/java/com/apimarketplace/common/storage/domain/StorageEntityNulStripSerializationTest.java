package com.apimarketplace.common.storage.domain;

import com.apimarketplace.common.storage.exception.StorageSerializationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Funnel tests for {@code StorageEntity.serializeToJson} - the single
 * serialization point feeding the {@code storage.storage} jsonb columns
 * ({@code data}, {@code metadata}, {@code data_mapped}).
 *
 * <p>Pins the two hardening rules of the output-loss fix:
 * <ol>
 *   <li>U+0000 codepoints are stripped (PG rejects them with 22P05, which used
 *       to poison the transaction and silently cost a step its whole output
 *       blob) while a LITERAL backslash-u0000 in the data is preserved;</li>
 *   <li>the old {@code toString()} fallback is GONE: a Jackson failure now
 *       surfaces as {@link StorageSerializationException} instead of writing
 *       non-JSON garbage into a jsonb column.</li>
 * </ol>
 */
@DisplayName("StorageEntity - jsonb funnel: NUL strip + honest serialization failure")
class StorageEntityNulStripSerializationTest {

    /** The NUL codepoint, built per convention via (char) 0 - never a source escape. */
    private static final String NUL = String.valueOf((char) 0);

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    @DisplayName("U+0000 strip (detect-then-clean)")
    class NulStrip {

        @Test
        @DisplayName("an object graph with U+0000 in nested strings persists with the codepoint gone and surrounding text intact (a<NUL>b -> ab)")
        void stripsNulFromNestedStrings() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("plain", "a" + NUL + "b");
            payload.put("nested", Map.of("list", List.of("x" + NUL, Map.of("deep" + NUL + "key", NUL + "v"))));

            StorageEntity entity = new StorageEntity("tenant-1", "application/json",
                    payload, 10, "checksum", (Instant) null);

            String stored = entity.getData();
            assertThat(stored).doesNotContain(NUL);
            Map<?, ?> back = mapper.readValue(stored, Map.class);
            assertThat(back.get("plain")).isEqualTo("ab");
            Map<?, ?> nested = (Map<?, ?>) back.get("nested");
            List<?> list = (List<?>) nested.get("list");
            assertThat(list.get(0)).isEqualTo("x");
            assertThat(((Map<?, ?>) list.get(1)).get("deepkey")).isEqualTo("v");
        }

        @Test
        @DisplayName("a string containing the LITERAL text backslash-u0000 is NOT altered")
        void literalBackslashU0000Preserved() throws Exception {
            String literal = "\\" + "u0000"; // 6 chars of legal data, no NUL codepoint
            StorageEntity entity = new StorageEntity("tenant-1", "application/json",
                    Map.of("k", literal), 10, "checksum", (Instant) null);

            Map<?, ?> back = mapper.readValue(entity.getData(), Map.class);
            assertThat(back.get("k")).isEqualTo(literal);
        }

        @Test
        @DisplayName("hit-free payloads serialize byte-identically to plain Jackson (no re-serialization pass)")
        void cleanPayloadUnaffected() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("k", "value");
            payload.put("n", 42);

            StorageEntity entity = new StorageEntity("tenant-1", "application/json",
                    payload, 10, "checksum", (Instant) null);

            assertThat(entity.getData()).isEqualTo(mapper.writeValueAsString(payload));
        }

        @Test
        @DisplayName("setMetadata(Object) goes through the same funnel")
        void metadataFunnelStripsToo() throws Exception {
            StorageEntity entity = new StorageEntity();
            entity.setMetadata(Map.of("m", "a" + NUL + "b"));

            assertThat(entity.getMetadata()).doesNotContain(NUL);
            Map<?, ?> back = mapper.readValue(entity.getMetadata(), Map.class);
            assertThat(back.get("m")).isEqualTo("ab");
        }
    }

    @Nested
    @DisplayName("Serialization failure is honest (toString() fallback removed)")
    class SerializationFailure {

        /** A type Jackson cannot serialize (no properties) whose toString would leak garbage. */
        static class Unserializable {
            @Override
            public String toString() {
                return "GARBAGE-NOT-JSON";
            }
        }

        @Test
        @DisplayName("Jackson failure throws StorageSerializationException instead of writing toString() garbage into a jsonb column")
        void jacksonFailureThrows() {
            assertThatThrownBy(() -> new StorageEntity("tenant-1", "application/json",
                    new Unserializable(), 10, "checksum", (Instant) null))
                    .isInstanceOf(StorageSerializationException.class)
                    .hasMessageNotContaining("GARBAGE-NOT-JSON");
        }

        @Test
        @DisplayName("setData(Object) surfaces the same honest failure")
        void setDataSurfacesFailure() {
            StorageEntity entity = new StorageEntity();
            assertThatThrownBy(() -> entity.setData((Object) new Unserializable()))
                    .isInstanceOf(StorageSerializationException.class);
        }
    }
}
