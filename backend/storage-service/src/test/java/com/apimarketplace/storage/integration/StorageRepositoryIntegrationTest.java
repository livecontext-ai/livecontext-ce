package com.apimarketplace.storage.integration;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.repository.StoredFileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link StoredFileRepository}.
 *
 * <p>Tests all repository query methods against the H2 in-memory database
 * to verify JPQL and derived queries work correctly.</p>
 */
@IntegrationTest
@DisplayName("StoredFileRepository Integration Tests")
class StorageRepositoryIntegrationTest {

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private EntityManager entityManager;

    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;

    @BeforeEach
    void setUp() {
        storedFileRepository.deleteAll();
    }

    // ========== Basic CRUD Tests ==========

    @Nested
    @DisplayName("Basic CRUD operations")
    class BasicCrudTests {

        @Test
        @DisplayName("should save and retrieve a stored file by id")
        void shouldSaveAndRetrieveById() {
            StoredFile file = createFile(USER_A, "test.txt", "text/plain", 1024L, false);
            StoredFile saved = storedFileRepository.save(file);

            assertThat(saved.getId()).isNotNull();

            Optional<StoredFile> found = storedFileRepository.findById(saved.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getOriginalName()).isEqualTo("test.txt");
            assertThat(found.get().getUserId()).isEqualTo(USER_A);
        }

        @Test
        @DisplayName("should update an existing stored file")
        void shouldUpdateExistingFile() {
            StoredFile saved = storedFileRepository.save(
                    createFile(USER_A, "original.txt", "text/plain", 100L, false)
            );

            saved.setDescription("Updated description");
            saved.setPublic(true);
            storedFileRepository.save(saved);

            StoredFile updated = storedFileRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.getDescription()).isEqualTo("Updated description");
            assertThat(updated.isPublic()).isTrue();
        }

