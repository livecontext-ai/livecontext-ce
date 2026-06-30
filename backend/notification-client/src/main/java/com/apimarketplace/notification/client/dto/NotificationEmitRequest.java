package com.apimarketplace.notification.client.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for {@code POST /api/internal/notifications/emit}.
 *
 * <p>All fields except {@code runId}, {@code runIdPublic}, {@code planVersion}
 * are required. {@code payload} MUST contain a {@code status} key (lowercase
 * string discriminator, V174 schema contract). The endpoint validates this
 * and returns {@code 400} on missing keys.
 *
 * <p>Idempotency contract: {@code (tenantId, category, sourceId)} is the V172
 * unique index. Producers MUST derive {@code sourceId} deterministically per
 * logical event (e.g. {@code credentialId + ":" + expiry_epoch_day} for
 * {@code CRED_EXPIRED}) so retries / multi-replica races collapse to one row.
 *
 * <p>Subject-type allow-list is enforced by V176 {@code chk_notif_subject_type_v1}:
 * {@code WORKFLOW}, {@code APPLICATION}, {@code AGENT_TASK}, {@code CREDENTIAL},
 * {@code TRIGGER}, {@code ORG_INVITATION}.
 */
public class NotificationEmitRequest {

    private String tenantId;
    private String organizationId;
    private String category;
    private String severity;
    private String subjectType;
    private UUID subjectId;
    private String sourceId;
    private Map<String, Object> payload;
    private Instant occurredAt;
    private UUID runId;
    private String runIdPublic;
    private Integer planVersion;

    public NotificationEmitRequest() {}

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    /** Post-V261 - recipient's workspace org (NOT NULL on persisted row).
     * For ORG_INVITATION_PENDING the emitter sets the invitee's default
     * personal org so the notification surfaces in their personal sidebar
     * regardless of which workspace the inviter was in. */
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }

    public String getRunIdPublic() { return runIdPublic; }
    public void setRunIdPublic(String runIdPublic) { this.runIdPublic = runIdPublic; }

    public Integer getPlanVersion() { return planVersion; }
    public void setPlanVersion(Integer planVersion) { this.planVersion = planVersion; }
}
