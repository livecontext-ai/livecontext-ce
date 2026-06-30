package com.apimarketplace.datasource.crud.domain;

import java.util.Set;

/**
 * Represents a WHERE condition for CRUD operations.
 * Immutable record with validation.
 */
public record WhereCondition(
    String column,
    String operator,
    Object value
) {
    /**
     * Valid SQL operators for WHERE conditions.
     */
    public static final Set<String> VALID_OPERATORS = Set.of(
        "=", "==", "!=", "<>", ">", "<", ">=", "<=",
        "LIKE", "IN", "IS NULL", "IS NOT NULL"
    );

    /**
     * Operators that don't require a value.
     */
    public static final Set<String> NO_VALUE_OPERATORS = Set.of(
        "IS NULL", "IS NOT NULL"
    );

    /**
     * Validate the WHERE condition.
     * @throws IllegalArgumentException if the condition is invalid
     */
    public void validate() {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException(
                "WHERE column cannot be empty. Example: where={column:'status', operator:'=', value:'open'}");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException(
                "WHERE operator cannot be empty. Valid operators: " + validOperatorsList()
                    + ". Example: where={column:'status', operator:'=', value:'open'}");
        }

        String normalizedOperator = normalizeOperator(operator);
        if (!VALID_OPERATORS.contains(normalizedOperator)) {
            throw new IllegalArgumentException(
                "Invalid WHERE operator: '" + operator + "'. Valid operators: " + validOperatorsList()
                    + ". Aliases also accepted: ==, EQ, NE, GT, LT, GTE, LTE, CONTAINS (→LIKE), ISNULL, NOTNULL.");
        }

        // Value is required for most operators
        if (!NO_VALUE_OPERATORS.contains(normalizedOperator) && value == null) {
            throw new IllegalArgumentException(
                "WHERE value is required for operator '" + operator + "'. "
                    + "Example: where={column:'status', operator:'" + operator + "', value:'open'}. "
                    + "For null checks, use operator 'IS NULL' or 'IS NOT NULL' (no value needed).");
        }
    }

    private static String validOperatorsList() {
        // Deterministic order so error messages are stable/testable.
        return "=, !=, >, <, >=, <=, LIKE, IN, IS NULL, IS NOT NULL";
    }

    /**
     * Normalize the operator, supporting common aliases that LLMs tend to use.
     */
    public static String normalizeOperator(String operator) {
        if (operator == null) return null;
        String trimmed = operator.trim().toUpperCase();
        return switch (trimmed) {
            case "==", "EQ", "EQUALS" -> "=";
            case "<>", "NE", "NEQ", "NOT_EQUAL", "NOT_EQUALS" -> "!=";
            case "GT", "GREATER_THAN" -> ">";
            case "LT", "LESS_THAN" -> "<";
            case "GTE", "GE", "GREATER_THAN_OR_EQUAL" -> ">=";
            case "LTE", "LE", "LESS_THAN_OR_EQUAL" -> "<=";
            case "CONTAINS" -> "LIKE";
            case "NULL", "ISNULL", "IS_NULL" -> "IS NULL";
            case "NOT_NULL", "NOTNULL", "IS_NOT_NULL", "ISNOTNULL" -> "IS NOT NULL";
            default -> trimmed;
        };
    }

    /**
     * Get the normalized operator for SQL.
     */
    public String getNormalizedOperator() {
        return normalizeOperator(operator);
    }

    /**
     * Check if this operator requires a value.
     */
    public boolean requiresValue() {
        return !NO_VALUE_OPERATORS.contains(getNormalizedOperator());
    }
}
