package com.apimarketplace.publication.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AcquireResultDto {
    private UUID workflowId;
    private String title;
    private String message;
    private Map<String, Object> workflow;

    public AcquireResultDto() {
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(UUID workflowId) {
        this.workflowId = workflowId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Map<String, Object> workflow) {
        this.workflow = workflow;
    }
}
