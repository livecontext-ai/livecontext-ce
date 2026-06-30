package com.apimarketplace.orchestrator.services.file;

import java.time.Duration;

/**
 * Interface for file downloading (Dependency Inversion Principle).
 * Allows swapping implementations without changing client code.
 *
 * Implementations:
 * - WebClientFileDownloader: Uses Spring WebClient (production)
 * - HttpClientFileDownloader: Uses Java HttpClient (alternative)
 * - MockFileDownloader: For testing
 */
public interface FileDownloader {

    /**
     * Download a file from URL with default timeout.
     *
     * @param url URL to download from
     * @return File content bytes
     * @throws FileDownloadException if download fails
     */
    byte[] download(String url) throws FileDownloadException;

    /**
     * Download a file from URL with custom timeout.
     *
     * @param url URL to download from
     * @param timeout Request timeout
     * @return File content bytes
     * @throws FileDownloadException if download fails
     */
    byte[] download(String url, Duration timeout) throws FileDownloadException;

    /**
     * Exception for download failures with status code context.
     */
    class FileDownloadException extends RuntimeException {
        private final int statusCode;

        public FileDownloadException(String message) {
            super(message);
            this.statusCode = -1;
        }

        public FileDownloadException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public FileDownloadException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
        }

        public FileDownloadException(String message, int statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isClientError() {
            return statusCode >= 400 && statusCode < 500;
        }

        public boolean isServerError() {
            return statusCode >= 500;
        }
    }
}
