package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ResponseFormat enum.
 *
 * Tests enum values, database value conversion, JSON validation requirements,
 * and fromString/fromDatabaseValue parsing.
 */
@DisplayName("ResponseFormat Tests")
class ResponseFormatTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // ENUM VALUES TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Enum Values")
    class EnumValuesTests {

        @Test
        @DisplayName("Should have 6 response formats")
        void shouldHaveSixFormats() {
            assertEquals(6, ResponseFormat.values().length);
        }

        @Test
        @DisplayName("Should have all expected values")
        void shouldHaveAllExpectedValues() {
            assertNotNull(ResponseFormat.JSON);
            assertNotNull(ResponseFormat.HTML);
            assertNotNull(ResponseFormat.CSV);
            assertNotNull(ResponseFormat.TEXT);
            assertNotNull(ResponseFormat.XML);
            assertNotNull(ResponseFormat.BINARY);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getDatabaseValue() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getDatabaseValue()")
    class GetDatabaseValueTests {

        @ParameterizedTest
        @EnumSource(ResponseFormat.class)
        @DisplayName("Should return lowercase value for all formats")
        void shouldReturnLowercaseForAllFormats(ResponseFormat format) {
            String dbValue = format.getDatabaseValue();

            assertNotNull(dbValue);
            assertEquals(dbValue.toLowerCase(), dbValue);
            assertEquals(format.name().toLowerCase(), dbValue);
        }

        @Test
        @DisplayName("Should return correct database values")
        void shouldReturnCorrectDatabaseValues() {
            assertEquals("json", ResponseFormat.JSON.getDatabaseValue());
            assertEquals("html", ResponseFormat.HTML.getDatabaseValue());
            assertEquals("csv", ResponseFormat.CSV.getDatabaseValue());
            assertEquals("text", ResponseFormat.TEXT.getDatabaseValue());
            assertEquals("xml", ResponseFormat.XML.getDatabaseValue());
            assertEquals("binary", ResponseFormat.BINARY.getDatabaseValue());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // requiresJsonValidation() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("requiresJsonValidation()")
    class RequiresJsonValidationTests {

        @Test
        @DisplayName("Should return true only for JSON format")
        void shouldReturnTrueOnlyForJson() {
            assertTrue(ResponseFormat.JSON.requiresJsonValidation());
        }

        @ParameterizedTest
        @EnumSource(value = ResponseFormat.class, names = {"HTML", "CSV", "TEXT", "XML", "BINARY"})
        @DisplayName("Should return false for non-JSON formats")
        void shouldReturnFalseForNonJsonFormats(ResponseFormat format) {
            assertFalse(format.requiresJsonValidation());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // fromDatabaseValue() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fromDatabaseValue()")
    class FromDatabaseValueTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(ResponseFormat.fromDatabaseValue(null));
        }

        @Test
        @DisplayName("Should return null for empty string")
        void shouldReturnNullForEmptyString() {
            assertNull(ResponseFormat.fromDatabaseValue(""));
        }

        @Test
        @DisplayName("Should return null for whitespace only")
        void shouldReturnNullForWhitespace() {
            assertNull(ResponseFormat.fromDatabaseValue("   "));
        }

        @ParameterizedTest
        @CsvSource({
            "json, JSON",
            "JSON, JSON",
            "html, HTML",
            "HTML, HTML",
            "csv, CSV",
            "CSV, CSV",
            "text, TEXT",
            "TEXT, TEXT",
            "xml, XML",
            "XML, XML",
            "binary, BINARY",
            "BINARY, BINARY"
        })
        @DisplayName("Should parse valid database values")
        void shouldParseValidDatabaseValues(String input, ResponseFormat expected) {
            assertEquals(expected, ResponseFormat.fromDatabaseValue(input));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // fromString() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fromString()")
    class FromStringTests {

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return JSON as default for null or empty")
        void shouldReturnJsonForNullOrEmpty(String input) {
            assertEquals(ResponseFormat.JSON, ResponseFormat.fromString(input));
        }

        @Test
        @DisplayName("Should return JSON for whitespace only")
        void shouldReturnJsonForWhitespace() {
            assertEquals(ResponseFormat.JSON, ResponseFormat.fromString("   "));
        }

        @Nested
        @DisplayName("JSON parsing")
        class JsonParsingTests {

            @ParameterizedTest
            @ValueSource(strings = {"json", "JSON", "Json", "application/json", "J"})
            @DisplayName("Should parse JSON formats")
            void shouldParseJsonFormats(String input) {
                assertEquals(ResponseFormat.JSON, ResponseFormat.fromString(input));
            }
        }

        @Nested
        @DisplayName("XML parsing")
        class XmlParsingTests {

            @ParameterizedTest
            @ValueSource(strings = {"xml", "XML", "Xml", "application/xml", "X"})
            @DisplayName("Should parse XML formats")
            void shouldParseXmlFormats(String input) {
                assertEquals(ResponseFormat.XML, ResponseFormat.fromString(input));
            }
        }

        @Nested
        @DisplayName("CSV parsing")
        class CsvParsingTests {

            @ParameterizedTest
            @ValueSource(strings = {"csv", "CSV", "Csv", "text/csv", "C"})
            @DisplayName("Should parse CSV formats")
            void shouldParseCsvFormats(String input) {
                assertEquals(ResponseFormat.CSV, ResponseFormat.fromString(input));
            }
        }

        @Nested
        @DisplayName("HTML parsing")
        class HtmlParsingTests {

            @ParameterizedTest
            @ValueSource(strings = {"html", "HTML", "Html", "text/html", "xhtml", "XHTML", "H"})
            @DisplayName("Should parse HTML formats")
            void shouldParseHtmlFormats(String input) {
                assertEquals(ResponseFormat.HTML, ResponseFormat.fromString(input));
            }
        }

        @Nested
        @DisplayName("TEXT parsing")
        class TextParsingTests {

            @ParameterizedTest
            @ValueSource(strings = {"text", "TEXT", "Text", "text/plain", "plain", "PLAIN", "T"})
            @DisplayName("Should parse TEXT formats")
            void shouldParseTextFormats(String input) {
                assertEquals(ResponseFormat.TEXT, ResponseFormat.fromString(input));
            }
        }

        @Nested
        @DisplayName("BINARY parsing")
        class BinaryParsingTests {

            @ParameterizedTest
            @ValueSource(strings = {
                "binary", "BINARY", "Binary",
                "application/octet-stream", "octet",
                "pdf", "PDF",
                "zip", "ZIP",
                "image/png", "image/jpeg",
                "png", "PNG",
                "jpg", "JPG", "jpeg", "JPEG",
                "B"
            })
            @DisplayName("Should parse BINARY formats")
            void shouldParseBinaryFormats(String input) {
                assertEquals(ResponseFormat.BINARY, ResponseFormat.fromString(input));
            }
        }

        @Test
        @DisplayName("Should return JSON for unknown formats")
        void shouldReturnJsonForUnknownFormats() {
            assertEquals(ResponseFormat.JSON, ResponseFormat.fromString("unknown"));
            assertEquals(ResponseFormat.JSON, ResponseFormat.fromString("foo"));
            assertEquals(ResponseFormat.JSON, ResponseFormat.fromString("bar"));
        }

        @Test
        @DisplayName("Should handle mixed case with whitespace")
        void shouldHandleMixedCaseWithWhitespace() {
            assertEquals(ResponseFormat.JSON, ResponseFormat.fromString("  JSON  "));
            assertEquals(ResponseFormat.XML, ResponseFormat.fromString("  xml  "));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle content-type headers")
        void shouldHandleContentTypeHeaders() {
            assertEquals(ResponseFormat.JSON, ResponseFormat.fromString("application/json; charset=utf-8"));
            assertEquals(ResponseFormat.XML, ResponseFormat.fromString("application/xml"));
            assertEquals(ResponseFormat.HTML, ResponseFormat.fromString("text/html"));
        }

        @Test
        @DisplayName("Should prioritize JSON over XML for ambiguous input")
        void shouldPrioritizeForAmbiguousInput() {
            // If input contains JSON, it should be JSON even if it also contains XML
            assertEquals(ResponseFormat.JSON, ResponseFormat.fromString("json-xml"));
        }

        @Test
        @DisplayName("Enum valueOf should work for exact names")
        void enumValueOfShouldWorkForExactNames() {
            assertEquals(ResponseFormat.JSON, ResponseFormat.valueOf("JSON"));
            assertEquals(ResponseFormat.XML, ResponseFormat.valueOf("XML"));
            assertEquals(ResponseFormat.CSV, ResponseFormat.valueOf("CSV"));
            assertEquals(ResponseFormat.TEXT, ResponseFormat.valueOf("TEXT"));
            assertEquals(ResponseFormat.HTML, ResponseFormat.valueOf("HTML"));
            assertEquals(ResponseFormat.BINARY, ResponseFormat.valueOf("BINARY"));
        }

        @Test
        @DisplayName("Enum valueOf should throw for lowercase names")
        void enumValueOfShouldThrowForLowercaseNames() {
            assertThrows(IllegalArgumentException.class, () -> ResponseFormat.valueOf("json"));
        }
    }
}
