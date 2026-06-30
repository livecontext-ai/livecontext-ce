package com.apimarketplace.auth.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Audit log row for an organization-scoped sensitive action. See PR-4b
 * Javadoc on {@code OrganizationAuditService} for the event vocabulary
 * and write contract.
 */
@Entity
@Table(name = "organization_audit_event")
public class OrganizationAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> eventData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OrganizationAuditEvent() {}

    public OrganizationAuditEvent(UUID orgId, Long actorUserId, String eventType, Map<String, Object> eventData) {
        this.orgId = orgId;
        this.actorUserId = actorUserId;
        this.eventType = eventType;
        this.eventData = eventData != null ? eventData : Map.of();
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getOrgId() { return orgId; }
    public Long getActorUserId() { return actorUserId; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getEventData() { return eventData; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public void setEventData(Map<String, Object> eventData) { this.eventData = eventData; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /**
     * Canonical event vocabulary. Anything outside this set is a programming
     * error. Surfaced as a separate inner class so callers refer to constants
     * instead of stringly-typed magic.
     */
    public static final class Type {
        public static final String MEMBER_INVITED       = "ORG_MEMBER_INVITED";
        public static final String INVITE_ACCEPTED      = "ORG_INVITE_ACCEPTED";
        public static final String INVITE_CANCELLED     = "ORG_INVITE_CANCELLED";
        public static final String INVITE_RATE_LIMITED  = "ORG_INVITE_RATE_LIMITED";
        public static final String MEMBER_REMOVED       = "ORG_MEMBER_REMOVED";
        public static final String MEMBER_LEFT          = "ORG_MEMBER_LEFT";
        public static final String ROLE_CHANGED         = "ORG_ROLE_CHANGED";
        public static final String OWNERSHIP_TRANSFERRED = "ORG_OWNERSHIP_TRANSFERRED";
        public static final String DELETED              = "ORG_DELETED";
        // Workspace delete lifecycle: soft-delete (DELETED) → restore within grace
        // (RESTORED) or hard-purge after grace (PURGED). PURGED survives the purge
        // (the audit log is a retained table).
        public static final String RESTORED             = "ORG_RESTORED";
        public static final String PURGED               = "ORG_PURGED";
        // PR11c - per-member quota cap CRUD audit events.
        public static final String QUOTA_CAP_SET        = "ORG_QUOTA_CAP_SET";
        public static final String QUOTA_CAP_REMOVED    = "ORG_QUOTA_CAP_REMOVED";
        public static final String QUOTA_CAP_EXCEEDED   = "ORG_QUOTA_CAP_EXCEEDED";
        public static final String SAML_SSO_CONFIGURED  = "ORG_SAML_SSO_CONFIGURED";
        public static final String SAML_SSO_DELETED     = "ORG_SAML_SSO_DELETED";
        public static final String SAML_SSO_MEMBER_JOINED = "ORG_SAML_SSO_MEMBER_JOINED";
        private Type() {}
    }
}
