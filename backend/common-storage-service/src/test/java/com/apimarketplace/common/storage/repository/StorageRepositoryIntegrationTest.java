package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.domain.TenantStorageQuota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository integration tests using H2 in-memory database.
 * Tests JPQL queries that are compatible with H2.
 * Native PostgreSQL queries (jsonb_extract_path, getSkeletonOnly, etc.)
 * are excluded as they require a real PostgreSQL instance.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Repository Integration Tests")
class StorageRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private StorageRepository storageRepository;

    @Autowired
    private TenantStorageQuotaRepository quotaRepository;

    private static final String TENANT_1 = "tenant-repo-1";
    private static final String TENANT_2 = "tenant-repo-2";

    @BeforeEach
    void setUp() {
        // Clean up before each test
        storageRepository.deleteAll();
        quotaRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();
    }

    // ========== StorageRepository Tests ==========

    @Nested
    @DisplayName("StorageRepository - Basic CRUD")
    class StorageRepositoryBasicTests {

        @Test
        @DisplayName("should save a storage entity and generate UUID")
        void shouldSaveAndGenerateUuid() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("name", "test"));

            StorageEntity saved = storageRepository.save(entity);
            entityManager.flush();

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getTenantId()).isEqualTo(TENANT_1);
            assertThat(saved.getStatus()).isEqualTo(StorageStatus.ACTIVE);
        }

        @Test
        @DisplayName("should find entity by ID and tenant ID")
        void shouldFindByIdAndTenantId() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("key", "value"));
            StorageEntity saved = storageRepository.save(entity);
            entityManager.flush();
            entityManager.clear();

            Optional<StorageEntity> found = storageRepository.findByIdAndTenantId(saved.getId(), TENANT_1);

            assertThat(found).isPresent();
            assertThat(found.get().getTenantId()).isEqualTo(TENANT_1);
        }

        @Test
        @DisplayName("should not find entity with wrong tenant ID")
        void shouldNotFindWithWrongTenantId() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("key", "value"));
            StorageEntity saved = storageRepository.save(entity);
            entityManager.flush();
            entityManager.clear();

            Optional<StorageEntity> found = storageRepository.findByIdAndTenantId(saved.getId(), TENANT_2);

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should not find deleted entity by ID and tenant ID")
        void shouldNotFindDeletedEntity() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("key", "value"));
            entity.setStatus(StorageStatus.DELETED);
            StorageEntity saved = storageRepository.save(entity);
            entityManager.flush();
            entityManager.clear();

            Optional<StorageEntity> found = storageRepository.findByIdAndTenantId(saved.getId(), TENANT_1);

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("StorageRepository - findByTenantId")
    class FindByTenantIdTests {

        @Test
        @DisplayName("should return all active entities for a tenant")
        void shouldReturnAllActiveEntitiesForTenant() {
            storageRepository.save(createJsonEntity(TENANT_1, Map.of("a", 1)));
            storageRepository.save(createJsonEntity(TENANT_1, Map.of("b", 2)));
            storageRepository.save(createJsonEntity(TENANT_1, Map.of("c", 3)));
            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> results = storageRepository.findByTenantId(TENANT_1);

            assertThat(results).hasSize(3);
        }

        @Test
        @DisplayName("should not include deleted entities in list")
        void shouldNotIncludeDeletedEntities() {
            storageRepository.save(createJsonEntity(TENANT_1, Map.of("active", true)));

            StorageEntity deleted = createJsonEntity(TENANT_1, Map.of("deleted", true));
            deleted.setStatus(StorageStatus.DELETED);
            storageRepository.save(deleted);

            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> results = storageRepository.findByTenantId(TENANT_1);

            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("should isolate tenants - return only entities for specified tenant")
        void shouldIsolateTenants() {
            storageRepository.save(createJsonEntity(TENANT_1, Map.of("t1", "data")));
            storageRepository.save(createJsonEntity(TENANT_1, Map.of("t1b", "data")));
            storageRepository.save(createJsonEntity(TENANT_2, Map.of("t2", "data")));
            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> tenant1Results = storageRepository.findByTenantId(TENANT_1);
            List<StorageEntity> tenant2Results = storageRepository.findByTenantId(TENANT_2);

            assertThat(tenant1Results).hasSize(2);
            assertThat(tenant2Results).hasSize(1);
        }

        @Test
        @DisplayName("should return entities ordered by createdAt descending")
        void shouldReturnOrderedByCreatedAtDesc() throws InterruptedException {
            StorageEntity first = createJsonEntity(TENANT_1, Map.of("order", 1));
            first.setCreatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
            storageRepository.save(first);

            StorageEntity second = createJsonEntity(TENANT_1, Map.of("order", 2));
            second.setCreatedAt(Instant.now().minus(1, ChronoUnit.HOURS));
            storageRepository.save(second);

            StorageEntity third = createJsonEntity(TENANT_1, Map.of("order", 3));
            third.setCreatedAt(Instant.now());
            storageRepository.save(third);

            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> results = storageRepository.findByTenantId(TENANT_1);

            assertThat(results).hasSize(3);
            // Most recent first
            assertThat(results.get(0).getCreatedAt())
                .isAfterOrEqualTo(results.get(1).getCreatedAt());
            assertThat(results.get(1).getCreatedAt())
                .isAfterOrEqualTo(results.get(2).getCreatedAt());
        }
    }

    @Nested
    @DisplayName("StorageRepository - calculateTenantUsage")
    class CalculateTenantUsageTests {

        @Test
        @DisplayName("should calculate total usage for a tenant")
        void shouldCalculateTotalUsage() {
            StorageEntity e1 = createJsonEntity(TENANT_1, Map.of("a", 1));
            e1.setSizeBytes(1000);
            storageRepository.save(e1);

            StorageEntity e2 = createJsonEntity(TENANT_1, Map.of("b", 2));
            e2.setSizeBytes(2000);
            storageRepository.save(e2);

            entityManager.flush();
            entityManager.clear();

            Long usage = storageRepository.calculateTenantUsage(TENANT_1);

            assertThat(usage).isEqualTo(3000L);
        }

        @Test
        @DisplayName("should return 0 for tenant with no entities")
        void shouldReturnZeroForEmptyTenant() {
            Long usage = storageRepository.calculateTenantUsage("nonexistent-tenant");

            assertThat(usage).isEqualTo(0L);
        }

        @Test
        @DisplayName("should exclude deleted entities from usage calculation")
        void shouldExcludeDeletedFromUsage() {
            StorageEntity active = createJsonEntity(TENANT_1, Map.of("a", 1));
            active.setSizeBytes(5000);
            storageRepository.save(active);

            StorageEntity deleted = createJsonEntity(TENANT_1, Map.of("b", 2));
            deleted.setSizeBytes(3000);
            deleted.setStatus(StorageStatus.DELETED);
            storageRepository.save(deleted);

            entityManager.flush();
            entityManager.clear();

            Long usage = storageRepository.calculateTenantUsage(TENANT_1);

            assertThat(usage).isEqualTo(5000L);
        }

        @Test
        @DisplayName("should calculate usage independently per tenant")
        void shouldCalculateUsagePerTenant() {
            StorageEntity e1 = createJsonEntity(TENANT_1, Map.of("a", 1));
            e1.setSizeBytes(1000);
            storageRepository.save(e1);

            StorageEntity e2 = createJsonEntity(TENANT_2, Map.of("b", 2));
            e2.setSizeBytes(5000);
            storageRepository.save(e2);

            entityManager.flush();
            entityManager.clear();

            assertThat(storageRepository.calculateTenantUsage(TENANT_1)).isEqualTo(1000L);
            assertThat(storageRepository.calculateTenantUsage(TENANT_2)).isEqualTo(5000L);
        }
    }

    @Nested
    @DisplayName("StorageRepository - findExpiredStorages")
    class FindExpiredStoragesTests {

        @Test
        @DisplayName("should find entities past their expiration time")
        void shouldFindExpiredEntities() {
            StorageEntity expired = createJsonEntity(TENANT_1, Map.of("expired", true));
            expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            storageRepository.save(expired);

            StorageEntity notExpired = createJsonEntity(TENANT_1, Map.of("valid", true));
            notExpired.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
            storageRepository.save(notExpired);

            StorageEntity noExpiry = createJsonEntity(TENANT_1, Map.of("noexpiry", true));
            storageRepository.save(noExpiry);

            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> expired_list = storageRepository.findExpiredStorages(Instant.now());

            assertThat(expired_list).hasSize(1);
        }

        @Test
        @DisplayName("should not include already deleted expired entities")
        void shouldNotIncludeDeletedExpiredEntities() {
            StorageEntity expiredDeleted = createJsonEntity(TENANT_1, Map.of("exp", true));
            expiredDeleted.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            expiredDeleted.setStatus(StorageStatus.DELETED);
            storageRepository.save(expiredDeleted);

            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> results = storageRepository.findExpiredStorages(Instant.now());

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("StorageRepository - updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("should update entity status to DELETED")
        void shouldUpdateStatusToDeleted() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("key", "value"));
            StorageEntity saved = storageRepository.save(entity);
            entityManager.flush();

            int updated = storageRepository.updateStatus(saved.getId(), StorageStatus.DELETED);

            assertThat(updated).isEqualTo(1);

            entityManager.clear();
            StorageEntity found = entityManager.find(StorageEntity.class, saved.getId());
            assertThat(found.getStatus()).isEqualTo(StorageStatus.DELETED);
        }

        @Test
        @DisplayName("should return 0 when updating non-existent entity")
        void shouldReturnZeroForNonExistent() {
            int updated = storageRepository.updateStatus(UUID.randomUUID(), StorageStatus.DELETED);

            assertThat(updated).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("StorageRepository - updateAccessedAt")
    class UpdateAccessedAtTests {

        @Test
        @DisplayName("should update accessed_at timestamp")
        void shouldUpdateAccessedAt() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("key", "value"));
            StorageEntity saved = storageRepository.save(entity);
            entityManager.flush();

            // Truncate to microseconds to match H2 timestamp precision
            Instant newTime = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
            int updated = storageRepository.updateAccessedAt(saved.getId(), newTime);

            assertThat(updated).isEqualTo(1);

            entityManager.clear();
            StorageEntity found = entityManager.find(StorageEntity.class, saved.getId());
            assertThat(found.getAccessedAt()).isEqualTo(newTime);
        }
    }

    @Nested
    @DisplayName("StorageRepository - softDeleteByTenantId")
    class SoftDeleteByTenantIdTests {

        @Test
        @DisplayName("should soft delete all active entities for a tenant")
        void shouldSoftDeleteAllForTenant() {
            storageRepository.save(createJsonEntity(TENANT_1, Map.of("a", 1)));
            storageRepository.save(createJsonEntity(TENANT_1, Map.of("b", 2)));
            storageRepository.save(createJsonEntity(TENANT_2, Map.of("c", 3)));
            entityManager.flush();

            int deleted = storageRepository.softDeleteByTenantId(TENANT_1);

            assertThat(deleted).isEqualTo(2);

            entityManager.clear();
            assertThat(storageRepository.findByTenantId(TENANT_1)).isEmpty();
            assertThat(storageRepository.findByTenantId(TENANT_2)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("StorageRepository - findByTenantIdAndContentType")
    class FindByContentTypeTests {

        @Test
        @DisplayName("should find entities by content type")
        void shouldFindByContentType() {
            StorageEntity json1 = createJsonEntity(TENANT_1, Map.of("a", 1));
            json1.setContentType("application/json");
            storageRepository.save(json1);

            StorageEntity xml = createJsonEntity(TENANT_1, Map.of("b", 2));
            xml.setContentType("application/xml");
            storageRepository.save(xml);

            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> jsonResults = storageRepository.findByTenantIdAndContentType(
                TENANT_1, "application/json");

            assertThat(jsonResults).hasSize(1);
            assertThat(jsonResults.get(0).getContentType()).isEqualTo("application/json");
        }
    }

    @Nested
    @DisplayName("StorageRepository - findByTenantIdAndStorageType")
    class FindByStorageTypeTests {

        @Test
        @DisplayName("should find entities by storage type")
        void shouldFindByStorageType() {
            StorageEntity json = createJsonEntity(TENANT_1, Map.of("a", 1));
            json.setStorageType("JSON");
            storageRepository.save(json);

            StorageEntity binary = createJsonEntity(TENANT_1, Map.of("b", 2));
            binary.setStorageType("BINARY");
            storageRepository.save(binary);

            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> jsonResults = storageRepository.findByTenantIdAndStorageType(TENANT_1, "JSON");
            List<StorageEntity> binaryResults = storageRepository.findByTenantIdAndStorageType(TENANT_1, "BINARY");

            assertThat(jsonResults).hasSize(1);
            assertThat(binaryResults).hasSize(1);
        }
    }

    @Nested
    @DisplayName("StorageRepository - Run Context Queries")
    class RunContextQueryTests {

        @Test
        @DisplayName("should find entities by runId and epoch")
        void shouldFindByRunIdAndEpoch() {
            StorageEntity e1 = createJsonEntity(TENANT_1, Map.of("step", "a"));
            e1.setRunId("run-100");
            e1.setStepKey("mcp:step_a");
            e1.setEpoch(0);
            storageRepository.save(e1);

            StorageEntity e2 = createJsonEntity(TENANT_1, Map.of("step", "b"));
            e2.setRunId("run-100");
            e2.setStepKey("mcp:step_b");
            e2.setEpoch(0);
            storageRepository.save(e2);

            StorageEntity e3 = createJsonEntity(TENANT_1, Map.of("step", "c"));
            e3.setRunId("run-100");
            e3.setStepKey("mcp:step_c");
            e3.setEpoch(1); // Different epoch
            storageRepository.save(e3);

            entityManager.flush();
            entityManager.clear();

            List<StorageEntity> epoch0 = storageRepository.findByRunIdAndEpoch("run-100", 0, TENANT_1);
            List<StorageEntity> epoch1 = storageRepository.findByRunIdAndEpoch("run-100", 1, TENANT_1);

            assertThat(epoch0).hasSize(2);
            assertThat(epoch1).hasSize(1);
        }

        @Test
        @DisplayName("should find entity by runId, stepKey, and epoch")
        void shouldFindByRunIdStepKeyAndEpoch() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("result", "ok"));
            entity.setRunId("run-200");
            entity.setStepKey("mcp:enricher");
            entity.setEpoch(0);
            storageRepository.save(entity);
            entityManager.flush();
            entityManager.clear();

            Optional<StorageEntity> found = storageRepository.findByRunIdAndStepKeyAndEpoch(
                "run-200", "mcp:enricher", 0, TENANT_1);

            assertThat(found).isPresent();
            assertThat(found.get().getStepKey()).isEqualTo("mcp:enricher");
        }

        @Test
        @DisplayName("should find entity by runId, stepKey, itemIndex, and epoch")
        void shouldFindByRunIdStepKeyItemIndexAndEpoch() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("item", "data"));
            entity.setRunId("run-300");
            entity.setStepKey("mcp:process");
            entity.setItemIndex(5);
            entity.setEpoch(0);
            storageRepository.save(entity);
            entityManager.flush();
            entityManager.clear();

            Optional<StorageEntity> found = storageRepository.findByRunIdAndStepKeyAndItemIndexAndEpoch(
                "run-300", "mcp:process", 5, 0, TENANT_1);

            assertThat(found).isPresent();
            assertThat(found.get().getItemIndex()).isEqualTo(5);
        }

        @Test
        @DisplayName("should not find entity with wrong epoch")
        void shouldNotFindWithWrongEpoch() {
            StorageEntity entity = createJsonEntity(TENANT_1, Map.of("result", "ok"));
            entity.setRunId("run-400");
            entity.setStepKey("mcp:step");
            entity.setEpoch(0);
            storageRepository.save(entity);
            entityManager.flush();
            entityManager.clear();

            Optional<StorageEntity> found = storageRepository.findByRunIdAndStepKeyAndEpoch(
                "run-400", "mcp:step", 1, TENANT_1);

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("StorageRepository - findStoragesNotAccessedSince")
    class FindNotAccessedSinceTests {

        @Test
        @DisplayName("should find entities not accessed since a given date")
        void shouldFindNotAccessedSince() {
            StorageEntity old = createJsonEntity(TENANT_1, Map.of("old", true));
            old.setAccessedAt(Instant.now().minus(30, ChronoUnit.DAYS));
            storageRepository.save(old);

            StorageEntity recent = createJsonEntity(TENANT_1, Map.of("recent", true));
            recent.setAccessedAt(Instant.now());
            storageRepository.save(recent);

            entityManager.flush();
            entityManager.clear();

            Instant threshold = Instant.now().minus(7, ChronoUnit.DAYS);
            List<StorageEntity> results = storageRepository.findStoragesNotAccessedSince(threshold);

            assertThat(results).hasSize(1);
        }
    }

    // ========== TenantStorageQuotaRepository Tests ==========

    @Nested
    @DisplayName("TenantStorageQuotaRepository")
    class TenantStorageQuotaRepositoryTests {

        @Test
        @DisplayName("should save and retrieve quota by tenant ID")
        void shouldSaveAndRetrieveQuota() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_1, 1_073_741_824L);
            quotaRepository.save(quota);
            entityManager.flush();
            entityManager.clear();

            Optional<TenantStorageQuota> found = quotaRepository.findByTenantId(TENANT_1);

            assertThat(found).isPresent();
            assertThat(found.get().getMaxBytes()).isEqualTo(1_073_741_824L);
            assertThat(found.get().getUsedBytes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return empty for unknown tenant")
        void shouldReturnEmptyForUnknownTenant() {
            Optional<TenantStorageQuota> found = quotaRepository.findByTenantId("unknown-tenant");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should update used bytes via JPQL")
        void shouldUpdateUsedBytes() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_1, 1_000_000L);
            quotaRepository.save(quota);
            entityManager.flush();

            Instant now = Instant.now();
            int updated = quotaRepository.updateUsedBytes(TENANT_1, 500_000L, now);

            assertThat(updated).isEqualTo(1);

            entityManager.clear();
            TenantStorageQuota found = entityManager.find(TenantStorageQuota.class, TENANT_1);
            assertThat(found.getUsedBytes()).isEqualTo(500_000L);
        }

        @Test
        @DisplayName("should update limits via JPQL")
        void shouldUpdateLimits() {
            TenantStorageQuota quota = new TenantStorageQuota(TENANT_1, 1_000_000L);
            quotaRepository.save(quota);
            entityManager.flush();

            Instant now = Instant.now();
            int updated = quotaRepository.updateLimits(TENANT_1, 5_000_000L, 4_000_000L, 5_000_000L, now);

            assertThat(updated).isEqualTo(1);

            entityManager.clear();
            TenantStorageQuota found = entityManager.find(TenantStorageQuota.class, TENANT_1);
            assertThat(found.getMaxBytes()).isEqualTo(5_000_000L);
            assertThat(found.getSoftLimitBytes()).isEqualTo(4_000_000L);
            assertThat(found.getHardLimitBytes()).isEqualTo(5_000_000L);
        }

        @Test
        @DisplayName("should maintain separate quotas per tenant")
        void shouldMaintainSeparateQuotas() {
            quotaRepository.save(new TenantStorageQuota(TENANT_1, 1_000_000L));
            quotaRepository.save(new TenantStorageQuota(TENANT_2, 5_000_000L));
            entityManager.flush();
            entityManager.clear();

            Optional<TenantStorageQuota> q1 = quotaRepository.findByTenantId(TENANT_1);
            Optional<TenantStorageQuota> q2 = quotaRepository.findByTenantId(TENANT_2);

            assertThat(q1).isPresent();
            assertThat(q2).isPresent();
            assertThat(q1.get().getMaxBytes()).isEqualTo(1_000_000L);
            assertThat(q2.get().getMaxBytes()).isEqualTo(5_000_000L);
        }
    }

    // ========== Helper Methods ==========

    private StorageEntity createJsonEntity(String tenantId, Map<String, Object> data) {
        StorageEntity entity = new StorageEntity(
            tenantId,
            "application/json",
            data,
            100,    // sizeBytes placeholder
            "sha256-test-checksum",
            null    // no expiration
        );
        // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
        entity.setOrganizationId(tenantId);
        return entity;
    }
}
