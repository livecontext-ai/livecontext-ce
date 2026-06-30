package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for delete-row operation.
 */
public final class DeleteRowRequest extends CrudRequest {

    @NotNull(message = "WHERE condition is required")
    @Valid
    private WhereConditionDto where;

    @Override
    public CrudOperation getOperation() {
        return CrudOperation.DELETE_ROW;
    }

    public WhereConditionDto getWhere() {
        return where;
    }

    public void setWhere(WhereConditionDto where) {
        this.where = where;
    }
}
