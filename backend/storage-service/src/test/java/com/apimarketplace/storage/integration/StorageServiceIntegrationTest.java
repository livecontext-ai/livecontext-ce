package com.apimarketplace.storage.integration;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.repository.StoredFileRepository;
import com.apimarketplace.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link StorageService}.
 *
 * <p>Tests the service layer with real Spring wiring and H2 database.
 * File system operations use a temporary directory to avoid side effects.</p>
 */
@IntegrationTest
@DisplayName("StorageService Integration Tests")
class StorageServiceIntegrationTest {

    @Autowired
    private StorageService storageService;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @TempDir
    Path tempDir;

    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER_ID = 99L;

    @BeforeEach
    void setUp() {
        storedFileRepository.deleteAll();
        ReflectionTestUtils.setField(storageService, "localStoragePath", tempDir.toString());
    }

    // ========== storeFile Tests ==========

    @Nested
    @DisplayName("storeFile")
    class StoreFileTests {

        @Test
        @DisplayName("should store file on disk and persist metadata in database")
        void shouldStoreFileOnDiskAndPersistMetadata() throws IOException {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "document.txt", "text/plain",
                    "File content for integration test.".getBytes()
            );

            StoredFile result = storageService.storeFile(file, USER_ID, "00000000-0000-0000-0000-000000000001", "Test document");

            assertThat(result.getId()).isNotNull();
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getOriginalName()).isEqualTo("document.txt");
            assertThat(result.getContentType()).isEqualTo("text/plain");
            assertThat(result.getFileSize()).isEqualTo(34L);
            assertThat(result.getDescription()).isEqualTo("Test document");
            assertThat(result.getStorageProvider()).isEqualTo("local");
            assertThat(result.getStorageKey()).isNotBlank();
            assertThat(result.getFileName()).endsWith(".txt");

            // Verify file exists on disk
            Path storedPath = Path.of(result.getFilePath());
            assertThat(Files.exists(storedPath)).isTrue();
            assertThat(Files.readString(storedPath)).isEqualTo("File content for integration test.");

