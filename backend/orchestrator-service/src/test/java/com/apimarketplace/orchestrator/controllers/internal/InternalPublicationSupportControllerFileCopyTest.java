package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationSupportController.copyFile")
class InternalPublicationSupportControllerFileCopyTest {

    @Mock private FileStorageService fileStorageService;

    private InternalPublicationSupportController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalPublicationSupportController(
                null, null, null, null, null, null, fileStorageService, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("infers source tenant from sourcePath when copy request omits sourceTenantId")
    void copyFileInfersSourceTenantFromPath() {
        String sourcePath = "publisher-tenant/workflow/run/step/report.txt";
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        when(fileStorageService.download("publisher-tenant", sourcePath))
                .thenReturn(Optional.of(content));
        when(fileStorageService.upload(
                eq("_publications"), eq("publication-id"), eq("snapshot"), eq("snapshot-runout"),
                eq("report.txt"), eq("text/plain"), any(byte[].class)))
                .thenReturn(FileRef.of("_publications/publication-id/snapshot/report.txt", "report.txt", "text/plain", content.length));

        ResponseEntity<?> response = controller.copyFile(Map.of(
                "sourcePath", sourcePath,
                "tenantId", "_publications",
                "workflowId", "publication-id",
                "runId", "snapshot",
                "stepAlias", "snapshot-runout",
                "fileName", "report.txt",
                "mimeType", "text/plain"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("newPath", "_publications/publication-id/snapshot/report.txt");
        verify(fileStorageService).download("publisher-tenant", sourcePath);
    }

    @Test
    @DisplayName("returns the new storage-row id so the cloned FileRef can adopt it (opaque by-id URL resolves in the target tenant)")
    void copyFileReturnsNewIdFromUpload() {
        String sourcePath = "publisher-tenant/workflow/run/step/report.txt";
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        when(fileStorageService.download("publisher-tenant", sourcePath))
                .thenReturn(Optional.of(content));
        when(fileStorageService.upload(
                eq("_publications"), eq("publication-id"), eq("snapshot"), eq("snapshot-runout"),
                eq("report.txt"), eq("text/plain"), any(byte[].class)))
                .thenReturn(FileRef.of("_publications/publication-id/snapshot/report.txt",
                        "report.txt", "text/plain", content.length, "new-storage-id-123"));

        ResponseEntity<?> response = controller.copyFile(Map.of(
                "sourcePath", sourcePath,
                "tenantId", "_publications",
                "workflowId", "publication-id",
                "runId", "snapshot",
                "stepAlias", "snapshot-runout",
                "fileName", "report.txt",
                "mimeType", "text/plain"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("newPath", "_publications/publication-id/snapshot/report.txt");
        assertThat(body).containsEntry("newId", "new-storage-id-123");
    }

    @Test
    @DisplayName("omits newId when the upload produced an id-less FileRef (caller then drops the stale source id rather than keeping a wrong one)")
    void copyFileOmitsNewIdWhenUploadHasNoId() {
        String sourcePath = "publisher-tenant/workflow/run/step/report.txt";
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        when(fileStorageService.download("publisher-tenant", sourcePath))
                .thenReturn(Optional.of(content));
        // 4-arg of(...) yields id == null (legacy / local-FS storage backend).
        when(fileStorageService.upload(
                eq("_publications"), eq("publication-id"), eq("snapshot"), eq("snapshot-runout"),
                eq("report.txt"), eq("text/plain"), any(byte[].class)))
                .thenReturn(FileRef.of("_publications/publication-id/snapshot/report.txt",
                        "report.txt", "text/plain", content.length));

        ResponseEntity<?> response = controller.copyFile(Map.of(
                "sourcePath", sourcePath,
                "tenantId", "_publications",
                "workflowId", "publication-id",
                "runId", "snapshot",
                "stepAlias", "snapshot-runout",
                "fileName", "report.txt",
                "mimeType", "text/plain"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("newPath", "_publications/publication-id/snapshot/report.txt");
        assertThat(body).doesNotContainKey("newId");
    }
}
