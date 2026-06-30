package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Per-org storage quota repository. Mirror of
 * {@link TenantStorageQuotaRepository} for the org dimension.
 */
@Repository
public interface OrganizationStorageQuotaRepository
        extends JpaRepository<OrganizationStorageQuota, String> {

    Optional<OrganizationStorageQuota> findByOrganizationId(String organizationId);

    @Modifying
    @Query("UPDATE OrganizationStorageQuota q SET q.usedBytes = :usedBytes, "
         + "q.updatedAt = :updatedAt WHERE q.organizationId = :orgId")
    int updateUsedBytes(@Param("orgId") String organizationId,
                        @Param("usedBytes") Long usedBytes,
                        @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Query("UPDATE OrganizationStorageQuota q SET q.maxBytes = :maxBytes, "
         + "q.softLimitBytes = :softLimitBytes, q.hardLimitBytes = :hardLimitBytes, "
         + "q.updatedAt = :updatedAt WHERE q.organizationId = :orgId")
    int updateLimits(@Param("orgId") String organizationId,
                     @Param("maxBytes") Long maxBytes,
                     @Param("softLimitBytes") Long softLimitBytes,
                     @Param("hardLimitBytes") Long hardLimitBytes,
                     @Param("updatedAt") Instant updatedAt);
}
