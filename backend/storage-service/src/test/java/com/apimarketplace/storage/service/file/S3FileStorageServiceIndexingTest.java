package com.apimarketplace.storage.service.file;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract guard for the v3.0 reconciliation: every {@code uploadGeneric}
 * call must register a row in {@code storage.storage} so the Files tab /
 * Storage Explorer can surface it. Before v3.0 generic uploads landed in
 * MinIO invisible to the UI, which broke the chat-card → Files-panel
 * navigation path the agent advertises.
 *
 * <p>We exercise the package-private {@code indexGenericUpload} entrypoint
 * to bypass the {@code @PostConstruct} S3 wiring - only the indexing arg
 * forwarding is under test here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3FileStorageService - indexGenericUpload")
class S3FileStorageServiceIndexingTest {

    @Mock StorageService storageIndexService;
    @Mock QuotaOperations quotaService;
    @Mock S3Client s3Client;

    @Test
    @DisplayName("forwards tenantId / key / fileName / mimeType / size to saveS3FileIndex with null workflow scope")
    void indexesUploadWithNullWorkflowScope() {
        // Generic uploads (chat attachments, catalog-binary dehydrator output,
        // standalone uploads) are NOT bound to a workflow run - workflowId,
        // runId and stepKey are deliberately null. Tenant scoping is what
        // makes the entry visible in the user's Files tab.
        S3FileStorageService svc = new S3FileStorageService();
        ReflectionTestUtils.setField(svc, "storageIndexService", storageIndexService);
        when(storageIndexService.saveS3FileIndex(
                eq("tenant-42"), isNull(), isNull(), isNull(),
                eq("tenant-42/general/catalog-binary/abc_img.png"),
                eq("img.png"), eq("image/png"), eq(12_345L), eq(0)))
                .thenReturn(UUID.randomUUID());

        svc.indexGenericUpload(
                "tenant-42", "catalog-binary",
                "tenant-42/general/catalog-binary/abc_img.png",
                "img.png", "image/png", 12_345L);

        verify(storageIndexService).saveS3FileIndex(
                eq("tenant-42"), isNull(), isNull(), isNull(),
                eq("tenant-42/general/catalog-binary/abc_img.png"),
                eq("img.png"), eq("image/png"), eq(12_345L), eq(0));
    }

    @Test
    @DisplayName("forwards a non-null parentFolderId to the folder-aware saveS3FileIndex so the upload lands in the current folder (V313)")
    void indexesUploadWithParentFolderId() {
        UUID folderId = UUID.randomUUID();
        S3FileStorageService svc = new S3FileStorageService();
        ReflectionTestUtils.setField(svc, "storageIndexService", storageIndexService);
        when(storageIndexService.saveS3FileIndex(
                eq("tenant-42"), isNull(), isNull(), isNull(),
                eq("tenant-42/general/files/abc_doc.pdf"),
                eq("doc.pdf"), eq("application/pdf"), eq(2_048L),
                eq(0), eq(0), isNull(), isNull(), eq(folderId)))
                .thenReturn(UUID.randomUUID());

        svc.indexGenericUpload(
                "tenant-42", "files",
                "tenant-42/general/files/abc_doc.pdf",
                "doc.pdf", "application/pdf", 2_048L, folderId);

        verify(storageIndexService).saveS3FileIndex(
                eq("tenant-42"), isNull(), isNull(), isNull(),
                eq("tenant-42/general/files/abc_doc.pdf"),
                eq("doc.pdf"), eq("application/pdf"), eq(2_048L),
                eq(0), eq(0), isNull(), isNull(), eq(folderId));
    }

    @Test
    @DisplayName("storageIndexService=null (test profiles without common-storage-service) → no-op, no NPE")
    void absentIndexerIsNoOp() {
        S3FileStorageService svc = new S3FileStorageService();
        // Leave storageIndexService null - common-storage-service jar absent.
        // The upload itself still succeeded; the indexing miss is logged but
        // must NOT bubble up an NPE that would leak an orphan S3 object.
        svc.indexGenericUpload("t", "cat", "k", "f.png", "image/png", 1L);
        // Implicit: no exception thrown.
    }

