package com.apimarketplace.storage.util;

import java.time.Duration;

/**
 * Shared constants for file operations.
 */
public final class FileConstants {

    public static final int MAX_FILE_SIZE_MB = 50;
    public static final long MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024L * 1024L;

    public static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration PRESIGNED_URL_EXPIRY = Duration.ofMinutes(15);
    public static final Duration PRESIGNED_URL_MAX_EXPIRY = Duration.ofMinutes(60);

    public static final String PATH_SEPARATOR = "/";
    public static final String UUID_FILENAME_SEPARATOR = "_";

    public static final String DEFAULT_FILENAME = "download";
    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private FileConstants() {}
}
