package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * DTO for WHERE condition in CRUD operations.
 */
public record WhereConditionDto(
    @NotBlank(message = "Column name is required")
    String column,

    @NotBlank(message = "Operator is required")
    @Pattern(
        regexp = "^(==?|!=|<>|>|<|>=|<=|LIKE|IN|IS NULL|IS NOT NULL)$",
        flags = Pattern.Flag.CASE_INSENSITIVE,
        message = "Invalid operator. Allowed: =, ==, !=, <>, >, <, >=, <=, LIKE, IN, IS NULL, IS NOT NULL"
    )
    String operator,

    Object value
) {
    /**
     * Convert to domain object.
     */
    public WhereCondition toDomain() {
        return new WhereCondition(column, operator, value);
    }

    /**
     * Create from domain object.
     */
    public static WhereConditionDto fromDomain(WhereCondition domain) {
        return new WhereConditionDto(domain.column(), domain.operator(), domain.value());
    }
}
