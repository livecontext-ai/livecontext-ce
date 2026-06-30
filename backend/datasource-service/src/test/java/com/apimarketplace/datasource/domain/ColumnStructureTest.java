package com.apimarketplace.datasource.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ColumnStructure")
class ColumnStructureTest {

    @Nested
    @DisplayName("getValue()")
    class GetValueTests {

        @Test
        @DisplayName("SCALAR should return 'scalar'")
        void scalarShouldReturnScalar() {
            assertEquals("scalar", ColumnStructure.SCALAR.getValue());
        }

        @Test
        @DisplayName("OBJECT should return 'object'")
        void objectShouldReturnObject() {
            assertEquals("object", ColumnStructure.OBJECT.getValue());
        }

        @Test
        @DisplayName("ARRAY should return 'array'")
        void arrayShouldReturnArray() {
            assertEquals("array", ColumnStructure.ARRAY.getValue());
        }
    }

    @Nested
    @DisplayName("fromValue()")
    class FromValueTests {

        @Test
        @DisplayName("Should parse 'scalar' to SCALAR")
        void shouldParseScalar() {
            assertEquals(ColumnStructure.SCALAR, ColumnStructure.fromValue("scalar"));
        }

        @Test
        @DisplayName("Should parse 'object' to OBJECT")
        void shouldParseObject() {
            assertEquals(ColumnStructure.OBJECT, ColumnStructure.fromValue("object"));
        }

        @Test
        @DisplayName("Should parse 'array' to ARRAY")
        void shouldParseArray() {
            assertEquals(ColumnStructure.ARRAY, ColumnStructure.fromValue("array"));
        }

        @Test
        @DisplayName("Should parse case-insensitively")
        void shouldParseCaseInsensitive() {
            assertEquals(ColumnStructure.SCALAR, ColumnStructure.fromValue("SCALAR"));
            assertEquals(ColumnStructure.OBJECT, ColumnStructure.fromValue("Object"));
            assertEquals(ColumnStructure.ARRAY, ColumnStructure.fromValue("ARRAY"));
        }

        @ParameterizedTest
        @DisplayName("Should default to SCALAR for null or blank values")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void shouldDefaultToScalarForNullOrBlank(String value) {
            assertEquals(ColumnStructure.SCALAR, ColumnStructure.fromValue(value));
        }

        @Test
        @DisplayName("Should throw for invalid value")
        void shouldThrowForInvalid() {
            assertThrows(IllegalArgumentException.class, () -> ColumnStructure.fromValue("invalid"));
        }

        @ParameterizedTest
        @DisplayName("Round-trip: fromValue(getValue()) should return same enum")
        @EnumSource(ColumnStructure.class)
        void roundTripShouldWork(ColumnStructure structure) {
            assertEquals(structure, ColumnStructure.fromValue(structure.getValue()));
        }
    }

    @Nested
    @DisplayName("Enum completeness")
    class CompletenessTests {

        @Test
        @DisplayName("Should have exactly 3 values")
        void shouldHave3Values() {
            assertEquals(3, ColumnStructure.values().length);
        }
    }
}
