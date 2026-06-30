package com.apimarketplace.orchestrator.utils.file;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Utility for extracting filenames from various sources.
 * Single Responsibility: filename extraction only.
 *
 * Replaces duplicate implementations in:
 * - DownloadFileNode.java - extractFilenameFromUrl()
 * - FileToolsProvider.java - extractFilenameFromUrl()
 * - FileController.java - extractFileName()
 */
public final class FileNameExtractor {

    private FileNameExtractor() {
        // Utility class
    }

    /**
     * Extract filename from a URL.
     * Handles path extraction and URL decoding.
     *
     * Example: "https://example.com/path/report.pdf?token=abc" -> "report.pdf"
     *
     * @param url Full URL string
     * @return Filename or default name if extraction fails
     */
    public static String fromUrl(String url) {
        if (url == null || url.isBlank()) {
            return FileConstants.DEFAULT_FILENAME;
        }

        try {
            URI uri = URI.create(url);
            String path = uri.getPath();

            if (path == null || path.isBlank() || path.equals("/")) {
                return FileConstants.DEFAULT_FILENAME;
            }

            int lastSlash = path.lastIndexOf('/');
            String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;

            // Handle query parameters that might be in the filename part
            int queryStart = filename.indexOf('?');
            if (queryStart > 0) {
                filename = filename.substring(0, queryStart);
            }

            // URL decode the filename
            filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);

            return filename.isBlank() ? FileConstants.DEFAULT_FILENAME : filename;

        } catch (Exception e) {
            return FileConstants.DEFAULT_FILENAME;
        }
    }

}
