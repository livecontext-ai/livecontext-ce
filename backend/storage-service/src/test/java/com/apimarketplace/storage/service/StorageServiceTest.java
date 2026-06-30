package com.apimarketplace.storage.service;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.repository.StoredFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("StorageService Tests")
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    private StoredFileRepository storedFileRepository;

    @InjectMocks
    private StorageService storageService;

    private static final String ORG_ACME = "00000000-0000-0000-0000-000000000001";
    private static final String ORG_GLOBEX = "00000000-0000-0000-0000-000000000002";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(storageService, "localStoragePath", "./test-uploads");
        ReflectionTestUtils.setField(storageService, "maxFileSize", 10485760L); // 10MB
        ReflectionTestUtils.setField(storageService, "allowedContentTypes", "image/*,application/pdf,text/*");
    }

    @Nested
    @DisplayName("getFile")
    class GetFileTests {

        @Test
        @DisplayName("should return file when user is the owner")
        void shouldReturnFileWhenUserIsOwner() {
            Long fileId = 1L;
            Long userId = 42L;
            StoredFile storedFile = createStoredFile(fileId, userId, false);
            when(storedFileRepository.findById(fileId)).thenReturn(Optional.of(storedFile));
            // updateLastAccessed calls save
            when(storedFileRepository.save(any(StoredFile.class))).thenReturn(storedFile);

            Optional<StoredFile> result = storageService.getFile(fileId, userId);

            assertThat(result).isPresent();
            assertThat(result.get().getUserId()).isEqualTo(userId);
        }

        @Test
        @DisplayName("should return file when file is public and user is not owner")
        void shouldReturnFileWhenPublicAndNotOwner() {
            Long fileId = 1L;
            Long ownerId = 42L;
            Long requesterId = 99L;
            StoredFile storedFile = createStoredFile(fileId, ownerId, true);
            when(storedFileRepository.findById(fileId)).thenReturn(Optional.of(storedFile));
            when(storedFileRepository.save(any(StoredFile.class))).thenReturn(storedFile);

            Optional<StoredFile> result = storageService.getFile(fileId, requesterId);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("should return empty when file is private and user is not owner")
        void shouldReturnEmptyWhenPrivateAndNotOwner() {
            Long fileId = 1L;
            Long ownerId = 42L;
            Long requesterId = 99L;
            StoredFile storedFile = createStoredFile(fileId, ownerId, false);
            when(storedFileRepository.findById(fileId)).thenReturn(Optional.of(storedFile));

            Optional<StoredFile> result = storageService.getFile(fileId, requesterId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when file does not exist")
        void shouldReturnEmptyWhenFileDoesNotExist() {
            when(storedFileRepository.findById(99L)).thenReturn(Optional.empty());

            Optional<StoredFile> result = storageService.getFile(99L, 1L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sameUserFileInAnotherWorkspaceIsHidden")
        void sameUserFileInAnotherWorkspaceIsHidden() {
            Long fileId = 1L;
            Long userId = 42L;
            StoredFile storedFile = createStoredFile(fileId, userId, false);
            storedFile.setOrganizationId(ORG_GLOBEX);
            when(storedFileRepository.findById(fileId)).thenReturn(Optional.of(storedFile));

            Optional<StoredFile> result = storageService.getFile(fileId, userId, ORG_ACME);

            assertThat(result).isEmpty();
            verify(storedFileRepository, never()).save(any(StoredFile.class));
        }
    }

    @Nested
    @DisplayName("getUserFiles")
    class GetUserFilesTests {

        @Test
        @DisplayName("should return user files ordered by creation date descending")
        void shouldReturnUserFilesOrderedByCreatedAtDesc() {
            Long userId = 42L;
            List<StoredFile> files = List.of(
                createStoredFile(1L, userId, false),
                createStoredFile(2L, userId, false)
            );
            when(storedFileRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(files);

            List<StoredFile> result = storageService.getUserFiles(userId);

            assertThat(result).hasSize(2);
            verify(storedFileRepository).findByUserIdOrderByCreatedAtDesc(userId);
        }

        @Test
        @DisplayName("should return empty list when user has no files")
        void shouldReturnEmptyListWhenNoFiles() {
            Long userId = 999L;
            when(storedFileRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Collections.emptyList());

            List<StoredFile> result = storageService.getUserFiles(userId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("currentWorkspaceListUsesOrganizationScopedRepository")
        void currentWorkspaceListUsesOrganizationScopedRepository() {
            Long userId = 42L;
            List<StoredFile> files = List.of(createStoredFile(1L, userId, false));
            when(storedFileRepository.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(userId, ORG_ACME))
                    .thenReturn(files);

            List<StoredFile> result = storageService.getUserFiles(userId, ORG_ACME);

            assertThat(result).hasSize(1);
            verify(storedFileRepository).findByUserIdAndOrganizationIdOrderByCreatedAtDesc(userId, ORG_ACME);
            verify(storedFileRepository, never()).findByUserIdOrderByCreatedAtDesc(userId);
        }
    }

    @Nested
    @DisplayName("getPublicFiles")
    class GetPublicFilesTests {

        @Test
        @DisplayName("should return public files ordered by creation date descending")
        void shouldReturnPublicFilesOrderedByCreatedAtDesc() {
            List<StoredFile> files = List.of(
                createStoredFile(1L, 10L, true),
                createStoredFile(2L, 20L, true)
            );
            when(storedFileRepository.findByIsPublicTrueOrderByCreatedAtDesc()).thenReturn(files);

            List<StoredFile> result = storageService.getPublicFiles();

            assertThat(result).hasSize(2);
            result.forEach(f -> assertThat(f.isPublic()).isTrue());
        }
    }

    @Nested
    @DisplayName("updateFile")
    class UpdateFileTests {

        @Test
        @DisplayName("should update file description and visibility")
        void shouldUpdateFileDescriptionAndVisibility() {
            Long fileId = 1L;
            Long userId = 42L;
            StoredFile storedFile = createStoredFile(fileId, userId, false);
            when(storedFileRepository.findByIdAndUserId(fileId, userId)).thenReturn(Optional.of(storedFile));
            when(storedFileRepository.save(any(StoredFile.class))).thenAnswer(inv -> inv.getArgument(0));

            StoredFile result = storageService.updateFile(fileId, userId, "Updated description", true);

            assertThat(result.getDescription()).isEqualTo("Updated description");
            assertThat(result.isPublic()).isTrue();
            verify(storedFileRepository).save(storedFile);
        }

        @Test
        @DisplayName("should throw exception when file not found for update")
        void shouldThrowExceptionWhenFileNotFoundForUpdate() {
            Long fileId = 999L;
            Long userId = 42L;
            when(storedFileRepository.findByIdAndUserId(fileId, userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storageService.updateFile(fileId, userId, "desc", false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("non trouve");
        }
    }

    @Nested
    @DisplayName("deleteFile")
    class DeleteFileTests {

        @Test
        @DisplayName("should throw exception when file not found for deletion")
        void shouldThrowExceptionWhenFileNotFoundForDeletion() {
            Long fileId = 999L;
            Long userId = 42L;
            when(storedFileRepository.findByIdAndUserId(fileId, userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storageService.deleteFile(fileId, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("non trouve");
        }
    }

    @Nested
    @DisplayName("deleteUserFiles")
    class DeleteUserFilesTests {

        @Test
        @DisplayName("should delete all files for a user from database")
        void shouldDeleteAllFilesForUserFromDatabase() throws IOException {
            Long userId = 42L;
            when(storedFileRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

            storageService.deleteUserFiles(userId);

            verify(storedFileRepository).deleteByUserId(userId);
        }
    }

    @Nested
    @DisplayName("storeFile validation")
    class StoreFileValidationTests {

        @Test
        @DisplayName("should throw exception for empty file")
        void shouldThrowExceptionForEmptyFile() {
            MultipartFile emptyFile = mock(MultipartFile.class);
            when(emptyFile.isEmpty()).thenReturn(true);

            assertThatThrownBy(() -> storageService.storeFile(emptyFile, 1L, "00000000-0000-0000-0000-000000000001", "desc"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("vide");
        }

        @Test
        @DisplayName("should throw exception when file exceeds max size")
        void shouldThrowExceptionWhenFileExceedsMaxSize() {
            MultipartFile largeFile = mock(MultipartFile.class);
            when(largeFile.isEmpty()).thenReturn(false);
            when(largeFile.getSize()).thenReturn(20_000_000L); // 20MB > 10MB max

            assertThatThrownBy(() -> storageService.storeFile(largeFile, 1L, "00000000-0000-0000-0000-000000000001", "desc"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("volumineux");
        }

        @Test
        @DisplayName("should throw exception for disallowed content type")
        void shouldThrowExceptionForDisallowedContentType() {
            MultipartFile disallowedFile = mock(MultipartFile.class);
            when(disallowedFile.isEmpty()).thenReturn(false);
            when(disallowedFile.getSize()).thenReturn(100L);
            when(disallowedFile.getContentType()).thenReturn("application/executable");

            assertThatThrownBy(() -> storageService.storeFile(disallowedFile, 1L, "00000000-0000-0000-0000-000000000001", "desc"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("non autorise");
        }
    }

    @Nested
    @DisplayName("getFilePath")
    class GetFilePathTests {

        @Test
        @DisplayName("should return correct path from StoredFile")
        void shouldReturnCorrectPathFromStoredFile() {
            StoredFile storedFile = new StoredFile();
            storedFile.setFilePath("/uploads/42/test-file.txt");

            Path result = storageService.getFilePath(storedFile);

            assertThat(result).isEqualTo(Paths.get("/uploads/42/test-file.txt"));
        }
    }

    @Nested
    @DisplayName("getStorageUsage")
    class GetStorageUsageTests {

        @Test
        @DisplayName("should calculate total storage usage for user")
        void shouldCalculateTotalStorageUsageForUser() {
            Long userId = 42L;
            StoredFile file1 = createStoredFile(1L, userId, false);
            file1.setFileSize(1000L);
            StoredFile file2 = createStoredFile(2L, userId, false);
            file2.setFileSize(2000L);
            when(storedFileRepository.findByUserId(userId)).thenReturn(List.of(file1, file2));

            long usage = storageService.getStorageUsage(userId);

            assertThat(usage).isEqualTo(3000L);
        }

        @Test
        @DisplayName("currentWorkspaceUsageIgnoresSameUserFilesInOtherWorkspaces")
        void currentWorkspaceUsageIgnoresSameUserFilesInOtherWorkspaces() {
            Long userId = 42L;
            StoredFile file = createStoredFile(1L, userId, false);
            file.setFileSize(1000L);
            when(storedFileRepository.findByUserIdAndOrganizationId(userId, ORG_ACME)).thenReturn(List.of(file));

            long usage = storageService.getStorageUsage(userId, ORG_ACME);

            assertThat(usage).isEqualTo(1000L);
            verify(storedFileRepository).findByUserIdAndOrganizationId(userId, ORG_ACME);
            verify(storedFileRepository, never()).findByUserId(userId);
        }

        @Test
        @DisplayName("should return zero when user has no files")
        void shouldReturnZeroWhenUserHasNoFiles() {
            Long userId = 999L;
            when(storedFileRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

            long usage = storageService.getStorageUsage(userId);

            assertThat(usage).isZero();
        }
    }

    @Nested
    @DisplayName("cleanupExpiredFiles")
    class CleanupExpiredFilesTests {

        @Test
        @DisplayName("should find expired files and attempt to delete them")
        void shouldFindExpiredFilesAndAttemptDeletion() {
            when(storedFileRepository.findByLastAccessedAtBefore(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

            storageService.cleanupExpiredFiles();

            verify(storedFileRepository).findByLastAccessedAtBefore(any(LocalDateTime.class));
        }
    }

    @Nested
    @DisplayName("updateLastAccessed")
    class UpdateLastAccessedTests {

        @Test
        @DisplayName("should update last accessed time and save")
        void shouldUpdateLastAccessedTimeAndSave() {
            StoredFile storedFile = createStoredFile(1L, 42L, false);
            when(storedFileRepository.save(any(StoredFile.class))).thenReturn(storedFile);

            storageService.updateLastAccessed(storedFile);

            verify(storedFileRepository).save(storedFile);
            assertThat(storedFile.getLastAccessedAt()).isNotNull();
        }
    }

    // ========== Helper methods ==========

    private StoredFile createStoredFile(Long id, Long userId, boolean isPublic) {
        StoredFile storedFile = new StoredFile(userId, "file-" + id + ".txt", "original-" + id + ".txt",
            "text/plain", 1024L, "/uploads/" + userId + "/file-" + id + ".txt");
        storedFile.setId(id);
        storedFile.setPublic(isPublic);
        storedFile.setStorageProvider("local");
        storedFile.setStorageKey("file-" + id + ".txt");
        return storedFile;
    }
}