        @Test
        @DisplayName("should delete a stored file")
        void shouldDeleteFile() {
            StoredFile saved = storedFileRepository.save(
                    createFile(USER_A, "delete-me.txt", "text/plain", 100L, false)
            );
            Long id = saved.getId();

            storedFileRepository.delete(saved);

            assertThat(storedFileRepository.findById(id)).isEmpty();
        }
    }

    // ========== findByUserId Tests ==========

    @Nested
    @DisplayName("findByUserId")
    class FindByUserIdTests {

        @Test
        @DisplayName("should return all files for a specific user")
        void shouldReturnAllFilesForUser() {
            storedFileRepository.save(createFile(USER_A, "a1.txt", "text/plain", 100L, false));
            storedFileRepository.save(createFile(USER_A, "a2.txt", "text/plain", 200L, false));
            storedFileRepository.save(createFile(USER_B, "b1.txt", "text/plain", 300L, false));

            List<StoredFile> userAFiles = storedFileRepository.findByUserId(USER_A);

            assertThat(userAFiles).hasSize(2);
            assertThat(userAFiles).allMatch(f -> f.getUserId().equals(USER_A));
        }

        @Test
        @DisplayName("should return empty list for user with no files")
        void shouldReturnEmptyListForUserWithNoFiles() {
            List<StoredFile> files = storedFileRepository.findByUserId(9999L);

            assertThat(files).isEmpty();
        }
    }

    // ========== findByUserIdOrderByCreatedAtDesc Tests ==========

    @Nested
    @DisplayName("findByUserIdOrderByCreatedAtDesc")
    class FindByUserIdOrderedTests {

        @Test
        @DisplayName("should return files ordered by creation date descending")
        void shouldReturnFilesOrderedByCreatedAtDesc() {
            StoredFile older = createFile(USER_A, "old.txt", "text/plain", 100L, false);
            older.setCreatedAt(LocalDateTime.now().minusDays(2));
            storedFileRepository.save(older);

            StoredFile newer = createFile(USER_A, "new.txt", "text/plain", 200L, false);
            newer.setCreatedAt(LocalDateTime.now().minusDays(1));
            storedFileRepository.save(newer);

            StoredFile newest = createFile(USER_A, "newest.txt", "text/plain", 300L, false);
            storedFileRepository.save(newest);

            List<StoredFile> files = storedFileRepository.findByUserIdOrderByCreatedAtDesc(USER_A);

            assertThat(files).hasSize(3);
            assertThat(files.get(0).getCreatedAt()).isAfterOrEqualTo(files.get(1).getCreatedAt());
            assertThat(files.get(1).getCreatedAt()).isAfterOrEqualTo(files.get(2).getCreatedAt());
        }
    }

    // ========== findByIsPublicTrueOrderByCreatedAtDesc Tests ==========

    @Nested
    @DisplayName("findByIsPublicTrueOrderByCreatedAtDesc")
    class FindPublicFilesTests {

        @Test
        @DisplayName("should return only public files")
        void shouldReturnOnlyPublicFiles() {
            storedFileRepository.save(createFile(USER_A, "public1.txt", "text/plain", 100L, true));
            storedFileRepository.save(createFile(USER_A, "private.txt", "text/plain", 200L, false));
            storedFileRepository.save(createFile(USER_B, "public2.txt", "text/plain", 300L, true));

            List<StoredFile> publicFiles = storedFileRepository.findByIsPublicTrueOrderByCreatedAtDesc();

            assertThat(publicFiles).hasSize(2);
            assertThat(publicFiles).allMatch(StoredFile::isPublic);
        }

        @Test
        @DisplayName("should return empty list when no public files exist")
        void shouldReturnEmptyListWhenNoPublicFiles() {
            storedFileRepository.save(createFile(USER_A, "private.txt", "text/plain", 100L, false));

            List<StoredFile> publicFiles = storedFileRepository.findByIsPublicTrueOrderByCreatedAtDesc();

            assertThat(publicFiles).isEmpty();
        }
    }

    // ========== findByIdAndUserId Tests ==========

    @Nested
    @DisplayName("findByIdAndUserId")
    class FindByIdAndUserIdTests {

        @Test
        @DisplayName("should return file when both id and userId match")
        void shouldReturnFileWhenBothMatch() {
            StoredFile saved = storedFileRepository.save(
                    createFile(USER_A, "match.txt", "text/plain", 100L, false)
            );

            Optional<StoredFile> found = storedFileRepository.findByIdAndUserId(saved.getId(), USER_A);

            assertThat(found).isPresent();
            assertThat(found.get().getOriginalName()).isEqualTo("match.txt");
        }

        @Test
        @DisplayName("should return empty when userId does not match")
        void shouldReturnEmptyWhenUserIdDoesNotMatch() {
            StoredFile saved = storedFileRepository.save(
                    createFile(USER_A, "mismatch.txt", "text/plain", 100L, false)
            );

            Optional<StoredFile> found = storedFileRepository.findByIdAndUserId(saved.getId(), USER_B);

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should return empty when id does not exist")
        void shouldReturnEmptyWhenIdDoesNotExist() {
            Optional<StoredFile> found = storedFileRepository.findByIdAndUserId(99999L, USER_A);

            assertThat(found).isEmpty();
        }
    }

    // ========== findByUserIdAndContentTypeContaining Tests ==========

    @Nested
    @DisplayName("findByUserIdAndContentTypeContaining")
    class FindByContentTypeTests {

        @Test
        @DisplayName("should find files by content type substring")
        void shouldFindByContentTypeSubstring() {
            storedFileRepository.save(createFile(USER_A, "text.txt", "text/plain", 100L, false));
            storedFileRepository.save(createFile(USER_A, "html.html", "text/html", 200L, false));
            storedFileRepository.save(createFile(USER_A, "doc.pdf", "application/pdf", 300L, false));

            List<StoredFile> textFiles = storedFileRepository.findByUserIdAndContentTypeContaining(USER_A, "text");

            assertThat(textFiles).hasSize(2);
            assertThat(textFiles).allMatch(f -> f.getContentType().contains("text"));
        }
    }

    // ========== getTotalStorageUsedByUser Tests ==========

    @Nested
    @DisplayName("getTotalStorageUsedByUser")
    class GetTotalStorageTests {

        @Test
        @DisplayName("should sum file sizes for a user")
        void shouldSumFileSizesForUser() {
            storedFileRepository.save(createFile(USER_A, "a.txt", "text/plain", 1000L, false));
            storedFileRepository.save(createFile(USER_A, "b.txt", "text/plain", 2000L, false));
            storedFileRepository.save(createFile(USER_B, "c.txt", "text/plain", 5000L, false));

            Long total = storedFileRepository.getTotalStorageUsedByUser(USER_A);

            assertThat(total).isEqualTo(3000L);
        }

        @Test
        @DisplayName("should return null when user has no files")
        void shouldReturnNullWhenNoFiles() {
            Long total = storedFileRepository.getTotalStorageUsedByUser(9999L);

            assertThat(total).isNull();
        }
    }

    // ========== countByUserId Tests ==========

    @Nested
    @DisplayName("countByUserId")
    class CountByUserIdTests {

        @Test
        @DisplayName("should return correct count of files for user")
        void shouldReturnCorrectCount() {
            storedFileRepository.save(createFile(USER_A, "1.txt", "text/plain", 100L, false));
            storedFileRepository.save(createFile(USER_A, "2.txt", "text/plain", 200L, false));
            storedFileRepository.save(createFile(USER_A, "3.txt", "text/plain", 300L, false));

            Long count = storedFileRepository.countByUserId(USER_A);

            assertThat(count).isEqualTo(3L);
        }

        @Test
        @DisplayName("should return zero when user has no files")
        void shouldReturnZeroWhenNoFiles() {
            Long count = storedFileRepository.countByUserId(9999L);

            assertThat(count).isZero();
        }
    }

    // ========== deleteByUserId Tests ==========

    @Nested
    @DisplayName("deleteByUserId")
    class DeleteByUserIdTests {

        @Test
        @DisplayName("should delete all files for a specific user")
        void shouldDeleteAllFilesForUser() {
            storedFileRepository.save(createFile(USER_A, "a1.txt", "text/plain", 100L, false));
            storedFileRepository.save(createFile(USER_A, "a2.txt", "text/plain", 200L, false));
            storedFileRepository.save(createFile(USER_B, "b1.txt", "text/plain", 300L, false));

            storedFileRepository.deleteByUserId(USER_A);

            assertThat(storedFileRepository.findByUserId(USER_A)).isEmpty();
            assertThat(storedFileRepository.findByUserId(USER_B)).hasSize(1);
        }
    }

    // ========== updateFileVisibility Tests ==========

    @Nested
    @DisplayName("updateFileVisibility")
    class UpdateFileVisibilityTests {

        @Test
        @DisplayName("should update visibility for correct file and user")
        void shouldUpdateVisibilityForCorrectFileAndUser() {
            StoredFile saved = storedFileRepository.save(
                    createFile(USER_A, "visibility.txt", "text/plain", 100L, false)
            );

            int updated = storedFileRepository.updateFileVisibility(saved.getId(), USER_A, true);

            assertThat(updated).isEqualTo(1);

            // Clear persistence context to force reload from database
            entityManager.flush();
            entityManager.clear();
            StoredFile reloaded = storedFileRepository.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.isPublic()).isTrue();
        }

        @Test
        @DisplayName("should not update visibility when user does not match")
        void shouldNotUpdateWhenUserDoesNotMatch() {
            StoredFile saved = storedFileRepository.save(
                    createFile(USER_A, "protected.txt", "text/plain", 100L, false)
            );

            int updated = storedFileRepository.updateFileVisibility(saved.getId(), USER_B, true);

            assertThat(updated).isZero();
        }
    }

    // ========== findRecentFilesByUser Tests ==========

    @Nested
    @DisplayName("findRecentFilesByUser")
    class FindRecentFilesTests {

        @Test
        @DisplayName("should find files created after threshold")
        void shouldFindFilesAfterThreshold() {
            StoredFile recent = createFile(USER_A, "recent.txt", "text/plain", 100L, false);
            storedFileRepository.save(recent);

            StoredFile old = createFile(USER_A, "old.txt", "text/plain", 200L, false);
            old.setCreatedAt(LocalDateTime.now().minusDays(10));
            storedFileRepository.save(old);

            LocalDateTime threshold = LocalDateTime.now().minusDays(1);
            List<StoredFile> recentFiles = storedFileRepository.findRecentFilesByUser(USER_A, threshold);

            assertThat(recentFiles).hasSize(1);
            assertThat(recentFiles.get(0).getOriginalName()).isEqualTo("recent.txt");
        }
    }

    // ========== findByLastAccessedAtBefore Tests ==========

    @Nested
    @DisplayName("findByLastAccessedAtBefore")
    class FindByLastAccessedAtBeforeTests {

        @Test
        @DisplayName("should find files not accessed recently")
        void shouldFindFilesNotAccessedRecently() {
            StoredFile stale = createFile(USER_A, "stale.txt", "text/plain", 100L, false);
            stale.setLastAccessedAt(LocalDateTime.now().minusDays(60));
            storedFileRepository.save(stale);

            StoredFile fresh = createFile(USER_A, "fresh.txt", "text/plain", 200L, false);
            storedFileRepository.save(fresh);

            LocalDateTime threshold = LocalDateTime.now().minusDays(30);
            List<StoredFile> staleFiles = storedFileRepository.findByLastAccessedAtBefore(threshold);

            assertThat(staleFiles).hasSize(1);
            assertThat(staleFiles.get(0).getOriginalName()).isEqualTo("stale.txt");
        }
    }

    // ========== findByUserIdAndFileSizeGreaterThan Tests ==========

    @Nested
    @DisplayName("findByUserIdAndFileSizeGreaterThan")
    class FindByFileSizeTests {

        @Test
        @DisplayName("should find files larger than specified size")
        void shouldFindFilesLargerThanSize() {
            storedFileRepository.save(createFile(USER_A, "small.txt", "text/plain", 100L, false));
            storedFileRepository.save(createFile(USER_A, "medium.txt", "text/plain", 5000L, false));
            storedFileRepository.save(createFile(USER_A, "large.txt", "text/plain", 10000L, false));

            List<StoredFile> largeFiles = storedFileRepository.findByUserIdAndFileSizeGreaterThan(USER_A, 1000L);

            assertThat(largeFiles).hasSize(2);
            assertThat(largeFiles).allMatch(f -> f.getFileSize() > 1000L);
        }
    }

    // ========== getFileTypeDistributionByUser Tests ==========

    @Nested
    @DisplayName("getFileTypeDistributionByUser")
    class GetFileTypeDistributionTests {

        @Test
        @DisplayName("should return content type distribution for user")
        void shouldReturnContentTypeDistribution() {
            storedFileRepository.save(createFile(USER_A, "a.txt", "text/plain", 100L, false));
            storedFileRepository.save(createFile(USER_A, "b.txt", "text/plain", 200L, false));
            storedFileRepository.save(createFile(USER_A, "c.pdf", "application/pdf", 300L, false));
            storedFileRepository.save(createFile(USER_A, "d.json", "application/json", 400L, false));

            List<Object[]> distribution = storedFileRepository.getFileTypeDistributionByUser(USER_A);

            assertThat(distribution).hasSize(3);
            // Verify each entry has [contentType, count] structure
            for (Object[] entry : distribution) {
                assertThat(entry).hasSize(2);
                assertThat(entry[0]).isInstanceOf(String.class);
                assertThat(entry[1]).isInstanceOf(Long.class);
            }
        }

        @Test
        @DisplayName("should return empty distribution for user with no files")
        void shouldReturnEmptyDistributionForUserWithNoFiles() {
            List<Object[]> distribution = storedFileRepository.getFileTypeDistributionByUser(9999L);

            assertThat(distribution).isEmpty();
        }
    }

    // ========== findByUserIdAndFileNameContainingIgnoreCase Tests ==========

    @Nested
    @DisplayName("findByUserIdAndFileNameContainingIgnoreCase")
    class FindByFileNameTests {

        @Test
        @DisplayName("should find files by file name case-insensitive match")
        void shouldFindByFileNameCaseInsensitive() {
            StoredFile file = createFile(USER_A, "original.txt", "text/plain", 100L, false);
            file.setFileName("REPORT-2024.txt");
            storedFileRepository.save(file);

            StoredFile file2 = createFile(USER_A, "other.txt", "text/plain", 200L, false);
            file2.setFileName("invoice-2024.pdf");
            storedFileRepository.save(file2);

            StoredFile file3 = createFile(USER_A, "readme.txt", "text/plain", 300L, false);
            file3.setFileName("readme.md");
            storedFileRepository.save(file3);

            List<StoredFile> results = storedFileRepository.findByUserIdAndFileNameContainingIgnoreCase(USER_A, "report");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getFileName()).isEqualToIgnoringCase("REPORT-2024.txt");
        }
    }

    // ========== Helper Methods ==========

    private StoredFile createFile(Long userId, String originalName, String contentType, Long fileSize, boolean isPublic) {
        StoredFile file = new StoredFile(
                userId,
                "uuid-" + System.nanoTime() + "-" + originalName,
                originalName,
                contentType,
                fileSize,
                "/test-storage/" + userId + "/uuid-" + originalName
        );
        file.setStorageProvider("local");
        file.setStorageKey("uuid-" + originalName);
        file.setPublic(isPublic);
        // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
        file.setOrganizationId("org-" + userId);
        return file;
    }
}
