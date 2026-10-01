package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One application to the partner program (table {@code auth.partner_application}, V553).
 *
 * <p>{@code PENDING} until an admin decides. {@code APPROVED} carries the PARTNER code the
 * approval created ({@link #rewardCodeId}); {@code REJECTED} may carry a note addressed to
 * the applicant. Rows are never deleted: a rejected applicant applies again with a new row.
 *
 * <p>{@link #version} makes a decision optimistic: of two admins deciding the same application
 * at once, the second fails with an optimistic-lock error instead of overwriting the first.
 */
@Entity
@Table(name = "partner_application")
public class PartnerApplication {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "company_name", nullable = false, length = 120)
    private String companyName;

    @Column(length = 255)
    private String website;

    @Column(length = 500)
    private String audience;

    @Column(length = 2000)
    private String message;

    @Column(name = "reward_code_id")
    private Long rewardCodeId;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    /** Optimistic lock: a second, concurrent decision fails instead of overwriting the first. */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getRewardCodeId() { return rewardCodeId; }
    public void setRewardCodeId(Long rewardCodeId) { this.rewardCodeId = rewardCodeId; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
