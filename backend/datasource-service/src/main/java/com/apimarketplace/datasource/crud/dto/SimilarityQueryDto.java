package com.apimarketplace.datasource.crud.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for vector similarity search queries.
 * Used in combination with (or instead of) WhereConditionDto in ReadRowRequest.
 */
public final class SimilarityQueryDto {

    @NotBlank(message = "Similarity column name is required")
    private String column;

    @NotNull(message = "Query vector is required")
    private float[] queryVector;

    @Min(value = 1, message = "topK must be at least 1")
    @Max(value = 100, message = "topK cannot exceed 100")
    private Integer topK = 5;

    private Float threshold;

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public float[] getQueryVector() {
        return queryVector;
    }

    public void setQueryVector(float[] queryVector) {
        this.queryVector = queryVector;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Float getThreshold() {
        return threshold;
    }

    public void setThreshold(Float threshold) {
        this.threshold = threshold;
    }
}
