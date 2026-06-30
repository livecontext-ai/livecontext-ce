package com.apimarketplace.catalog.mapping;

import com.apimarketplace.catalog.domain.ResponseFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SourceFormat enum.
 *
 * SourceFormat defines supported source formats for mapping operations.
 */
@DisplayName("SourceFormat")
class SourceFormatTest {

    // ========================================================================
    // Enum values tests
    // ========================================================================

    @Nested
    @DisplayName("Enum values")
    class EnumValuesTests {

        @Test
        @DisplayName("should have all expected values")
        void shouldHaveAllExpectedValues() {
            SourceFormat[] values = SourceFormat.values();

            assertEquals(6, values.length);
            assertNotNull(SourceFormat.valueOf("JSON"));
            assertNotNull(SourceFormat.valueOf("XML"));
            assertNotNull(SourceFormat.valueOf("HTML"));
            assertNotNull(SourceFormat.valueOf("CSV"));
            assertNotNull(SourceFormat.valueOf("TEXT"));
            assertNotNull(SourceFormat.valueOf("BINARY"));
        }

        @ParameterizedTest
        @EnumSource(SourceFormat.class)
        @DisplayName("should have valid name for all formats")
        void shouldHaveValidNameForAllFormats(SourceFormat format) {
            assertNotNull(format.name());
            assertFalse(format.name().isEmpty());
        }
    }

    // ========================================================================
    // fromResponseFormat tests
    // ========================================================================

    @Nested
    @DisplayName("fromResponseFormat()")
    class FromResponseFormatTests {

        @Test
        @DisplayName("should convert JSON ResponseFormat to JSON SourceFormat")
        void shouldConvertJsonResponseFormat() {
            SourceFormat result = SourceFormat.fromResponseFormat(ResponseFormat.JSON);

            assertEquals(SourceFormat.JSON, result);
        }

        @Test
        @DisplayName("should convert XML ResponseFormat to XML SourceFormat")
        void shouldConvertXmlResponseFormat() {
            SourceFormat result = SourceFormat.fromResponseFormat(ResponseFormat.XML);

            assertEquals(SourceFormat.XML, result);
        }

        @Test
        @DisplayName("should convert HTML ResponseFormat to HTML SourceFormat")
        void shouldConvertHtmlResponseFormat() {
            SourceFormat result = SourceFormat.fromResponseFormat(ResponseFormat.HTML);

            assertEquals(SourceFormat.HTML, result);
        }

        @Test
        @DisplayName("should convert CSV ResponseFormat to CSV SourceFormat")
        void shouldConvertCsvResponseFormat() {
            SourceFormat result = SourceFormat.fromResponseFormat(ResponseFormat.CSV);

            assertEquals(SourceFormat.CSV, result);
        }

        @Test
        @DisplayName("should convert TEXT ResponseFormat to TEXT SourceFormat")
        void shouldConvertTextResponseFormat() {
            SourceFormat result = SourceFormat.fromResponseFormat(ResponseFormat.TEXT);

            assertEquals(SourceFormat.TEXT, result);
        }

        @Test
        @DisplayName("should convert BINARY ResponseFormat to BINARY SourceFormat")
        void shouldConvertBinaryResponseFormat() {
            SourceFormat result = SourceFormat.fromResponseFormat(ResponseFormat.BINARY);

            assertEquals(SourceFormat.BINARY, result);
        }

        @Test
        @DisplayName("should return BINARY for null ResponseFormat")
        void shouldReturnBinaryForNullResponseFormat() {
            SourceFormat result = SourceFormat.fromResponseFormat(null);

            assertEquals(SourceFormat.BINARY, result);
        }
    }

    // ========================================================================
    // toResponseFormat tests
    // ========================================================================

    @Nested
    @DisplayName("toResponseFormat()")
    class ToResponseFormatTests {

        @Test
        @DisplayName("should convert JSON SourceFormat to JSON ResponseFormat")
        void shouldConvertJsonSourceFormat() {
            ResponseFormat result = SourceFormat.JSON.toResponseFormat();

            assertEquals(ResponseFormat.JSON, result);
        }

        @Test
        @DisplayName("should convert XML SourceFormat to XML ResponseFormat")
        void shouldConvertXmlSourceFormat() {
            ResponseFormat result = SourceFormat.XML.toResponseFormat();

            assertEquals(ResponseFormat.XML, result);
        }

        @Test
        @DisplayName("should convert HTML SourceFormat to HTML ResponseFormat")
        void shouldConvertHtmlSourceFormat() {
            ResponseFormat result = SourceFormat.HTML.toResponseFormat();

            assertEquals(ResponseFormat.HTML, result);
        }

        @Test
        @DisplayName("should convert CSV SourceFormat to CSV ResponseFormat")
        void shouldConvertCsvSourceFormat() {
            ResponseFormat result = SourceFormat.CSV.toResponseFormat();

            assertEquals(ResponseFormat.CSV, result);
        }

        @Test
        @DisplayName("should convert TEXT SourceFormat to TEXT ResponseFormat")
        void shouldConvertTextSourceFormat() {
            ResponseFormat result = SourceFormat.TEXT.toResponseFormat();

            assertEquals(ResponseFormat.TEXT, result);
        }

        @Test
        @DisplayName("should convert BINARY SourceFormat to BINARY ResponseFormat")
        void shouldConvertBinarySourceFormat() {
            ResponseFormat result = SourceFormat.BINARY.toResponseFormat();

            assertEquals(ResponseFormat.BINARY, result);
        }
    }

    // ========================================================================
    // Round-trip conversion tests
    // ========================================================================

    @Nested
    @DisplayName("Round-trip conversions")
    class RoundTripTests {

        @ParameterizedTest
        @EnumSource(SourceFormat.class)
        @DisplayName("should preserve format through round-trip conversion")
        void shouldPreserveFormatThroughRoundTrip(SourceFormat sourceFormat) {
            ResponseFormat responseFormat = sourceFormat.toResponseFormat();
            SourceFormat roundTrip = SourceFormat.fromResponseFormat(responseFormat);

            assertEquals(sourceFormat, roundTrip);
        }

        @ParameterizedTest
        @EnumSource(ResponseFormat.class)
        @DisplayName("should preserve ResponseFormat through round-trip conversion")
        void shouldPreserveResponseFormatThroughRoundTrip(ResponseFormat responseFormat) {
            SourceFormat sourceFormat = SourceFormat.fromResponseFormat(responseFormat);
            ResponseFormat roundTrip = sourceFormat.toResponseFormat();

            assertEquals(responseFormat, roundTrip);
        }
    }
}
