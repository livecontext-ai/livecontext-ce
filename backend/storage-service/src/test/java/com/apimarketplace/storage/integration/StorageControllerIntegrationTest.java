package com.apimarketplace.storage.integration;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.repository.StoredFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link com.apimarketplace.storage.web.StorageController}.
 *
 * <p>Uses a full Spring application context with H2 in-memory database.
 * File system operations use a temporary directory that is cleaned up after tests.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
@DisplayName("StorageController Integration Tests")
class StorageControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private com.apimarketplace.storage.service.StorageService storageService;

    @TempDir
    Path tempDir;

    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER_ID = 99L;

    @BeforeEach
    void setUp() {
        storedFileRepository.deleteAll();
        // Redirect file storage to temporary directory for tests
        ReflectionTestUtils.setField(storageService, "localStoragePath", tempDir.toString());
    }

    // ========== Upload Tests ==========

    @Nested
    @DisplayName("POST /api/storage/upload")
    class UploadTests {

        @Test
        @WithMockUser
        @DisplayName("should upload a text file and persist metadata in database")
        void shouldUploadTextFileAndPersistMetadata() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test-document.txt", "text/plain",
                    "Hello, this is a test document content.".getBytes()
            );

            mockMvc.perform(multipart("/api/storage/upload")
                            .file(file)
                            .param("userId", USER_ID.toString())
                            .param("description", "Integration test file")
                            .with(csrf())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.userId").value(USER_ID))
                    .andExpect(jsonPath("$.originalName").value("test-document.txt"))
                    .andExpect(jsonPath("$.contentType").value("text/plain"))
                    .andExpect(jsonPath("$.fileSize").value(39))
                    .andExpect(jsonPath("$.description").value("Integration test file"))
                    .andExpect(jsonPath("$.storageProvider").value("local"))
                    .andExpect(jsonPath("$.storageKey").isNotEmpty());

            // Verify the file was saved in the database
            List<StoredFile> userFiles = storedFileRepository.findByUserId(USER_ID);
            assertThat(userFiles).hasSize(1);
            assertThat(userFiles.get(0).getOriginalName()).isEqualTo("test-document.txt");
            assertThat(userFiles.get(0).getFileSize()).isEqualTo(39L);
        }

        @Test
        @WithMockUser
        @DisplayName("should upload a PDF file successfully")
        void shouldUploadPdfFile() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "report.pdf", "application/pdf",
                    "PDF content bytes".getBytes()
            );

            mockMvc.perform(multipart("/api/storage/upload")
                            .file(file)
                            .param("userId", USER_ID.toString())
                            .with(csrf())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contentType").value("application/pdf"))
                    .andExpect(jsonPath("$.originalName").value("report.pdf"));
        }

        @Test
        @WithMockUser
        @DisplayName("should upload file without description")
        void shouldUploadFileWithoutDescription() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "nodesc.txt", "text/plain",
                    "Content without description.".getBytes()
            );

            mockMvc.perform(multipart("/api/storage/upload")
                            .file(file)
                            .param("userId", USER_ID.toString())
                            .with(csrf())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").isNumber());
        }

        @Test
        @WithMockUser
        @DisplayName("should upload an image file successfully")
        void shouldUploadImageFile() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "photo.png", "image/png",
                    new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}
            );

            mockMvc.perform(multipart("/api/storage/upload")
                            .file(file)
                            .param("userId", USER_ID.toString())
                            .param("description", "Test image")
                            .with(csrf())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contentType").value("image/png"));
        }
    }

    // ========== Get File Info Tests ==========

    @Nested
    @DisplayName("GET /api/storage/files/{fileId}")
    class GetFileInfoTests {

        @Test
        @WithMockUser
        @DisplayName("should return file info when user is the owner")
        void shouldReturnFileInfoWhenOwner() throws Exception {
            StoredFile saved = persistTestFile(USER_ID, "info-test.txt", "text/plain", false);

            mockMvc.perform(get("/api/storage/files/{fileId}", saved.getId())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID)
                            .param("userId", USER_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(saved.getId()))
                    .andExpect(jsonPath("$.originalName").value("info-test.txt"))
                    .andExpect(jsonPath("$.userId").value(USER_ID));
        }

        @Test
        @WithMockUser
        @DisplayName("should return file info for public file even if not owner")
        void shouldReturnFileInfoForPublicFile() throws Exception {
            StoredFile saved = persistTestFile(USER_ID, "public-file.txt", "text/plain", true);

            mockMvc.perform(get("/api/storage/files/{fileId}", saved.getId())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID)
                            .param("userId", OTHER_USER_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(saved.getId()));
        }

        @Test
        @WithMockUser
        @DisplayName("should return 404 when private file requested by non-owner")
        void shouldReturn404WhenPrivateFileRequestedByNonOwner() throws Exception {
            StoredFile saved = persistTestFile(USER_ID, "private-file.txt", "text/plain", false);

            mockMvc.perform(get("/api/storage/files/{fileId}", saved.getId())
                            .header("X-User-ID", String.valueOf(OTHER_USER_ID))
                            .header("X-Organization-ID", "org-" + OTHER_USER_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser
        @DisplayName("should return 404 when file does not exist")
        void shouldReturn404WhenFileDoesNotExist() throws Exception {
            mockMvc.perform(get("/api/storage/files/{fileId}", 99999L)
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID)
                            .param("userId", USER_ID.toString()))
                    .andExpect(status().isNotFound());
        }
    }

    // ========== Get User Files Tests ==========

    @Nested
    @DisplayName("GET /api/storage/user/{userId}/files")
    class GetUserFilesTests {

        @Test
        @WithMockUser
        @DisplayName("should return all files for a user ordered by creation date")
        void shouldReturnAllFilesForUser() throws Exception {
            persistTestFile(USER_ID, "file1.txt", "text/plain", false);
            persistTestFile(USER_ID, "file2.pdf", "application/pdf", false);
            persistTestFile(OTHER_USER_ID, "other-user-file.txt", "text/plain", false);

            mockMvc.perform(get("/api/storage/user/{userId}/files", USER_ID)
                    .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[*].userId", everyItem(is(USER_ID.intValue()))));
        }

        @Test
        @WithMockUser
        @DisplayName("should return empty list when user has no files")
        void shouldReturnEmptyListWhenNoFiles() throws Exception {
            // Audit 2026-05-16 round-3: caller path-userId must match X-User-ID,
            // so empty-state coverage uses a fresh caller (FRESH_USER_ID) with no files.
            Long freshUser = 12345L;
            mockMvc.perform(get("/api/storage/user/{userId}/files", freshUser)
                    .header("X-User-ID", String.valueOf(freshUser))
                    .header("X-Organization-ID", "org-" + freshUser))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ========== Get Public Files Tests ==========

    @Nested
    @DisplayName("GET /api/storage/public/files")
    class GetPublicFilesTests {

        @Test
        @WithMockUser
        @DisplayName("should return only public files")
        void shouldReturnOnlyPublicFiles() throws Exception {
            persistTestFile(USER_ID, "private.txt", "text/plain", false);
            persistTestFile(USER_ID, "public1.txt", "text/plain", true);
            persistTestFile(OTHER_USER_ID, "public2.txt", "text/plain", true);

            mockMvc.perform(get("/api/storage/public/files"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[*].public", everyItem(is(true))));
        }
    }

    // ========== Update File Tests ==========

    @Nested
    @DisplayName("PUT /api/storage/files/{fileId}")
    class UpdateFileTests {

        @Test
        @WithMockUser
        @DisplayName("should update file description and visibility")
        void shouldUpdateFileDescriptionAndVisibility() throws Exception {
            StoredFile saved = persistTestFile(USER_ID, "update-test.txt", "text/plain", false);

            mockMvc.perform(put("/api/storage/files/{fileId}", saved.getId())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID)
                            .param("userId", USER_ID.toString())
                            .param("description", "Updated description")
                            .param("isPublic", "true")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value("Updated description"))
                    .andExpect(jsonPath("$.public").value(true));

            // Verify persistence
            StoredFile updated = storedFileRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.getDescription()).isEqualTo("Updated description");
            assertThat(updated.isPublic()).isTrue();
        }

        @Test
        @WithMockUser
        @DisplayName("should return 400 when updating file owned by another user")
        void shouldReturn400WhenUpdatingOtherUsersFile() throws Exception {
            // Audit 2026-05-16 round-3: identity is the X-User-ID header. The
            // OtherUser scenario now updates a USER_ID-owned file while
            // authenticated as OTHER_USER_ID.
            StoredFile saved = persistTestFile(USER_ID, "other-user.txt", "text/plain", false);

            mockMvc.perform(put("/api/storage/files/{fileId}", saved.getId())
                            .header("X-User-ID", String.valueOf(OTHER_USER_ID))
                            .header("X-Organization-ID", "org-" + OTHER_USER_ID)
                            .param("description", "Hacked")
                            .with(csrf()))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== Delete File Tests ==========

    @Nested
    @DisplayName("DELETE /api/storage/files/{fileId}")
    class DeleteFileTests {

        @Test
        @WithMockUser
        @DisplayName("should delete file and return success message")
        void shouldDeleteFileAndReturnSuccess() throws Exception {
            StoredFile saved = persistTestFile(USER_ID, "to-delete.txt", "text/plain", false);

            mockMvc.perform(delete("/api/storage/files/{fileId}", saved.getId())
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID)
                            .param("userId", USER_ID.toString())
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(containsString("supprime")));

            // Verify file was removed from database
            assertThat(storedFileRepository.findById(saved.getId())).isEmpty();
        }

        @Test
        @WithMockUser
        @DisplayName("should return 400 when deleting file owned by another user")
        void shouldReturn400WhenDeletingOtherUsersFile() throws Exception {
            // Audit 2026-05-16 round-3: identity from X-User-ID header.
            StoredFile saved = persistTestFile(USER_ID, "not-yours.txt", "text/plain", false);

            mockMvc.perform(delete("/api/storage/files/{fileId}", saved.getId())
                            .header("X-User-ID", String.valueOf(OTHER_USER_ID))
                            .header("X-Organization-ID", "org-" + OTHER_USER_ID)
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("Erreur")));
        }
    }

    // ========== Delete User Files Tests ==========

    @Nested
    @DisplayName("DELETE /api/storage/user/{userId}/files")
    class DeleteUserFilesTests {

        @Test
        @WithMockUser
        @DisplayName("should delete all files for a user")
        void shouldDeleteAllFilesForUser() throws Exception {
            persistTestFile(USER_ID, "file1.txt", "text/plain", false);
            persistTestFile(USER_ID, "file2.txt", "text/plain", false);
            persistTestFile(OTHER_USER_ID, "other.txt", "text/plain", false);

            mockMvc.perform(delete("/api/storage/user/{userId}/files", USER_ID)
                            .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").isNotEmpty());

            // Verify only target user's files were deleted
            assertThat(storedFileRepository.findByUserId(USER_ID)).isEmpty();
            assertThat(storedFileRepository.findByUserId(OTHER_USER_ID)).hasSize(1);
        }
    }

    // ========== Storage Usage Tests ==========

    @Nested
    @DisplayName("GET /api/storage/user/{userId}/usage")
    class StorageUsageTests {

        @Test
        @WithMockUser
        @DisplayName("should return storage usage for user with files")
        void shouldReturnStorageUsageForUser() throws Exception {
            persistTestFileWithSize(USER_ID, "big.txt", "text/plain", 5_242_880L);
            persistTestFileWithSize(USER_ID, "small.txt", "text/plain", 1024L);

            mockMvc.perform(get("/api/storage/user/{userId}/usage", USER_ID)
                    .header("X-User-ID", String.valueOf(USER_ID))
                            .header("X-Organization-ID", "org-" + USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(USER_ID))
                    .andExpect(jsonPath("$.usageBytes").value(5_242_880L + 1024L))
                    .andExpect(jsonPath("$.usageMB").isNumber())
                    .andExpect(jsonPath("$.usageGB").isNumber());
        }

        @Test
        @WithMockUser
        @DisplayName("should return zero usage for user with no files")
        void shouldReturnZeroUsageForUserWithNoFiles() throws Exception {
            // Audit 2026-05-16 round-3: caller path-userId must match X-User-ID.
            Long freshUser = 12345L;
            mockMvc.perform(get("/api/storage/user/{userId}/usage", freshUser)
                    .header("X-User-ID", String.valueOf(freshUser))
                    .header("X-Organization-ID", "org-" + freshUser))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usageBytes").value(0));
        }
    }

    // ========== Health Tests ==========

    @Nested
    @DisplayName("GET /api/storage/health")
    class HealthTests {

        @Test
        @WithMockUser
        @DisplayName("should return UP status")
        void shouldReturnUpStatus() throws Exception {
            mockMvc.perform(get("/api/storage/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.service").value("storage-service"));
        }
    }

    // ========== Helper Methods ==========

    private StoredFile persistTestFile(Long userId, String originalName, String contentType, boolean isPublic) {
        StoredFile file = new StoredFile(
                userId,
                "uuid-" + originalName,
                originalName,
                contentType,
                1024L,
                tempDir.resolve(userId.toString()).resolve("uuid-" + originalName).toString()
        );
        file.setStorageProvider("local");
        file.setStorageKey("uuid-" + originalName);
        file.setPublic(isPublic);
        file.setDescription("Test file: " + originalName);
        file.setOrganizationId("org-" + userId);  // V263 OrgScopedEntity
        return storedFileRepository.save(file);
    }

    private StoredFile persistTestFileWithSize(Long userId, String originalName, String contentType, long fileSize) {
        StoredFile file = new StoredFile(
                userId,
                "uuid-" + originalName,
                originalName,
                contentType,
                fileSize,
                tempDir.resolve(userId.toString()).resolve("uuid-" + originalName).toString()
        );
        file.setStorageProvider("local");
        file.setStorageKey("uuid-" + originalName);
        file.setOrganizationId("org-" + userId);  // V263 OrgScopedEntity
        return storedFileRepository.save(file);
    }
}
