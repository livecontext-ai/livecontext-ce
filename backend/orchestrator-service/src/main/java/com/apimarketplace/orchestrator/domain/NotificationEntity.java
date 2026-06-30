package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Bell event row - one per failed pinned-run terminal event (V172 scope).
 *
 * <p>Idempotency key: {@code (tenant_id, category, source_id)}. The emitter
 * uses {@code INSERT ON CONFLICT DO NOTHING} so retry/recovery never produces
 * duplicates. {@code source_id} encodes {@code run_id} for V1; future
 * per-epoch reusable-run scope will use {@code run_id || ':' || epoch}.
 *
 * <p>Read-state is stored separately on {@link NotificationReadStateEntity}
 * keyed per user (single cursor).
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "notifications", schema = "orchestrator")
public class NotificationEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    /**
     * V220 - workspace tag for org-mode bell fan-out. Post-V261 (2026-05-19)
     * every user-scoped notification carries a non-null
     * {@code organization_id} (personal workspaces use the user's default
     * personal org); {@code NotificationService} filters strict
     * ({@code organization_id = :orgId}) in every workspace. The legacy
     * personal-strict (IS NULL + tenant_id) predicate was removed. User-level
     * rows (ORG_INVITATION_PENDING) may still carry NULL - those are
     * matched via the {@code USER_LEVEL_CATEGORY_SQL} sub-clause and remain
     * visible while the user browses any organization. Mirrors the
     * V215/V210/V218 contract for sibling tables.
     */
    @Column(name = "organization_id", length = 64)
    private String organizationId;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "subject_type", nullable = false, length = 20)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "source_id", nullable = false, length = 255)
    private String sourceId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "run_id_public", length = 255)
    private String runIdPublic;

    @Column(name = "plan_version")
    private Integer planVersion;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public NotificationEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
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
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getRunIdPublic() { return runIdPublic; }
    public void setRunIdPublic(String runIdPublic) { this.runIdPublic = runIdPublic; }
    public Integer getPlanVersion() { return planVersion; }
    public void setPlanVersion(Integer planVersion) { this.planVersion = planVersion; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
