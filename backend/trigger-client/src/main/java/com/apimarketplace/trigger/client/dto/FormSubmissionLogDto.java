package com.apimarketplace.trigger.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FormSubmissionLogDto {

    private Long id;
    private Map<String, Object> submissionData;
    private String responseStatus;
    private Integer workflowsTriggered;
    private String ipAddress;
    private Instant submittedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Map<String, Object> getSubmissionData() { return submissionData; }
    public void setSubmissionData(Map<String, Object> submissionData) { this.submissionData = submissionData; }

    public String getResponseStatus() { return responseStatus; }
    public void setResponseStatus(String responseStatus) { this.responseStatus = responseStatus; }

    public Integer getWorkflowsTriggered() { return workflowsTriggered; }
    public void setWorkflowsTriggered(Integer workflowsTriggered) { this.workflowsTriggered = workflowsTriggered; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
}