    @Test
    @DisplayName("uploadGeneric refuses before S3 put when current workspace hard quota is reached")
    void uploadGenericRefusesBeforeS3PutWhenWorkspaceQuotaReached() {
        S3FileStorageService svc = new S3FileStorageService();
        ReflectionTestUtils.setField(svc, "quotaService", quotaService);
        ReflectionTestUtils.setField(svc, "s3Client", s3Client);
        when(quotaService.checkOrganizationQuota("org-42", 5L))
                .thenReturn(QuotaStatus.HARD_LIMIT_REACHED);

        TenantResolver.runWithOrgScope("org-42", () ->
            assertThatThrownBy(() -> svc.uploadGeneric(
                    "tenant-42", "general", "file.txt", "text/plain",
                    new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 5L))
                .isInstanceOf(QuotaExceededException.class)
        );

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("indexer throws → swallowed (upload already succeeded; orphan-cleanup sweep is the safety net)")
    void indexerExceptionIsSwallowed() {
        S3FileStorageService svc = new S3FileStorageService();
        ReflectionTestUtils.setField(svc, "storageIndexService", storageIndexService);
        when(storageIndexService.saveS3FileIndex(
                eq("t"), isNull(), isNull(), isNull(),
                eq("k"), eq("f.png"), eq("image/png"), eq(1L), eq(0)))
                .thenThrow(new RuntimeException("DB unavailable"));

        // Must not throw - indexing failure is non-fatal at the upload layer.
        // (If it threw, the surrounding uploadGeneric would have already
        // succeeded in pushing bytes to S3, leaving the user's FileRef pointing
        // at storage but invisible to the Files tab. Worse than no row at all.)
        svc.indexGenericUpload("t", "cat", "k", "f.png", "image/png", 1L);

        // The throwing call was actually issued - confirms the swallow happened
        // on the real code path, not because the indexer was bypassed.
        verify(storageIndexService).saveS3FileIndex(
                eq("t"), isNull(), isNull(), isNull(),
                eq("k"), eq("f.png"), eq("image/png"), eq(1L), eq(0));
    }

    @Test
    @DisplayName("deleteRunFiles follows the continuation token so a run with >1000 files is fully deleted (not just the first 1000)")
    void deleteRunFilesPaginatesBeyondFirstPage() {
        S3FileStorageService svc = new S3FileStorageService();
        ReflectionTestUtils.setField(svc, "s3Client", s3Client);
        ReflectionTestUtils.setField(svc, "bucket", "test-bucket");

        // Page 1 is truncated (simulating the 1000-key cap) and hands back a continuation token;
        // page 2 is the tail. Pre-fix, only page 1 was deleted and the tail was orphaned forever.
        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("t/w/r/a").build(),
                          S3Object.builder().key("t/w/r/b").build())
                .isTruncated(true).nextContinuationToken("TOKEN-2").build();
        ListObjectsV2Response page2 = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("t/w/r/c").build())
                .isTruncated(false).build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page1).thenReturn(page2);
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());

        int deleted = svc.deleteRunFiles("t", "w", "r");

        assertThat(deleted).isEqualTo(3); // both pages counted, not just the first 2
        ArgumentCaptor<ListObjectsV2Request> listCap = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, times(2)).listObjectsV2(listCap.capture());
        assertThat(listCap.getAllValues().get(0).continuationToken()).isNull();
        assertThat(listCap.getAllValues().get(1).continuationToken()).isEqualTo("TOKEN-2");
        verify(s3Client, times(2)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    @DisplayName("deleteRunFiles with no matching objects returns 0 and issues no delete")
    void deleteRunFilesEmptyReturnsZero() {
        S3FileStorageService svc = new S3FileStorageService();
        ReflectionTestUtils.setField(svc, "s3Client", s3Client);
        ReflectionTestUtils.setField(svc, "bucket", "test-bucket");
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build());

        int deleted = svc.deleteRunFiles("t", "w", "r");

        assertThat(deleted).isZero();
        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    @DisplayName("indexer quota exception is rethrown so the upload call cannot report success")
    void indexerQuotaExceptionIsRethrown() {
        S3FileStorageService svc = new S3FileStorageService();
        ReflectionTestUtils.setField(svc, "storageIndexService", storageIndexService);
        ReflectionTestUtils.setField(svc, "s3Client", s3Client);
        when(storageIndexService.saveS3FileIndex(
                eq("t"), isNull(), isNull(), isNull(),
                eq("k"), eq("f.png"), eq("image/png"), eq(1L), eq(0)))
                .thenThrow(new QuotaExceededException("Storage quota hard limit reached", "t"));

        assertThatThrownBy(() -> svc.indexGenericUpload("t", "cat", "k", "f.png", "image/png", 1L))
                .isInstanceOf(QuotaExceededException.class);

        verify(storageIndexService).saveS3FileIndex(
                eq("t"), isNull(), isNull(), isNull(),
                eq("k"), eq("f.png"), eq("image/png"), eq(1L), eq(0));
    }
}
