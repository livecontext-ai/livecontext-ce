package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.service.file.StorageStreamingMetrics;
import com.apimarketplace.storage.util.MimeTypeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the upload write gap: {@code /upload} and {@code /generic-upload} checked
 * no role, so a read-only VIEWER could write files into the workspace and consume its
 * storage quota (the sibling {@code DELETE /api/files} already refused them).
 */
@DisplayName("FileController - uploads refuse the workspace VIEWER role")
class FileControllerUploadViewerGateTest {

    private FileStorageService fileStorageService;
    private TenantResolver tenantResolver;
    private HttpServletRequest request;
    private FileController controller;

    @BeforeEach
    void setUp() {
        fileStorageService = mock(FileStorageService.class);
        tenantResolver = mock(TenantResolver.class);
        request = mock(HttpServletRequest.class);
        controller = new FileController(fileStorageService, mock(MimeTypeRegistry.class), tenantResolver,
                new StorageStreamingMetrics(null),
                new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner(""),
                new PublicFileUrlBuilder("https://livecontext.ai"),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        when(tenantResolver.resolve(request)).thenReturn("58");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-f");
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
    }

    @Test
    @DisplayName("a VIEWER upload with workflow context is 403 and nothing is stored")
    void viewerUploadRefused() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn("VIEWER");

        ResponseEntity<?> response = controller.uploadFile(file(), "wf-1", "run-1", "upload", request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "VIEWER role cannot upload files"));
        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("a VIEWER generic upload (any case) is 403 and nothing is stored")
    void viewerGenericUploadRefused() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn(" viewer ");

        ResponseEntity<?> response = controller.genericUpload(file(), "files", request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("a MEMBER upload goes past the gate to the storage service")
    void memberUploadProceeds() {
        when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");

        ResponseEntity<?> response = controller.uploadFile(file(), "wf-1", "run-1", "upload", request);

        assertThat(response.getStatusCode().value()).isNotEqualTo(403);
        org.mockito.Mockito.verify(fileStorageService).upload(
                org.mockito.ArgumentMatchers.eq("58"), org.mockito.ArgumentMatchers.eq("wf-1"),
                org.mockito.ArgumentMatchers.eq("run-1"), org.mockito.ArgumentMatchers.eq("upload"),
                org.mockito.ArgumentMatchers.eq("a.txt"), org.mockito.ArgumentMatchers.eq("text/plain"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(5L));
    }
}
