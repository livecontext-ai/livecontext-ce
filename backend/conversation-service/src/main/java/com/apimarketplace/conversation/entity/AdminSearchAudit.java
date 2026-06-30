package com.apimarketplace.conversation.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Audit row written every time an admin calls
 * {@code /api/admin/conversations/messages/search}. Used to answer
 * "who searched what on whom?" during incident response.
 */
@Entity
@Table(name = "admin_search_audit", schema = "conversation")
public class AdminSearchAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_user_id", nullable = false)
    private String adminUserId;

    @Column(name = "target_user_id", nullable = false)
    private String targetUserId;

    @Column(name = "query", nullable = false, columnDefinition = "TEXT")
    private String query;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filters", columnDefinition = "jsonb")
    private Map<String, Object> filters;

    @Column(name = "result_count")
    private Integer resultCount;

    /**
     * Typed as {@link Instant} (UTC moment) to match the underlying
     * {@code TIMESTAMPTZ} column. Using {@link java.time.LocalDateTime}
     * here would silently drift by the server's TZ offset.
     */
    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public AdminSearchAudit() {}

    public AdminSearchAudit(String adminUserId, String targetUserId, String query,
                            Map<String, Object> filters, Integer resultCount) {
        this.adminUserId = adminUserId;
        this.targetUserId = targetUserId;
        this.query = query;
        this.filters = filters;
        this.resultCount = resultCount;
    }

    public Long getId() { return id; }
    public String getAdminUserId() { return adminUserId; }
    public String getTargetUserId() { return targetUserId; }
    public String getQuery() { return query; }
    public Map<String, Object> getFilters() { return filters; }
    public Integer getResultCount() { return resultCount; }
    public Instant getOccurredAt() { return occurredAt; }

    public void setResultCount(Integer resultCount) { this.resultCount = resultCount; }
}
