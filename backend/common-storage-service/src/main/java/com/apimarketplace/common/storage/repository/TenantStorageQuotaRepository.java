package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.TenantStorageQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository pour les quotas de stockage par tenant
 * Respecte les principes SOLID et les bonnes pratiques
 */
@Repository
public interface TenantStorageQuotaRepository extends JpaRepository<TenantStorageQuota, String> {
    
    /**
     * Trouve le quota d'un tenant
     */
    Optional<TenantStorageQuota> findByTenantId(String tenantId);
    
    /**
     * Met a jour l'usage d'un tenant
     */
    @Modifying
    @Query("UPDATE TenantStorageQuota t SET t.usedBytes = :usedBytes, t.updatedAt = :updatedAt WHERE t.tenantId = :tenantId")
    int updateUsedBytes(@Param("tenantId") String tenantId, @Param("usedBytes") Long usedBytes, @Param("updatedAt") Instant updatedAt);
    
    /**
     * Met a jour les limites d'un tenant
     */
    @Modifying
    @Query("UPDATE TenantStorageQuota t SET t.maxBytes = :maxBytes, t.softLimitBytes = :softLimitBytes, " +
           "t.hardLimitBytes = :hardLimitBytes, t.updatedAt = :updatedAt WHERE t.tenantId = :tenantId")
    int updateLimits(@Param("tenantId") String tenantId, 
                     @Param("maxBytes") Long maxBytes, 
                     @Param("softLimitBytes") Long softLimitBytes, 
                     @Param("hardLimitBytes") Long hardLimitBytes, 
                     @Param("updatedAt") Instant updatedAt);
}
