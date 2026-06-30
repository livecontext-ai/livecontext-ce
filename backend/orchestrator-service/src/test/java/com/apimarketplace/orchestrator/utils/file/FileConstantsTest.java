package com.apimarketplace.orchestrator.utils.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FileConstants")
class FileConstantsTest {

    @Test
    @DisplayName("MAX_FILE_SIZE_MB should be 50")
    void maxFileSizeMbShouldBe50() {
        assertEquals(50, FileConstants.MAX_FILE_SIZE_MB);
    }

    @Test
    @DisplayName("MAX_FILE_SIZE_BYTES should be 50 * 1024 * 1024")
    void maxFileSizeBytesShouldBeConsistent() {
        assertEquals(50L * 1024L * 1024L, FileConstants.MAX_FILE_SIZE_BYTES);
    }

    @Test
    @DisplayName("DOWNLOAD_TIMEOUT should be 60 seconds")
    void downloadTimeoutShouldBe60Seconds() {
        assertEquals(Duration.ofSeconds(60), FileConstants.DOWNLOAD_TIMEOUT);
    }

    @Test
    @DisplayName("PRESIGNED_URL_EXPIRY should be 15 minutes")
    void presignedUrlExpiryShouldBe15Minutes() {
        assertEquals(Duration.ofMinutes(15), FileConstants.PRESIGNED_URL_EXPIRY);
    }

    @Test
    @DisplayName("PRESIGNED_URL_MAX_EXPIRY should be 60 minutes")
    void presignedUrlMaxExpiryShouldBe60Minutes() {
        assertEquals(Duration.ofMinutes(60), FileConstants.PRESIGNED_URL_MAX_EXPIRY);
    }

    @Test
    @DisplayName("Default values should be set correctly")
    void defaultValuesShouldBeCorrect() {
        assertEquals("download", FileConstants.DEFAULT_FILENAME);
        assertEquals("application/octet-stream", FileConstants.DEFAULT_MIME_TYPE);
        assertEquals("/", FileConstants.PATH_SEPARATOR);
        assertEquals("_", FileConstants.UUID_FILENAME_SEPARATOR);
    }
}
