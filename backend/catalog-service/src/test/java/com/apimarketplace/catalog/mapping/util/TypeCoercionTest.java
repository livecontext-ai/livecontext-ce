package com.apimarketplace.catalog.mapping.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TypeCoercion class.
 *
 * TypeCoercion provides type coercion and conversion utilities.
 */
@DisplayName("TypeCoercion")
class TypeCoercionTest {

    // ========================================================================
    // coerce - null and empty handling
    // ========================================================================

    @Nested
    @DisplayName("coerce - null and empty handling")
    class CoerceNullEmptyTests {

        @Test
        @DisplayName("should return null for null value")
        void shouldReturnNullForNullValue() {
            Object result = TypeCoercion.coerce(null, "string");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for empty string")
        void shouldReturnNullForEmptyString() {
            Object result = TypeCoercion.coerce("", "string");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for whitespace string")
        void shouldReturnNullForWhitespaceString() {
            Object result = TypeCoercion.coerce("   ", "integer");

            assertNull(result);
        }
    }

    // ========================================================================
    // coerce - integer
    // ========================================================================

    @Nested
    @DisplayName("coerce - integer")
    class CoerceIntegerTests {

        @Test
        @DisplayName("should coerce string to integer")
        void shouldCoerceStringToInteger() {
            Object result = TypeCoercion.coerce("42", "integer");

            assertEquals(42, result);
        }

        @Test
        @DisplayName("should coerce negative string to integer")
        void shouldCoerceNegativeStringToInteger() {
            Object result = TypeCoercion.coerce("-100", "integer");

            assertEquals(-100, result);
        }

        @Test
        @DisplayName("should handle int alias")
        void shouldHandleIntAlias() {
            Object result = TypeCoercion.coerce("123", "int");

            assertEquals(123, result);
        }

        @Test
        @DisplayName("should remove commas from number")
        void shouldRemoveCommasFromNumber() {
            Object result = TypeCoercion.coerce("1,234,567", "integer");

            assertEquals(1234567, result);
        }

        @Test
        @DisplayName("should return original for invalid integer")
        void shouldReturnOriginalForInvalidInteger() {
            Object result = TypeCoercion.coerce("not-a-number", "integer");

            assertEquals("not-a-number", result);
        }
    }

    // ========================================================================
    // coerce - boolean
    // ========================================================================

    @Nested
    @DisplayName("coerce - boolean")
    class CoerceBooleanTests {

        @ParameterizedTest
        @ValueSource(strings = {"true", "TRUE", "True", "1", "yes", "YES", "on", "ON"})
        @DisplayName("should coerce truthy values to true")
        void shouldCoerceTruthyValuesToTrue(String value) {
            Object result = TypeCoercion.coerce(value, "boolean");

            assertEquals(true, result);
        }

        @ParameterizedTest
        @ValueSource(strings = {"false", "FALSE", "False", "0", "no", "NO", "off", "OFF"})
        @DisplayName("should coerce falsy values to false")
        void shouldCoerceFalsyValuesToFalse(String value) {
            Object result = TypeCoercion.coerce(value, "boolean");

            assertEquals(false, result);
        }

        @Test
        @DisplayName("should handle bool alias")
        void shouldHandleBoolAlias() {
            Object result = TypeCoercion.coerce("true", "bool");

            assertEquals(true, result);
        }

        @Test
        @DisplayName("should return original for invalid boolean")
        void shouldReturnOriginalForInvalidBoolean() {
            Object result = TypeCoercion.coerce("maybe", "boolean");

            assertEquals("maybe", result);
        }
    }

    // ========================================================================
    // coerce - datetime
    // ========================================================================

    @Nested
    @DisplayName("coerce - datetime")
    class CoerceDateTimeTests {

        @Test
        @DisplayName("should coerce ISO date format")
        void shouldCoerceIsoDateFormat() {
            Object result = TypeCoercion.coerce("2024-01-15", "datetime");

            assertNotNull(result);
            assertTrue(result.toString().contains("2024-01-15"));
        }

        @Test
        @DisplayName("should coerce ISO datetime format")
        void shouldCoerceIsoDateTimeFormat() {
            Object result = TypeCoercion.coerce("2024-01-15T10:30:00", "datetime");

            assertNotNull(result);
            assertTrue(result.toString().contains("2024-01-15"));
        }

        @Test
        @DisplayName("should coerce US date format")
        void shouldCoerceUsDateFormat() {
            Object result = TypeCoercion.coerce("01/15/2024", "datetime");

            assertNotNull(result);
        }

        @Test
        @DisplayName("should coerce timestamp")
        void shouldCoerceTimestamp() {
            Object result = TypeCoercion.coerce("1705320000", "datetime");

            assertNotNull(result);
        }

        @Test
        @DisplayName("should handle date alias")
        void shouldHandleDateAlias() {
            Object result = TypeCoercion.coerce("2024-01-15", "date");

            assertNotNull(result);
        }

        @Test
        @DisplayName("should return original for invalid datetime")
        void shouldReturnOriginalForInvalidDatetime() {
            Object result = TypeCoercion.coerce("not-a-date", "datetime");

            assertEquals("not-a-date", result);
        }
    }

    // ========================================================================
    // coerce - URI
    // ========================================================================

    @Nested
    @DisplayName("coerce - URI")
    class CoerceUriTests {

        @Test
        @DisplayName("should coerce valid HTTP URL")
        void shouldCoerceValidHttpUrl() {
            Object result = TypeCoercion.coerce("http://example.com/path", "uri");

            assertEquals("http://example.com/path", result);
        }

        @Test
        @DisplayName("should coerce valid HTTPS URL")
        void shouldCoerceValidHttpsUrl() {
            Object result = TypeCoercion.coerce("https://api.example.com/v1/users", "uri");

            assertEquals("https://api.example.com/v1/users", result);
        }

        @Test
        @DisplayName("should handle url alias")
        void shouldHandleUrlAlias() {
            Object result = TypeCoercion.coerce("https://example.com", "url");

            assertEquals("https://example.com", result);
        }

        @Test
        @DisplayName("should return original for invalid URI")
        void shouldReturnOriginalForInvalidUri() {
            Object result = TypeCoercion.coerce("not-a-url", "uri");

            assertEquals("not-a-url", result);
        }
    }

    // ========================================================================
    // coerce - string
    // ========================================================================

    @Nested
    @DisplayName("coerce - string")
    class CoerceStringTests {

        @Test
        @DisplayName("should return string as-is")
        void shouldReturnStringAsIs() {
            Object result = TypeCoercion.coerce("Hello World", "string");

            assertEquals("Hello World", result);
        }

        @Test
        @DisplayName("should convert non-string to string")
        void shouldConvertNonStringToString() {
            Object result = TypeCoercion.coerce(123, "string");

            assertEquals("123", result);
        }
    }

    // ========================================================================
    // canCoerce tests
    // ========================================================================

    @Nested
    @DisplayName("canCoerce")
    class CanCoerceTests {

        @Test
        @DisplayName("should return true for null value")
        void shouldReturnTrueForNullValue() {
            assertTrue(TypeCoercion.canCoerce(null, "integer"));
        }

        @Test
        @DisplayName("should return true for empty string")
        void shouldReturnTrueForEmptyString() {
            assertTrue(TypeCoercion.canCoerce("", "boolean"));
        }

        @Test
        @DisplayName("should return true for valid integer")
        void shouldReturnTrueForValidInteger() {
            assertTrue(TypeCoercion.canCoerce("42", "integer"));
        }

        @Test
        @DisplayName("should return false for invalid integer")
        void shouldReturnFalseForInvalidInteger() {
            assertFalse(TypeCoercion.canCoerce("abc", "integer"));
        }

        @Test
        @DisplayName("should return true for valid boolean")
        void shouldReturnTrueForValidBoolean() {
            assertTrue(TypeCoercion.canCoerce("true", "boolean"));
            assertTrue(TypeCoercion.canCoerce("yes", "boolean"));
            assertTrue(TypeCoercion.canCoerce("1", "boolean"));
        }

        @Test
        @DisplayName("should return false for invalid boolean")
        void shouldReturnFalseForInvalidBoolean() {
            assertFalse(TypeCoercion.canCoerce("maybe", "boolean"));
        }

        @Test
        @DisplayName("should return true for valid datetime")
        void shouldReturnTrueForValidDatetime() {
            // LocalDateTime.parse requires both date and time
            assertTrue(TypeCoercion.canCoerce("2024-01-15T10:30:00", "datetime"));
        }

        @Test
        @DisplayName("should return false for invalid datetime")
        void shouldReturnFalseForInvalidDatetime() {
            assertFalse(TypeCoercion.canCoerce("not-a-date", "datetime"));
        }

        @Test
        @DisplayName("should return true for valid URI")
        void shouldReturnTrueForValidUri() {
            assertTrue(TypeCoercion.canCoerce("https://example.com", "uri"));
        }

        @Test
        @DisplayName("should return false for invalid URI")
        void shouldReturnFalseForInvalidUri() {
            assertFalse(TypeCoercion.canCoerce("not-a-url", "uri"));
        }

        @Test
        @DisplayName("should always return true for string type")
        void shouldAlwaysReturnTrueForStringType() {
            assertTrue(TypeCoercion.canCoerce("anything", "string"));
            assertTrue(TypeCoercion.canCoerce(123, "string"));
        }

        @Test
        @DisplayName("should return true for unknown type")
        void shouldReturnTrueForUnknownType() {
            assertTrue(TypeCoercion.canCoerce("value", "unknown"));
        }
    }
}
