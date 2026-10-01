package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One acceptance of the Partner Program Terms (table {@code auth.partner_terms_acceptance},
 * V557): who accepted which version, when, and from where.
 *
 * <p>Append-only evidence of the contract: rows are written by
 * {@code PartnerTermsAcceptanceRepository#record} and never updated, so JPA only reads them.
 */
@Entity
@Table(name = "partner_terms_acceptance")
public class PartnerTermsAcceptance {

    /** Where the partner accepted the terms. */
    public enum Source { APPLICATION, DASHBOARD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "terms_version", nullable = false, length = 32)
    private String termsVersion;

    @Column(name = "terms_fingerprint", nullable = false, length = 80)
    private String termsFingerprint;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }
    public String getTermsFingerprint() { return termsFingerprint; }
    public void setTermsFingerprint(String termsFingerprint) { this.termsFingerprint = termsFingerprint; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
