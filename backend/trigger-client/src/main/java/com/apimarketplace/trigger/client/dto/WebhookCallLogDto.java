package com.apimarketplace.trigger.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for webhook call log data transferred between services.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookCallLogDto {

    private Long id;
    private UUID webhookId;
    private String requestMethod;
    private Map<String, Object> requestPayload;
    private String responseStatus;
    private Integer workflowsTriggered;
    private Instant calledAt;

    public WebhookCallLogDto() {}

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getWebhookId() { return webhookId; }
    public void setWebhookId(UUID webhookId) { this.webhookId = webhookId; }

    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }

    public Map<String, Object> getRequestPayload() { return requestPayload; }
    public void setRequestPayload(Map<String, Object> requestPayload) { this.requestPayload = requestPayload; }

    public String getResponseStatus() { return responseStatus; }
    public void setResponseStatus(String responseStatus) { this.responseStatus = responseStatus; }

    public Integer getWorkflowsTriggered() { return workflowsTriggered; }
    public void setWorkflowsTriggered(Integer workflowsTriggered) { this.workflowsTriggered = workflowsTriggered; }

    public Instant getCalledAt() { return calledAt; }
    public void setCalledAt(Instant calledAt) { this.calledAt = calledAt; }
}
