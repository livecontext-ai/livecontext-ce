package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Base class for CRUD operation requests.
 * Uses Jackson polymorphic deserialization based on "operation" field.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "operation"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = CreateRowRequest.class, name = "create-row"),
    @JsonSubTypes.Type(value = CreateColumnRequest.class, name = "create-column"),
    @JsonSubTypes.Type(value = ReadRowRequest.class, name = "read-row"),
    @JsonSubTypes.Type(value = UpdateRowRequest.class, name = "update-row"),
    @JsonSubTypes.Type(value = DeleteRowRequest.class, name = "delete-row")
})
public abstract sealed class CrudRequest
    permits CreateRowRequest, CreateColumnRequest, ReadRowRequest, UpdateRowRequest, DeleteRowRequest {

    @NotNull(message = "dataSourceId is required")
    @Positive(message = "dataSourceId must be positive")
    @JsonProperty("data_source_id")
    private Long dataSourceId;

    @JsonProperty("step_label")
    private String stepLabel; // For logging and tracking

    /**
     * LC-066/LC-011 re-audit item 2: true when the calling orchestrator node is executing inside
     * a run/conversation already tagged restricted (Gmail/Drive-derived content) - orchestrator's
     * {@code CrudToolExecutor} sets this from {@code StepPayloadService.isRunRestricted(runId)}.
     * On a write (create-row/update-row) it is what gets persisted as {@code data_sensitivity};
     * on a read (read-row) it is the "interim guard" - the read result is tagged RESTRICTED
     * whenever the CALLING context is restricted, even for rows whose own stored tag is NORMAL
     * (a restricted step can echo restricted values into a table without the row itself having
     * been written by a restricted step). Defaults to {@code false} so a caller that has not been
     * updated to send it degrades to the pre-fix behaviour, never to a startup/parse failure.
     */
    @JsonProperty("restricted")
    private boolean restricted;

    public abstract CrudOperation getOperation();

    public Long getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(Long dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public String getStepLabel() {
        return stepLabel;
    }

    public void setStepLabel(String stepLabel) {
        this.stepLabel = stepLabel;
    }

    public boolean isRestricted() {
        return restricted;
    }

    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }
}