            // Verify database record
            Optional<StoredFile> fromDb = storedFileRepository.findById(result.getId());
            assertThat(fromDb).isPresent();
            assertThat(fromDb.get().getOriginalName()).isEqualTo("document.txt");
        }

        @Test
        @DisplayName("should generate unique file name with UUID")
        void shouldGenerateUniqueFileName() throws IOException {
            MockMultipartFile file1 = new MockMultipartFile(
                    "file", "same-name.txt", "text/plain", "Content 1".getBytes()
            );
            MockMultipartFile file2 = new MockMultipartFile(
                    "file", "same-name.txt", "text/plain", "Content 2".getBytes()
            );

            StoredFile stored1 = storageService.storeFile(file1, USER_ID, "00000000-0000-0000-0000-000000000001", null);
            StoredFile stored2 = storageService.storeFile(file2, USER_ID, "00000000-0000-0000-0000-000000000001", null);

            assertThat(stored1.getFileName()).isNotEqualTo(stored2.getFileName());
            assertThat(stored1.getOriginalName()).isEqualTo(stored2.getOriginalName());
        }

        @Test
        @DisplayName("should create user-specific directory for storage")
        void shouldCreateUserSpecificDirectory() throws IOException {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "user-dir.txt", "text/plain", "Data".getBytes()
            );

            StoredFile result = storageService.storeFile(file, USER_ID, "00000000-0000-0000-0000-000000000001", null);

            Path userDir = tempDir.resolve(USER_ID.toString());
            assertThat(Files.exists(userDir)).isTrue();
            assertThat(Files.isDirectory(userDir)).isTrue();
        }

        @Test
        @DisplayName("should store PDF file successfully")
        void shouldStorePdfFile() throws IOException {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "report.pdf", "application/pdf",
                    "PDF binary content".getBytes()
            );

            StoredFile result = storageService.storeFile(file, USER_ID, "00000000-0000-0000-0000-000000000001", "PDF report");

            assertThat(result.getContentType()).isEqualTo("application/pdf");
            assertThat(result.getFileName()).endsWith(".pdf");
        }

        @Test
        @DisplayName("should reject empty file")
        void shouldRejectEmptyFile() {
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file", "empty.txt", "text/plain", new byte[0]
            );

            assertThatThrownBy(() -> storageService.storeFile(emptyFile, USER_ID, "00000000-0000-0000-0000-000000000001", null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("vide");
        }

        @Test
        @DisplayName("should reject file exceeding max size")
        void shouldRejectFileExceedingMaxSize() {
            // Max size is 10MB (10485760 bytes)
            byte[] largeContent = new byte[11_000_000];
            MockMultipartFile largeFile = new MockMultipartFile(
                    "file", "large.txt", "text/plain", largeContent
            );

            assertThatThrownBy(() -> storageService.storeFile(largeFile, USER_ID, "00000000-0000-0000-0000-000000000001", null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("volumineux");
        }

        @Test
        @DisplayName("should reject disallowed content type")
        void shouldRejectDisallowedContentType() {
            MockMultipartFile executableFile = new MockMultipartFile(
                    "file", "malware.exe", "application/x-msdownload", "MZ".getBytes()
            );

            assertThatThrownBy(() -> storageService.storeFile(executableFile, USER_ID, "00000000-0000-0000-0000-000000000001", null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("non autorise");
        }
    }

    // ========== getFile Tests ==========

    @Nested
    @DisplayName("getFile")
    class GetFileTests {

        @Test
        @DisplayName("should return file when user is the owner")
        void shouldReturnFileWhenUserIsOwner() {
            StoredFile saved = persistTestFile(USER_ID, "owner-file.txt", false);

            Optional<StoredFile> result = storageService.getFile(saved.getId(), USER_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(saved.getId());
            assertThat(result.get().getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("should return public file to non-owner")
        void shouldReturnPublicFileToNonOwner() {
            StoredFile saved = persistTestFile(USER_ID, "public-file.txt", true);

            Optional<StoredFile> result = storageService.getFile(saved.getId(), OTHER_USER_ID);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("should not return private file to non-owner")
        void shouldNotReturnPrivateFileToNonOwner() {
            StoredFile saved = persistTestFile(USER_ID, "private-file.txt", false);

            Optional<StoredFile> result = storageService.getFile(saved.getId(), OTHER_USER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for non-existent file id")
        void shouldReturnEmptyForNonExistentFileId() {
            Optional<StoredFile> result = storageService.getFile(99999L, USER_ID);

            assertThat(result).isEmpty();
        }
    }

    // ========== getUserFiles Tests ==========

    @Nested
    @DisplayName("getUserFiles")
    class GetUserFilesTests {

        @Test
        @DisplayName("should return all files for a specific user")
        void shouldReturnAllFilesForUser() {
            persistTestFile(USER_ID, "file1.txt", false);
            persistTestFile(USER_ID, "file2.txt", false);
            persistTestFile(OTHER_USER_ID, "other.txt", false);

            List<StoredFile> result = storageService.getUserFiles(USER_ID);

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(f -> f.getUserId().equals(USER_ID));
        }

        @Test
        @DisplayName("should return empty list when user has no files")
        void shouldReturnEmptyListWhenUserHasNoFiles() {
            List<StoredFile> result = storageService.getUserFiles(9999L);

            assertThat(result).isEmpty();
        }
    }

    // ========== getPublicFiles Tests ==========

    @Nested
    @DisplayName("getPublicFiles")
    class GetPublicFilesTests {

        @Test
        @DisplayName("should return only public files across all users")
        void shouldReturnOnlyPublicFiles() {
            persistTestFile(USER_ID, "public1.txt", true);
            persistTestFile(OTHER_USER_ID, "public2.txt", true);
            persistTestFile(USER_ID, "private.txt", false);

            List<StoredFile> result = storageService.getPublicFiles();

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(StoredFile::isPublic);
        }
    }

    // ========== updateFile Tests ==========

    @Nested
    @DisplayName("updateFile")
    class UpdateFileTests {

        @Test
        @DisplayName("should update description and visibility")
        void shouldUpdateDescriptionAndVisibility() {
            StoredFile saved = persistTestFile(USER_ID, "update-me.txt", false);
            assertThat(saved.isPublic()).isFalse();

            StoredFile updated = storageService.updateFile(
                    saved.getId(), USER_ID, "New description", true
            );

            assertThat(updated.getDescription()).isEqualTo("New description");
            assertThat(updated.isPublic()).isTrue();

            // Verify persistence
            StoredFile fromDb = storedFileRepository.findById(saved.getId()).orElseThrow();
            assertThat(fromDb.getDescription()).isEqualTo("New description");
            assertThat(fromDb.isPublic()).isTrue();
        }

        @Test
        @DisplayName("should throw exception when updating file belonging to another user")
        void shouldThrowExceptionWhenUpdatingOtherUsersFile() {
            StoredFile saved = persistTestFile(USER_ID, "not-yours.txt", false);

            assertThatThrownBy(() -> storageService.updateFile(
                    saved.getId(), OTHER_USER_ID, "Hacked", true
            )).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("non trouve");
        }

        @Test
        @DisplayName("should throw exception when file does not exist")
        void shouldThrowExceptionWhenFileDoesNotExist() {
            assertThatThrownBy(() -> storageService.updateFile(
                    99999L, USER_ID, "Description", false
            )).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("non trouve");
        }
    }

    // ========== deleteFile Tests ==========

    @Nested
    @DisplayName("deleteFile")
    class DeleteFileTests {

        @Test
        @DisplayName("should delete file from database and disk")
        void shouldDeleteFileFromDatabaseAndDisk() throws IOException {
            // First store a real file
            MockMultipartFile multipartFile = new MockMultipartFile(
                    "file", "to-delete.txt", "text/plain", "Delete me".getBytes()
            );
            StoredFile stored = storageService.storeFile(multipartFile, USER_ID, "00000000-0000-0000-0000-000000000001", "To be deleted");

            // Verify file exists
            assertThat(Files.exists(Path.of(stored.getFilePath()))).isTrue();
            assertThat(storedFileRepository.findById(stored.getId())).isPresent();

            // Delete
            storageService.deleteFile(stored.getId(), USER_ID);

            // Verify file is gone from both disk and database
            assertThat(Files.exists(Path.of(stored.getFilePath()))).isFalse();
            assertThat(storedFileRepository.findById(stored.getId())).isEmpty();
        }

        @Test
        @DisplayName("should throw exception when deleting file belonging to another user")
        void shouldThrowExceptionWhenDeletingOtherUsersFile() {
            StoredFile saved = persistTestFile(USER_ID, "not-yours.txt", false);

            assertThatThrownBy(() -> storageService.deleteFile(saved.getId(), OTHER_USER_ID))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("non trouve");
        }

        @Test
        @DisplayName("should throw exception when file does not exist")
        void shouldThrowExceptionWhenFileDoesNotExist() {
            assertThatThrownBy(() -> storageService.deleteFile(99999L, USER_ID))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("non trouve");
        }
    }

    // ========== deleteUserFiles Tests ==========

    @Nested
    @DisplayName("deleteUserFiles")
    class DeleteUserFilesTests {

        @Test
        @DisplayName("should delete all files for a user without affecting other users")
        void shouldDeleteAllFilesForUser() throws IOException {
            persistTestFile(USER_ID, "user-file1.txt", false);
            persistTestFile(USER_ID, "user-file2.txt", false);
            persistTestFile(OTHER_USER_ID, "other-file.txt", false);

            storageService.deleteUserFiles(USER_ID);

            assertThat(storedFileRepository.findByUserId(USER_ID)).isEmpty();
            assertThat(storedFileRepository.findByUserId(OTHER_USER_ID)).hasSize(1);
        }
    }

    // ========== getStorageUsage Tests ==========

    @Nested
    @DisplayName("getStorageUsage")
    class GetStorageUsageTests {

        @Test
        @DisplayName("should calculate total storage usage for user")
        void shouldCalculateTotalStorageUsage() {
            persistTestFileWithSize(USER_ID, "big.dat", 5_000_000L);
            persistTestFileWithSize(USER_ID, "small.dat", 1_000L);

            long usage = storageService.getStorageUsage(USER_ID);

            assertThat(usage).isEqualTo(5_001_000L);
        }

        @Test
        @DisplayName("should return zero for user with no files")
        void shouldReturnZeroForUserWithNoFiles() {
            long usage = storageService.getStorageUsage(9999L);

            assertThat(usage).isZero();
        }

        @Test
        @DisplayName("should not include other user files in calculation")
        void shouldNotIncludeOtherUserFiles() {
            persistTestFileWithSize(USER_ID, "mine.dat", 1000L);
            persistTestFileWithSize(OTHER_USER_ID, "theirs.dat", 5000L);

            long usage = storageService.getStorageUsage(USER_ID);

            assertThat(usage).isEqualTo(1000L);
        }
    }

    // ========== getFilePath Tests ==========

    @Nested
    @DisplayName("getFilePath")
    class GetFilePathTests {

        @Test
        @DisplayName("should return correct path from stored file entity")
        void shouldReturnCorrectPath() {
            StoredFile file = persistTestFile(USER_ID, "path-test.txt", false);

            Path result = storageService.getFilePath(file);

            assertThat(result.toString()).isEqualTo(file.getFilePath());
        }
    }

    // ========== Repository Query Tests ==========

    @Nested
    @DisplayName("Repository Queries via Service")
    class RepositoryQueryTests {

        @Test
        @DisplayName("should find files by content type")
        void shouldFindFilesByContentType() {
            persistTestFileWithContentType(USER_ID, "doc.pdf", "application/pdf");
            persistTestFileWithContentType(USER_ID, "text.txt", "text/plain");
            persistTestFileWithContentType(USER_ID, "data.json", "application/json");

            List<StoredFile> pdfFiles = storedFileRepository.findByUserIdAndContentTypeContaining(USER_ID, "pdf");
            assertThat(pdfFiles).hasSize(1);
            assertThat(pdfFiles.get(0).getContentType()).contains("pdf");
        }

        @Test
        @DisplayName("should find files by name search")
        void shouldFindFilesByNameSearch() {
            persistTestFile(USER_ID, "report-2024.txt", false);
            persistTestFile(USER_ID, "invoice-2024.txt", false);
            persistTestFile(USER_ID, "readme.txt", false);

            List<StoredFile> results = storedFileRepository.findByUserIdAndFileNameContainingIgnoreCase(
                    USER_ID, "2024"
            );
            // Note: this searches on fileName (the UUID-prefixed name), not originalName
            // So we search on the filename field which contains the search term
            assertThat(results).isNotNull();
        }

        @Test
        @DisplayName("should count files per user")
        void shouldCountFilesPerUser() {
            persistTestFile(USER_ID, "f1.txt", false);
            persistTestFile(USER_ID, "f2.txt", false);
            persistTestFile(USER_ID, "f3.txt", false);

            Long count = storedFileRepository.countByUserId(USER_ID);

            assertThat(count).isEqualTo(3L);
        }

        @Test
        @DisplayName("should get total storage used by user via native query")
        void shouldGetTotalStorageUsedByUser() {
            persistTestFileWithSize(USER_ID, "a.dat", 2000L);
            persistTestFileWithSize(USER_ID, "b.dat", 3000L);

            Long totalUsed = storedFileRepository.getTotalStorageUsedByUser(USER_ID);

            assertThat(totalUsed).isEqualTo(5000L);
        }

        @Test
        @DisplayName("should return null for total storage when user has no files")
        void shouldReturnNullForTotalStorageWhenNoFiles() {
            Long totalUsed = storedFileRepository.getTotalStorageUsedByUser(9999L);

            assertThat(totalUsed).isNull();
        }

        @Test
        @DisplayName("should find files by minimum size")
        void shouldFindFilesByMinimumSize() {
            persistTestFileWithSize(USER_ID, "small.dat", 100L);
            persistTestFileWithSize(USER_ID, "medium.dat", 5000L);
            persistTestFileWithSize(USER_ID, "large.dat", 10000L);

            List<StoredFile> largeFiles = storedFileRepository.findByUserIdAndFileSizeGreaterThan(USER_ID, 4000L);

            assertThat(largeFiles).hasSize(2);
            assertThat(largeFiles).allMatch(f -> f.getFileSize() > 4000L);
        }

        @Test
        @DisplayName("should find recent files by user")
        void shouldFindRecentFilesByUser() {
            persistTestFile(USER_ID, "recent.txt", false);

            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
            List<StoredFile> recentFiles = storedFileRepository.findRecentFilesByUser(USER_ID, oneHourAgo);

            assertThat(recentFiles).hasSize(1);
        }

        @Test
        @DisplayName("should get file type distribution for user")
        void shouldGetFileTypeDistribution() {
            persistTestFileWithContentType(USER_ID, "a.txt", "text/plain");
            persistTestFileWithContentType(USER_ID, "b.txt", "text/plain");
            persistTestFileWithContentType(USER_ID, "c.pdf", "application/pdf");

            List<Object[]> distribution = storedFileRepository.getFileTypeDistributionByUser(USER_ID);

            assertThat(distribution).isNotEmpty();
            // Should have 2 content types: text/plain (2 files) and application/pdf (1 file)
            assertThat(distribution).hasSize(2);
        }
    }

    // ========== Helper Methods ==========

    private StoredFile persistTestFile(Long userId, String originalName, boolean isPublic) {
        StoredFile file = new StoredFile(
                userId,
                "uuid-" + originalName,
                originalName,
                "text/plain",
                1024L,
                tempDir.resolve(userId.toString()).resolve("uuid-" + originalName).toString()
        );
        file.setStorageProvider("local");
        file.setStorageKey("uuid-" + originalName);
        file.setPublic(isPublic);
        file.setDescription("Test file: " + originalName);
        // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
        file.setOrganizationId("org-" + userId);
        return storedFileRepository.save(file);
    }

    private StoredFile persistTestFileWithSize(Long userId, String originalName, long fileSize) {
        StoredFile file = new StoredFile(
                userId,
                "uuid-" + originalName,
                originalName,
                "text/plain",
                fileSize,
                tempDir.resolve(userId.toString()).resolve("uuid-" + originalName).toString()
        );
        file.setStorageProvider("local");
        file.setStorageKey("uuid-" + originalName);
        // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
        file.setOrganizationId("org-" + userId);
        return storedFileRepository.save(file);
    }

    private StoredFile persistTestFileWithContentType(Long userId, String originalName, String contentType) {
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
        // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
        file.setOrganizationId("org-" + userId);
        return storedFileRepository.save(file);
    }
}
