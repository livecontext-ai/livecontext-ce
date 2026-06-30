package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.dto.MappingResolutionResult;
import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StorageService (common-storage-service) Unit Tests")
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    private static final String TENANT_ID = "tenant-123";
    private static final String ORG_ID = "org-123";

    @Mock
    private StorageRepository storageRepository;

    @Mock
    private QuotaOperations quotaService;

    @Mock
    private MappingOperations mappingService;

    @Mock
    private StorageUtils storageUtils;

    @Mock
    private JsonSkeletonGenerator skeletonGenerator;

    @Mock
    private StorageBreakdownService breakdownService;

    @Captor
    private ArgumentCaptor<StorageEntity> entityCaptor;

    private StorageService storageService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        storageService = new StorageService(
            storageRepository, quotaService, mappingService,
            storageUtils, skeletonGenerator, objectMapper,
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

    @Nested
    @DisplayName("renameByIdForScope")
    class RenameByIdForScopeTests {

        @Test
        @DisplayName("Found in org scope: updates only file_name and saves, returns true")
        void renamesInScope() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = new StorageEntity();
            entity.setId(id);
            entity.setFileName("old.png");
            entity.setS3Key("org-123/run/abc/old.png");
            when(storageRepository.findByIdAndOrganizationIdStrict(id, ORG_ID)).thenReturn(Optional.of(entity));

            boolean result = storageService.renameByIdForScope(id, TENANT_ID, ORG_ID, "new-name.png");

            assertThat(result).isTrue();
            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getFileName()).isEqualTo("new-name.png");
            // s3 object key is immutable - the blob is untouched.
            assertThat(entityCaptor.getValue().getS3Key()).isEqualTo("org-123/run/abc/old.png");
        }

        @Test
        @DisplayName("Not found in org scope: returns false, no save (cross-tenant guard)")
        void notFoundReturnsFalse() {
            UUID id = UUID.randomUUID();
            when(storageRepository.findByIdAndOrganizationIdStrict(id, ORG_ID)).thenReturn(Optional.empty());

            boolean result = storageService.renameByIdForScope(id, TENANT_ID, ORG_ID, "new-name.png");

            assertThat(result).isFalse();
            verify(storageRepository, never()).save(any(StorageEntity.class));
        }

        @Test
        @DisplayName("Null organizationId is rejected (org scope is mandatory post-V261)")
        void nullOrgRejected() {
            UUID id = UUID.randomUUID();
            assertThatThrownBy(() -> storageService.renameByIdForScope(id, TENANT_ID, null, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(storageRepository, never()).save(any(StorageEntity.class));
        }
    }

    @Nested
    @DisplayName("saveJson")
    class SaveJsonTests {

        @Test
        @DisplayName("should save JSON data and return UUID")
        void shouldSaveJsonDataAndReturnUuid() {
            Map<String, String> data = Map.of("key", "value");
            UUID expectedId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(20);
            when(quotaService.checkQuota(TENANT_ID, 20)).thenReturn(QuotaStatus.OK);
            when(storageUtils.calculateChecksum(data)).thenReturn("checksum123");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID result = storageService.saveJson(TENANT_ID, data, "application/json", null);

            assertThat(result).isEqualTo(expectedId);
            verify(quotaService).updateUsage(TENANT_ID);
            verify(storageRepository).save(any(StorageEntity.class));
        }

        @Test
        @DisplayName("org-scoped save charges the current workspace quota and rollup")
        void orgScopedSaveChargesCurrentWorkspaceQuotaAndRollup() {
            Map<String, String> data = Map.of("key", "value");
            UUID expectedId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(20);
            when(quotaService.checkOrganizationQuota(ORG_ID, 20L)).thenReturn(QuotaStatus.OK);
            when(storageUtils.calculateChecksum(data)).thenReturn("checksum123");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID[] result = new UUID[1];
            TenantResolver.runWithOrgScope(ORG_ID, () ->
                result[0] = storageService.saveJson(TENANT_ID, data, "application/json", null)
            );

            assertThat(result[0]).isEqualTo(expectedId);
            verify(quotaService).checkOrganizationQuota(ORG_ID, 20L);
            verify(breakdownService).trackSave(TENANT_ID, "STEP_OUTPUTS", 20L, ORG_ID);
            verify(quotaService).updateOrganizationUsage(ORG_ID);
            verify(quotaService, never()).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should throw QuotaExceededException when hard limit reached")
        void shouldThrowQuotaExceededWhenHardLimit() {
            Map<String, String> data = Map.of("key", "value");
            when(storageUtils.calculateSize(data)).thenReturn(20);
            when(quotaService.checkQuota(TENANT_ID, 20)).thenReturn(QuotaStatus.HARD_LIMIT_REACHED);

            assertThatThrownBy(() -> storageService.saveJson(TENANT_ID, data, "application/json", null))
                .isInstanceOf(QuotaExceededException.class);
        }

        @Test
        @DisplayName("should allow save when soft limit reached")
        void shouldAllowSaveWhenSoftLimitReached() {
            Map<String, String> data = Map.of("key", "value");
            UUID expectedId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(20);
            when(quotaService.checkQuota(TENANT_ID, 20)).thenReturn(QuotaStatus.SOFT_LIMIT_REACHED);
            when(storageUtils.calculateChecksum(data)).thenReturn("chk");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID result = storageService.saveJson(TENANT_ID, data, "application/json", null);

            assertThat(result).isEqualTo(expectedId);
        }

        @Test
        @DisplayName("F6: stamps _skeletonError sentinel when skeleton generation throws - no silent null")
        void f6StampsSentinelWhenSkeletonGenerationFails() {
            // F6 regression: pre-fix a Jackson cyclic-ref or oversize-depth in
            // skeleton generation left structureSkeleton=null. The frontend's
            // intelligent lazy-loading reads null as "no data produced" and
            // shows an empty tree to the user - even when the run had real
            // output (which still lives in dataMap). Post-fix we stamp
            // {"_skeletonError":"<exceptionClass>"} so the FE can render a
            // distinct "preview unavailable" fallback.
            Map<String, String> data = Map.of("key", "value");
            UUID expectedId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(20);
            when(quotaService.checkQuota(TENANT_ID, 20)).thenReturn(QuotaStatus.OK);
            when(storageUtils.calculateChecksum(data)).thenReturn("chk");
            // Force skeleton generation to fail - realistic trigger is a Spring
            // proxy with cyclic refs inside a StepExecutionResult sub-map.
            when(skeletonGenerator.generateSkeleton(any()))
                    .thenThrow(new RuntimeException("Direct self-reference leading to cycle"));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID result = storageService.saveJson(TENANT_ID, data, "application/json", null);

            assertThat(result).isEqualTo(expectedId);
            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();
            assertThat(saved.getStructureSkeleton())
                    .as("F6: skeleton field must carry the sentinel, not null - FE depends on this to distinguish "
                            + "'preview unavailable' from 'no-data produced'")
                    .isNotNull()
                    .contains("_skeletonError")
                    .contains("RuntimeException");
        }

        @Test
        @DisplayName("CE simulation (checkQuota returns OK) → save proceeds AND tracking still fires")
        void usageStillTrackedInCe() {
            // Simulates CE behavior by stubbing the gated checkQuota to return OK. This
            // proves that gating QuotaService.checkQuota does not short-circuit the
            // downstream tracking calls (breakdownService.trackSave + quotaService.updateUsage).
            Map<String, String> data = Map.of("key", "value");
            UUID expectedId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(20);
            when(quotaService.checkQuota(TENANT_ID, 20)).thenReturn(QuotaStatus.OK);
            when(storageUtils.calculateChecksum(data)).thenReturn("chk-ce");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID result = storageService.saveJson(TENANT_ID, data, "application/json", null);

            assertThat(result).isEqualTo(expectedId);
            verify(quotaService).updateUsage(TENANT_ID);
            verify(storageRepository).save(any(StorageEntity.class));
        }
    }

    @Nested
    @DisplayName("saveJsonWithContext")
    class SaveJsonWithContextTests {

        @Test
        @DisplayName("should save with run context fields")
        void shouldSaveWithRunContextFields() {
            Map<String, String> data = Map.of("output", "result");
            UUID expectedId = UUID.randomUUID();
            UUID toolId = UUID.randomUUID();
            String runId = "run-abc";
            String stepKey = "mcp:api_call";

            when(storageUtils.calculateSize(data)).thenReturn(30);
            when(quotaService.checkQuota(TENANT_ID, 30)).thenReturn(QuotaStatus.OK);
            when(storageUtils.calculateChecksum(data)).thenReturn("chk");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID result = storageService.saveJsonWithContext(
                TENANT_ID, data, "application/json", null, toolId, runId, stepKey, 2, 1
            );

            assertThat(result).isEqualTo(expectedId);
            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();
            assertThat(saved.getRunId()).isEqualTo(runId);
            assertThat(saved.getStepKey()).isEqualTo(stepKey);
            assertThat(saved.getItemIndex()).isEqualTo(2);
            assertThat(saved.getEpoch()).isEqualTo(1);
        }

        @Test
        @DisplayName("should not set run context fields when null")
        void shouldNotSetRunContextWhenNull() {
            Map<String, String> data = Map.of("k", "v");
            UUID expectedId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(10);
            when(quotaService.checkQuota(TENANT_ID, 10)).thenReturn(QuotaStatus.OK);
            when(storageUtils.calculateChecksum(data)).thenReturn("chk");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            storageService.saveJsonWithContext(TENANT_ID, data, "application/json", null, null, null, null, null, 0);

            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();
            assertThat(saved.getRunId()).isNull();
            assertThat(saved.getStepKey()).isNull();
            assertThat(saved.getItemIndex()).isNull();
            assertThat(saved.getEpoch()).isZero();
        }

        @Test
        @DisplayName("should apply mapping when toolId is provided and content is JSON")
        void shouldApplyMappingWhenToolIdProvided() {
            Map<String, String> data = Map.of("key", "value");
            UUID expectedId = UUID.randomUUID();
            UUID toolId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(20);
            when(quotaService.checkQuota(TENANT_ID, 20)).thenReturn(QuotaStatus.OK);
            when(storageUtils.calculateChecksum(data)).thenReturn("chk");
            when(mappingService.isEnabled()).thenReturn(true);
            MappingResolutionResult mappingResult = MappingResolutionResult.success(Map.of("mapped", "data"), 1);
            when(mappingService.resolve(eq(toolId), anyString())).thenReturn(mappingResult);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            storageService.saveJsonWithContext(TENANT_ID, data, "application/json", null, toolId, null, null, null, 0);

            verify(mappingService).resolve(eq(toolId), anyString());
            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getDataMapped()).isNotNull();
        }

        @Test
        @DisplayName("should not apply mapping when mapping is disabled")
        void shouldNotApplyMappingWhenDisabled() {
            Map<String, String> data = Map.of("key", "value");
            UUID expectedId = UUID.randomUUID();
            UUID toolId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(20);
            when(quotaService.checkQuota(TENANT_ID, 20)).thenReturn(QuotaStatus.OK);
            when(storageUtils.calculateChecksum(data)).thenReturn("chk");
            when(mappingService.isEnabled()).thenReturn(false);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            storageService.saveJsonWithContext(TENANT_ID, data, "application/json", null, toolId, null, null, null, 0);

            verify(mappingService, never()).resolve(any(), anyString());
        }
    }

    @Nested
    @DisplayName("saveBinary")
    class SaveBinaryTests {

        @Test
        @DisplayName("should save binary data and return UUID")
        void shouldSaveBinaryDataAndReturnUuid() {
            byte[] data = new byte[]{1, 2, 3, 4, 5};
            UUID expectedId = UUID.randomUUID();

            when(quotaService.checkQuota(TENANT_ID, 5)).thenReturn(QuotaStatus.OK);
            when(storageUtils.extractFileExtension("image.png")).thenReturn("png");
            when(storageUtils.calculateChecksum(data)).thenReturn("bin-chk");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID result = storageService.saveBinary(TENANT_ID, data, "image.png", "image/png", null);

            assertThat(result).isEqualTo(expectedId);
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should handle null binary data")
        void shouldHandleNullBinaryData() {
            UUID expectedId = UUID.randomUUID();
            when(quotaService.checkQuota(TENANT_ID, 0)).thenReturn(QuotaStatus.OK);
            when(storageUtils.extractFileExtension("file.bin")).thenReturn("bin");
            when(storageUtils.calculateChecksum(null)).thenReturn(null);
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID result = storageService.saveBinary(TENANT_ID, null, "file.bin", "application/octet-stream", null);

            assertThat(result).isEqualTo(expectedId);
        }
    }

    @Nested
    @DisplayName("saveText")
    class SaveTextTests {

        @Test
        @DisplayName("should save text data and return UUID")
        void shouldSaveTextDataAndReturnUuid() {
            String data = "Hello, this is text content";
            UUID expectedId = UUID.randomUUID();

            when(storageUtils.calculateSize(data)).thenReturn(27);
            when(quotaService.checkQuota(TENANT_ID, 27)).thenReturn(QuotaStatus.OK);
            when(storageUtils.extractFileExtension("doc.txt")).thenReturn("txt");
            when(storageUtils.calculateChecksum(data)).thenReturn("txt-chk");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID result = storageService.saveText(TENANT_ID, data, "doc.txt", "text/plain", null);

            assertThat(result).isEqualTo(expectedId);
            verify(quotaService).updateUsage(TENANT_ID);
        }
    }

    @Nested
    @DisplayName("saveS3FileIndex")
    class SaveS3FileIndexTests {

        @Test
        @DisplayName("org-scoped S3 index checks the workspace quota before saving")
        void orgScopedS3IndexChecksWorkspaceQuotaBeforeSaving() {
            doReturn(QuotaStatus.HARD_LIMIT_REACHED)
                .when(quotaService).checkOrganizationQuota(ORG_ID, 123L);

            TenantResolver.runWithOrgScope(ORG_ID, () ->
                assertThatThrownBy(() -> storageService.saveS3FileIndex(
                    TENANT_ID, null, null, null,
                    "tenant-123/general/test/file.txt", "file.txt", "text/plain", 123L, 0))
                    .isInstanceOf(QuotaExceededException.class)
            );

            verify(storageRepository, never()).save(any(StorageEntity.class));
            verify(breakdownService, never()).trackSave(anyString(), anyString(), anyLong(), anyString());
            verify(quotaService, never()).updateOrganizationUsage(anyString());
        }

        @Test
        @DisplayName("org-scoped S3 index updates the workspace quota and files rollup")
        void orgScopedS3IndexUpdatesWorkspaceQuotaAndFilesRollup() {
            UUID expectedId = UUID.randomUUID();
            doReturn(QuotaStatus.OK)
                .when(quotaService).checkOrganizationQuota(ORG_ID, 123L);
            when(storageUtils.extractFileExtension("file.txt")).thenReturn("txt");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });

            UUID[] result = new UUID[1];
            TenantResolver.runWithOrgScope(ORG_ID, () ->
                result[0] = storageService.saveS3FileIndex(
                    TENANT_ID, null, null, null,
                    "tenant-123/general/test/file.txt", "file.txt", "text/plain", 123L, 0)
            );

            assertThat(result[0]).isEqualTo(expectedId);
            verify(breakdownService).trackSave(TENANT_ID, "FILES", 123L, ORG_ID);
            verify(quotaService).updateOrganizationUsage(ORG_ID);
            verify(quotaService, never()).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("folder-aware index stamps parent_folder_id when the target is a folder in the workspace (V313 upload-into-folder)")
        void folderAwareIndexStampsParentFolderId() {
            UUID folderId = UUID.randomUUID();
            UUID expectedId = UUID.randomUUID();
            doReturn(QuotaStatus.OK).when(quotaService).checkOrganizationQuota(ORG_ID, 50L);
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(folderId, ORG_ID))
                    .thenReturn(Optional.of(new StorageEntity()));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity e = inv.getArgument(0);
                e.setId(expectedId);
                return e;
            });

            TenantResolver.runWithOrgScope(ORG_ID, () ->
                    storageService.saveS3FileIndex(TENANT_ID, null, null, null,
                            "k", "f.png", "image/png", 50L, 0, 0, null, null, folderId));

            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getParentFolderId())
                    .as("a valid in-workspace folder id must be stamped on the uploaded row")
                    .isEqualTo(folderId);
        }

        @Test
        @DisplayName("folder-aware index drops a non-folder / cross-org parentFolderId to root instead of failing the upload (V313)")
        void folderAwareIndexDropsInvalidParentToRoot() {
            UUID notAFolder = UUID.randomUUID();
            UUID expectedId = UUID.randomUUID();
            doReturn(QuotaStatus.OK).when(quotaService).checkOrganizationQuota(ORG_ID, 50L);
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(notAFolder, ORG_ID))
                    .thenReturn(Optional.empty());
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity e = inv.getArgument(0);
                e.setId(expectedId);
                return e;
            });

            UUID[] result = new UUID[1];
            TenantResolver.runWithOrgScope(ORG_ID, () ->
                    result[0] = storageService.saveS3FileIndex(TENANT_ID, null, null, null,
                            "k", "f.png", "image/png", 50L, 0, 0, null, null, notAFolder));

            assertThat(result[0]).isEqualTo(expectedId);
            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getParentFolderId())
                    .as("an upload pointed at a non-folder must land at root, never orphan the already-uploaded S3 object")
                    .isNull();
        }

        @Test
        @DisplayName("returns the saved id even when post-save usage tracking throws (org-less catalog upload - "
                + "regression: an uncaught throw discarded the id, so catalog-binary FileRefs shipped id-less)")
        void returnsIdEvenWhenPostSaveUsageTrackingThrows() {
            UUID expectedId = UUID.randomUUID();
            when(storageUtils.extractFileExtension("media.json")).thenReturn("json");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity entity = inv.getArgument(0);
                entity.setId(expectedId);
                return entity;
            });
            // Org-less (catalog/internal) path → updateUsageForScope calls quotaService.updateUsage(tenantId).
            // A usage-recompute failure must NOT bubble out and discard the already-persisted row's id.
            doThrow(new RuntimeException("usage recompute boom")).when(quotaService).updateUsage(TENANT_ID);

            UUID result = storageService.saveS3FileIndex(
                    TENANT_ID, null, null, null,
                    "1/general/catalog-binary/abc_media.json", "media.json", "application/json", 176L, 0);

            assertThat(result)
                    .as("the storage id MUST be returned once the row is saved - a best-effort tracking "
                            + "failure cannot discard it (the opaque by-id file URL is built from this id)")
                    .isEqualTo(expectedId);
            verify(storageRepository).save(any(StorageEntity.class));
        }

        @Test
        @DisplayName("context-carrying overload persists epoch / spawn / itemIndex / sourceType onto the row "
                + "(workflow file producers group by workflow→epoch→spawn→iteration)")
        void contextOverloadPersistsRunCoordinatesAndSourceType() {
            when(storageUtils.extractFileExtension("report.pdf")).thenReturn("pdf");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            storageService.saveS3FileIndex(
                    TENANT_ID, "wf-1", "run-1", "mcp:download",
                    "tenant-123/wf-1/run-1/mcp:download/abc_report.pdf",
                    "report.pdf", "application/pdf", 200L,
                    /* epoch */ 3, /* spawn */ 2, /* itemIndex */ 7,
                    StorageSourceTypes.STEP_OUTPUT);

            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();
            assertThat(saved.getEpoch()).isEqualTo(3);
            assertThat(saved.getSpawn()).isEqualTo(2);
            assertThat(saved.getItemIndex()).isEqualTo(7);
            assertThat(saved.getSourceType()).isEqualTo(StorageSourceTypes.STEP_OUTPUT);
            assertThat(saved.getWorkflowId()).isEqualTo("wf-1");
            assertThat(saved.getRunId()).isEqualTo("run-1");
            assertThat(saved.getStepKey()).isEqualTo("mcp:download");
        }

        @Test
        @DisplayName("legacy 9-arg overload defaults spawn=0, itemIndex=null, sourceType=S3_FILE "
                + "(generic / non-workflow uploads keep the epoch-0, no-context path)")
        void legacyOverloadDefaultsRunCoordinatesAndSourceType() {
            when(storageUtils.extractFileExtension("img.png")).thenReturn("png");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            // 9-arg call (no spawn/itemIndex/sourceType) → must reach the full overload with defaults.
            storageService.saveS3FileIndex(
                    TENANT_ID, null, null, null,
                    "tenant-123/general/catalog-binary/abc_img.png",
                    "img.png", "image/png", 50L, /* epoch */ 0);

            verify(storageRepository).save(entityCaptor.capture());
            StorageEntity saved = entityCaptor.getValue();
            assertThat(saved.getEpoch()).isEqualTo(0);
            assertThat(saved.getSpawn()).isEqualTo(0);
            assertThat(saved.getItemIndex())
                    .as("itemIndex stays null on the legacy/generic path (not item-scoped)")
                    .isNull();
            assertThat(saved.getSourceType()).isEqualTo(StorageSourceTypes.S3_FILE);
        }

        @Test
        @DisplayName("null / blank sourceType falls back to S3_FILE")
        void blankSourceTypeFallsBackToS3File() {
            when(storageUtils.extractFileExtension(any())).thenReturn("bin");
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            storageService.saveS3FileIndex(
                    TENANT_ID, "wf", "run", "step", "k", "f.bin", "application/octet-stream", 1L,
                    1, 0, null, "   ");

            verify(storageRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getSourceType()).isEqualTo(StorageSourceTypes.S3_FILE);
        }
    }

    @Nested
    @DisplayName("getById")
    class GetByIdTests {

        @Test
        @DisplayName("should return data when entity found and not expired")
        void shouldReturnDataWhenFoundAndNotExpired() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setData("{\"key\":\"value\"}");
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));

            Optional<Object> result = storageService.getById(id, TENANT_ID);

            assertThat(result).isPresent();
            verify(storageRepository).updateAccessedAt(eq(id), any(Instant.class));
        }

        @Test
        @DisplayName("should return empty when entity not found")
        void shouldReturnEmptyWhenNotFound() {
            UUID id = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.empty());

            Optional<Object> result = storageService.getById(id, TENANT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when entity is expired")
        void shouldReturnEmptyWhenExpired() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setExpiresAt(Instant.now().minusSeconds(3600));
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));

            Optional<Object> result = storageService.getById(id, TENANT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return binary data for BINARY type")
        void shouldReturnBinaryDataForBinaryType() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setStorageType("BINARY");
            byte[] binaryData = new byte[]{10, 20, 30};
            entity.setDataBinary(binaryData);
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));

            Optional<Object> result = storageService.getById(id, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(binaryData);
        }

        @Test
        @DisplayName("should return text data for TEXT type")
        void shouldReturnTextDataForTextType() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setStorageType("TEXT");
            entity.setDataText("some text content");
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));

            Optional<Object> result = storageService.getById(id, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("some text content");
        }
    }

    @Nested
    @DisplayName("getByIdReadOnly")
    class GetByIdReadOnlyTests {

        @Test
        @DisplayName("should return data without updating access time")
        void shouldReturnDataWithoutUpdatingAccessTime() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setData("{\"key\":\"value\"}");
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));

            Optional<Object> result = storageService.getByIdReadOnly(id, TENANT_ID);

            assertThat(result).isPresent();
            verify(storageRepository, never()).updateAccessedAt(any(), any());
        }
    }

    @Nested
    @DisplayName("getEntityById")
    class GetEntityByIdTests {

        @Test
        @DisplayName("should return entity when found and active")
        void shouldReturnEntityWhenFoundAndActive() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));

            Optional<StorageEntity> result = storageService.getEntityById(id, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("should return empty when entity is expired")
        void shouldReturnEmptyWhenExpired() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setExpiresAt(Instant.now().minusSeconds(100));
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));

            Optional<StorageEntity> result = storageService.getEntityById(id, TENANT_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("deleteById")
    class DeleteByIdTests {

        @Test
        @DisplayName("should return true and soft delete when entity found")
        void shouldReturnTrueAndSoftDelete() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));

            boolean result = storageService.deleteById(id, TENANT_ID);

            assertThat(result).isTrue();
            verify(storageRepository).updateStatus(id, StorageStatus.DELETED);
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should return false when entity not found")
        void shouldReturnFalseWhenNotFound() {
            UUID id = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.empty());

            boolean result = storageService.deleteById(id, TENANT_ID);

            assertThat(result).isFalse();
            verify(storageRepository, never()).updateStatus(any(), any());
        }
    }

    @Nested
    @DisplayName("listByTenant")
    class ListByTenantTests {

        @Test
        @DisplayName("should return list of entities for tenant")
        void shouldReturnListForTenant() {
            List<StorageEntity> entities = List.of(createActiveEntity(UUID.randomUUID()));
            when(storageRepository.findByTenantId(TENANT_ID)).thenReturn(entities);

            List<StorageEntity> result = storageService.listByTenant(TENANT_ID);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should return empty list when tenant has no storage")
        void shouldReturnEmptyListWhenNoStorage() {
            when(storageRepository.findByTenantId(TENANT_ID)).thenReturn(Collections.emptyList());

            List<StorageEntity> result = storageService.listByTenant(TENANT_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("cleanupExpired")
    class CleanupExpiredTests {

        @Test
        @DisplayName("should mark expired entities as DELETED")
        void shouldMarkExpiredAsDeleted() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            StorageEntity expired1 = createActiveEntity(id1);
            StorageEntity expired2 = createActiveEntity(id2);
            when(storageRepository.findExpiredStorages(any(Instant.class))).thenReturn(List.of(expired1, expired2));

            int result = storageService.cleanupExpired();

            assertThat(result).isEqualTo(2);
            verify(storageRepository).updateStatus(id1, StorageStatus.DELETED);
            verify(storageRepository).updateStatus(id2, StorageStatus.DELETED);
        }

        @Test
        @DisplayName("should return zero when no expired entities")
        void shouldReturnZeroWhenNoExpired() {
            when(storageRepository.findExpiredStorages(any(Instant.class))).thenReturn(Collections.emptyList());

            int result = storageService.cleanupExpired();

            assertThat(result).isZero();
        }
    }

    @Nested
    @DisplayName("getSkeletonOnly")
    class GetSkeletonOnlyTests {

        @Test
        @DisplayName("should return skeleton when present")
        void shouldReturnSkeletonWhenPresent() {
            UUID id = UUID.randomUUID();
            String skeleton = "{\"_t\":\"obj\",\"props\":{\"key\":\"string\"}}";
            when(storageRepository.getSkeletonOnly(id, TENANT_ID)).thenReturn(skeleton);

            Optional<String> result = storageService.getSkeletonOnly(id, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(skeleton);
        }

        @Test
        @DisplayName("should return empty when skeleton not found")
        void shouldReturnEmptyWhenSkeletonNotFound() {
            UUID id = UUID.randomUUID();
            when(storageRepository.getSkeletonOnly(id, TENANT_ID)).thenReturn(null);

            Optional<String> result = storageService.getSkeletonOnly(id, TENANT_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getValueAtPath")
    class GetValueAtPathTests {

        @Test
        @DisplayName("should return value at path")
        void shouldReturnValueAtPath() {
            UUID id = UUID.randomUUID();
            String[] path = {"output", "name"};
            when(storageRepository.extractJsonPath(id, TENANT_ID, path)).thenReturn("John");

            Optional<String> result = storageService.getValueAtPath(id, TENANT_ID, path);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("John");
        }

        @Test
        @DisplayName("should return empty when path not found")
        void shouldReturnEmptyWhenPathNotFound() {
            UUID id = UUID.randomUUID();
            String[] path = {"nonexistent"};
            when(storageRepository.extractJsonPath(id, TENANT_ID, path)).thenReturn(null);

            Optional<String> result = storageService.getValueAtPath(id, TENANT_ID, path);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getObjectAtPath")
    class GetObjectAtPathTests {

        @Test
        @DisplayName("should return JSON object at path")
        void shouldReturnJsonObjectAtPath() {
            UUID id = UUID.randomUUID();
            String[] path = {"data", "user"};
            String jsonObject = "{\"name\":\"Alice\",\"age\":30}";
            when(storageRepository.extractJsonObject(id, TENANT_ID, path)).thenReturn(jsonObject);

            Optional<String> result = storageService.getObjectAtPath(id, TENANT_ID, path);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(jsonObject);
        }
    }

    @Nested
    @DisplayName("updateJson")
    class UpdateJsonTests {

        @Test
        @DisplayName("should update JSON data for existing entity")
        void shouldUpdateJsonDataForExistingEntity() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setData("{\"old\":\"data\"}");
            Map<String, String> newData = Map.of("new", "data");
            Map<String, String> newMapped = Map.of("mapped", "data");

            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));
            when(storageUtils.calculateSize(newData)).thenReturn(20);
            when(storageUtils.calculateChecksum(newData)).thenReturn("new-chk");
            when(storageRepository.save(any(StorageEntity.class))).thenReturn(entity);

            Optional<Object> result = storageService.updateJson(id, TENANT_ID, newData, newMapped);

            assertThat(result).isPresent();
            verify(storageRepository).save(entity);
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should return empty when entity not found")
        void shouldReturnEmptyWhenNotFound() {
            UUID id = UUID.randomUUID();
            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.empty());

            Optional<Object> result = storageService.updateJson(id, TENANT_ID, Map.of("k", "v"), null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should not set dataMapped when null")
        void shouldNotSetDataMappedWhenNull() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setData("{\"old\":\"data\"}");

            when(storageRepository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(entity));
            when(storageUtils.calculateSize(any())).thenReturn(10);
            when(storageUtils.calculateChecksum(any())).thenReturn("chk");
            when(storageRepository.save(any(StorageEntity.class))).thenReturn(entity);

            storageService.updateJson(id, TENANT_ID, Map.of("k", "v"), null);

            // dataMapped should not have been set since we passed null
            verify(storageRepository).save(entity);
        }

        @Test
        @DisplayName("updateJsonForScope rejects positive growth when workspace hard quota is reached")
        void updateJsonForScopeRejectsPositiveGrowthWhenWorkspaceQuotaReached() {
            UUID id = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(id);
            entity.setSizeBytes(100);
            Map<String, String> newData = Map.of("large", "payload");

            when(storageRepository.findByIdAndOrganizationIdStrict(id, ORG_ID)).thenReturn(Optional.of(entity));
            when(storageUtils.calculateSize(newData)).thenReturn(150);
            doReturn(QuotaStatus.HARD_LIMIT_REACHED)
                .when(quotaService).checkOrganizationQuota(ORG_ID, 50L);

            assertThatThrownBy(() -> storageService.updateJsonForScope(id, TENANT_ID, ORG_ID, newData, null))
                .isInstanceOf(QuotaExceededException.class);

            verify(storageRepository, never()).save(any(StorageEntity.class));
            verify(breakdownService, never()).trackSizeChange(anyString(), anyString(), anyLong(), anyString());
            verify(quotaService, never()).updateOrganizationUsage(anyString());
        }
    }

    @Nested
    @DisplayName("File ↔ project assignment (org-scoped)")
    class FileProjectAssignment {

        @Test
        @DisplayName("assignFileToProjectForScope returns true when a file row was updated in scope")
        void assignReturnsTrueWhenRowUpdated() {
            UUID fileId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            when(storageRepository.updateFileProjectIdForScope(fileId, ORG_ID, projectId)).thenReturn(1);

            assertThat(storageService.assignFileToProjectForScope(fileId, ORG_ID, projectId)).isTrue();
            verify(storageRepository).updateFileProjectIdForScope(fileId, ORG_ID, projectId);
        }

        @Test
        @DisplayName("assignFileToProjectForScope returns false when no file row matched (cross-org / not a real file)")
        void assignReturnsFalseWhenNoRowMatched() {
            UUID fileId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            when(storageRepository.updateFileProjectIdForScope(fileId, ORG_ID, projectId)).thenReturn(0);

            assertThat(storageService.assignFileToProjectForScope(fileId, ORG_ID, projectId)).isFalse();
        }

        @Test
        @DisplayName("removeFileFromProjectForScope clears the assignment when the file belongs to that project")
        void removeClearsWhenFileBelongsToProject() {
            UUID fileId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(fileId);
            entity.setFileName("report.pdf");
            entity.setProjectId(projectId);
            when(storageRepository.findByIdAndOrganizationIdStrict(fileId, ORG_ID)).thenReturn(Optional.of(entity));
            when(storageRepository.updateFileProjectIdForScope(fileId, ORG_ID, null)).thenReturn(1);

            assertThat(storageService.removeFileFromProjectForScope(fileId, ORG_ID, projectId)).isTrue();
            verify(storageRepository).updateFileProjectIdForScope(fileId, ORG_ID, null);
        }

        @Test
        @DisplayName("removeFileFromProjectForScope is a no-op when the file is assigned to a DIFFERENT project (mismatch guard)")
        void removeNoOpWhenAssignedToDifferentProject() {
            UUID fileId = UUID.randomUUID();
            UUID requestedProject = UUID.randomUUID();
            UUID actualProject = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(fileId);
            entity.setFileName("report.pdf");
            entity.setProjectId(actualProject);
            when(storageRepository.findByIdAndOrganizationIdStrict(fileId, ORG_ID)).thenReturn(Optional.of(entity));

            assertThat(storageService.removeFileFromProjectForScope(fileId, ORG_ID, requestedProject)).isFalse();
            verify(storageRepository, never()).updateFileProjectIdForScope(eq(fileId), eq(ORG_ID), any());
        }

        @Test
        @DisplayName("removeFileFromProjectForScope is a no-op for a non-file storage row (no file name)")
        void removeNoOpForNonFileRow() {
            UUID fileId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            StorageEntity entity = createActiveEntity(fileId);
            entity.setFileName(null);
            entity.setProjectId(projectId);
            when(storageRepository.findByIdAndOrganizationIdStrict(fileId, ORG_ID)).thenReturn(Optional.of(entity));

            assertThat(storageService.removeFileFromProjectForScope(fileId, ORG_ID, projectId)).isFalse();
            verify(storageRepository, never()).updateFileProjectIdForScope(eq(fileId), eq(ORG_ID), any());
        }
    }

    // ========== Helper methods ==========

    private StorageEntity createActiveEntity(UUID id) {
        StorageEntity entity = new StorageEntity();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setStatus(StorageStatus.ACTIVE);
        entity.setStorageType("JSON");
        entity.setCreatedAt(Instant.now());
        entity.setAccessedAt(Instant.now());
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        entity.setSizeBytes(100);
        entity.setContentType("application/json");
        entity.setData("{\"key\":\"value\"}");
        return entity;
    }
}
