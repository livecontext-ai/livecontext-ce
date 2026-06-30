package com.apimarketplace.orchestrator.controllers.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowExecutionResponse {

    private final String runId;
    private final String workflowId;
    private final String status;
    private final String message;
    private final String tenantId;
    private final Instant startTime;
    private final int totalSteps;
    private final boolean success;
    private final Instant timestamp;

    public WorkflowExecutionResponse(String runId,
                                     String workflowId,
                                     String status,
                                     String message,
                                     String tenantId,
                                     Instant startTime,
                                     int totalSteps,
                                     boolean success,
                                     Instant timestamp) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.status = status;
        this.message = message;
        this.tenantId = tenantId;
        this.startTime = startTime;
        this.totalSteps = totalSteps;
        this.success = success;
        this.timestamp = timestamp;
    }

    public String getRunId() {
        return runId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    @JsonIgnore
    public String getTenantId() {
        return tenantId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public boolean isSuccess() {
        return success;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
