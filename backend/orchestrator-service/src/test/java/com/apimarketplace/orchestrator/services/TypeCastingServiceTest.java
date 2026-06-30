package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.services.TypeCastingService.TypeConversionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TypeCastingService.
 *
 * This service converts values to expected types (number, string, array, boolean)
 * for API parameter handling.
 */
@DisplayName("TypeCastingService")
class TypeCastingServiceTest {

    private TypeCastingService typeCastingService;

    @BeforeEach
    void setUp() {
        typeCastingService = new TypeCastingService();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // castValue() - General tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("castValue() - General")
    class CastValueGeneralTests {

        @Test
        @DisplayName("Should return null for null value")
        void shouldReturnNullForNullValue() {
            assertNull(typeCastingService.castValue(null, "number", "param"));
            assertNull(typeCastingService.castValue(null, "string", "param"));
            assertNull(typeCastingService.castValue(null, "array", "param"));
            assertNull(typeCastingService.castValue(null, "boolean", "param"));
        }

        @Test
        @DisplayName("Should throw for unsupported type")
        void shouldThrowForUnsupportedType() {
            TypeConversionException exception = assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue("value", "unsupported", "param")
            );
            assertTrue(exception.getMessage().contains("unsupported"));
            assertTrue(exception.getMessage().contains("param"));
        }

        @Test
        @DisplayName("Should be case-insensitive for type names")
        void shouldBeCaseInsensitiveForTypeNames() {
            assertEquals(42, typeCastingService.castValue(42, "NUMBER", "param"));
            assertEquals(42, typeCastingService.castValue(42, "Number", "param"));
            assertEquals("hello", typeCastingService.castValue("hello", "STRING", "param"));
            assertEquals(true, typeCastingService.castValue(true, "BOOLEAN", "param"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // castValue() - Number type
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("castValue() - Number type")
    class CastToNumberTests {

        @Test
        @DisplayName("Should return Number directly")
        void shouldReturnNumberDirectly() {
            assertEquals(42, typeCastingService.castValue(42, "number", "param"));
            assertEquals(3.14, typeCastingService.castValue(3.14, "number", "param"));
            assertEquals(100L, typeCastingService.castValue(100L, "number", "param"));
        }

        @ParameterizedTest
        @CsvSource({
            "123, 123",
            "-456, -456",
            "0, 0"
        })
        @DisplayName("Should parse integer strings")
        void shouldParseIntegerStrings(String input, int expected) {
            assertEquals(expected, typeCastingService.castValue(input, "number", "param"));
        }

        @Test
        @DisplayName("Should parse double strings")
        void shouldParseDoubleStrings() {
            assertEquals(3.14, typeCastingService.castValue("3.14", "number", "param"));
            assertEquals(-2.5, typeCastingService.castValue("-2.5", "number", "param"));
        }

        @Test
        @DisplayName("Should throw for non-numeric strings")
        void shouldThrowForNonNumericStrings() {
            assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue("not_a_number", "number", "param")
            );
        }

        @Test
        @DisplayName("Should throw for non-convertible types")
        void shouldThrowForNonConvertibleTypes() {
            assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue(List.of(1, 2, 3), "number", "param")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // castValue() - String type
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("castValue() - String type")
    class CastToStringTests {

        @Test
        @DisplayName("Should return String directly")
        void shouldReturnStringDirectly() {
            assertEquals("hello", typeCastingService.castValue("hello", "string", "param"));
            assertEquals("", typeCastingService.castValue("", "string", "param"));
        }

        @Test
        @DisplayName("Should convert Number to String")
        void shouldConvertNumberToString() {
            assertEquals("42", typeCastingService.castValue(42, "string", "param"));
            assertEquals("3.14", typeCastingService.castValue(3.14, "string", "param"));
        }

        @Test
        @DisplayName("Should convert Boolean to String")
        void shouldConvertBooleanToString() {
            assertEquals("true", typeCastingService.castValue(true, "string", "param"));
            assertEquals("false", typeCastingService.castValue(false, "string", "param"));
        }

        @Test
        @DisplayName("Should convert any object to String using valueOf")
        void shouldConvertAnyObjectToString() {
            List<Integer> list = List.of(1, 2, 3);
            String result = (String) typeCastingService.castValue(list, "string", "param");
            assertEquals("[1, 2, 3]", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // castValue() - Array type
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("castValue() - Array type")
    class CastToArrayTests {

        @Test
        @DisplayName("Should return List directly")
        void shouldReturnListDirectly() {
            List<Integer> list = List.of(1, 2, 3);
            assertSame(list, typeCastingService.castValue(list, "array", "param"));
        }

        @Test
        @DisplayName("Should convert Object[] to List")
        void shouldConvertArrayToList() {
            Object[] array = new Object[]{"a", "b", "c"};
            Object result = typeCastingService.castValue(array, "array", "param");
            assertTrue(result instanceof List);
            assertEquals(List.of("a", "b", "c"), result);
        }

        @Test
        @DisplayName("Should wrap String in single-element List")
        void shouldWrapStringInSingleElementList() {
            Object result = typeCastingService.castValue("value", "array", "param");
            assertTrue(result instanceof List);
            assertEquals(List.of("value"), result);
        }

        @Test
        @DisplayName("Should throw for non-convertible types")
        void shouldThrowForNonConvertibleTypes() {
            assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue(42, "array", "param")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // castValue() - Boolean type
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("castValue() - Boolean type")
    class CastToBooleanTests {

        @Test
        @DisplayName("Should return Boolean directly")
        void shouldReturnBooleanDirectly() {
            assertEquals(true, typeCastingService.castValue(true, "boolean", "param"));
            assertEquals(false, typeCastingService.castValue(false, "boolean", "param"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"true", "TRUE", "True", "1", "yes", "YES"})
        @DisplayName("Should convert truthy strings to true")
        void shouldConvertTruthyStringsToTrue(String input) {
            assertEquals(true, typeCastingService.castValue(input, "boolean", "param"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"false", "FALSE", "False", "0", "no", "NO"})
        @DisplayName("Should convert falsy strings to false")
        void shouldConvertFalsyStringsToFalse(String input) {
            assertEquals(false, typeCastingService.castValue(input, "boolean", "param"));
        }

        @Test
        @DisplayName("Should throw for invalid boolean strings")
        void shouldThrowForInvalidBooleanStrings() {
            assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue("maybe", "boolean", "param")
            );
        }

        @Test
        @DisplayName("Should convert numbers to boolean (0=false, others=true)")
        void shouldConvertNumbersToBoolean() {
            assertEquals(false, typeCastingService.castValue(0, "boolean", "param"));
            assertEquals(true, typeCastingService.castValue(1, "boolean", "param"));
            assertEquals(true, typeCastingService.castValue(-1, "boolean", "param"));
            assertEquals(true, typeCastingService.castValue(42, "boolean", "param"));
        }

        @Test
        @DisplayName("Should throw for non-convertible types")
        void shouldThrowForNonConvertibleTypes() {
            assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue(List.of(1, 2), "boolean", "param")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TypeConversionException tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TypeConversionException")
    class TypeConversionExceptionTests {

        @Test
        @DisplayName("Should include parameter name in error message")
        void shouldIncludeParameterNameInErrorMessage() {
            TypeConversionException exception = assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue("abc", "number", "myParameter")
            );
            assertTrue(exception.getMessage().contains("myParameter"));
        }

        @Test
        @DisplayName("Should include value in error message")
        void shouldIncludeValueInErrorMessage() {
            TypeConversionException exception = assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue("invalid_value", "number", "param")
            );
            assertTrue(exception.getMessage().contains("invalid_value"));
        }

        @Test
        @DisplayName("Should include expected type in error message")
        void shouldIncludeExpectedTypeInErrorMessage() {
            TypeConversionException exception = assertThrows(
                TypeConversionException.class,
                () -> typeCastingService.castValue("value", "unknown_type", "param")
            );
            assertTrue(exception.getMessage().contains("unknown_type"));
        }
    }
}
