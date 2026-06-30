package com.apimarketplace.storage.service.file;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streaming download handle returned by
 * {@link FileStorageService#openStream(String)}.
 *
 * <p>The {@link #stream()} is owned by the caller - wrap it in
 * {@code try-with-resources} (or close it explicitly) to release the underlying
 * S3 connection back to the AWS SDK pool. Failing to close leaks Apache HTTP
 * client connections, which the SDK detects and warns about. The {@link #close()}
 * method here closes the wrapped stream so the whole {@code DownloadStream}
 * record can be used with try-with-resources directly.
 *
 * <p>Used by {@code FileController.proxyDownload} and
 * {@code InternalFileController.download} via {@code StreamingResponseBody}
 * to stream object content end-to-end without buffering the full payload in
 * heap memory (the original {@code Optional<byte[]> download(String)} path
 * caused a 2026-05-04 OOM with 3 concurrent ~2 MB downloads on a 192 MB heap).
 */
public record DownloadStream(InputStream stream, long contentLength, String contentType)
        implements AutoCloseable {

    @Override
    public void close() throws IOException {
        if (stream != null) {
            stream.close();
        }
    }
}
