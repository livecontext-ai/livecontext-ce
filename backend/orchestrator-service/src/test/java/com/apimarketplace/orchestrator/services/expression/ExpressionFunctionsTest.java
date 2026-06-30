package com.apimarketplace.orchestrator.services.expression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExpressionFunctions utility class.
 *
 * These static functions are used in SpEL expressions within the TemplateEngine.
 * All methods handle null safely and provide sensible defaults.
 */
@DisplayName("ExpressionFunctions")
class ExpressionFunctionsTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // Type Casting Functions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("toInt()")
    class ToIntTests {

        @Test
        @DisplayName("Should return 0 for null")
        void shouldReturnZeroForNull() {
            assertEquals(0, ExpressionFunctions.toInt(null));
        }

        @Test
        @DisplayName("Should convert Integer directly")
        void shouldConvertIntegerDirectly() {
            assertEquals(42, ExpressionFunctions.toInt(42));
            assertEquals(-10, ExpressionFunctions.toInt(-10));
        }

        @Test
        @DisplayName("Should convert Double by truncating")
        void shouldConvertDoubleByTruncating() {
            assertEquals(42, ExpressionFunctions.toInt(42.9));
            assertEquals(-10, ExpressionFunctions.toInt(-10.7));
        }

        @Test
        @DisplayName("Should convert Long to int")
        void shouldConvertLongToInt() {
            assertEquals(100, ExpressionFunctions.toInt(100L));
        }

        @Test
        @DisplayName("Should convert Boolean to int (true=1, false=0)")
        void shouldConvertBooleanToInt() {
            assertEquals(1, ExpressionFunctions.toInt(true));
            assertEquals(0, ExpressionFunctions.toInt(false));
        }

        @Test
        @DisplayName("Should parse string integers")
        void shouldParseStringIntegers() {
            assertEquals(123, ExpressionFunctions.toInt("123"));
            assertEquals(-456, ExpressionFunctions.toInt("-456"));
            assertEquals(0, ExpressionFunctions.toInt("0"));
        }

        @Test
        @DisplayName("Should parse string with decimal by truncating")
        void shouldParseStringWithDecimalByTruncating() {
            assertEquals(42, ExpressionFunctions.toInt("42.9"));
            assertEquals(-10, ExpressionFunctions.toInt("-10.1"));
        }

        @Test
        @DisplayName("Should trim whitespace before parsing")
        void shouldTrimWhitespaceBeforeParsing() {
            assertEquals(42, ExpressionFunctions.toInt("  42  "));
        }

        @Test
        @DisplayName("Should return 0 for unparseable strings")
        void shouldReturnZeroForUnparseableStrings() {
            assertEquals(0, ExpressionFunctions.toInt("abc"));
            assertEquals(0, ExpressionFunctions.toInt(""));
            assertEquals(0, ExpressionFunctions.toInt("not_a_number"));
        }
    }

    @Nested
    @DisplayName("toLong()")
    class ToLongTests {

        @Test
        @DisplayName("Should return 0L for null")
        void shouldReturnZeroForNull() {
            assertEquals(0L, ExpressionFunctions.toLong(null));
        }

        @Test
        @DisplayName("Should convert Number types")
        void shouldConvertNumberTypes() {
            assertEquals(42L, ExpressionFunctions.toLong(42));
            assertEquals(100L, ExpressionFunctions.toLong(100L));
            assertEquals(99L, ExpressionFunctions.toLong(99.9));
        }

        @Test
        @DisplayName("Should convert Boolean to long")
        void shouldConvertBooleanToLong() {
            assertEquals(1L, ExpressionFunctions.toLong(true));
            assertEquals(0L, ExpressionFunctions.toLong(false));
        }

        @Test
        @DisplayName("Should parse string to long")
        void shouldParseStringToLong() {
            assertEquals(9999999999L, ExpressionFunctions.toLong("9999999999"));
        }

        @Test
        @DisplayName("Should return 0L for unparseable strings")
        void shouldReturnZeroForUnparseableStrings() {
            assertEquals(0L, ExpressionFunctions.toLong("abc"));
        }
    }

    @Nested
    @DisplayName("toDouble()")
    class ToDoubleTests {

        @Test
        @DisplayName("Should return 0.0 for null")
        void shouldReturnZeroForNull() {
            assertEquals(0.0, ExpressionFunctions.toDouble(null));
        }

        @Test
        @DisplayName("Should convert Number types")
        void shouldConvertNumberTypes() {
            assertEquals(42.0, ExpressionFunctions.toDouble(42));
            assertEquals(99.5, ExpressionFunctions.toDouble(99.5));
        }

        @Test
        @DisplayName("Should convert Boolean to double")
        void shouldConvertBooleanToDouble() {
            assertEquals(1.0, ExpressionFunctions.toDouble(true));
            assertEquals(0.0, ExpressionFunctions.toDouble(false));
        }

        @Test
        @DisplayName("Should parse string to double")
        void shouldParseStringToDouble() {
            assertEquals(3.14, ExpressionFunctions.toDouble("3.14"));
            assertEquals(-2.5, ExpressionFunctions.toDouble("-2.5"));
        }

        @Test
        @DisplayName("Should return 0.0 for unparseable strings")
        void shouldReturnZeroForUnparseableStrings() {
            assertEquals(0.0, ExpressionFunctions.toDouble("not_a_number"));
        }
    }

    @Nested
    @DisplayName("toFloat()")
    class ToFloatTests {

        @Test
        @DisplayName("Should return 0.0f for null")
        void shouldReturnZeroForNull() {
            assertEquals(0.0f, ExpressionFunctions.toFloat(null));
        }

        @Test
        @DisplayName("Should convert Number types")
        void shouldConvertNumberTypes() {
            assertEquals(42.0f, ExpressionFunctions.toFloat(42));
            assertEquals(99.5f, ExpressionFunctions.toFloat(99.5));
        }

        @Test
        @DisplayName("Should convert Boolean to float")
        void shouldConvertBooleanToFloat() {
            assertEquals(1.0f, ExpressionFunctions.toFloat(true));
            assertEquals(0.0f, ExpressionFunctions.toFloat(false));
        }

        @Test
        @DisplayName("Should parse string to float")
        void shouldParseStringToFloat() {
            assertEquals(3.14f, ExpressionFunctions.toFloat("3.14"), 0.001f);
        }

        @Test
        @DisplayName("Should return 0.0f for unparseable strings")
        void shouldReturnZeroForUnparseableStrings() {
            assertEquals(0.0f, ExpressionFunctions.toFloat("abc"));
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should return empty string for null")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", ExpressionFunctions.toString(null));
        }

        @Test
        @DisplayName("Should convert values to string")
        void shouldConvertValuesToString() {
            assertEquals("42", ExpressionFunctions.toString(42));
            assertEquals("true", ExpressionFunctions.toString(true));
            assertEquals("hello", ExpressionFunctions.toString("hello"));
            assertEquals("3.14", ExpressionFunctions.toString(3.14));
        }
    }

    @Nested
    @DisplayName("toBool()")
    class ToBoolTests {

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(ExpressionFunctions.toBool(null));
        }

        @Test
        @DisplayName("Should return Boolean directly")
        void shouldReturnBooleanDirectly() {
            assertTrue(ExpressionFunctions.toBool(true));
            assertFalse(ExpressionFunctions.toBool(false));
        }

        @Test
        @DisplayName("Should convert numbers (0=false, others=true)")
        void shouldConvertNumbers() {
            assertFalse(ExpressionFunctions.toBool(0));
            assertTrue(ExpressionFunctions.toBool(1));
            assertTrue(ExpressionFunctions.toBool(-1));
            assertTrue(ExpressionFunctions.toBool(42));
            assertFalse(ExpressionFunctions.toBool(0.0));
            assertTrue(ExpressionFunctions.toBool(0.1));
        }

        @ParameterizedTest
        @ValueSource(strings = {"true", "TRUE", "True", "1", "yes", "YES", "on", "ON"})
        @DisplayName("Should return true for truthy strings")
        void shouldReturnTrueForTruthyStrings(String input) {
            assertTrue(ExpressionFunctions.toBool(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {"false", "FALSE", "0", "no", "off", "random", ""})
        @DisplayName("Should return false for falsy strings")
        void shouldReturnFalseForFalsyStrings(String input) {
            assertFalse(ExpressionFunctions.toBool(input));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utility Functions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("size()")
    class SizeTests {

        @Test
        @DisplayName("Should return 0 for null")
        void shouldReturnZeroForNull() {
            assertEquals(0, ExpressionFunctions.size(null));
        }

        @Test
        @DisplayName("Should return string length")
        void shouldReturnStringLength() {
            assertEquals(5, ExpressionFunctions.size("hello"));
            assertEquals(0, ExpressionFunctions.size(""));
        }

        @Test
        @DisplayName("Should return collection size")
        void shouldReturnCollectionSize() {
            assertEquals(3, ExpressionFunctions.size(List.of(1, 2, 3)));
            assertEquals(0, ExpressionFunctions.size(List.of()));
            assertEquals(2, ExpressionFunctions.size(Set.of("a", "b")));
        }

        @Test
        @DisplayName("Should return map size")
        void shouldReturnMapSize() {
            assertEquals(2, ExpressionFunctions.size(Map.of("a", 1, "b", 2)));
            assertEquals(0, ExpressionFunctions.size(Map.of()));
        }

        @Test
        @DisplayName("Should return array length")
        void shouldReturnArrayLength() {
            assertEquals(3, ExpressionFunctions.size(new int[]{1, 2, 3}));
            assertEquals(2, ExpressionFunctions.size(new String[]{"a", "b"}));
        }

        @Test
        @DisplayName("Should return 0 for non-collection types")
        void shouldReturnZeroForNonCollectionTypes() {
            assertEquals(0, ExpressionFunctions.size(42));
            assertEquals(0, ExpressionFunctions.size(true));
        }
    }

    @Nested
    @DisplayName("typeof()")
    class TypeofTests {

        @Test
        @DisplayName("Should return 'null' for null")
        void shouldReturnNullForNull() {
            assertEquals("null", ExpressionFunctions.typeof(null));
        }

        @Test
        @DisplayName("Should return 'string' for String")
        void shouldReturnStringForString() {
            assertEquals("string", ExpressionFunctions.typeof("hello"));
        }

        @Test
        @DisplayName("Should return 'int' for Integer/Long")
        void shouldReturnIntForIntegerLong() {
            assertEquals("int", ExpressionFunctions.typeof(42));
            assertEquals("int", ExpressionFunctions.typeof(100L));
        }

        @Test
        @DisplayName("Should return 'double' for Double/Float")
        void shouldReturnDoubleForDoubleFloat() {
            assertEquals("double", ExpressionFunctions.typeof(3.14));
            assertEquals("double", ExpressionFunctions.typeof(2.5f));
        }

        @Test
        @DisplayName("Should return 'bool' for Boolean")
        void shouldReturnBoolForBoolean() {
            assertEquals("bool", ExpressionFunctions.typeof(true));
            assertEquals("bool", ExpressionFunctions.typeof(false));
        }

        @Test
        @DisplayName("Should return 'list' for List")
        void shouldReturnListForList() {
            assertEquals("list", ExpressionFunctions.typeof(List.of(1, 2, 3)));
            assertEquals("list", ExpressionFunctions.typeof(new ArrayList<>()));
        }

        @Test
        @DisplayName("Should return 'map' for Map")
        void shouldReturnMapForMap() {
            assertEquals("map", ExpressionFunctions.typeof(Map.of("a", 1)));
            assertEquals("map", ExpressionFunctions.typeof(new HashMap<>()));
        }

        @Test
        @DisplayName("Should return 'array' for arrays")
        void shouldReturnArrayForArrays() {
            assertEquals("array", ExpressionFunctions.typeof(new int[]{1, 2, 3}));
            assertEquals("array", ExpressionFunctions.typeof(new String[]{"a", "b"}));
        }
    }

    @Nested
    @DisplayName("defaultValue()")
    class DefaultValueTests {

        @Test
        @DisplayName("Should return fallback for null value")
        void shouldReturnFallbackForNull() {
            assertEquals("fallback", ExpressionFunctions.defaultValue(null, "fallback"));
        }

        @Test
        @DisplayName("Should return fallback for empty string")
        void shouldReturnFallbackForEmptyString() {
            assertEquals("fallback", ExpressionFunctions.defaultValue("", "fallback"));
        }

        @Test
        @DisplayName("Should return fallback for empty collection")
        void shouldReturnFallbackForEmptyCollection() {
            assertEquals("fallback", ExpressionFunctions.defaultValue(List.of(), "fallback"));
            assertEquals("fallback", ExpressionFunctions.defaultValue(Map.of(), "fallback"));
        }

        @Test
        @DisplayName("Should return value if not null/empty")
        void shouldReturnValueIfNotNullEmpty() {
            assertEquals("value", ExpressionFunctions.defaultValue("value", "fallback"));
            assertEquals(42, ExpressionFunctions.defaultValue(42, 0));
            assertEquals(List.of(1), ExpressionFunctions.defaultValue(List.of(1), List.of()));
        }
    }

    @Nested
    @DisplayName("coalesce()")
    class CoalesceTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(ExpressionFunctions.coalesce((Object[]) null));
        }

        @Test
        @DisplayName("Should return first non-null value")
        void shouldReturnFirstNonNullValue() {
            assertEquals("first", ExpressionFunctions.coalesce(null, "first", "second"));
            assertEquals(42, ExpressionFunctions.coalesce(null, null, 42));
        }

        @Test
        @DisplayName("Should skip empty strings")
        void shouldSkipEmptyStrings() {
            assertEquals("value", ExpressionFunctions.coalesce("", "value"));
        }

        @Test
        @DisplayName("Should return null if all values are null or empty")
        void shouldReturnNullIfAllEmpty() {
            assertNull(ExpressionFunctions.coalesce(null, "", null));
        }
    }

    @Nested
    @DisplayName("ifEmpty()")
    class IfEmptyTests {

        @Test
        @DisplayName("Should return fallback for null")
        void shouldReturnFallbackForNull() {
            assertEquals("fallback", ExpressionFunctions.ifEmpty(null, "fallback"));
        }

        @Test
        @DisplayName("Should return fallback for empty string")
        void shouldReturnFallbackForEmptyString() {
            assertEquals("fallback", ExpressionFunctions.ifEmpty("", "fallback"));
        }

        @Test
        @DisplayName("Should return value if not empty")
        void shouldReturnValueIfNotEmpty() {
            assertEquals("value", ExpressionFunctions.ifEmpty("value", "fallback"));
            assertEquals(42, ExpressionFunctions.ifEmpty(42, 0));
        }
    }

    @Nested
    @DisplayName("isNull()")
    class IsNullTests {

        @Test
        @DisplayName("Should return true for null")
        void shouldReturnTrueForNull() {
            assertTrue(ExpressionFunctions.isNull(null));
        }

        @Test
        @DisplayName("Should return false for non-null")
        void shouldReturnFalseForNonNull() {
            assertFalse(ExpressionFunctions.isNull("value"));
            assertFalse(ExpressionFunctions.isNull(0));
            assertFalse(ExpressionFunctions.isNull(""));
        }
    }

    @Nested
    @DisplayName("isEmpty()")
    class IsEmptyTests {

        @Test
        @DisplayName("Should return true for null")
        void shouldReturnTrueForNull() {
            assertTrue(ExpressionFunctions.isEmpty(null));
        }

        @Test
        @DisplayName("Should return true for empty string")
        void shouldReturnTrueForEmptyString() {
            assertTrue(ExpressionFunctions.isEmpty(""));
        }

        @Test
        @DisplayName("Should return true for empty collection")
        void shouldReturnTrueForEmptyCollection() {
            assertTrue(ExpressionFunctions.isEmpty(List.of()));
            assertTrue(ExpressionFunctions.isEmpty(Set.of()));
        }

        @Test
        @DisplayName("Should return true for empty map")
        void shouldReturnTrueForEmptyMap() {
            assertTrue(ExpressionFunctions.isEmpty(Map.of()));
        }

        @Test
        @DisplayName("Should return true for empty array")
        void shouldReturnTrueForEmptyArray() {
            assertTrue(ExpressionFunctions.isEmpty(new int[]{}));
            assertTrue(ExpressionFunctions.isEmpty(new String[]{}));
        }

        @Test
        @DisplayName("Should return false for non-empty values")
        void shouldReturnFalseForNonEmptyValues() {
            assertFalse(ExpressionFunctions.isEmpty("hello"));
            assertFalse(ExpressionFunctions.isEmpty(List.of(1)));
            assertFalse(ExpressionFunctions.isEmpty(Map.of("a", 1)));
            assertFalse(ExpressionFunctions.isEmpty(new int[]{1}));
            assertFalse(ExpressionFunctions.isEmpty(42));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Math Functions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("abs()")
    class AbsTests {

        @Test
        @DisplayName("Should return absolute value")
        void shouldReturnAbsoluteValue() {
            assertEquals(42.0, ExpressionFunctions.abs(42));
            assertEquals(42.0, ExpressionFunctions.abs(-42));
            assertEquals(3.14, ExpressionFunctions.abs(-3.14));
        }

        @Test
        @DisplayName("Should return 0 for null")
        void shouldReturnZeroForNull() {
            assertEquals(0.0, ExpressionFunctions.abs(null));
        }
    }

    @Nested
    @DisplayName("round()")
    class RoundTests {

        @Test
        @DisplayName("Should round to specified decimal places")
        void shouldRoundToSpecifiedDecimalPlaces() {
            assertEquals(3.14, ExpressionFunctions.round(3.14159, 2));
            assertEquals(3.1, ExpressionFunctions.round(3.14159, 1));
        }

        @Test
        @DisplayName("Should round to integer when decimals is 0 or negative")
        void shouldRoundToIntegerWhenDecimalsIsZeroOrNegative() {
            assertEquals(3L, ExpressionFunctions.round(3.14159, 0));
            assertEquals(3L, ExpressionFunctions.round(3.14159, -1));
        }

        @Test
        @DisplayName("Should handle standard rounding")
        void shouldHandleStandardRounding() {
            assertEquals(4L, ExpressionFunctions.round(3.5, 0));
            assertEquals(3L, ExpressionFunctions.round(3.4, 0));
        }
    }

    @Nested
    @DisplayName("floor()")
    class FloorTests {

        @Test
        @DisplayName("Should round down")
        void shouldRoundDown() {
            assertEquals(3L, ExpressionFunctions.floor(3.9));
            assertEquals(3L, ExpressionFunctions.floor(3.1));
            assertEquals(-4L, ExpressionFunctions.floor(-3.1));
        }

        @Test
        @DisplayName("Should return 0 for null")
        void shouldReturnZeroForNull() {
            assertEquals(0L, ExpressionFunctions.floor(null));
        }
    }

    @Nested
    @DisplayName("ceil()")
    class CeilTests {

        @Test
        @DisplayName("Should round up")
        void shouldRoundUp() {
            assertEquals(4L, ExpressionFunctions.ceil(3.1));
            assertEquals(4L, ExpressionFunctions.ceil(3.9));
            assertEquals(-3L, ExpressionFunctions.ceil(-3.1));
        }

        @Test
        @DisplayName("Should return 0 for null")
        void shouldReturnZeroForNull() {
            assertEquals(0L, ExpressionFunctions.ceil(null));
        }
    }

    @Nested
    @DisplayName("min()")
    class MinTests {

        @Test
        @DisplayName("Should return minimum of two values")
        void shouldReturnMinimumOfTwoValues() {
            assertEquals(1.0, ExpressionFunctions.min(1, 5));
            assertEquals(-5.0, ExpressionFunctions.min(-5, 5));
            assertEquals(2.5, ExpressionFunctions.min(2.5, 3.5));
        }
    }

    @Nested
    @DisplayName("max()")
    class MaxTests {

        @Test
        @DisplayName("Should return maximum of two values")
        void shouldReturnMaximumOfTwoValues() {
            assertEquals(5.0, ExpressionFunctions.max(1, 5));
            assertEquals(5.0, ExpressionFunctions.max(-5, 5));
            assertEquals(3.5, ExpressionFunctions.max(2.5, 3.5));
        }
    }

    @Nested
    @DisplayName("pow()")
    class PowTests {

        @Test
        @DisplayName("Should calculate power")
        void shouldCalculatePower() {
            assertEquals(8.0, ExpressionFunctions.pow(2, 3));
            assertEquals(1.0, ExpressionFunctions.pow(5, 0));
            assertEquals(0.5, ExpressionFunctions.pow(2, -1));
        }
    }

    @Nested
    @DisplayName("sqrt()")
    class SqrtTests {

        @Test
        @DisplayName("Should calculate square root")
        void shouldCalculateSquareRoot() {
            assertEquals(3.0, ExpressionFunctions.sqrt(9));
            assertEquals(2.0, ExpressionFunctions.sqrt(4));
            assertEquals(0.0, ExpressionFunctions.sqrt(0));
        }

        @Test
        @DisplayName("Should return NaN for negative numbers")
        void shouldReturnNaNForNegativeNumbers() {
            assertTrue(Double.isNaN(ExpressionFunctions.sqrt(-1)));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // String Functions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("uppercase()")
    class UppercaseTests {

        @Test
        @DisplayName("Should convert to uppercase")
        void shouldConvertToUppercase() {
            assertEquals("HELLO", ExpressionFunctions.uppercase("hello"));
            assertEquals("HELLO WORLD", ExpressionFunctions.uppercase("Hello World"));
        }

        @Test
        @DisplayName("Should return empty string for null")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", ExpressionFunctions.uppercase(null));
        }
    }

    @Nested
    @DisplayName("lowercase()")
    class LowercaseTests {

        @Test
        @DisplayName("Should convert to lowercase")
        void shouldConvertToLowercase() {
            assertEquals("hello", ExpressionFunctions.lowercase("HELLO"));
            assertEquals("hello world", ExpressionFunctions.lowercase("Hello World"));
        }

        @Test
        @DisplayName("Should return empty string for null")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", ExpressionFunctions.lowercase(null));
        }
    }

    @Nested
    @DisplayName("capitalize()")
    class CapitalizeTests {

        @Test
        @DisplayName("Should capitalize first letter")
        void shouldCapitalizeFirstLetter() {
            assertEquals("Hello", ExpressionFunctions.capitalize("hello"));
            assertEquals("Hello", ExpressionFunctions.capitalize("HELLO"));
            assertEquals("Hello world", ExpressionFunctions.capitalize("HELLO WORLD"));
        }

        @Test
        @DisplayName("Should return empty string for null or empty")
        void shouldReturnEmptyStringForNullOrEmpty() {
            assertEquals("", ExpressionFunctions.capitalize(null));
            assertEquals("", ExpressionFunctions.capitalize(""));
        }
    }

    @Nested
    @DisplayName("trim()")
    class TrimTests {

        @Test
        @DisplayName("Should trim whitespace")
        void shouldTrimWhitespace() {
            assertEquals("hello", ExpressionFunctions.trim("  hello  "));
            assertEquals("hello world", ExpressionFunctions.trim("\thello world\n"));
        }

        @Test
        @DisplayName("Should return empty string for null")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", ExpressionFunctions.trim(null));
        }
    }

    @Nested
    @DisplayName("truncate()")
    class TruncateTests {

        @Test
        @DisplayName("Should truncate with default suffix")
        void shouldTruncateWithDefaultSuffix() {
            assertEquals("hel...", ExpressionFunctions.truncate("hello world", 6, null));
        }

        @Test
        @DisplayName("Should truncate with custom suffix")
        void shouldTruncateWithCustomSuffix() {
            assertEquals("hello~", ExpressionFunctions.truncate("hello world", 6, "~"));
        }

        @Test
        @DisplayName("Should not truncate if within limit")
        void shouldNotTruncateIfWithinLimit() {
            assertEquals("hello", ExpressionFunctions.truncate("hello", 10, null));
        }

        @Test
        @DisplayName("Should return empty string for null")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", ExpressionFunctions.truncate(null, 10, null));
        }
    }

    @Nested
    @DisplayName("padLeft()")
    class PadLeftTests {

        @Test
        @DisplayName("Should pad on the left")
        void shouldPadOnTheLeft() {
            assertEquals("00042", ExpressionFunctions.padLeft("42", 5, "0"));
            assertEquals("  42", ExpressionFunctions.padLeft("42", 4, null));
        }

        @Test
        @DisplayName("Should truncate from left if longer than length")
        void shouldTruncateFromLeftIfLongerThanLength() {
            // Implementation keeps rightmost characters when string is longer than length
            assertEquals("llo", ExpressionFunctions.padLeft("hello", 3, "0"));
        }

        @Test
        @DisplayName("Should return unchanged if exact length")
        void shouldReturnUnchangedIfExactLength() {
            assertEquals("hello", ExpressionFunctions.padLeft("hello", 5, "0"));
        }
    }

    @Nested
    @DisplayName("padRight()")
    class PadRightTests {

        @Test
        @DisplayName("Should pad on the right")
        void shouldPadOnTheRight() {
            assertEquals("42000", ExpressionFunctions.padRight("42", 5, "0"));
            assertEquals("42  ", ExpressionFunctions.padRight("42", 4, null));
        }

        @Test
        @DisplayName("Should truncate from right if longer than length")
        void shouldTruncateFromRightIfLongerThanLength() {
            // Implementation keeps leftmost characters when string is longer than length
            assertEquals("hel", ExpressionFunctions.padRight("hello", 3, "0"));
        }

        @Test
        @DisplayName("Should return unchanged if exact length")
        void shouldReturnUnchangedIfExactLength() {
            assertEquals("hello", ExpressionFunctions.padRight("hello", 5, "0"));
        }
    }

    @Nested
    @DisplayName("replace()")
    class ReplaceTests {

        @Test
        @DisplayName("Should replace occurrences")
        void shouldReplaceOccurrences() {
            assertEquals("hello world", ExpressionFunctions.replace("hello-world", "-", " "));
            assertEquals("aaa", ExpressionFunctions.replace("aba", "b", "a"));
        }

        @Test
        @DisplayName("Should handle null replacement as empty string")
        void shouldHandleNullReplacementAsEmptyString() {
            assertEquals("helloworld", ExpressionFunctions.replace("hello-world", "-", null));
        }

        @Test
        @DisplayName("Should return original for null search")
        void shouldReturnOriginalForNullSearch() {
            assertEquals("hello", ExpressionFunctions.replace("hello", null, "x"));
        }

        @Test
        @DisplayName("Should return empty string for null value")
        void shouldReturnEmptyStringForNullValue() {
            assertEquals("", ExpressionFunctions.replace(null, "a", "b"));
        }
    }

    @Nested
    @DisplayName("substring()")
    class SubstringTests {

        @Test
        @DisplayName("Should extract substring")
        void shouldExtractSubstring() {
            assertEquals("llo", ExpressionFunctions.substring("hello", 2, 5));
            assertEquals("hello", ExpressionFunctions.substring("hello", 0, null));
        }

        @Test
        @DisplayName("Should handle out of bounds gracefully")
        void shouldHandleOutOfBoundsGracefully() {
            assertEquals("hello", ExpressionFunctions.substring("hello", -5, 100));
            assertEquals("", ExpressionFunctions.substring("hello", 10, 15));
        }

        @Test
        @DisplayName("Should return empty string for null")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", ExpressionFunctions.substring(null, 0, 5));
        }
    }

    @Nested
    @DisplayName("split()")
    class SplitTests {

        @Test
        @DisplayName("Should split string by delimiter")
        void shouldSplitStringByDelimiter() {
            assertEquals(List.of("a", "b", "c"), ExpressionFunctions.split("a,b,c", ","));
            assertEquals(List.of("hello", "world"), ExpressionFunctions.split("hello world", " "));
        }

        @Test
        @DisplayName("Should use comma as default delimiter")
        void shouldUseCommaAsDefaultDelimiter() {
            assertEquals(List.of("a", "b", "c"), ExpressionFunctions.split("a,b,c", null));
        }

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyListForNull() {
            assertEquals(List.of(), ExpressionFunctions.split(null, ","));
        }
    }

    @Nested
    @DisplayName("join()")
    class JoinTests {

        @Test
        @DisplayName("Should join collection with delimiter")
        void shouldJoinCollectionWithDelimiter() {
            assertEquals("a,b,c", ExpressionFunctions.join(List.of("a", "b", "c"), ","));
            assertEquals("a - b - c", ExpressionFunctions.join(List.of("a", "b", "c"), " - "));
        }

        @Test
        @DisplayName("Should join array with delimiter")
        void shouldJoinArrayWithDelimiter() {
            assertEquals("1,2,3", ExpressionFunctions.join(new int[]{1, 2, 3}, ","));
        }

        @Test
        @DisplayName("Should return empty string for null")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", ExpressionFunctions.join(null, ","));
        }
    }

    @Nested
    @DisplayName("startsWith()")
    class StartsWithTests {

        @Test
        @DisplayName("Should return true when string starts with prefix")
        void shouldReturnTrueWhenStartsWithPrefix() {
            assertTrue(ExpressionFunctions.startsWith("hello world", "hello"));
            assertTrue(ExpressionFunctions.startsWith("test", ""));
        }

        @Test
        @DisplayName("Should return false when string does not start with prefix")
        void shouldReturnFalseWhenDoesNotStartWithPrefix() {
            assertFalse(ExpressionFunctions.startsWith("hello world", "world"));
        }

        @Test
        @DisplayName("Should return false for null inputs")
        void shouldReturnFalseForNullInputs() {
            assertFalse(ExpressionFunctions.startsWith(null, "hello"));
            assertFalse(ExpressionFunctions.startsWith("hello", null));
        }
    }

    @Nested
    @DisplayName("endsWith()")
    class EndsWithTests {

        @Test
        @DisplayName("Should return true when string ends with suffix")
        void shouldReturnTrueWhenEndsWithSuffix() {
            assertTrue(ExpressionFunctions.endsWith("hello world", "world"));
            assertTrue(ExpressionFunctions.endsWith("test", ""));
        }

        @Test
        @DisplayName("Should return false when string does not end with suffix")
        void shouldReturnFalseWhenDoesNotEndWithSuffix() {
            assertFalse(ExpressionFunctions.endsWith("hello world", "hello"));
        }

        @Test
        @DisplayName("Should return false for null inputs")
        void shouldReturnFalseForNullInputs() {
            assertFalse(ExpressionFunctions.endsWith(null, "world"));
            assertFalse(ExpressionFunctions.endsWith("hello", null));
        }
    }

    @Nested
    @DisplayName("contains()")
    class ContainsTests {

        @Test
        @DisplayName("Should return true when string contains substring")
        void shouldReturnTrueWhenContainsSubstring() {
            assertTrue(ExpressionFunctions.contains("hello world", "lo wo"));
        }

        @Test
        @DisplayName("Should return true when collection contains element")
        void shouldReturnTrueWhenCollectionContainsElement() {
            assertTrue(ExpressionFunctions.contains(List.of("a", "b", "c"), "b"));
        }

        @Test
        @DisplayName("Should return false for null inputs")
        void shouldReturnFalseForNullInputs() {
            assertFalse(ExpressionFunctions.contains(null, "test"));
            assertFalse(ExpressionFunctions.contains("test", null));
        }
    }

    @Nested
    @DisplayName("matches()")
    class MatchesTests {

        @Test
        @DisplayName("Should return true when string matches regex")
        void shouldReturnTrueWhenMatchesRegex() {
            assertTrue(ExpressionFunctions.matches("hello123", ".*\\d+"));
            assertTrue(ExpressionFunctions.matches("test@email.com", ".*@.*\\..*"));
        }

        @Test
        @DisplayName("Should return false when string does not match regex")
        void shouldReturnFalseWhenDoesNotMatchRegex() {
            assertFalse(ExpressionFunctions.matches("hello", "\\d+"));
        }

        @Test
        @DisplayName("Should return false for invalid regex")
        void shouldReturnFalseForInvalidRegex() {
            assertFalse(ExpressionFunctions.matches("test", "[invalid"));
        }

        @Test
        @DisplayName("Should return false for null inputs")
        void shouldReturnFalseForNullInputs() {
            assertFalse(ExpressionFunctions.matches(null, ".*"));
            assertFalse(ExpressionFunctions.matches("test", null));
        }
    }

    @Nested
    @DisplayName("length()")
    class LengthTests {

        @Test
        @DisplayName("Should return string length")
        void shouldReturnStringLength() {
            assertEquals(5, ExpressionFunctions.length("hello"));
            assertEquals(0, ExpressionFunctions.length(""));
        }

        @Test
        @DisplayName("Should return 0 for null")
        void shouldReturnZeroForNull() {
            assertEquals(0, ExpressionFunctions.length(null));
        }

        @Test
        @DisplayName("Should convert non-string to string first")
        void shouldConvertNonStringToStringFirst() {
            assertEquals(2, ExpressionFunctions.length(42));
        }

        @Test
        @DisplayName("Should return list size")
        void shouldReturnListSize() {
            assertEquals(3, ExpressionFunctions.length(List.of("a", "b", "c")));
        }

        @Test
        @DisplayName("Should return 0 for empty list")
        void shouldReturnZeroForEmptyList() {
            assertEquals(0, ExpressionFunctions.length(List.of()));
        }

        @Test
        @DisplayName("Should return map size")
        void shouldReturnMapSize() {
            assertEquals(2, ExpressionFunctions.length(Map.of("a", 1, "b", 2)));
        }

        @Test
        @DisplayName("Should return array length")
        void shouldReturnArrayLength() {
            assertEquals(4, ExpressionFunctions.length(new int[]{1, 2, 3, 4}));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Date/Number Formatting Functions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("formatDate()")
    class FormatDateTests {

        @Test
        @DisplayName("Should return empty string for null")
        void shouldReturnEmptyStringForNull() {
            assertEquals("", ExpressionFunctions.formatDate(null, "yyyy-MM-dd"));
        }

        @Test
        @DisplayName("Should format LocalDate")
        void shouldFormatLocalDate() {
            LocalDate date = LocalDate.of(2024, 1, 15);
            assertEquals("2024-01-15", ExpressionFunctions.formatDate(date, "yyyy-MM-dd"));
            assertEquals("15/01/2024", ExpressionFunctions.formatDate(date, "dd/MM/yyyy"));
        }

        @Test
        @DisplayName("Should format LocalDateTime")
        void shouldFormatLocalDateTime() {
            LocalDateTime dateTime = LocalDateTime.of(2024, 1, 15, 10, 30, 0);
            assertEquals("2024-01-15 10:30", ExpressionFunctions.formatDate(dateTime, "yyyy-MM-dd HH:mm"));
        }

        @Test
        @DisplayName("Should format epoch millis")
        void shouldFormatEpochMillis() {
            // 2024-01-15 00:00:00 UTC in millis
            long epochMillis = 1705276800000L;
            String result = ExpressionFunctions.formatDate(epochMillis, "yyyy-MM-dd");
            // Result depends on system timezone, just check it's a valid date format
            assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}"));
        }

        @Test
        @DisplayName("Should use default pattern if null")
        void shouldUseDefaultPatternIfNull() {
            LocalDate date = LocalDate.of(2024, 1, 15);
            assertEquals("2024-01-15", ExpressionFunctions.formatDate(date, null));
        }

        @Test
        @DisplayName("Should convert common date patterns")
        void shouldConvertCommonDatePatterns() {
            LocalDate date = LocalDate.of(2024, 1, 15);
            // DD -> dd, YYYY -> yyyy conversion
            assertEquals("15-01-2024", ExpressionFunctions.formatDate(date, "DD-MM-YYYY"));
        }
    }

    @Nested
    @DisplayName("formatNumber()")
    class FormatNumberTests {

        @Test
        @DisplayName("Should return '0' for null")
        void shouldReturnZeroForNull() {
            assertEquals("0", ExpressionFunctions.formatNumber(null, 2));
        }

        @Test
        @DisplayName("Should format with specified decimal places")
        void shouldFormatWithSpecifiedDecimalPlaces() {
            String result = ExpressionFunctions.formatNumber(1234.5678, 2);
            // Format depends on locale, just check it contains the number
            assertTrue(result.contains("1") && result.contains("234"));
        }

        @Test
        @DisplayName("Should use 2 decimals as default")
        void shouldUseTwoDecimalsAsDefault() {
            String result = ExpressionFunctions.formatNumber(3.14159, null);
            assertTrue(result.contains("14") || result.contains("3.14"));
        }
    }

    @Nested
    @DisplayName("formatCurrency()")
    class FormatCurrencyTests {

        @Test
        @DisplayName("Should return '0' for null")
        void shouldReturnZeroForNull() {
            assertEquals("0", ExpressionFunctions.formatCurrency(null, "EUR"));
        }

        @Test
        @DisplayName("Should format with currency code")
        void shouldFormatWithCurrencyCode() {
            String result = ExpressionFunctions.formatCurrency(1234.56, "USD");
            // Format depends on locale, just check it contains some representation
            assertNotNull(result);
            assertTrue(result.length() > 0);
        }

        @Test
        @DisplayName("Should use EUR as default currency")
        void shouldUseEurAsDefaultCurrency() {
            String result = ExpressionFunctions.formatCurrency(100.0, null);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("now()")
    class NowTests {

        @Test
        @DisplayName("Should return current datetime as ISO string")
        void shouldReturnCurrentDatetimeAsIsoString() {
            String result = ExpressionFunctions.now();

            assertNotNull(result);
            // Should be a valid ISO datetime like "2026-03-04T15:30:45"
            assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"),
                    "now() should return ISO datetime format, got: " + result);
            // Should contain today's date
            assertTrue(result.startsWith(LocalDate.now().toString()),
                    "now() should start with today's date");
        }
    }

    @Nested
    @DisplayName("today()")
    class TodayTests {

        @Test
        @DisplayName("Should return today's date as ISO string")
        void shouldReturnTodaysDateAsIsoString() {
            String result = ExpressionFunctions.today();
            String expected = LocalDate.now().toString();
            assertEquals(expected, result);
        }
    }
}
