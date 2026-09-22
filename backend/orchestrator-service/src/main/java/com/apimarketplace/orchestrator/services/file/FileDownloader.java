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

    /**
     * The URL, or a URL it redirected to, is one this platform refuses to fetch.
     *
     * <p>Distinct from its parent because the two mean opposite things to a caller: an
     * ordinary {@link FileDownloadException} is a failure worth retrying, while this one
     * will refuse identically every time. {@code FileToolsProvider} maps it to
     * {@code INVALID_PARAMETER_VALUE} so an agent corrects the URL instead of retrying a
     * refusal until it runs out of iterations.
     */
    class UrlNotAllowedException extends FileDownloadException {
        public UrlNotAllowedException(String message) {
            super(message);
        }
    }
}
