package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for create-column operation.
 */
public final class CreateColumnRequest extends CrudRequest {

    @NotEmpty(message = "At least one column definition is required")
    private List<ColumnDefinition> columns;

    @Override
    public CrudOperation getOperation() {
        return CrudOperation.CREATE_COLUMN;
    }

    public List<ColumnDefinition> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnDefinition> columns) {
        this.columns = columns;
    }

    /**
     * Definition for a column to create.
     */
    public record ColumnDefinition(
        String name,
        String type,  // text, number, date, checkbox, select, multi_select, rating, sentiment, progress, file, image, email, phone, url, vector
        @JsonProperty("default_value") String defaultValue,
        Map<String, Object> display
    ) {}
}
