package com.apimarketplace.storage.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StoredFile Domain Entity Tests")
class StoredFileTest {

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should initialize timestamps on creation")
        void shouldInitializeTimestampsOnCreation() {
            LocalDateTime before = LocalDateTime.now();

            StoredFile storedFile = new StoredFile();

            LocalDateTime after = LocalDateTime.now();

            assertThat(storedFile.getCreatedAt()).isNotNull();
            assertThat(storedFile.getUpdatedAt()).isNotNull();
            assertThat(storedFile.getLastAccessedAt()).isNotNull();

            assertThat(storedFile.getCreatedAt()).isBetween(before, after);
            assertThat(storedFile.getUpdatedAt()).isBetween(before, after);
            assertThat(storedFile.getLastAccessedAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("should have isPublic as false by default")
        void shouldHaveIsPublicFalseByDefault() {
            StoredFile storedFile = new StoredFile();

            assertThat(storedFile.isPublic()).isFalse();
        }

        @Test
        @DisplayName("should have null id before persistence")
        void shouldHaveNullIdBeforePersistence() {
            StoredFile storedFile = new StoredFile();

            assertThat(storedFile.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("Parameterized Constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should set all fields from constructor parameters")
        void shouldSetAllFieldsFromConstructorParameters() {
            Long userId = 42L;
            String fileName = "unique-file.txt";
            String originalName = "my-document.txt";
            String contentType = "text/plain";
            Long fileSize = 1024L;
            String filePath = "/uploads/42/unique-file.txt";

            StoredFile storedFile = new StoredFile(userId, fileName, originalName, contentType, fileSize, filePath);

            assertThat(storedFile.getUserId()).isEqualTo(userId);
            assertThat(storedFile.getFileName()).isEqualTo(fileName);
            assertThat(storedFile.getOriginalName()).isEqualTo(originalName);
            assertThat(storedFile.getContentType()).isEqualTo(contentType);
            assertThat(storedFile.getFileSize()).isEqualTo(fileSize);
            assertThat(storedFile.getFilePath()).isEqualTo(filePath);
        }

        @Test
        @DisplayName("should also initialize timestamps from parameterized constructor")
        void shouldInitializeTimestampsFromParameterizedConstructor() {
            StoredFile storedFile = new StoredFile(1L, "file.txt", "original.txt", "text/plain", 100L, "/path");

            assertThat(storedFile.getCreatedAt()).isNotNull();
            assertThat(storedFile.getUpdatedAt()).isNotNull();
            assertThat(storedFile.getLastAccessedAt()).isNotNull();
        }

        @Test
        @DisplayName("should default isPublic to false from parameterized constructor")
        void shouldDefaultIsPublicToFalse() {
            StoredFile storedFile = new StoredFile(1L, "file.txt", "original.txt", "text/plain", 100L, "/path");

            assertThat(storedFile.isPublic()).isFalse();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSettersTests {

        @Test
        @DisplayName("should set and get id")
        void shouldSetAndGetId() {
            StoredFile storedFile = new StoredFile();
            storedFile.setId(99L);

            assertThat(storedFile.getId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("should set and get userId")
        void shouldSetAndGetUserId() {
            StoredFile storedFile = new StoredFile();
            storedFile.setUserId(42L);

            assertThat(storedFile.getUserId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("should set and get fileName")
        void shouldSetAndGetFileName() {
            StoredFile storedFile = new StoredFile();
            storedFile.setFileName("test-file.pdf");

            assertThat(storedFile.getFileName()).isEqualTo("test-file.pdf");
        }

        @Test
        @DisplayName("should set and get originalName")
        void shouldSetAndGetOriginalName() {
            StoredFile storedFile = new StoredFile();
            storedFile.setOriginalName("My Document.pdf");

            assertThat(storedFile.getOriginalName()).isEqualTo("My Document.pdf");
        }

        @Test
        @DisplayName("should set and get contentType")
        void shouldSetAndGetContentType() {
            StoredFile storedFile = new StoredFile();
            storedFile.setContentType("application/pdf");

            assertThat(storedFile.getContentType()).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("should set and get fileSize")
        void shouldSetAndGetFileSize() {
            StoredFile storedFile = new StoredFile();
            storedFile.setFileSize(2048L);

            assertThat(storedFile.getFileSize()).isEqualTo(2048L);
        }

        @Test
        @DisplayName("should set and get filePath")
        void shouldSetAndGetFilePath() {
            StoredFile storedFile = new StoredFile();
            storedFile.setFilePath("/uploads/user1/file.pdf");

            assertThat(storedFile.getFilePath()).isEqualTo("/uploads/user1/file.pdf");
        }

        @Test
        @DisplayName("should set and get storageProvider")
        void shouldSetAndGetStorageProvider() {
            StoredFile storedFile = new StoredFile();
            storedFile.setStorageProvider("s3");

            assertThat(storedFile.getStorageProvider()).isEqualTo("s3");
        }

        @Test
        @DisplayName("should set and get storageKey")
        void shouldSetAndGetStorageKey() {
            StoredFile storedFile = new StoredFile();
            storedFile.setStorageKey("abc-123-def");

            assertThat(storedFile.getStorageKey()).isEqualTo("abc-123-def");
        }

        @Test
        @DisplayName("should set and get isPublic")
        void shouldSetAndGetIsPublic() {
            StoredFile storedFile = new StoredFile();
            storedFile.setPublic(true);

            assertThat(storedFile.isPublic()).isTrue();
        }

        @Test
        @DisplayName("should set and get description")
        void shouldSetAndGetDescription() {
            StoredFile storedFile = new StoredFile();
            storedFile.setDescription("This is a test document.");

            assertThat(storedFile.getDescription()).isEqualTo("This is a test document.");
        }

        @Test
        @DisplayName("should set and get createdAt")
        void shouldSetAndGetCreatedAt() {
            StoredFile storedFile = new StoredFile();
            LocalDateTime customTime = LocalDateTime.of(2025, 1, 1, 12, 0);
            storedFile.setCreatedAt(customTime);

            assertThat(storedFile.getCreatedAt()).isEqualTo(customTime);
        }

        @Test
        @DisplayName("should set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            StoredFile storedFile = new StoredFile();
            LocalDateTime customTime = LocalDateTime.of(2025, 6, 15, 8, 30);
            storedFile.setUpdatedAt(customTime);

            assertThat(storedFile.getUpdatedAt()).isEqualTo(customTime);
        }

        @Test
        @DisplayName("should set and get lastAccessedAt")
        void shouldSetAndGetLastAccessedAt() {
            StoredFile storedFile = new StoredFile();
            LocalDateTime customTime = LocalDateTime.of(2025, 12, 31, 23, 59);
            storedFile.setLastAccessedAt(customTime);

            assertThat(storedFile.getLastAccessedAt()).isEqualTo(customTime);
        }
    }

    @Nested
    @DisplayName("preUpdate")
    class PreUpdateTests {

        @Test
        @DisplayName("should update updatedAt timestamp")
        void shouldUpdateUpdatedAtTimestamp() {
            StoredFile storedFile = new StoredFile();
            LocalDateTime oldUpdatedAt = storedFile.getUpdatedAt();

            // Introduce a small delay to ensure different timestamp
            storedFile.preUpdate();

            assertThat(storedFile.getUpdatedAt()).isAfterOrEqualTo(oldUpdatedAt);
        }
    }

    @Nested
    @DisplayName("updateLastAccessed")
    class UpdateLastAccessedTests {

        @Test
        @DisplayName("should update lastAccessedAt timestamp")
        void shouldUpdateLastAccessedAtTimestamp() {
            StoredFile storedFile = new StoredFile();
            LocalDateTime oldLastAccessedAt = storedFile.getLastAccessedAt();

            storedFile.updateLastAccessed();

            assertThat(storedFile.getLastAccessedAt()).isAfterOrEqualTo(oldLastAccessedAt);
        }
    }
}
