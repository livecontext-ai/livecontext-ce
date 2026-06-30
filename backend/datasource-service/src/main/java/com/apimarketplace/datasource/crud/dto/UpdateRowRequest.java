package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request DTO for update-row operation.
 */
public final class UpdateRowRequest extends CrudRequest {

    @NotNull(message = "WHERE condition is required")
    @Valid
    private WhereConditionDto where;

    @NotEmpty(message = "At least one column to update is required")
    private Map<String, Object> set;

    @Override
    public CrudOperation getOperation() {
        return CrudOperation.UPDATE_ROW;
    }

    public WhereConditionDto getWhere() {
        return where;
    }

    public void setWhere(WhereConditionDto where) {
        this.where = where;
    }

    public Map<String, Object> getSet() {
        return set;
    }

    public void setSet(Map<String, Object> set) {
        this.set = set;
    }
}
