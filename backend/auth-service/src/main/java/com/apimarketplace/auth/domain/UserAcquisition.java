package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * First-touch acquisition attribution of one user (V527): where they came from the first
 * time the frontend saw them. Write-once: the row is inserted with
 * {@code ON CONFLICT DO NOTHING} by {@code UserAcquisitionRepository#insertIfAbsent} and is
 * never updated. Deleted with the user (FK {@code ON DELETE CASCADE}).
 */
@Entity
@Table(name = "user_acquisition", schema = "auth")
public class UserAcquisition {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "utm_source", length = 255)
    private String utmSource;

    @Column(name = "utm_medium", length = 255)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 255)
    private String utmCampaign;

    @Column(name = "utm_content", length = 255)
    private String utmContent;

    @Column(name = "utm_term", length = 255)
    private String utmTerm;

    @Column(name = "referrer", length = 1024)
    private String referrer;

    @Column(name = "landing_path", length = 1024)
    private String landingPath;

    @Column(name = "first_seen_at")
    private Instant firstSeenAt;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt = Instant.now();

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUtmSource() { return utmSource; }
    public void setUtmSource(String utmSource) { this.utmSource = utmSource; }
    public String getUtmMedium() { return utmMedium; }
    public void setUtmMedium(String utmMedium) { this.utmMedium = utmMedium; }
    public String getUtmCampaign() { return utmCampaign; }
    public void setUtmCampaign(String utmCampaign) { this.utmCampaign = utmCampaign; }
    public String getUtmContent() { return utmContent; }
    public void setUtmContent(String utmContent) { this.utmContent = utmContent; }
    public String getUtmTerm() { return utmTerm; }
    public void setUtmTerm(String utmTerm) { this.utmTerm = utmTerm; }
    public String getReferrer() { return referrer; }
    public void setReferrer(String referrer) { this.referrer = referrer; }
    public String getLandingPath() { return landingPath; }
    public void setLandingPath(String landingPath) { this.landingPath = landingPath; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
}
