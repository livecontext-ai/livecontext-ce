package com.apimarketplace.datasource.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ColumnType")
class ColumnTypeTest {

    @Nested
    @DisplayName("getValue()")
    class GetValueTests {

        @Test
        @DisplayName("TEXT should return 'text'")
        void textShouldReturnText() {
            assertEquals("text", ColumnType.TEXT.getValue());
        }

        @Test
        @DisplayName("NUMBER should return 'number'")
        void numberShouldReturnNumber() {
            assertEquals("number", ColumnType.NUMBER.getValue());
        }

        @Test
        @DisplayName("DATE should return 'date'")
        void dateShouldReturnDate() {
            assertEquals("date", ColumnType.DATE.getValue());
        }

        @Test
        @DisplayName("CHECKBOX should return 'checkbox'")
        void checkboxShouldReturnCheckbox() {
            assertEquals("checkbox", ColumnType.CHECKBOX.getValue());
        }

        @Test
        @DisplayName("MULTI_SELECT should return 'multi_select'")
        void multiSelectShouldReturnMultiSelect() {
            assertEquals("multi_select", ColumnType.MULTI_SELECT.getValue());
        }

        @Test
        @DisplayName("EMAIL should return 'email'")
        void emailShouldReturnEmail() {
            assertEquals("email", ColumnType.EMAIL.getValue());
        }

        @Test
        @DisplayName("PHONE should return 'phone'")
        void phoneShouldReturnPhone() {
            assertEquals("phone", ColumnType.PHONE.getValue());
        }

        @Test
        @DisplayName("URL should return 'url'")
        void urlShouldReturnUrl() {
            assertEquals("url", ColumnType.URL.getValue());
        }

        @DisplayName("All enum values should have non-null value")
        @ParameterizedTest
        @EnumSource(ColumnType.class)
        void allValuesShouldBeNonNull(ColumnType type) {
            assertNotNull(type.getValue());
            assertFalse(type.getValue().isBlank());
        }
    }

    @Nested
    @DisplayName("fromValue()")
    class FromValueTests {

        @Test
        @DisplayName("Should parse 'text' to TEXT")
        void shouldParseText() {
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("text"));
        }

        @Test
        @DisplayName("Should parse case-insensitively")
        void shouldParseCaseInsensitive() {
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("TEXT"));
            assertEquals(ColumnType.NUMBER, ColumnType.fromValue("Number"));
            assertEquals(ColumnType.CHECKBOX, ColumnType.fromValue("CHECKBOX"));
        }

        @ParameterizedTest
        @DisplayName("Should default to TEXT for null or blank values")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void shouldDefaultToTextForNullOrBlank(String value) {
            assertEquals(ColumnType.TEXT, ColumnType.fromValue(value));
        }

        @Test
        @DisplayName("Should fallback to TEXT for unknown value")
        void shouldFallbackToTextForUnknown() {
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("invalid_type"));
        }

        @ParameterizedTest
        @DisplayName("Round-trip: fromValue(getValue()) should return same enum")
        @EnumSource(ColumnType.class)
        void roundTripShouldWork(ColumnType type) {
            assertEquals(type, ColumnType.fromValue(type.getValue()));
        }
    }

    @Nested
    @DisplayName("Unknown types fallback to TEXT")
    class FallbackTests {

        @Test
        @DisplayName("Unknown types should fallback to TEXT")
        void unknownTypesShouldFallbackToText() {
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("boolean"));
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("badge"));
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("tags"));
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("link"));
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("json"));
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("code"));
            assertEquals(ColumnType.TEXT, ColumnType.fromValue("whatever"));
        }
    }

    @Nested
    @DisplayName("Enum completeness")
    class CompletenessTests {

        @Test
        @DisplayName("Should have exactly 15 values")
        void shouldHave15Values() {
            assertEquals(15, ColumnType.values().length);
        }

        @Test
        @DisplayName("Should contain all expected types")
        void shouldContainAllTypes() {
            assertNotNull(ColumnType.valueOf("TEXT"));
            assertNotNull(ColumnType.valueOf("NUMBER"));
            assertNotNull(ColumnType.valueOf("DATE"));
            assertNotNull(ColumnType.valueOf("CHECKBOX"));
            assertNotNull(ColumnType.valueOf("SELECT"));
            assertNotNull(ColumnType.valueOf("MULTI_SELECT"));
            assertNotNull(ColumnType.valueOf("RATING"));
            assertNotNull(ColumnType.valueOf("SENTIMENT"));
            assertNotNull(ColumnType.valueOf("PROGRESS"));
            assertNotNull(ColumnType.valueOf("FILE"));
            assertNotNull(ColumnType.valueOf("IMAGE"));
            assertNotNull(ColumnType.valueOf("EMAIL"));
            assertNotNull(ColumnType.valueOf("PHONE"));
            assertNotNull(ColumnType.valueOf("URL"));
        }
    }
}
