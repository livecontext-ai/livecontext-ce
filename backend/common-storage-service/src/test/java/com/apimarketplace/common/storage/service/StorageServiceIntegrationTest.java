package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for StorageService.
 * Uses Mockito mocks for repositories since native PostgreSQL queries
 * (jsonb_extract_path, getSkeletonOnly, etc.) are not compatible with H2.
 */
@DisplayName("StorageService Integration Tests")
@ExtendWith(MockitoExtension.class)
class StorageServiceIntegrationTest {

    private static final String TENANT_ID = "tenant-integration-1";
    private static final String OTHER_TENANT_ID = "tenant-integration-2";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_TEXT = "text/plain";

    @Mock
    private StorageRepository storageRepository;

    @Mock
    private QuotaOperations quotaService;

    @Mock
    private MappingOperations mappingService;

    @Mock
    private StorageBreakdownService breakdownService;

    @Captor
    private ArgumentCaptor<StorageEntity> entityCaptor;

    private StorageService storageService;
    private StorageUtils storageUtils;
    private JsonSkeletonGenerator skeletonGenerator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        storageUtils = new StorageUtils();
        skeletonGenerator = new JsonSkeletonGenerator(objectMapper);
        storageService = new StorageService(
            storageRepository,
            quotaService,
            mappingService,
            storageUtils,
            skeletonGenerator,
            objectMapper,
            breakdownService
        );
        lenient().when(quotaService.checkQuotaForScope(anyString(), nullable(String.class), anyLong()))
            .thenAnswer(inv -> {
                String tenantId = inv.getArgument(0);
                String organizationId = inv.getArgument(1);
                long additionalBytes = inv.getArgument(2);
                if (organizationId != null && !organizationId.isBlank()) {
                    return quotaService.checkOrganizationQuota(organizationId, additionalBytes);
                }
                return quotaService.checkQuota(tenantId, additionalBytes);
            });
    }

    // ========== saveJson Tests ==========

    @Nested
    @DisplayName("saveJson")
    class SaveJsonTests {

        @Test
        @DisplayName("should save JSON data and return a UUID")
        void shouldSaveJsonAndReturnUuid() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            Map<String, Object> data = Map.of("key", "value", "count", 42);
            UUID id = storageService.saveJson(TENANT_ID, data, CONTENT_TYPE_JSON, null);

            assertThat(id).isNotNull();
            verify(storageRepository).save(any(StorageEntity.class));
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should save JSON with correct tenant, content type, and size")
        void shouldSaveJsonWithCorrectFields() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            Map<String, Object> data = Map.of("name", "test");
            storageService.saveJson(TENANT_ID, data, CONTENT_TYPE_JSON, null);

            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();

            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(saved.getContentType()).isEqualTo(CONTENT_TYPE_JSON);
            assertThat(saved.getSizeBytes()).isGreaterThan(0);
            assertThat(saved.getChecksum()).isNotNull();
            assertThat(saved.getStatus()).isEqualTo(StorageStatus.ACTIVE);
            assertThat(saved.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should save JSON with expiration time")
        void shouldSaveJsonWithExpirationTime() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            Map<String, Object> data = Map.of("key", "value");
            storageService.saveJson(TENANT_ID, data, CONTENT_TYPE_JSON, expiresAt);

            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getExpiresAt()).isEqualTo(expiresAt);
        }

        @Test
        @DisplayName("should generate structure skeleton for JSON data")
        void shouldGenerateStructureSkeleton() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            Map<String, Object> data = Map.of(
                "users", List.of(Map.of("name", "Alice", "age", 30)),
                "total", 1
            );
            storageService.saveJson(TENANT_ID, data, CONTENT_TYPE_JSON, null);

            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();

            assertThat(saved.getStructureSkeleton()).isNotNull();
            assertThat(saved.getStructureSkeleton()).contains("_t");
        }

        @Test
        @DisplayName("should throw QuotaExceededException when hard limit is reached")
        void shouldThrowQuotaExceededWhenHardLimitReached() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong()))
                .thenReturn(QuotaStatus.HARD_LIMIT_REACHED);

            Map<String, Object> data = Map.of("key", "value");

            assertThatThrownBy(() -> storageService.saveJson(TENANT_ID, data, CONTENT_TYPE_JSON, null))
                .isInstanceOf(QuotaExceededException.class);

            verify(storageRepository, never()).save(any());
        }

        @Test
        @DisplayName("should allow save when soft limit is reached (warning only)")
        void shouldAllowSaveWhenSoftLimitReached() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.SOFT_LIMIT_REACHED);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            Map<String, Object> data = Map.of("key", "value");
            UUID id = storageService.saveJson(TENANT_ID, data, CONTENT_TYPE_JSON, null);

            assertThat(id).isNotNull();
            verify(storageRepository).save(any(StorageEntity.class));
        }

        @Test
        @DisplayName("should not apply mapping when mapping service is disabled")
        void shouldNotApplyMappingWhenDisabled() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(mappingService.isEnabled()).thenReturn(false);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            UUID toolId = UUID.randomUUID();
            Map<String, Object> data = Map.of("key", "value");
            storageService.saveJson(TENANT_ID, data, CONTENT_TYPE_JSON, null, toolId);

            verify(mappingService, never()).resolve(any(), any());
        }
    }

    // ========== saveJsonWithContext Tests ==========

    @Nested
    @DisplayName("saveJsonWithContext")
    class SaveJsonWithContextTests {

        @Test
        @DisplayName("should save JSON with run context (runId, stepKey, epoch)")
        void shouldSaveJsonWithRunContext() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            String runId = "run-123";
            String stepKey = "mcp:enricher";
            int epoch = 1;
            Map<String, Object> data = Map.of("result", "success");

            UUID id = storageService.saveJsonWithContext(
                TENANT_ID, data, CONTENT_TYPE_JSON, null, null, runId, stepKey, null, epoch
            );

            assertThat(id).isNotNull();
            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();

            assertThat(saved.getRunId()).isEqualTo(runId);
            assertThat(saved.getStepKey()).isEqualTo(stepKey);
            assertThat(saved.getEpoch()).isEqualTo(epoch);
        }

        @Test
        @DisplayName("should save JSON with item index for loop/split context")
        void shouldSaveJsonWithItemIndex() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            Map<String, Object> data = Map.of("item", "data");
            storageService.saveJsonWithContext(
                TENANT_ID, data, CONTENT_TYPE_JSON, null, null,
                "run-456", "mcp:process", 3, 0
            );

            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getItemIndex()).isEqualTo(3);
        }
    }

    // ========== saveBinary Tests ==========

    @Nested
    @DisplayName("saveBinary")
    class SaveBinaryTests {

        @Test
        @DisplayName("should save binary data with file metadata")
        void shouldSaveBinaryWithFileMetadata() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            byte[] binaryData = "binary content here".getBytes();
            UUID id = storageService.saveBinary(TENANT_ID, binaryData, "document.pdf", "application/pdf", null);

            assertThat(id).isNotNull();
            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();

            assertThat(saved.getFileName()).isEqualTo("document.pdf");
            assertThat(saved.getFileExtension()).isEqualTo("pdf");
            assertThat(saved.getMimeType()).isEqualTo("application/pdf");
            assertThat(saved.getStorageType()).isEqualTo("BINARY");
            assertThat(saved.getSizeBytes()).isEqualTo(binaryData.length);
        }

        @Test
        @DisplayName("should handle null binary data gracefully")
        void shouldHandleNullBinaryData() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            UUID id = storageService.saveBinary(TENANT_ID, null, "empty.bin", "application/octet-stream", null);

            assertThat(id).isNotNull();
            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getSizeBytes()).isEqualTo(0);
        }
    }

    // ========== saveText Tests ==========

    @Nested
    @DisplayName("saveText")
    class SaveTextTests {

        @Test
        @DisplayName("should save text data with file metadata")
        void shouldSaveTextWithFileMetadata() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            String textData = "This is a text document with some content.";
            UUID id = storageService.saveText(TENANT_ID, textData, "readme.txt", "text/plain", null);

            assertThat(id).isNotNull();
            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();

            assertThat(saved.getFileName()).isEqualTo("readme.txt");
            assertThat(saved.getFileExtension()).isEqualTo("txt");
            assertThat(saved.getMimeType()).isEqualTo("text/plain");
            assertThat(saved.getStorageType()).isEqualTo("TEXT");
        }
    }

    // ========== getById Tests ==========

    @Nested
    @DisplayName("getById")
    class GetByIdTests {

        @Test
        @DisplayName("should return data for existing active entity")
        void shouldReturnDataForActiveEntity() {
            UUID storageId = UUID.randomUUID();
            StorageEntity entity = createJsonEntity(storageId, TENANT_ID, Map.of("key", "value"));

            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.of(entity));
            when(storageRepository.updateAccessedAt(eq(storageId), any(Instant.class))).thenReturn(1);

            Optional<Object> result = storageService.getById(storageId, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("should return empty for non-existent entity")
        void shouldReturnEmptyForNonExistentEntity() {
            UUID storageId = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.empty());

            Optional<Object> result = storageService.getById(storageId, TENANT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for entity belonging to different tenant")
        void shouldReturnEmptyForDifferentTenant() {
            UUID storageId = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(storageId, OTHER_TENANT_ID))
                .thenReturn(Optional.empty());

            Optional<Object> result = storageService.getById(storageId, OTHER_TENANT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for expired entity")
        void shouldReturnEmptyForExpiredEntity() {
            UUID storageId = UUID.randomUUID();
            StorageEntity entity = createJsonEntity(storageId, TENANT_ID, Map.of("key", "value"));
            entity.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.of(entity));

            Optional<Object> result = storageService.getById(storageId, TENANT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should update access time when getting entity")
        void shouldUpdateAccessTimeOnGet() {
            UUID storageId = UUID.randomUUID();
            StorageEntity entity = createJsonEntity(storageId, TENANT_ID, Map.of("key", "value"));

            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.of(entity));
            when(storageRepository.updateAccessedAt(eq(storageId), any(Instant.class))).thenReturn(1);

            storageService.getById(storageId, TENANT_ID);

            verify(storageRepository).updateAccessedAt(eq(storageId), any(Instant.class));
        }
    }

    // ========== getEntityById Tests ==========

    @Nested
    @DisplayName("getEntityById")
    class GetEntityByIdTests {

        @Test
        @DisplayName("should return full entity with all fields")
        void shouldReturnFullEntity() {
            UUID storageId = UUID.randomUUID();
            StorageEntity entity = createJsonEntity(storageId, TENANT_ID, Map.of("key", "value"));

            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.of(entity));
            when(storageRepository.updateAccessedAt(eq(storageId), any(Instant.class))).thenReturn(1);

            Optional<StorageEntity> result = storageService.getEntityById(storageId, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getTenantId()).isEqualTo(TENANT_ID);
            assertThat(result.get().getContentType()).isEqualTo(CONTENT_TYPE_JSON);
        }
    }

    // ========== deleteById Tests ==========

    @Nested
    @DisplayName("deleteById")
    class DeleteByIdTests {

        @Test
        @DisplayName("should soft delete existing entity")
        void shouldSoftDeleteExistingEntity() {
            UUID storageId = UUID.randomUUID();
            StorageEntity entity = createJsonEntity(storageId, TENANT_ID, Map.of("key", "value"));

            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.of(entity));
            when(storageRepository.updateStatus(storageId, StorageStatus.DELETED)).thenReturn(1);

            boolean result = storageService.deleteById(storageId, TENANT_ID);

            assertThat(result).isTrue();
            verify(storageRepository).updateStatus(storageId, StorageStatus.DELETED);
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should return false for non-existent entity")
        void shouldReturnFalseForNonExistentEntity() {
            UUID storageId = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.empty());

            boolean result = storageService.deleteById(storageId, TENANT_ID);

            assertThat(result).isFalse();
            verify(storageRepository, never()).updateStatus(any(), any());
        }

        @Test
        @DisplayName("should not delete entity belonging to another tenant")
        void shouldNotDeleteEntityOfAnotherTenant() {
            UUID storageId = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(storageId, OTHER_TENANT_ID))
                .thenReturn(Optional.empty());

            boolean result = storageService.deleteById(storageId, OTHER_TENANT_ID);

            assertThat(result).isFalse();
        }
    }

    // ========== listByTenant Tests ==========

    @Nested
    @DisplayName("listByTenant")
    class ListByTenantTests {

        @Test
        @DisplayName("should return all active entities for tenant")
        void shouldReturnAllActiveEntitiesForTenant() {
            StorageEntity e1 = createJsonEntity(UUID.randomUUID(), TENANT_ID, Map.of("a", 1));
            StorageEntity e2 = createJsonEntity(UUID.randomUUID(), TENANT_ID, Map.of("b", 2));

            when(storageRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(e1, e2));

            List<StorageEntity> result = storageService.listByTenant(TENANT_ID);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list when tenant has no entities")
        void shouldReturnEmptyListWhenNoEntities() {
            when(storageRepository.findByTenantId(TENANT_ID)).thenReturn(List.of());

            List<StorageEntity> result = storageService.listByTenant(TENANT_ID);

            assertThat(result).isEmpty();
        }
    }

    // ========== cleanupExpired Tests ==========

    @Nested
    @DisplayName("cleanupExpired")
    class CleanupExpiredTests {

        @Test
        @DisplayName("should mark expired entities as DELETED")
        void shouldMarkExpiredEntitiesAsDeleted() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            StorageEntity e1 = createJsonEntity(id1, TENANT_ID, Map.of("a", 1));
            StorageEntity e2 = createJsonEntity(id2, OTHER_TENANT_ID, Map.of("b", 2));

            when(storageRepository.findExpiredStorages(any(Instant.class))).thenReturn(List.of(e1, e2));
            when(storageRepository.updateStatus(any(), eq(StorageStatus.DELETED))).thenReturn(1);

            int cleaned = storageService.cleanupExpired();

            assertThat(cleaned).isEqualTo(2);
            verify(storageRepository).updateStatus(id1, StorageStatus.DELETED);
            verify(storageRepository).updateStatus(id2, StorageStatus.DELETED);
        }

        @Test
        @DisplayName("should return 0 when no expired entities exist")
        void shouldReturnZeroWhenNoExpiredEntities() {
            when(storageRepository.findExpiredStorages(any(Instant.class))).thenReturn(List.of());

            int cleaned = storageService.cleanupExpired();

            assertThat(cleaned).isEqualTo(0);
        }
    }

    // ========== updateJson Tests ==========

    @Nested
    @DisplayName("updateJson")
    class UpdateJsonTests {

        @Test
        @DisplayName("should update JSON data and recalculate size/checksum")
        void shouldUpdateJsonDataAndRecalculate() {
            UUID storageId = UUID.randomUUID();
            StorageEntity entity = createJsonEntity(storageId, TENANT_ID, Map.of("old", "data"));

            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.of(entity));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(storageRepository.updateAccessedAt(eq(storageId), any(Instant.class))).thenReturn(1);

            Map<String, Object> newData = Map.of("new", "data", "extra", "field");
            Optional<Object> result = storageService.updateJson(storageId, TENANT_ID, newData, null);

            assertThat(result).isPresent();
            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity updated = entityCaptor.getValue();

            assertThat(updated.getChecksum()).isNotNull();
            assertThat(updated.getSizeBytes()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should update both data and dataMapped")
        void shouldUpdateDataAndDataMapped() {
            UUID storageId = UUID.randomUUID();
            StorageEntity entity = createJsonEntity(storageId, TENANT_ID, Map.of("old", "data"));

            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.of(entity));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(storageRepository.updateAccessedAt(eq(storageId), any(Instant.class))).thenReturn(1);

            Map<String, Object> newData = Map.of("key", "value");
            Map<String, Object> newMapped = Map.of("mappedKey", "mappedValue");
            Optional<Object> result = storageService.updateJson(storageId, TENANT_ID, newData, newMapped);

            assertThat(result).isPresent();
            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getDataMapped()).isNotNull();
        }

        @Test
        @DisplayName("should return empty when entity does not exist")
        void shouldReturnEmptyWhenEntityNotFound() {
            UUID storageId = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(storageId, TENANT_ID))
                .thenReturn(Optional.empty());

            Optional<Object> result = storageService.updateJson(storageId, TENANT_ID, Map.of("a", 1), null);

            assertThat(result).isEmpty();
            verify(storageRepository, never()).save(any());
        }
    }

    // ========== getSkeletonOnly Tests ==========

    @Nested
    @DisplayName("getSkeletonOnly")
    class GetSkeletonOnlyTests {

        @Test
        @DisplayName("should return skeleton JSON string when present")
        void shouldReturnSkeletonWhenPresent() {
            UUID storageId = UUID.randomUUID();
            String skeleton = "{\"_t\":\"obj\",\"props\":{\"name\":\"string\"}}";
            when(storageRepository.getSkeletonOnly(storageId, TENANT_ID)).thenReturn(skeleton);

            Optional<String> result = storageService.getSkeletonOnly(storageId, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(skeleton);
        }

        @Test
        @DisplayName("should return empty when no skeleton exists")
        void shouldReturnEmptyWhenNoSkeleton() {
            UUID storageId = UUID.randomUUID();
            when(storageRepository.getSkeletonOnly(storageId, TENANT_ID)).thenReturn(null);

            Optional<String> result = storageService.getSkeletonOnly(storageId, TENANT_ID);

            assertThat(result).isEmpty();
        }
    }

    // ========== getValueAtPath Tests ==========

    @Nested
    @DisplayName("getValueAtPath")
    class GetValueAtPathTests {

        @Test
        @DisplayName("should extract value at given JSON path")
        void shouldExtractValueAtPath() {
            UUID storageId = UUID.randomUUID();
            String[] path = {"output", "data", "name"};
            when(storageRepository.extractJsonPath(storageId, TENANT_ID, path)).thenReturn("Alice");

            Optional<String> result = storageService.getValueAtPath(storageId, TENANT_ID, path);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("Alice");
        }

        @Test
        @DisplayName("should return empty for non-existent path")
        void shouldReturnEmptyForNonExistentPath() {
            UUID storageId = UUID.randomUUID();
            String[] path = {"nonexistent", "path"};
            when(storageRepository.extractJsonPath(storageId, TENANT_ID, path)).thenReturn(null);

            Optional<String> result = storageService.getValueAtPath(storageId, TENANT_ID, path);

            assertThat(result).isEmpty();
        }
    }

    // ========== getObjectAtPath Tests ==========

    @Nested
    @DisplayName("getObjectAtPath")
    class GetObjectAtPathTests {

        @Test
        @DisplayName("should extract JSON object at given path")
        void shouldExtractObjectAtPath() {
            UUID storageId = UUID.randomUUID();
            String[] path = {"output", "data"};
            String jsonObject = "{\"name\":\"Alice\",\"age\":30}";
            when(storageRepository.extractJsonObject(storageId, TENANT_ID, path)).thenReturn(jsonObject);

            Optional<String> result = storageService.getObjectAtPath(storageId, TENANT_ID, path);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("Alice");
        }
    }

    // ========== Large Payload Tests ==========

    @Nested
    @DisplayName("Large Payload Handling")
    class LargePayloadTests {

        @Test
        @DisplayName("should handle large JSON payload correctly")
        void shouldHandleLargeJsonPayload() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            // Build a large data map with many entries
            Map<String, Object> largeData = new HashMap<>();
            for (int i = 0; i < 1000; i++) {
                largeData.put("key_" + i, "value_" + i + "_" + "x".repeat(100));
            }

            UUID id = storageService.saveJson(TENANT_ID, largeData, CONTENT_TYPE_JSON, null);

            assertThat(id).isNotNull();
            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getSizeBytes()).isGreaterThan(100_000);
        }
    }

    // ========== Tenant Isolation Tests ==========

    @Nested
    @DisplayName("Tenant Isolation")
    class TenantIsolationTests {

        @Test
        @DisplayName("should enforce tenant isolation on save and quota check")
        void shouldEnforceTenantIsolationOnSave() {
            when(quotaService.checkQuota(eq(TENANT_ID), anyLong())).thenReturn(QuotaStatus.OK);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

            Map<String, Object> data = Map.of("secret", "tenant1-data");
            storageService.saveJson(TENANT_ID, data, CONTENT_TYPE_JSON, null);

            verify(quotaService).checkQuota(eq(TENANT_ID), anyLong());
            verify(quotaService).updateUsage(TENANT_ID);

            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        }
    }

    // ========== Helper Methods ==========

    private StorageEntity createJsonEntity(UUID id, String tenantId, Map<String, Object> data) {
        StorageEntity entity = new StorageEntity(
            tenantId,
            CONTENT_TYPE_JSON,
            data,
            storageUtils.calculateSize(data),
            storageUtils.calculateChecksum(data),
            null
        );
        entity.setId(id);
        return entity;
    }
}
