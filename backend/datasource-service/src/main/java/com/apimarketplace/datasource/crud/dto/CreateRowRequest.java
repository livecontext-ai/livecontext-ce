package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for create-row operation.
 */
public final class CreateRowRequest extends CrudRequest {

    @NotEmpty(message = "At least one row is required")
    private List<RowData> rows;

    @Override
    public CrudOperation getOperation() {
        return CrudOperation.CREATE_ROW;
    }

    public List<RowData> getRows() {
        return rows;
    }

    public void setRows(List<RowData> rows) {
        this.rows = rows;
    }

    /**
     * Data for a single row to insert.
     */
    public record RowData(
        String id,  // Internal row ID (e.g., "row1")
        Map<String, Object> columns  // column_name -> value
    ) {}
}
