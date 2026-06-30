package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Test configuration that provides a no-op StorageBreakdownService.
 * <p>
 * The real service uses PostgreSQL-specific ON CONFLICT SQL which H2 does not support.
 * This override makes all storage tracking methods no-ops in test profiles,
 * preventing UnexpectedRollbackException from propagating to the caller transaction.
 * <p>
 * Production code is NOT changed - ON CONFLICT SQL stays as-is.
 */
@Configuration
@Profile({"test", "e2e", "integration-test"})
public class TestStorageBreakdownConfig {

    private static final Logger log = LoggerFactory.getLogger(TestStorageBreakdownConfig.class);

    @Bean
    @Primary
    public StorageBreakdownService storageBreakdownService() {
        log.info("Using no-op StorageBreakdownService for H2 test profile");
        return new StorageBreakdownService(null, null) {
            @Override
            public void increment(String tenantId, String category, long deltaBytes, int deltaCount) {
                // No-op: H2 does not support ON CONFLICT
            }

            @Override
            public void increment(String tenantId, String category, long deltaBytes, int deltaCount, String organizationId) {
                // No-op
            }

            @Override
            public void trackSave(String tenantId, String category, long sizeBytes) {
                // No-op
            }

            @Override
            public void trackSave(String tenantId, String category, long sizeBytes, String organizationId) {
                // No-op
            }

            @Override
            public void trackDelete(String tenantId, String category, long sizeBytes) {
                // No-op
            }

            @Override
            public void trackDelete(String tenantId, String category, long sizeBytes, String organizationId) {
                // No-op
            }

            @Override
            public void trackSizeChange(String tenantId, String category, long deltaBytes) {
                // No-op
            }

            @Override
            public void trackSizeChange(String tenantId, String category, long deltaBytes, String organizationId) {
                // No-op
            }

            @Override
            public void setUsage(String tenantId, String category, long usedBytes, int itemCount) {
                // No-op
            }

            @Override
            public void setOrgUsage(String organizationId, String category, long usedBytes, int itemCount) {
                // No-op
            }

            @Override
            public long getTotalUsage(String tenantId) {
                return 0L;
            }

            @Override
            public java.util.List<com.apimarketplace.common.storage.domain.TenantStorageBreakdown> getBreakdown(String tenantId) {
                return java.util.List.of();
            }

            @Override
            public java.util.List<com.apimarketplace.common.storage.domain.OrgStorageBreakdown> getOrgBreakdown(String organizationId) {
                return java.util.List.of();
            }
        };
    }
}
