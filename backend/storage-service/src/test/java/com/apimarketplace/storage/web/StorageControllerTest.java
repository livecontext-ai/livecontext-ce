package com.apimarketplace.storage.web;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StorageController Tests")
@ExtendWith(MockitoExtension.class)
class StorageControllerTest {

    @Mock
    private StorageService storageService;

    @InjectMocks
    private StorageController storageController;

    private static final Long USER_ID = 42L;
    private static final String USER_ID_HEADER = "42";
    private static final Long FILE_ID = 1L;
    private static final String ORG_ID = "00000000-0000-0000-0000-000000000001";

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Nested
    @DisplayName("POST /api/storage/upload")
    class UploadFileTests {

        @Test
        @DisplayName("should return 200 OK when upload is successful")
        void shouldReturn200WhenUploadSuccessful() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            StoredFile storedFile = createStoredFile(FILE_ID, USER_ID);
            when(storageService.storeFile(eq(file), eq(USER_ID), anyString(), eq("Test description"))).thenReturn(storedFile);

            ResponseEntity<StoredFile> response = storageController.uploadFile(file, "Test description", USER_ID_HEADER, "00000000-0000-0000-0000-000000000001");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(FILE_ID);
        }

        @Test
        @DisplayName("should return 400 BAD_REQUEST when IOException occurs")
        void shouldReturn400WhenIOException() throws IOException {
            MultipartFile file = mock(MultipartFile.class);
            when(storageService.storeFile(eq(file), eq(USER_ID), anyString(), isNull())).thenThrow(new IOException("Disk error"));

            ResponseEntity<StoredFile> response = storageController.uploadFile(file, null, USER_ID_HEADER, "00000000-0000-0000-0000-000000000001");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("GET /api/storage/files/{fileId}")
    class GetFileInfoTests {

        @Test
        @DisplayName("should return 200 OK with file info when found")
        void shouldReturn200WhenFileFound() {
            StoredFile storedFile = createStoredFile(FILE_ID, USER_ID);
            when(storageService.getFile(FILE_ID, USER_ID)).thenReturn(Optional.of(storedFile));

            ResponseEntity<StoredFile> response = storageController.getFileInfo(FILE_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(FILE_ID);
        }

        @Test
        @DisplayName("should return 404 NOT_FOUND when file not found")
        void shouldReturn404WhenFileNotFound() {
            when(storageService.getFile(FILE_ID, USER_ID)).thenReturn(Optional.empty());

            ResponseEntity<StoredFile> response = storageController.getFileInfo(FILE_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /api/storage/files/{fileId}/download")
    class DownloadFileTests {

        @Test
        @DisplayName("should return 404 when file not found")
        void shouldReturn404WhenFileNotFound() {
            when(storageService.getFile(FILE_ID, USER_ID)).thenReturn(Optional.empty());

            ResponseEntity<Resource> response = storageController.downloadFile(FILE_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /api/storage/files/{fileId}/view")
    class ViewFileTests {

        @Test
        @DisplayName("should return 404 when file not found")
        void shouldReturn404WhenFileNotFound() {
            when(storageService.getFile(FILE_ID, USER_ID)).thenReturn(Optional.empty());

            ResponseEntity<Resource> response = storageController.viewFile(FILE_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /api/storage/user/{userId}/files")
    class GetUserFilesTests {

        @Test
        @DisplayName("should return 200 OK with user files")
        void shouldReturn200WithUserFiles() {
            List<StoredFile> files = List.of(
                createStoredFile(1L, USER_ID),
                createStoredFile(2L, USER_ID)
            );
            when(storageService.getUserFiles(USER_ID)).thenReturn(files);

            ResponseEntity<List<StoredFile>> response = storageController.getUserFiles(USER_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
        }

        @Test
        @DisplayName("scopes file list to the current workspace header")
        void scopesFileListToCurrentWorkspaceHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Organization-ID", ORG_ID);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            List<StoredFile> files = List.of(createStoredFile(1L, USER_ID));
            when(storageService.getUserFiles(USER_ID, ORG_ID)).thenReturn(files);

            ResponseEntity<List<StoredFile>> response = storageController.getUserFiles(USER_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            verify(storageService).getUserFiles(USER_ID, ORG_ID);
            verify(storageService, never()).getUserFiles(USER_ID);
        }

        @Test
        @DisplayName("should return 200 OK with empty list when user has no files")
        void shouldReturn200WithEmptyList() {
            when(storageService.getUserFiles(USER_ID)).thenReturn(Collections.emptyList());

            ResponseEntity<List<StoredFile>> response = storageController.getUserFiles(USER_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("GET /api/storage/public/files")
    class GetPublicFilesTests {

        @Test
        @DisplayName("should return 200 OK with public files")
        void shouldReturn200WithPublicFiles() {
            List<StoredFile> files = List.of(
                createStoredFile(1L, 10L),
                createStoredFile(2L, 20L)
            );
            when(storageService.getPublicFiles()).thenReturn(files);

            ResponseEntity<List<StoredFile>> response = storageController.getPublicFiles();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("PUT /api/storage/files/{fileId}")
    class UpdateFileTests {

        @Test
        @DisplayName("should return 200 OK when update is successful")
        void shouldReturn200WhenUpdateSuccessful() {
            StoredFile updatedFile = createStoredFile(FILE_ID, USER_ID);
            updatedFile.setDescription("Updated");
            updatedFile.setPublic(true);
            when(storageService.updateFile(FILE_ID, USER_ID, "Updated", true)).thenReturn(updatedFile);

            ResponseEntity<StoredFile> response = storageController.updateFile(FILE_ID, "Updated", true, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getDescription()).isEqualTo("Updated");
        }

        @Test
        @DisplayName("should return 400 BAD_REQUEST when update fails")
        void shouldReturn400WhenUpdateFails() {
            when(storageService.updateFile(FILE_ID, USER_ID, "", false))
                .thenThrow(new RuntimeException("File not found"));

            ResponseEntity<StoredFile> response = storageController.updateFile(FILE_ID, null, null, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should default to empty description and false isPublic when params are null")
        void shouldDefaultWhenParamsNull() {
            StoredFile updatedFile = createStoredFile(FILE_ID, USER_ID);
            when(storageService.updateFile(FILE_ID, USER_ID, "", false)).thenReturn(updatedFile);

            ResponseEntity<StoredFile> response = storageController.updateFile(FILE_ID, null, null, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(storageService).updateFile(FILE_ID, USER_ID, "", false);
        }
    }

    @Nested
    @DisplayName("DELETE /api/storage/files/{fileId}")
    class DeleteFileTests {

        @Test
        @DisplayName("should return 200 OK with success message when deleted")
        void shouldReturn200WithSuccessMessage() throws Exception {
            doNothing().when(storageService).deleteFile(FILE_ID, USER_ID);

            ResponseEntity<Map<String, String>> response = storageController.deleteFile(FILE_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("message");
            assertThat(response.getBody().get("message")).contains("supprime");
        }

        @Test
        @DisplayName("should return 400 BAD_REQUEST when deletion fails")
        void shouldReturn400WhenDeletionFails() throws Exception {
            doThrow(new RuntimeException("Delete failed")).when(storageService).deleteFile(FILE_ID, USER_ID);

            ResponseEntity<Map<String, String>> response = storageController.deleteFile(FILE_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("message");
            assertThat(response.getBody().get("message")).contains("Erreur");
        }
    }

    @Nested
    @DisplayName("DELETE /api/storage/user/{userId}/files")
    class DeleteUserFilesTests {

        @Test
        @DisplayName("should return 200 OK when user files are deleted")
        void shouldReturn200WhenUserFilesDeleted() throws Exception {
            doNothing().when(storageService).deleteUserFiles(USER_ID);

            ResponseEntity<Map<String, String>> response = storageController.deleteUserFiles(USER_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("message");
        }

        @Test
        @DisplayName("should return 400 BAD_REQUEST when deletion fails")
        void shouldReturn400WhenDeletionFails() throws Exception {
            doThrow(new IOException("Error")).when(storageService).deleteUserFiles(USER_ID);

            ResponseEntity<Map<String, String>> response = storageController.deleteUserFiles(USER_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("message");
        }
    }

    @Nested
    @DisplayName("GET /api/storage/user/{userId}/usage")
    class GetStorageUsageTests {

        @Test
        @DisplayName("should return 200 OK with usage info")
        void shouldReturn200WithUsageInfo() {
            when(storageService.getStorageUsage(USER_ID)).thenReturn(10_485_760L); // 10MB

            ResponseEntity<Map<String, Object>> response = storageController.getStorageUsage(USER_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("userId")).isEqualTo(USER_ID);
            assertThat(body.get("usageBytes")).isEqualTo(10_485_760L);
            assertThat(body.get("usageMB")).isEqualTo(10L);
        }

        @Test
        @DisplayName("should return zero usage for user with no files")
        void shouldReturnZeroUsage() {
            when(storageService.getStorageUsage(USER_ID)).thenReturn(0L);

            ResponseEntity<Map<String, Object>> response = storageController.getStorageUsage(USER_ID, USER_ID_HEADER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("usageBytes")).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("GET /api/storage/health")
    class HealthTests {

        @Test
        @DisplayName("should return UP status")
        void shouldReturnUpStatus() {
            ResponseEntity<Map<String, String>> response = storageController.health();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, String> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("status")).isEqualTo("UP");
            assertThat(body.get("service")).isEqualTo("storage-service");
        }
    }

    // ========== Helper methods ==========

    private StoredFile createStoredFile(Long id, Long userId) {
        StoredFile storedFile = new StoredFile(userId, "file-" + id + ".txt", "original-" + id + ".txt",
            "text/plain", 1024L, "/uploads/" + userId + "/file-" + id + ".txt");
        storedFile.setId(id);
        storedFile.setStorageProvider("local");
        return storedFile;
    }
}
