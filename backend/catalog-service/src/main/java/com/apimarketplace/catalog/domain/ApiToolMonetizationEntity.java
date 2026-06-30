package com.apimarketplace.catalog.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entity representing API tool monetization
 */
@Table("api_tool_monetization")
public class ApiToolMonetizationEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("api_tool_id")
    private UUID apiToolId;

    @Column("monetization_type")
    private String monetizationType; // FREEMIUM, PAID

    @Column("plan_name")
    private String planName; // BASIC, PRO, ULTRA, MEGA

    @Column("rate_limit_requests")
    private Integer rateLimitRequests;

    @Column("rate_limit_period")
    private String rateLimitPeriod;

    @Column("free_requests")
    private Integer freeRequests;

    @Column("free_requests_type")
    private String freeRequestsType; // "per-user" or "global" for FREEMIUM

    @Column("mau_value")
    private Integer mauValue;

    @Column("price_per_mau")
    private BigDecimal pricePerMau;

    @Column("calls")
    private Integer calls; // Number of calls per MAU

    @Column("quota")
    private Integer quota;

    @Column("price")
    private BigDecimal price;

    @Column("overusage_cost")
    private BigDecimal overusageCost;

    @Column("hard_limit")
    private Boolean hardLimit;

    @Column("created_at")
    private Long createdAt;

    @Column("updated_at")
    private Long updatedAt;

    @Column("version")
    private String version = "1.0.0";

    // Enums
    public enum MonetizationType {
        FREEMIUM, PAID
    }

    public enum PlanName {
        BASIC, PRO, ULTRA, MEGA
    }

    // Constructors
    public ApiToolMonetizationEntity() {}

    public ApiToolMonetizationEntity(UUID id, UUID apiToolId, String monetizationType, String planName,
                                     Integer rateLimitRequests, String rateLimitPeriod, Integer freeRequests,
                                     Integer mauValue, BigDecimal pricePerMau, Integer calls, Integer quota, BigDecimal price,
                                     BigDecimal overusageCost, Boolean hardLimit, Long createdAt, Long updatedAt) {
        this.id = id;
        this.apiToolId = apiToolId;
        this.monetizationType = monetizationType;
        this.planName = planName;
        this.rateLimitRequests = rateLimitRequests;
        this.rateLimitPeriod = rateLimitPeriod;
        this.freeRequests = freeRequests;
        this.mauValue = mauValue;
        this.pricePerMau = pricePerMau;
        this.calls = calls;
        this.quota = quota;
        this.price = price;
        this.overusageCost = overusageCost;
        this.hardLimit = hardLimit;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getApiToolId() {
        return apiToolId;
    }

    public void setApiToolId(UUID apiToolId) {
        this.apiToolId = apiToolId;
    }

    public String getMonetizationType() {
        return monetizationType;
    }

    public void setMonetizationType(String monetizationType) {
        this.monetizationType = monetizationType;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public Integer getRateLimitRequests() {
        return rateLimitRequests;
    }

    public void setRateLimitRequests(Integer rateLimitRequests) {
        this.rateLimitRequests = rateLimitRequests;
    }

    public String getRateLimitPeriod() {
        return rateLimitPeriod;
    }

    public void setRateLimitPeriod(String rateLimitPeriod) {
        this.rateLimitPeriod = rateLimitPeriod;
    }

    public Integer getFreeRequests() {
        return freeRequests;
    }

    public void setFreeRequests(Integer freeRequests) {
        this.freeRequests = freeRequests;
    }

    public String getFreeRequestsType() {
        return freeRequestsType;
    }

    public void setFreeRequestsType(String freeRequestsType) {
        this.freeRequestsType = freeRequestsType;
    }

    public Integer getMauValue() {
        return mauValue;
    }

    public void setMauValue(Integer mauValue) {
        this.mauValue = mauValue;
    }

    public BigDecimal getPricePerMau() {
        return pricePerMau;
    }

    public void setPricePerMau(BigDecimal pricePerMau) {
        this.pricePerMau = pricePerMau;
    }

    public Integer getCalls() {
        return calls;
    }

    public void setCalls(Integer calls) {
        this.calls = calls;
    }

    public Integer getQuota() {
        return quota;
    }

    public void setQuota(Integer quota) {
        this.quota = quota;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getOverusageCost() {
        return overusageCost;
    }

    public void setOverusageCost(BigDecimal overusageCost) {
        this.overusageCost = overusageCost;
    }

    public Boolean getHardLimit() {
        return hardLimit;
    }

    public void setHardLimit(Boolean hardLimit) {
        this.hardLimit = hardLimit;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
