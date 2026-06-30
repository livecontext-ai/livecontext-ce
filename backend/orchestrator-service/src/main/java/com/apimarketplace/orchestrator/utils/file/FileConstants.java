package com.apimarketplace.orchestrator.utils.file;

import java.time.Duration;

/**
 * Shared constants for file operations.
 * Single source of truth - eliminates duplication across DownloadFileNode, FileToolsProvider, FileController.
 */
public final class FileConstants {

    // Size limits
    public static final int MAX_FILE_SIZE_MB = 50;
    public static final long MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024L * 1024L;

    // Timeouts
    public static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration PRESIGNED_URL_EXPIRY = Duration.ofMinutes(15);
    public static final Duration PRESIGNED_URL_MAX_EXPIRY = Duration.ofMinutes(60);

    // Storage paths
    public static final String PATH_SEPARATOR = "/";
    public static final String UUID_FILENAME_SEPARATOR = "_";

    // Default values
    public static final String DEFAULT_FILENAME = "download";
    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private FileConstants() {
        // Utility class
    }
}
