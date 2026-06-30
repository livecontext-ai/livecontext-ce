package com.apimarketplace.monolith.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.storage.domain.FileRef;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.util.FileConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonolithFileController - CE /api/files bridge (upload + opaque raw serve)")
class MonolithFileControllerTest {

    @Mock private FileStorageService fileStorageService;
    @Mock private PublicFileUrlBuilder publicFileUrlBuilder;
    @Mock private StorageService storageService;
    @Mock private OrgAccessGuard orgAccessGuard;

    private MonolithFileController controller() {
        return new MonolithFileController(fileStorageService, publicFileUrlBuilder, storageService, orgAccessGuard);
    }

    private MultipartFile fileOf(long size, byte[] content) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        lenient().when(file.getSize()).thenReturn(size);
        lenient().when(file.isEmpty()).thenReturn(size == 0);
        lenient().when(file.getOriginalFilename()).thenReturn("report.txt");
        lenient().when(file.getContentType()).thenReturn("text/plain");
        lenient().when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        return file;
    }

    @Nested
    @DisplayName("generic-upload")
    class GenericUpload {

        @Test
        @DisplayName("Uploads, stamps the caller's workspace org-scope, and returns the canonical {url,id,...} shape")
        void uploadsAndReturnsCanonicalShape() throws IOException {
            byte[] content = "hello ce".getBytes(StandardCharsets.UTF_8);
            MultipartFile file = fileOf(content.length, content);

            // Capture the active org-scope binding seen by uploadGeneric so we prove the row is
            // persisted INSIDE runWithOrgScope (otherwise OrgScopedEntityListener would not stamp
            // organization_id and the explorer search would never find the file).
            AtomicReference<String> orgDuringUpload = new AtomicReference<>();
            AtomicReference<String> roleDuringUpload = new AtomicReference<>();
            when(fileStorageService.uploadGeneric(eq("42"), eq("files"), eq("report.txt"), eq("text/plain"), any(), anyLong()))
                    .thenAnswer(inv -> {
                        orgDuringUpload.set(TenantResolver.currentRequestOrganizationId());
                        roleDuringUpload.set(TenantResolver.currentRequestOrganizationRole());
                        return FileRef.of("42/general/files/abc_report.txt", "report.txt", "text/plain", content.length, "00000000-0000-0000-0000-000000000001");
                    });
            when(publicFileUrlBuilder.fileUrl("00000000-0000-0000-0000-000000000001", true))
                    .thenReturn("https://ce.example/api/proxy/files/by-id/00000000-0000-0000-0000-000000000001/raw?disposition=inline");

            ResponseEntity<?> response = controller().genericUpload(file, "files", null, "42", "org-9", "ADMIN");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("id")).isEqualTo("00000000-0000-0000-0000-000000000001");
            assertThat(body.get("url")).isEqualTo("https://ce.example/api/proxy/files/by-id/00000000-0000-0000-0000-000000000001/raw?disposition=inline");
            assertThat(body.get("storageKey")).isEqualTo("42/general/files/abc_report.txt");
            assertThat(body.get("fileName")).isEqualTo("report.txt");
            assertThat(body.get("mimeType")).isEqualTo("text/plain");
            assertThat(body.get("size")).isEqualTo((long) content.length);

            assertThat(orgDuringUpload.get()).isEqualTo("org-9");
            assertThat(roleDuringUpload.get()).isEqualTo("ADMIN");
            // Org-scope must not leak past the upload (ThreadLocal restored).
            assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
        }

        @Test
        @DisplayName("threads a non-null parentFolderId to the folder-aware (7-arg) uploadGeneric so the file lands in the current folder (V313)")
        void uploadIntoCurrentFolderThreadsParentFolderId() throws IOException {
            byte[] content = "in folder".getBytes(StandardCharsets.UTF_8);
            MultipartFile file = fileOf(content.length, content);
            UUID folderId = UUID.randomUUID();
            when(fileStorageService.uploadGeneric(eq("42"), eq("files"), eq("report.txt"), eq("text/plain"), any(), anyLong(), eq(folderId)))
                    .thenReturn(FileRef.of("42/general/files/abc_report.txt", "report.txt", "text/plain", content.length, "00000000-0000-0000-0000-000000000002"));
            when(publicFileUrlBuilder.fileUrl("00000000-0000-0000-0000-000000000002", true))
                    .thenReturn("https://ce.example/api/proxy/files/by-id/00000000-0000-0000-0000-000000000002/raw?disposition=inline");

            ResponseEntity<?> response = controller().genericUpload(file, "files", folderId.toString(), "42", "org-9", "ADMIN");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // The folder-aware 7-arg overload must be used (not the root 6-arg) once a folder id is supplied.
            verify(fileStorageService).uploadGeneric(eq("42"), eq("files"), eq("report.txt"), eq("text/plain"), any(), anyLong(), eq(folderId));
            verify(fileStorageService, never()).uploadGeneric(anyString(), anyString(), anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("a malformed parentFolderId falls back to the root (6-arg) uploadGeneric (V313)")
        void uploadWithBlankParentFolderIdFallsBackToRoot() throws IOException {
            byte[] content = "root".getBytes(StandardCharsets.UTF_8);
            MultipartFile file = fileOf(content.length, content);
            when(fileStorageService.uploadGeneric(eq("42"), eq("files"), eq("report.txt"), eq("text/plain"), any(), anyLong()))
                    .thenReturn(FileRef.of("42/general/files/abc_report.txt", "report.txt", "text/plain", content.length, "00000000-0000-0000-0000-000000000003"));
            when(publicFileUrlBuilder.fileUrl("00000000-0000-0000-0000-000000000003", true)).thenReturn("https://ce.example/u3");

            ResponseEntity<?> response = controller().genericUpload(file, "files", "not-a-uuid", "42", "org-9", "ADMIN");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // A malformed id is parsed to null → root upload, never the folder-aware overload.
            verify(fileStorageService).uploadGeneric(eq("42"), eq("files"), eq("report.txt"), eq("text/plain"), any(), anyLong());
            verify(fileStorageService, never()).uploadGeneric(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Rejects an empty file with 400 and never touches storage")
        void rejectsEmptyFile() throws IOException {
            MultipartFile file = fileOf(0, new byte[0]);

            ResponseEntity<?> response = controller().genericUpload(file, "files", null, "42", "org-9", "ADMIN");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(fileStorageService, never()).uploadGeneric(anyString(), anyString(), anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("Rejects a file above the 50MB cap with 413 and never touches storage")
        void rejectsTooLargeFile() throws IOException {
            MultipartFile file = fileOf(FileConstants.MAX_FILE_SIZE_BYTES + 1, new byte[0]);

            ResponseEntity<?> response = controller().genericUpload(file, "files", null, "42", "org-9", "ADMIN");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            verify(fileStorageService, never()).uploadGeneric(anyString(), anyString(), anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("Fails loud (500) when the stored row has no id - an unindexed file can't be served opaquely")
        void failsWhenStorageReturnsNoId() throws IOException {
            byte[] content = "x".getBytes(StandardCharsets.UTF_8);
            MultipartFile file = fileOf(content.length, content);
            when(fileStorageService.uploadGeneric(anyString(), anyString(), anyString(), anyString(), any(), anyLong()))
                    .thenReturn(FileRef.of("42/general/files/abc_report.txt", "report.txt", "text/plain", 1)); // null id

            ResponseEntity<?> response = controller().genericUpload(file, "files", null, "42", "org-9", "ADMIN");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            verify(publicFileUrlBuilder, never()).fileUrl(anyString(), eq(true));
        }

        @Test
        @DisplayName("Maps a storage quota breach to 413")
        void mapsQuotaExceededTo413() throws IOException {
            byte[] content = "x".getBytes(StandardCharsets.UTF_8);
            MultipartFile file = fileOf(content.length, content);
            when(fileStorageService.uploadGeneric(anyString(), anyString(), anyString(), anyString(), any(), anyLong()))
                    .thenThrow(new QuotaExceededException("over quota"));

            ResponseEntity<?> response = controller().genericUpload(file, "files", null, "42", "org-9", "ADMIN");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }
    }

    @Nested
    @DisplayName("by-id/{id}/raw")
    class RawById {

        private final UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        @Test
        @DisplayName("Serves an org-scoped S3 file's bytes with its mime type")
        void servesS3FileForWorkspaceMember() {
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getFileName()).thenReturn("doc.pdf");
            lenient().when(entity.getMimeType()).thenReturn("application/pdf");
            when(entity.getS3Key()).thenReturn("42/general/files/doc.pdf");
            lenient().when(entity.getOrganizationId()).thenReturn("org-9");
            when(storageService.getEntityByIdForScope(id, "42", "org-9")).thenReturn(Optional.of(entity));
            when(orgAccessGuard.canAccess("org-9", "42", "file", id.toString(), "ADMIN")).thenReturn(true);
            byte[] bytes = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
            when(fileStorageService.download("42/general/files/doc.pdf")).thenReturn(Optional.of(bytes));

            ResponseEntity<byte[]> response = controller().rawById(id, "inline", "42", "org-9", "ADMIN");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(bytes);
            assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("application/pdf");
            assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("inline").contains("doc.pdf");
        }

        @Test
        @DisplayName("Falls back to the owner fast-path when no active-org header is present")
        void ownerFastPathWithoutOrgHeader() {
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getFileName()).thenReturn("note.txt");
            lenient().when(entity.getMimeType()).thenReturn("text/plain");
            when(entity.getS3Key()).thenReturn(null);
            when(entity.getDataBinary()).thenReturn(null);
            when(entity.getDataText()).thenReturn("hello owner");
            lenient().when(entity.getOrganizationId()).thenReturn(null);
            when(storageService.getEntityById(id, "42")).thenReturn(Optional.of(entity));

            ResponseEntity<byte[]> response = controller().rawById(id, "inline", "42", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("hello owner");
        }

        @Test
        @DisplayName("Returns 404 (never 403) when the row is not visible to the caller")
        void notFoundWhenUnresolved() {
            when(storageService.getEntityByIdForScope(id, "42", "org-9")).thenReturn(Optional.empty());
            when(storageService.getEntityById(id, "42")).thenReturn(Optional.empty());

            ResponseEntity<byte[]> response = controller().rawById(id, "inline", "42", "org-9", "MEMBER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Returns 404 when the org-access guard denies the file")
        void notFoundWhenAccessDenied() {
            StorageEntity entity = mock(StorageEntity.class);
            when(entity.getFileName()).thenReturn("secret.txt");
            when(entity.getOrganizationId()).thenReturn("org-9");
            when(storageService.getEntityByIdForScope(id, "42", "org-9")).thenReturn(Optional.of(entity));
            when(orgAccessGuard.canAccess("org-9", "42", "file", id.toString(), "MEMBER")).thenReturn(false);

            ResponseEntity<byte[]> response = controller().rawById(id, "inline", "42", "org-9", "MEMBER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(fileStorageService, never()).download(anyString());
        }
    }
}
