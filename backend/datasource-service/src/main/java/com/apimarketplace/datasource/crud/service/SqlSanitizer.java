package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Service for SQL injection protection.
 * Provides methods to validate and sanitize SQL identifiers and values.
 *
 * Security approach:
 * 1. Column and table names are validated against a whitelist pattern
 * 2. Operators are validated against a known set
 * 3. Values are ALWAYS passed as parameterized query parameters (never concatenated)
 * 4. Value length is capped to prevent abuse
 */
@Service
public class SqlSanitizer {

    private static final Logger log = LoggerFactory.getLogger(SqlSanitizer.class);

    /**
     * Valid SQL operators for WHERE conditions.
     */
    private static final Set<String> ALLOWED_OPERATORS = Set.of(
        "=", "!=", "<>", ">", "<", ">=", "<=",
        "LIKE", "IN", "IS NULL", "IS NOT NULL"
    );

    /**
     * Pattern for valid SQL identifiers (table names).
     * Allows: ASCII letters, numbers, underscore. Must start with letter or underscore.
     */
    private static final Pattern VALID_SQL_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * Pattern for valid JSONB column names (user-defined column names stored as JSONB keys).
     * Allows: Unicode letters (accents, CJK, etc.), digits, spaces, underscores, hyphens.
     * Must start with a letter or underscore.
     * These are never used as SQL identifiers - always parameterized or accessed via jsonb_extract_path_text.
     */
    private static final Pattern VALID_COLUMN_NAME = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_ -]*$", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Characters that are dangerous in SQL context and must be rejected even in JSONB keys.
     */
    private static final Pattern DANGEROUS_CHARS = Pattern.compile("[';\"\\\\(){}]|--");

    /**
     * Dangerous SQL keywords that should never appear in identifiers.
     */
    private static final Set<String> SQL_KEYWORDS = Set.of(
        "DROP", "DELETE", "TRUNCATE", "ALTER", "EXEC", "EXECUTE",
        "INSERT", "UPDATE", "UNION", "SELECT", "TABLE", "FROM",
        "WHERE", "OR", "AND", "CREATE", "GRANT", "REVOKE"
    );

    /**
     * Validate and sanitize a column name.
     *
     * @param columnName The column name to validate
     * @return The sanitized column name
     * @throws IllegalArgumentException if the column name is invalid
     */
    public String sanitizeColumnName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Column name cannot be empty");
        }

        String trimmed = columnName.trim();

        // Reject dangerous SQL characters (defense in depth)
        if (DANGEROUS_CHARS.matcher(trimmed).find()) {
            log.warn("Column name contains dangerous characters, rejected: {}", trimmed);
            throw new IllegalArgumentException("Invalid column name: contains invalid characters");
        }

        // Check against valid column name pattern (Unicode-aware for JSONB keys)
        if (!VALID_COLUMN_NAME.matcher(trimmed).matches()) {
            log.warn("Invalid column name rejected: {}", trimmed);
            throw new IllegalArgumentException("Invalid column name: contains invalid characters");
        }

        // SQL keywords are allowed for column names because these are JSONB keys,
        // not SQL identifiers. They are always accessed via parameterized queries
        // (jsonb_build_object(:key, :val)) or jsonb_extract_path_text, never interpolated
        // into SQL. Blocking "from", "select", etc. prevents legitimate use cases
        // (e.g. email "from" field).

        // Additional length check
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("Column name too long (max 128 characters)");
        }

        return trimmed;
    }

    /**
     * Validate and sanitize a table name.
     *
     * @param tableName The table name to validate
     * @return The sanitized table name
     * @throws IllegalArgumentException if the table name is invalid
     */
    public String sanitizeTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name cannot be empty");
        }

        String trimmed = tableName.trim();

        // Check against strict SQL identifier pattern (table names are actual SQL identifiers)
        if (!VALID_SQL_IDENTIFIER.matcher(trimmed).matches()) {
            log.warn("Invalid table name rejected: {}", trimmed);
            throw new IllegalArgumentException("Invalid table name: contains invalid characters");
        }

        // Additional length check
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("Table name too long (max 128 characters)");
        }

        return trimmed;
    }

    /**
     * Validate and normalize an SQL operator.
     *
     * @param operator The operator to validate
     * @return The normalized operator
     * @throws IllegalArgumentException if the operator is invalid
     */
    public String sanitizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException(
                "Operator cannot be empty. Valid operators: =, !=, >, <, >=, <=, LIKE, IN, IS NULL, IS NOT NULL");
        }

        // Delegate alias handling to WhereCondition so there is one source of truth for
        // operator aliases (EQ, GT, CONTAINS, ISNULL, …). Otherwise the tool layer accepts
        // an alias during validation but the SQL layer rejects it, which is exactly the
        // kind of silent inconsistency that burns agents on retry.
        String normalized = WhereCondition.normalizeOperator(operator);

        if (!ALLOWED_OPERATORS.contains(normalized)) {
            log.warn("Invalid SQL operator rejected: {}", operator);
            throw new IllegalArgumentException(
                "Invalid SQL operator: '" + operator + "'. Valid operators: "
                    + "=, !=, >, <, >=, <=, LIKE, IN, IS NULL, IS NOT NULL. "
                    + "Aliases also accepted: ==, EQ, NE, GT, LT, GTE, LTE, CONTAINS (→LIKE), ISNULL, NOTNULL.");
        }

        return normalized;
    }

    /**
     * Validate a value's length before storage.
     * SQL injection protection for values is handled by parameterized queries,
     * so no pattern-based validation is needed here.
     *
     * @param value The value to validate
     * @throws IllegalArgumentException if the value exceeds maximum length
     */
    public void validateValueLength(Object value) {
        if (value == null) {
            return;
        }

        if (value instanceof String strValue) {
            if (strValue.length() > 100_000) {
                throw new IllegalArgumentException("Value exceeds maximum allowed length (100KB)");
            }
        }
    }

    /**
     * Escape a string for use in LIKE patterns.
     * Escapes %, _, and \ characters.
     *
     * @param value The value to escape
     * @return The escaped value
     */
    public String escapeLikePattern(String value) {
        if (value == null) {
            return null;
        }

        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    /**
     * Check if an operator requires a value.
     *
     * @param operator The operator to check
     * @return true if the operator requires a value
     */
    public boolean operatorRequiresValue(String operator) {
        String normalized = sanitizeOperator(operator);
        return !Set.of("IS NULL", "IS NOT NULL").contains(normalized);
    }
}
