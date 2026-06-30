package com.apimarketplace.catalog.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UUIDDeserializer class.
 *
 * UUIDDeserializer handles both string and numeric UUID inputs.
 */
@DisplayName("UUIDDeserializer")
class UUIDDeserializerTest {

    private ObjectMapper mapper;
    private UUIDDeserializer deserializer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        deserializer = new UUIDDeserializer();
    }

    // ========================================================================
    // String UUID deserialization tests
    // ========================================================================

    @Nested
    @DisplayName("String UUID deserialization")
    class StringUuidTests {

        @Test
        @DisplayName("should deserialize valid UUID string")
        void shouldDeserializeValidUuidString() throws Exception {
            UUID expected = UUID.randomUUID();
            String json = "\"" + expected.toString() + "\"";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            UUID result = deserializer.deserialize(parser, ctxt);

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("should deserialize lowercase UUID string")
        void shouldDeserializeLowercaseUuidString() throws Exception {
            String uuidStr = "a1b2c3d4-e5f6-1234-5678-90abcdef1234";
            String json = "\"" + uuidStr + "\"";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            UUID result = deserializer.deserialize(parser, ctxt);

            assertEquals(UUID.fromString(uuidStr), result);
        }

        @Test
        @DisplayName("should deserialize uppercase UUID string")
        void shouldDeserializeUppercaseUuidString() throws Exception {
            String uuidStr = "A1B2C3D4-E5F6-1234-5678-90ABCDEF1234";
            String json = "\"" + uuidStr + "\"";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            UUID result = deserializer.deserialize(parser, ctxt);

            assertNotNull(result);
        }
    }

    // ========================================================================
    // Numeric ID deserialization tests
    // ========================================================================

    @Nested
    @DisplayName("Numeric ID deserialization")
    class NumericIdTests {

        @Test
        @DisplayName("should convert numeric ID to UUID")
        void shouldConvertNumericIdToUuid() throws Exception {
            String json = "123";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            UUID result = deserializer.deserialize(parser, ctxt);

            assertNotNull(result);
            assertTrue(result.toString().endsWith("000000000123"));
        }

        @Test
        @DisplayName("should convert large numeric ID to UUID")
        void shouldConvertLargeNumericIdToUuid() throws Exception {
            String json = "999999999999";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            UUID result = deserializer.deserialize(parser, ctxt);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should convert numeric string to UUID")
        void shouldConvertNumericStringToUuid() throws Exception {
            String json = "\"456\"";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            UUID result = deserializer.deserialize(parser, ctxt);

            assertNotNull(result);
            assertTrue(result.toString().endsWith("000000000456"));
        }
    }

    // ========================================================================
    // Null handling tests
    // ========================================================================

    @Nested
    @DisplayName("Null handling")
    class NullHandlingTests {

        @Test
        @DisplayName("should return null for null value")
        void shouldReturnNullForNullValue() throws Exception {
            String json = "null";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            UUID result = deserializer.deserialize(parser, ctxt);

            assertNull(result);
        }
    }

    // ========================================================================
    // Error handling tests
    // ========================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should throw for invalid UUID format")
        void shouldThrowForInvalidUuidFormat() throws Exception {
            String json = "\"not-a-uuid-or-number\"";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            assertThrows(IOException.class, () ->
                deserializer.deserialize(parser, ctxt)
            );
        }

        @Test
        @DisplayName("should throw for object input")
        void shouldThrowForObjectInput() throws Exception {
            String json = "{\"id\": 123}";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            assertThrows(IOException.class, () ->
                deserializer.deserialize(parser, ctxt)
            );
        }

        @Test
        @DisplayName("should throw for array input")
        void shouldThrowForArrayInput() throws Exception {
            String json = "[1, 2, 3]";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            assertThrows(IOException.class, () ->
                deserializer.deserialize(parser, ctxt)
            );
        }
    }

    // ========================================================================
    // UUID format preservation tests
    // ========================================================================

    @Nested
    @DisplayName("UUID format preservation")
    class UuidFormatTests {

        @Test
        @DisplayName("should preserve UUID version and variant")
        void shouldPreserveUuidVersionAndVariant() throws Exception {
            UUID original = UUID.randomUUID();
            String json = "\"" + original.toString() + "\"";

            JsonParser parser = mapper.getFactory().createParser(json);
            DeserializationContext ctxt = mapper.getDeserializationContext();
            parser.nextToken();

            UUID result = deserializer.deserialize(parser, ctxt);

            assertEquals(original.version(), result.version());
            assertEquals(original.variant(), result.variant());
        }

        @Test
        @DisplayName("should generate consistent UUID for same numeric ID")
        void shouldGenerateConsistentUuidForSameNumericId() throws Exception {
            String json1 = "42";
            String json2 = "42";

            JsonParser parser1 = mapper.getFactory().createParser(json1);
            parser1.nextToken();
            UUID result1 = deserializer.deserialize(parser1, mapper.getDeserializationContext());

            JsonParser parser2 = mapper.getFactory().createParser(json2);
            parser2.nextToken();
            UUID result2 = deserializer.deserialize(parser2, mapper.getDeserializationContext());

            assertEquals(result1, result2);
        }
    }
}
