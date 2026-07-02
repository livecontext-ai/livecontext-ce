package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression (org byte-layer sharing): an org member could SEE a teammate's
 * file metadata (rows are org-scoped) but presign/download presented the
 * CALLER's tenant while the internal storage route authorizes by KEY-OWNER
 * prefix - a silent 403 → broken preview / file missing from the zip. The
 * explorer now presents the entity OWNER's tenant on every byte/presign path
 * (the entity was already authorized upstream by getEntityByIdForScope).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageExplorerController - owner-tenant presign/download for org-shared files")
class StorageExplorerControllerOwnerTenantBytesTest {

    private static final String CALLER = "member-caller";
    private static final String OWNER = "teammate-owner";
    private static final String ORG = "org-1";

    @Mock private StorageExplorerService explorerService;
    @Mock private StorageService storageService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private FileStorageService fileStorageService;

    private StorageExplorerController controller;
    private UUID id;
    private StorageEntity entity;

    @BeforeEach
    void setUp() {
        controller = new StorageExplorerController(
                explorerService, storageService, orgAccessGuard, workflowRepository);
        ReflectionTestUtils.setField(controller, "fileStorageService", fileStorageService);

        id = UUID.randomUUID();
        entity = new StorageEntity();
        entity.setId(id);
        entity.setTenantId(OWNER);
        entity.setOrganizationId(ORG);
        entity.setStorageType("S3_FILE");
        entity.setS3Key(OWNER + "/wf/run/file.png");
        entity.setFileName("file.png");

        when(storageService.getEntityByIdForScope(id, CALLER, ORG)).thenReturn(Optional.of(entity));
        lenient().when(orgAccessGuard.canAccess(eq(ORG), eq(CALLER), anyString(), eq(id.toString()), any()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("download 302: presign is generated with the entity OWNER's tenant, not the caller's")
    void downloadPresignsWithOwnerTenant() {
        when(fileStorageService.generateDownloadUrl(OWNER, entity.getS3Key()))
                .thenReturn("https://s3/presigned");

        ResponseEntity<?> response = controller.download(CALLER, ORG, "MEMBER", id);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("https://s3/presigned");
    }

    @Test
    @DisplayName("preview: downloadUrl is presigned with the entity OWNER's tenant")
    void previewPresignsWithOwnerTenant() {
        when(fileStorageService.generateDownloadUrl(OWNER, entity.getS3Key()))
                .thenReturn("https://s3/presigned-preview");

        ResponseEntity<Map<String, Object>> response = controller.preview(CALLER, ORG, "MEMBER", id);

        assertThat(response.getBody()).containsEntry("downloadUrl", "https://s3/presigned-preview");
    }

    @Test
    @DisplayName("download-zip: S3 bytes are fetched with the entity OWNER's tenant (teammate file no longer silently missing)")
    void zipFetchesBytesWithOwnerTenant() throws Exception {
        when(fileStorageService.download(OWNER, entity.getS3Key()))
                .thenReturn(Optional.of("png-bytes".getBytes()));

        ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> response =
                controller.downloadZip(CALLER, ORG, "MEMBER", Map.of("ids", java.util.List.of(id.toString())));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // Execute the streaming body: pre-fix this fetched with the CALLER's
        // tenant, 403'd internally, and the entry was silently skipped.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        response.getBody().writeTo(out);
        org.mockito.Mockito.verify(fileStorageService).download(OWNER, entity.getS3Key());
        // The zip actually contains the teammate-owned entry.
        try (java.util.zip.ZipInputStream zip =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            java.util.zip.ZipEntry zipEntry = zip.getNextEntry();
            assertThat(zipEntry).isNotNull();
            assertThat(zipEntry.getName()).isEqualTo("file.png");
        }
    }
}
