package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request DTO for read-row operation.
 * The where condition is optional - if not provided, all rows are returned (up to limit).
 */
public final class ReadRowRequest extends CrudRequest {

    @Valid
    private WhereConditionDto where;

    @Valid
    private SimilarityQueryDto similarity;

    @Min(value = 1, message = "Limit must be at least 1")
    @Max(value = 100, message = "Limit cannot exceed 100")
    private Integer limit = 20;

    @Min(value = 0, message = "Offset must be at least 0")
    private Integer offset = 0;

    @Override
    public CrudOperation getOperation() {
        return CrudOperation.READ_ROW;
    }

    public WhereConditionDto getWhere() {
        return where;
    }

    public void setWhere(WhereConditionDto where) {
        this.where = where;
    }

    public SimilarityQueryDto getSimilarity() {
        return similarity;
    }

    public void setSimilarity(SimilarityQueryDto similarity) {
        this.similarity = similarity;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }
}
