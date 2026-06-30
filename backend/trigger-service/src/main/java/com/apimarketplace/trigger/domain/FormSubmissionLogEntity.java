package com.apimarketplace.trigger.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "form_submission_logs", schema = "trigger")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class FormSubmissionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_endpoint_id", nullable = false)
    private UUID formEndpointId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submission_data", columnDefinition = "jsonb")
    private Map<String, Object> submissionData;

    @Column(name = "response_status", nullable = false, length = 30)
    private String responseStatus;

    @Column(name = "workflows_triggered")
    private Integer workflowsTriggered = 0;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    public FormSubmissionLogEntity() {
        this.submittedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getFormEndpointId() { return formEndpointId; }
    public void setFormEndpointId(UUID formEndpointId) { this.formEndpointId = formEndpointId; }
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
