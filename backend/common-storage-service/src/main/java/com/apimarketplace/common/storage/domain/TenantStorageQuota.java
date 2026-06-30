package com.apimarketplace.common.storage.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entite pour gerer les quotas de stockage par tenant
 * Respecte les principes SOLID et les bonnes pratiques
 */
@Entity
@Table(name = "tenant_storage_quota", schema = "storage")
public class TenantStorageQuota {
    
    @Id
    @Column(name = "tenant_id")
    private String tenantId;
    
    @Column(name = "max_bytes", nullable = false)
    private Long maxBytes;
    
    @Column(name = "used_bytes", nullable = false)
    private Long usedBytes;
    
    @Column(name = "soft_limit_bytes", nullable = false)
    private Long softLimitBytes;
    
    @Column(name = "hard_limit_bytes", nullable = false)
    private Long hardLimitBytes;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    // Constructeurs
    public TenantStorageQuota() {}
    
    public TenantStorageQuota(String tenantId, Long maxBytes) {
        this.tenantId = tenantId;
        this.maxBytes = maxBytes;
        this.usedBytes = 0L;
        this.softLimitBytes = (long) (maxBytes * 0.8); // 80% par defaut
        this.hardLimitBytes = maxBytes;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    // Getters et Setters
    public String getTenantId() {
        return tenantId;
    }
    
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
    
    public Long getMaxBytes() {
        return maxBytes;
    }
    
    public void setMaxBytes(Long maxBytes) {
        this.maxBytes = maxBytes;
    }
    
    public Long getUsedBytes() {
        return usedBytes;
    }
    
    public void setUsedBytes(Long usedBytes) {
        this.usedBytes = usedBytes;
    }
    
    public Long getSoftLimitBytes() {
        return softLimitBytes;
    }
    
    public void setSoftLimitBytes(Long softLimitBytes) {
        this.softLimitBytes = softLimitBytes;
    }
    
    public Long getHardLimitBytes() {
        return hardLimitBytes;
    }
    
    public void setHardLimitBytes(Long hardLimitBytes) {
        this.hardLimitBytes = hardLimitBytes;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    // Methodes utilitaires
    public Long getAvailableBytes() {
        return Math.max(0, maxBytes - usedBytes);
    }
    
    public double getUsagePercentage() {
        return maxBytes > 0 ? (double) usedBytes / maxBytes * 100 : 0;
    }
    
    public boolean isSoftLimitReached() {
        return usedBytes >= softLimitBytes;
    }
    
    public boolean isHardLimitReached() {
        return usedBytes >= hardLimitBytes;
    }
    
    public boolean canStore(Long additionalBytes) {
        long used = usedBytes != null ? usedBytes : 0L;
        long hardLimit = hardLimitBytes != null ? hardLimitBytes : 0L;
        long additional = additionalBytes != null ? Math.max(0L, additionalBytes) : 0L;
        return used <= hardLimit && additional <= hardLimit - used;
    }
    
    public QuotaStatus getQuotaStatus() {
        if (isHardLimitReached()) {
            return QuotaStatus.HARD_LIMIT_REACHED;
        } else if (isSoftLimitReached()) {
            return QuotaStatus.SOFT_LIMIT_REACHED;
        } else {
            return QuotaStatus.OK;
        }
    }
}
