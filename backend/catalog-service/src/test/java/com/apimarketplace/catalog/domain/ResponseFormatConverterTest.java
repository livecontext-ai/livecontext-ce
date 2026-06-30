package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ResponseFormatConverter.
 *
 * ResponseFormatConverter is a JPA AttributeConverter that handles conversion
 * between ResponseFormat enum and database String values.
 */
@DisplayName("ResponseFormatConverter")
class ResponseFormatConverterTest {

    private ResponseFormatConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ResponseFormatConverter();
    }

    // ========================================================================
    // convertToDatabaseColumn tests
    // ========================================================================

    @Nested
    @DisplayName("convertToDatabaseColumn()")
    class ConvertToDatabaseColumnTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            String result = converter.convertToDatabaseColumn(null);
            assertNull(result);
        }

        @ParameterizedTest
        @EnumSource(ResponseFormat.class)
        @DisplayName("should convert all enum values to lowercase database values")
        void shouldConvertAllEnumValuesToLowercase(ResponseFormat format) {
            String result = converter.convertToDatabaseColumn(format);

            assertNotNull(result);
            assertEquals(format.name().toLowerCase(), result);
        }

        @Test
        @DisplayName("should convert JSON to 'json'")
        void shouldConvertJsonToLowercase() {
            String result = converter.convertToDatabaseColumn(ResponseFormat.JSON);
            assertEquals("json", result);
        }

        @Test
        @DisplayName("should convert HTML to 'html'")
        void shouldConvertHtmlToLowercase() {
            String result = converter.convertToDatabaseColumn(ResponseFormat.HTML);
            assertEquals("html", result);
        }

        @Test
        @DisplayName("should convert CSV to 'csv'")
        void shouldConvertCsvToLowercase() {
            String result = converter.convertToDatabaseColumn(ResponseFormat.CSV);
            assertEquals("csv", result);
        }

        @Test
        @DisplayName("should convert TEXT to 'text'")
        void shouldConvertTextToLowercase() {
            String result = converter.convertToDatabaseColumn(ResponseFormat.TEXT);
            assertEquals("text", result);
        }

        @Test
        @DisplayName("should convert XML to 'xml'")
        void shouldConvertXmlToLowercase() {
            String result = converter.convertToDatabaseColumn(ResponseFormat.XML);
            assertEquals("xml", result);
        }

        @Test
        @DisplayName("should convert BINARY to 'binary'")
        void shouldConvertBinaryToLowercase() {
            String result = converter.convertToDatabaseColumn(ResponseFormat.BINARY);
            assertEquals("binary", result);
        }
    }

    // ========================================================================
    // convertToEntityAttribute tests
    // ========================================================================

    @Nested
    @DisplayName("convertToEntityAttribute()")
    class ConvertToEntityAttributeTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            ResponseFormat result = converter.convertToEntityAttribute(null);
            assertNull(result);
        }

        @Test
        @DisplayName("should convert 'json' to JSON")
        void shouldConvertJsonStringToJson() {
            ResponseFormat result = converter.convertToEntityAttribute("json");
            assertEquals(ResponseFormat.JSON, result);
        }

        @Test
        @DisplayName("should convert 'JSON' (uppercase) to JSON")
        void shouldConvertUppercaseJsonToJson() {
            ResponseFormat result = converter.convertToEntityAttribute("JSON");
            assertEquals(ResponseFormat.JSON, result);
        }

        @Test
        @DisplayName("should convert 'html' to HTML")
        void shouldConvertHtmlStringToHtml() {
            ResponseFormat result = converter.convertToEntityAttribute("html");
            assertEquals(ResponseFormat.HTML, result);
        }

        @Test
        @DisplayName("should convert 'csv' to CSV")
        void shouldConvertCsvStringToCsv() {
            ResponseFormat result = converter.convertToEntityAttribute("csv");
            assertEquals(ResponseFormat.CSV, result);
        }

        @Test
        @DisplayName("should convert 'text' to TEXT")
        void shouldConvertTextStringToText() {
            ResponseFormat result = converter.convertToEntityAttribute("text");
            assertEquals(ResponseFormat.TEXT, result);
        }

        @Test
        @DisplayName("should convert 'xml' to XML")
        void shouldConvertXmlStringToXml() {
            ResponseFormat result = converter.convertToEntityAttribute("xml");
            assertEquals(ResponseFormat.XML, result);
        }

        @Test
        @DisplayName("should convert 'binary' to BINARY")
        void shouldConvertBinaryStringToBinary() {
            ResponseFormat result = converter.convertToEntityAttribute("binary");
            assertEquals(ResponseFormat.BINARY, result);
        }

        @ParameterizedTest
        @ValueSource(strings = {"application/json", "text/json", "JSON_DATA"})
        @DisplayName("should convert JSON-related content types to JSON")
        void shouldConvertJsonContentTypesToJson(String value) {
            ResponseFormat result = converter.convertToEntityAttribute(value);
            assertEquals(ResponseFormat.JSON, result);
        }

        @ParameterizedTest
        @ValueSource(strings = {"text/html", "HTML_PAGE"})
        @DisplayName("should convert HTML-related content types to HTML")
        void shouldConvertHtmlContentTypesToHtml(String value) {
            ResponseFormat result = converter.convertToEntityAttribute(value);
            assertEquals(ResponseFormat.HTML, result);
        }

        @Test
        @DisplayName("should convert xhtml+xml to XML (XML check precedes XHTML)")
        void shouldConvertXhtmlXmlToXml() {
            // Note: "application/xhtml+xml" contains "XML" which is checked before "XHTML"
            ResponseFormat result = converter.convertToEntityAttribute("application/xhtml+xml");
            assertEquals(ResponseFormat.XML, result);
        }

        @ParameterizedTest
        @ValueSource(strings = {"text/plain", "PLAIN_TEXT"})
        @DisplayName("should convert plain text content types to TEXT")
        void shouldConvertPlainTextContentTypesToText(String value) {
            ResponseFormat result = converter.convertToEntityAttribute(value);
            assertEquals(ResponseFormat.TEXT, result);
        }

        @ParameterizedTest
        @ValueSource(strings = {"application/pdf", "application/octet-stream", "image/png", "image/jpeg"})
        @DisplayName("should convert binary content types to BINARY")
        void shouldConvertBinaryContentTypesToBinary(String value) {
            ResponseFormat result = converter.convertToEntityAttribute(value);
            assertEquals(ResponseFormat.BINARY, result);
        }

        @Test
        @DisplayName("should return JSON as default for unknown format")
        void shouldReturnJsonAsDefaultForUnknownFormat() {
            ResponseFormat result = converter.convertToEntityAttribute("unknown_format");
            assertEquals(ResponseFormat.JSON, result);
        }

        @Test
        @DisplayName("should return JSON for empty string")
        void shouldReturnJsonForEmptyString() {
            ResponseFormat result = converter.convertToEntityAttribute("");
            assertEquals(ResponseFormat.JSON, result);
        }

        @Test
        @DisplayName("should return JSON for whitespace-only string")
        void shouldReturnJsonForWhitespaceString() {
            ResponseFormat result = converter.convertToEntityAttribute("   ");
            assertEquals(ResponseFormat.JSON, result);
        }
    }

    // ========================================================================
    // Round-trip tests
    // ========================================================================

    @Nested
    @DisplayName("Round-trip conversion")
    class RoundTripTests {

        @ParameterizedTest
        @EnumSource(ResponseFormat.class)
        @DisplayName("should maintain value after round-trip conversion")
        void shouldMaintainValueAfterRoundTrip(ResponseFormat original) {
            // Convert to DB and back
            String dbValue = converter.convertToDatabaseColumn(original);
            ResponseFormat restored = converter.convertToEntityAttribute(dbValue);

            assertEquals(original, restored);
        }
    }
}
