package com.apimarketplace.datasource.crud.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SqlSanitizer - SQL injection protection service.
 */
@DisplayName("SqlSanitizer")
class SqlSanitizerTest {

    private SqlSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new SqlSanitizer();
    }

    @Nested
    @DisplayName("sanitizeColumnName")
    class SanitizeColumnNameTests {

        @ParameterizedTest(name = "Should accept valid column name ''{0}''")
        @ValueSource(strings = {"name", "first_name", "_id", "col1", "user_123", "A",
            "Catégorie", "Expéditeur", "prénom", "città", "Straße", "日本語"})
        @DisplayName("Should accept valid column names including Unicode")
        void shouldAcceptValidColumnNames(String columnName) {
            String result = sanitizer.sanitizeColumnName(columnName);
            assertThat(result).isEqualTo(columnName);
        }

        @ParameterizedTest(name = "Should accept column name with spaces ''{0}''")
        @ValueSource(strings = {"First Name", "Date de naissance", "My Column"})
        @DisplayName("Should accept column names with spaces")
        void shouldAcceptColumnNamesWithSpaces(String columnName) {
            String result = sanitizer.sanitizeColumnName(columnName);
            assertThat(result).isEqualTo(columnName);
        }

        @ParameterizedTest(name = "Should reject null or blank column name")
        @NullAndEmptySource
        @DisplayName("Should throw for null or empty column names")
        void shouldThrowForNullOrEmptyColumnNames(String columnName) {
            assertThatThrownBy(() -> sanitizer.sanitizeColumnName(columnName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Column name cannot be empty");
        }

        @ParameterizedTest(name = "Should reject column name with dangerous chars: ''{0}''")
        @ValueSource(strings = {"col;name", "col'name", "col\"name", "col\\name", "col(name", "col)name", "col--name", "123col"})
        @DisplayName("Should reject column names with dangerous or invalid characters")
        void shouldRejectDangerousCharacters(String columnName) {
            assertThatThrownBy(() -> sanitizer.sanitizeColumnName(columnName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid characters");
        }

        @Test
        @DisplayName("Should reject data.columnName format (dot is invalid - caller must strip prefix)")
        void shouldRejectDataDotPrefix() {
            // The data. prefix must be stripped by CrudRepository before calling sanitize
            assertThatThrownBy(() -> sanitizer.sanitizeColumnName("data.name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid characters");
        }

        @ParameterizedTest(name = "Should accept SQL keyword ''{0}'' as JSONB column name")
        @ValueSource(strings = {"DROP", "DELETE", "SELECT", "TABLE", "UNION", "INSERT", "UPDATE", "from", "FROM", "where"})
        @DisplayName("Should accept SQL keywords as column names (JSONB keys are parameterized, no injection risk)")
        void shouldAcceptSqlKeywordsAsJsonbKeys(String keyword) {
            String result = sanitizer.sanitizeColumnName(keyword);
            assertThat(result).isEqualTo(keyword);
        }

        @Test
        @DisplayName("Should reject column names exceeding 128 characters")
        void shouldRejectTooLongColumnNames() {
            String longName = "a".repeat(129);
            assertThatThrownBy(() -> sanitizer.sanitizeColumnName(longName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
        }

        @Test
        @DisplayName("Should trim whitespace from column names")
        void shouldTrimWhitespace() {
            String result = sanitizer.sanitizeColumnName("  name  ");
            assertThat(result).isEqualTo("name");
        }
    }

    @Nested
    @DisplayName("sanitizeTableName")
    class SanitizeTableNameTests {

        @ParameterizedTest(name = "Should accept valid table name ''{0}''")
        @ValueSource(strings = {"users", "user_data", "_temp", "Table1"})
        @DisplayName("Should accept valid table names")
        void shouldAcceptValidTableNames(String tableName) {
            String result = sanitizer.sanitizeTableName(tableName);
            assertThat(result).isEqualTo(tableName);
        }

        @ParameterizedTest(name = "Should reject null or blank table name")
        @NullAndEmptySource
        @DisplayName("Should throw for null or empty table names")
        void shouldThrowForNullOrEmptyTableNames(String tableName) {
            assertThatThrownBy(() -> sanitizer.sanitizeTableName(tableName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Table name cannot be empty");
        }

        @Test
        @DisplayName("Should reject table names with special characters")
        void shouldRejectSpecialCharacters() {
            assertThatThrownBy(() -> sanitizer.sanitizeTableName("users; DROP TABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid characters");
        }

        @Test
        @DisplayName("Should reject table names exceeding 128 characters")
        void shouldRejectTooLongTableNames() {
            String longName = "t".repeat(129);
            assertThatThrownBy(() -> sanitizer.sanitizeTableName(longName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
        }
    }

    @Nested
    @DisplayName("sanitizeOperator")
    class SanitizeOperatorTests {

        @ParameterizedTest(name = "Should accept valid operator ''{0}''")
        @ValueSource(strings = {"=", "!=", "<>", ">", "<", ">=", "<=", "LIKE", "IN", "IS NULL", "IS NOT NULL"})
        @DisplayName("Should accept all valid operators")
        void shouldAcceptValidOperators(String operator) {
            String result = sanitizer.sanitizeOperator(operator);
            assertThat(result).isNotBlank();
        }

        @Test
        @DisplayName("Should normalize == to =")
        void shouldNormalizeDoubleEquals() {
            String result = sanitizer.sanitizeOperator("==");
            assertThat(result).isEqualTo("=");
        }

        @Test
        @DisplayName("Should normalize lowercase operators to uppercase")
        void shouldNormalizeCasing() {
            assertThat(sanitizer.sanitizeOperator("like")).isEqualTo("LIKE");
            assertThat(sanitizer.sanitizeOperator("in")).isEqualTo("IN");
            assertThat(sanitizer.sanitizeOperator("is null")).isEqualTo("IS NULL");
        }

        @ParameterizedTest(name = "Should reject invalid operator ''{0}''")
        @ValueSource(strings = {"BETWEEN", "EXISTS", "DROP", "&&", "||"})
        @DisplayName("Should reject invalid operators")
        void shouldRejectInvalidOperators(String operator) {
            assertThatThrownBy(() -> sanitizer.sanitizeOperator(operator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid SQL operator");
        }

        @ParameterizedTest(name = "Should reject null or blank operator")
        @NullAndEmptySource
        @DisplayName("Should throw for null or empty operators")
        void shouldThrowForNullOrEmptyOperators(String operator) {
            assertThatThrownBy(() -> sanitizer.sanitizeOperator(operator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Operator cannot be empty");
        }
    }

    @Nested
    @DisplayName("validateValueLength")
    class ValidateValueLengthTests {

        @Test
        @DisplayName("Should pass for null value")
        void shouldPassForNullValue() {
            sanitizer.validateValueLength(null); // Should not throw
        }

        @Test
        @DisplayName("Should pass for normal string values")
        void shouldPassForNormalStringValues() {
            sanitizer.validateValueLength("Alice");
            sanitizer.validateValueLength("42");
            sanitizer.validateValueLength("hello world");
        }

        @Test
        @DisplayName("Should pass for numeric values")
        void shouldPassForNumericValues() {
            sanitizer.validateValueLength(42);
            sanitizer.validateValueLength(3.14);
        }

        @Test
        @DisplayName("Should pass for HTML content (parameterized queries protect against injection)")
        void shouldPassForHtmlContent() {
            sanitizer.validateValueLength("<!doctype html><html><body>Hello CAST(x)</body></html>");
            sanitizer.validateValueLength("<script>var x = 'UNION SELECT';</script>");
        }

        @Test
        @DisplayName("Should reject values exceeding 100KB")
        void shouldRejectTooLongValues() {
            String longValue = "a".repeat(100_001);
            assertThatThrownBy(() -> sanitizer.validateValueLength(longValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum allowed length");
        }

        @Test
        @DisplayName("Should accept values up to 100KB")
        void shouldAcceptValuesUpTo100KB() {
            String maxValue = "a".repeat(100_000);
            sanitizer.validateValueLength(maxValue); // Should not throw
        }
    }

    @Nested
    @DisplayName("escapeLikePattern")
    class EscapeLikePatternTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertThat(sanitizer.escapeLikePattern(null)).isNull();
        }

        @Test
        @DisplayName("Should escape percent sign")
        void shouldEscapePercent() {
            assertThat(sanitizer.escapeLikePattern("100%")).isEqualTo("100\\%");
        }

        @Test
        @DisplayName("Should escape underscore")
        void shouldEscapeUnderscore() {
            assertThat(sanitizer.escapeLikePattern("test_value")).isEqualTo("test\\_value");
        }

        @Test
        @DisplayName("Should escape backslash")
        void shouldEscapeBackslash() {
            assertThat(sanitizer.escapeLikePattern("path\\file")).isEqualTo("path\\\\file");
        }

        @Test
        @DisplayName("Should escape all special characters together")
        void shouldEscapeAllSpecialCharacters() {
            assertThat(sanitizer.escapeLikePattern("10%_\\")).isEqualTo("10\\%\\_\\\\");
        }

        @Test
        @DisplayName("Should not modify normal strings")
        void shouldNotModifyNormalStrings() {
            assertThat(sanitizer.escapeLikePattern("hello world")).isEqualTo("hello world");
        }
    }

    @Nested
    @DisplayName("operatorRequiresValue")
    class OperatorRequiresValueTests {

        @Test
        @DisplayName("Should return true for comparison operators")
        void shouldReturnTrueForComparisonOperators() {
            assertThat(sanitizer.operatorRequiresValue("=")).isTrue();
            assertThat(sanitizer.operatorRequiresValue("!=")).isTrue();
            assertThat(sanitizer.operatorRequiresValue("LIKE")).isTrue();
        }

        @Test
        @DisplayName("Should return false for IS NULL")
        void shouldReturnFalseForIsNull() {
            assertThat(sanitizer.operatorRequiresValue("IS NULL")).isFalse();
        }

        @Test
        @DisplayName("Should return false for IS NOT NULL")
        void shouldReturnFalseForIsNotNull() {
            assertThat(sanitizer.operatorRequiresValue("IS NOT NULL")).isFalse();
        }
    }
}
