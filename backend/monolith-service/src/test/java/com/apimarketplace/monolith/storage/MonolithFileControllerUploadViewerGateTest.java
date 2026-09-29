package com.apimarketplace.monolith.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.util.MimeTypeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CE mirror of {@code FileControllerUploadViewerGateTest}: the monolith mounts its own
 * copy of {@code /api/files/upload} and {@code /api/files/generic-upload}, which had no
 * role check either, so a CE VIEWER could write files and consume the workspace quota.
 */
@DisplayName("MonolithFileController - CE uploads refuse the workspace VIEWER role")
class MonolithFileControllerUploadViewerGateTest {

    private FileStorageService fileStorageService;
    private MonolithFileController controller;

    @BeforeEach
    void setUp() {
        fileStorageService = mock(FileStorageService.class);
        controller = new MonolithFileController(fileStorageService, mock(PublicFileUrlBuilder.class),
                mock(StorageService.class), mock(OrgAccessGuard.class), new ShowcaseUrlSigner(""),
                mock(MimeTypeRegistry.class),
                new com.apimarketplace.storage.service.file.StorageStreamingMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
    }

    @Test
    @DisplayName("a VIEWER upload and generic upload are 403 and nothing is stored")
    void viewerUploadsRefused() {
        ResponseEntity<?> upload = controller.uploadFile(file(), "wf-1", "run-1", "upload", "7", "org-ce", "VIEWER");
        ResponseEntity<?> generic = controller.genericUpload(file(), "files", null, "7", "org-ce", "viewer");

        assertThat(upload.getStatusCode().value()).isEqualTo(403);
        assertThat(upload.getBody()).isEqualTo(Map.of("error", "VIEWER role cannot upload files"));
        assertThat(generic.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("a MEMBER upload goes past the gate to the storage service")
    void memberUploadProceeds() {
        ResponseEntity<?> upload = controller.uploadFile(file(), "wf-1", "run-1", "upload", "7", "org-ce", "MEMBER");

        assertThat(upload.getStatusCode().value()).isNotEqualTo(403);
        org.mockito.Mockito.verify(fileStorageService).upload(
                org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.eq("wf-1"),
                org.mockito.ArgumentMatchers.eq("run-1"), org.mockito.ArgumentMatchers.eq("upload"),
                org.mockito.ArgumentMatchers.eq("a.txt"), org.mockito.ArgumentMatchers.eq("text/plain"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(5L));
    }
}
